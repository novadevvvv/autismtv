package dev.novab.autismtv.client;

import dev.novab.autismtv.network.AutismTVPayloads;
import dev.novab.autismtv.network.RelayProtocol;
import dev.novab.autismtv.network.RelayProtocol.RelayPanelSnapshot;
import dev.novab.autismtv.network.RelayProtocol.RelaySessionInfo;
import dev.novab.autismtv.network.RelayProtocol.RelayViewerAccess;
import dev.novab.autismtv.network.RelayProtocol.RelayViewerInfo;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class ServerRelayTransport {
    private static final long SESSION_REQUEST_INTERVAL_MS = 800L;
    private static final long ACTION_TIMEOUT_MS = 3_000L;
    private static final Object ACTION_LOCK = new Object();

    private static volatile List<PeerShareTransport.SessionInfo> discoveredSessions = List.of();
    private static volatile PeerShareTransport.SessionInfo hostedSession;
    private static volatile List<PeerShareTransport.ViewerInfo> hostedViewers = List.of();
    private static volatile PeerShareTransport.SessionInfo remoteSession;
    private static volatile RelayViewerAccess remoteAccess = new RelayViewerAccess(false, false);
    private static volatile long lastSessionRequestMs;

    private static boolean initialized;
    private static int pendingActionType = -1;
    private static boolean pendingActionDone;
    private static boolean pendingActionSuccess;
    private static String pendingActionMessage = "";

    private ServerRelayTransport() {
    }

    static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        ClientPlayNetworking.registerGlobalReceiver(AutismTVPayloads.ServerToClientPayload.ID,
                (payload, context) -> context.client().execute(() -> handlePayload(payload.data())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetState());
    }

    static boolean isAvailable() {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean available = client != null
                && client.getNetworkHandler() != null
                && ClientPlayNetworking.canSend(AutismTVPayloads.ClientToServerPayload.ID);
        if (!available) {
            resetState();
        }
        return available;
    }

    static boolean isServerSession(PeerShareTransport.SessionInfo session) {
        return session != null && session.host().isEmpty() && session.port() == 0;
    }

    static String startHosting(String sessionName, String description, String password,
                               boolean defaultAllowClicks, boolean defaultAllowTyping) throws IOException {
        awaitAction(RelayProtocol.C2S_START_HOSTING, output -> {
            output.writeByte(RelayProtocol.C2S_START_HOSTING);
            output.writeUTF(sessionName == null ? "" : sessionName);
            output.writeUTF(description == null ? "" : description);
            output.writeUTF(password == null ? "" : password);
            output.writeBoolean(defaultAllowClicks);
            output.writeBoolean(defaultAllowTyping);
        });
        return "server-relay";
    }

    static void stopHosting() {
        if (hostedSession == null || !isAvailable()) {
            hostedSession = null;
            hostedViewers = List.of();
            return;
        }

        send(output -> output.writeByte(RelayProtocol.C2S_STOP_HOSTING));
        hostedSession = null;
        hostedViewers = List.of();
    }

    static PeerShareTransport.SessionInfo getHostedSessionInfo() {
        return hostedSession;
    }

    static List<PeerShareTransport.ViewerInfo> getConnectedViewers() {
        return hostedViewers;
    }

    static void updateViewerPermissions(String viewerId, boolean allowClicks, boolean allowTyping) {
        if (hostedSession == null || !isAvailable()) {
            return;
        }

        send(output -> {
            output.writeByte(RelayProtocol.C2S_UPDATE_VIEWER_PERMISSIONS);
            output.writeUTF(viewerId);
            output.writeBoolean(allowClicks);
            output.writeBoolean(allowTyping);
        });
    }

    static void updateDefaultPermissions(boolean allowClicks, boolean allowTyping) {
        if (hostedSession == null || !isAvailable()) {
            return;
        }

        send(output -> {
            output.writeByte(RelayProtocol.C2S_UPDATE_DEFAULT_PERMISSIONS);
            output.writeBoolean(allowClicks);
            output.writeBoolean(allowTyping);
        });
    }

    static void join(PeerShareTransport.SessionInfo session, String password) throws IOException {
        awaitAction(RelayProtocol.C2S_JOIN_SESSION, output -> {
            output.writeByte(RelayProtocol.C2S_JOIN_SESSION);
            output.writeUTF(session.id());
            output.writeUTF(password == null ? "" : password);
        });
    }

    static void disconnect() {
        if (!isAvailable()) {
            remoteSession = null;
            remoteAccess = new RelayViewerAccess(false, false);
            LocalPanelController.clearRemoteShare();
            return;
        }

        if (remoteSession != null) {
            send(output -> output.writeByte(RelayProtocol.C2S_LEAVE_SESSION));
        }
        if (hostedSession != null) {
            send(output -> output.writeByte(RelayProtocol.C2S_STOP_HOSTING));
        }

        hostedSession = null;
        hostedViewers = List.of();
        remoteSession = null;
        remoteAccess = new RelayViewerAccess(false, false);
        LocalPanelController.clearRemoteShare();
    }

    static PeerShareTransport.SessionInfo getCurrentRemoteSession() {
        return remoteSession;
    }

    static boolean canRemoteClick() {
        return remoteAccess.allowClicks();
    }

    static boolean canRemoteType() {
        return remoteAccess.allowTyping();
    }

    static List<PeerShareTransport.SessionInfo> getDiscoveredSessions() {
        if (!isAvailable()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        if (now - lastSessionRequestMs >= SESSION_REQUEST_INTERVAL_MS) {
            lastSessionRequestMs = now;
            send(output -> output.writeByte(RelayProtocol.C2S_REQUEST_SESSION_LIST));
        }
        return discoveredSessions;
    }

    static void broadcastPanelState(LocalPanelController.PanelSnapshot snapshot) {
        if (hostedSession == null || !isAvailable()) {
            return;
        }

        send(output -> {
            output.writeByte(RelayProtocol.C2S_PANEL_STATE);
            RelayProtocol.writePanelSnapshot(output, new RelayPanelSnapshot(
                    snapshot.centerX(), snapshot.centerY(), snapshot.centerZ(),
                    snapshot.normalX(), snapshot.normalY(), snapshot.normalZ(),
                    snapshot.rightX(), snapshot.rightY(), snapshot.rightZ(),
                    snapshot.upX(), snapshot.upY(), snapshot.upZ(),
                    snapshot.halfWidth(), snapshot.halfHeight(), snapshot.halfThickness(),
                    snapshot.billboard(), snapshot.rotationDegrees()));
        });
    }

    static void broadcastFrame(int width, int height, byte[] encodedFrame) {
        if (hostedSession == null || !isAvailable() || encodedFrame == null) {
            return;
        }

        send(output -> {
            output.writeByte(RelayProtocol.C2S_FRAME);
            output.writeInt(width);
            output.writeInt(height);
            output.writeInt(encodedFrame.length);
            output.write(encodedFrame);
        });
    }

    static void broadcastClear() {
        if (hostedSession == null || !isAvailable()) {
            return;
        }

        send(output -> output.writeByte(RelayProtocol.C2S_CLEAR));
    }

    static boolean sendRemoteMouse(int button, int action, double u, double v) {
        if (remoteSession == null || !isAvailable()) {
            return false;
        }

        return send(output -> {
            output.writeByte(RelayProtocol.C2S_REMOTE_MOUSE);
            output.writeInt(button);
            output.writeInt(action);
            output.writeDouble(u);
            output.writeDouble(v);
        });
    }

    static boolean sendRemoteKey(int action, int keycode) {
        if (remoteSession == null || !isAvailable()) {
            return false;
        }

        return send(output -> {
            output.writeByte(RelayProtocol.C2S_REMOTE_KEY);
            output.writeInt(action);
            output.writeInt(keycode);
        });
    }

    static boolean sendRemoteChar(int codepoint) {
        if (remoteSession == null || !isAvailable()) {
            return false;
        }

        return send(output -> {
            output.writeByte(RelayProtocol.C2S_REMOTE_CHAR);
            output.writeInt(codepoint);
        });
    }

    private static void handlePayload(byte[] data) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            int type = input.readUnsignedByte();
            switch (type) {
                case RelayProtocol.S2C_SESSION_LIST -> handleSessionList(input);
                case RelayProtocol.S2C_HOST_STATE -> handleHostState(input);
                case RelayProtocol.S2C_REMOTE_STATE -> handleRemoteState(input);
                case RelayProtocol.S2C_PANEL_STATE -> LocalPanelController.applyRemotePanel(toClientSnapshot(RelayProtocol.readPanelSnapshot(input)));
                case RelayProtocol.S2C_FRAME -> handleFrame(input);
                case RelayProtocol.S2C_CLEAR -> LocalPanelController.clearRemoteShare();
                case RelayProtocol.S2C_ACTION_RESULT -> handleActionResult(input);
                case RelayProtocol.S2C_REMOTE_MOUSE -> LocalPanelController.handleRemoteMouseInput(input.readInt(), input.readInt(), input.readDouble(), input.readDouble());
                case RelayProtocol.S2C_REMOTE_KEY -> LocalPanelController.handleRemoteKeyInput(input.readInt(), input.readInt());
                case RelayProtocol.S2C_REMOTE_CHAR -> LocalPanelController.handleRemoteCharInput(input.readInt());
                default -> {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void handleSessionList(DataInputStream input) throws IOException {
        int count = input.readInt();
        List<PeerShareTransport.SessionInfo> sessions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            sessions.add(toClientSession(RelayProtocol.readSessionInfo(input)));
        }
        discoveredSessions = List.copyOf(sessions);
    }

    private static void handleHostState(DataInputStream input) throws IOException {
        RelaySessionInfo info = RelayProtocol.readNullableSessionInfo(input);
        List<RelayViewerInfo> viewers = RelayProtocol.readViewerInfoList(input);
        hostedSession = info == null ? null : toClientSession(info);
        List<PeerShareTransport.ViewerInfo> mappedViewers = new ArrayList<>(viewers.size());
        for (RelayViewerInfo viewer : viewers) {
            mappedViewers.add(new PeerShareTransport.ViewerInfo(viewer.viewerId(), viewer.viewerName(), viewer.allowClicks(), viewer.allowTyping()));
        }
        hostedViewers = List.copyOf(mappedViewers);
    }

    private static void handleRemoteState(DataInputStream input) throws IOException {
        RelaySessionInfo info = RelayProtocol.readNullableSessionInfo(input);
        remoteSession = info == null ? null : toClientSession(info);
        remoteAccess = RelayProtocol.readViewerAccess(input);
        if (remoteSession == null) {
            LocalPanelController.clearRemoteShare();
        }
    }

    private static void handleFrame(DataInputStream input) throws IOException {
        int width = input.readInt();
        int height = input.readInt();
        int length = input.readInt();
        byte[] bytes = input.readNBytes(length);
        int[] pixels = PeerShareTransport.decodeFrame(bytes, width, height);
        if (pixels != null) {
            LocalPanelController.applyRemoteFrame(width, height, pixels);
        }
    }

    private static void handleActionResult(DataInputStream input) throws IOException {
        int actionType = input.readUnsignedByte();
        boolean success = input.readBoolean();
        String message = input.readUTF();

        synchronized (ACTION_LOCK) {
            if (pendingActionType != actionType) {
                return;
            }

            pendingActionSuccess = success;
            pendingActionMessage = message;
            pendingActionDone = true;
            ACTION_LOCK.notifyAll();
        }
    }

    private static void awaitAction(int actionType, PacketWriter writer) throws IOException {
        if (!isAvailable()) {
            throw new IOException("The connected server does not have AutismTV installed");
        }

        synchronized (ACTION_LOCK) {
            if (pendingActionType != -1) {
                throw new IOException("Another AutismTV server action is already pending");
            }

            pendingActionType = actionType;
            pendingActionDone = false;
            pendingActionSuccess = false;
            pendingActionMessage = "";

            if (!send(writer)) {
                clearPendingAction();
                throw new IOException("Failed to contact the server relay");
            }

            long deadline = System.currentTimeMillis() + ACTION_TIMEOUT_MS;
            while (!pendingActionDone) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    clearPendingAction();
                    throw new IOException("Timed out waiting for the server relay");
                }

                try {
                    ACTION_LOCK.wait(remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    clearPendingAction();
                    throw new IOException("Interrupted while waiting for the server relay", exception);
                }
            }

            boolean success = pendingActionSuccess;
            String message = pendingActionMessage;
            clearPendingAction();
            if (!success) {
                throw new IOException(message == null || message.isBlank() ? "The server rejected the request" : message);
            }
        }
    }

    private static void clearPendingAction() {
        pendingActionType = -1;
        pendingActionDone = false;
        pendingActionSuccess = false;
        pendingActionMessage = "";
    }

    private static boolean send(PacketWriter writer) {
        if (!isAvailable()) {
            return false;
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            ClientPlayNetworking.send(new AutismTVPayloads.ClientToServerPayload(bytes.toByteArray()));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void resetState() {
        discoveredSessions = List.of();
        hostedSession = null;
        hostedViewers = List.of();
        remoteSession = null;
        remoteAccess = new RelayViewerAccess(false, false);
        lastSessionRequestMs = 0L;
        LocalPanelController.clearRemoteShare();
        synchronized (ACTION_LOCK) {
            clearPendingAction();
            ACTION_LOCK.notifyAll();
        }
    }

    private static PeerShareTransport.SessionInfo toClientSession(RelaySessionInfo info) {
        return new PeerShareTransport.SessionInfo(info.id(), info.name(), info.description(), info.leaderName(), info.host(), info.port(),
                info.passwordProtected(), info.participantCount(), info.defaultAllowClicks(), info.defaultAllowTyping(), info.localHost());
    }

    private static LocalPanelController.PanelSnapshot toClientSnapshot(RelayPanelSnapshot snapshot) {
        return new LocalPanelController.PanelSnapshot(
                snapshot.centerX(), snapshot.centerY(), snapshot.centerZ(),
                snapshot.normalX(), snapshot.normalY(), snapshot.normalZ(),
                snapshot.rightX(), snapshot.rightY(), snapshot.rightZ(),
                snapshot.upX(), snapshot.upY(), snapshot.upZ(),
                snapshot.halfWidth(), snapshot.halfHeight(), snapshot.halfThickness(),
                snapshot.billboard(), snapshot.rotationDegrees());
    }

    private interface PacketWriter {
        void write(DataOutputStream output) throws IOException;
    }
}