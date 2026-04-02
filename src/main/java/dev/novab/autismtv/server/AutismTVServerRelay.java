package dev.novab.autismtv.server;

import dev.novab.autismtv.AutismTVMod;
import dev.novab.autismtv.network.AutismTVPayloads;
import dev.novab.autismtv.network.RelayProtocol;
import dev.novab.autismtv.network.RelayProtocol.RelayPanelSnapshot;
import dev.novab.autismtv.network.RelayProtocol.RelaySessionInfo;
import dev.novab.autismtv.network.RelayProtocol.RelayViewerAccess;
import dev.novab.autismtv.network.RelayProtocol.RelayViewerInfo;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AutismTVServerRelay {
    private static final Map<String, RelaySession> SESSIONS_BY_ID = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SESSION_BY_HOST = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SESSION_BY_VIEWER = new ConcurrentHashMap<>();
    private static boolean initialized;

    private AutismTVServerRelay() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        ServerPlayNetworking.registerGlobalReceiver(AutismTVPayloads.ClientToServerPayload.ID, (payload, context) ->
                context.server().execute(() -> handlePacket(context.server(), context.player(), payload.data())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> handleDisconnect(handler.player));
    }

    private static void handlePacket(MinecraftServer server, ServerPlayerEntity player, byte[] data) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            int type = input.readUnsignedByte();
            switch (type) {
                case RelayProtocol.C2S_REQUEST_SESSION_LIST -> sendSessionList(player);
                case RelayProtocol.C2S_START_HOSTING -> handleStartHosting(server, player, input);
                case RelayProtocol.C2S_STOP_HOSTING -> stopHosting(player);
                case RelayProtocol.C2S_JOIN_SESSION -> handleJoinSession(player, input);
                case RelayProtocol.C2S_LEAVE_SESSION -> leaveSession(player, true);
                case RelayProtocol.C2S_UPDATE_VIEWER_PERMISSIONS -> handleViewerPermissionUpdate(player, input);
                case RelayProtocol.C2S_UPDATE_DEFAULT_PERMISSIONS -> handleDefaultPermissionUpdate(player, input);
                case RelayProtocol.C2S_PANEL_STATE -> handlePanelState(player, input);
                case RelayProtocol.C2S_FRAME -> handleFrame(player, input);
                case RelayProtocol.C2S_CLEAR -> handleClear(player);
                case RelayProtocol.C2S_REMOTE_MOUSE -> handleRemoteMouse(player, input);
                case RelayProtocol.C2S_REMOTE_KEY -> handleRemoteKey(player, input);
                case RelayProtocol.C2S_REMOTE_CHAR -> handleRemoteChar(player, input);
                default -> AutismTVMod.LOGGER.debug("Ignoring unknown relay packet type {}", type);
            }
        } catch (IOException exception) {
            AutismTVMod.LOGGER.debug("Failed to read relay packet", exception);
        }
    }

    private static void handleStartHosting(MinecraftServer server, ServerPlayerEntity player, DataInputStream input) throws IOException {
        String sessionName = sanitizeName(input.readUTF());
        String description = sanitizeDescription(input.readUTF());
        String password = input.readUTF();
        boolean defaultAllowClicks = input.readBoolean();
        boolean defaultAllowTyping = input.readBoolean();

        leaveSession(player, false);
        stopHosting(player);

        RelaySession session = new RelaySession(server, player.getUuidAsString(), sessionName, description, password, player.getName().getString(),
                defaultAllowClicks, defaultAllowTyping);
        SESSIONS_BY_ID.put(session.sessionId, session);
        SESSION_BY_HOST.put(player.getUuid(), session.sessionId);

        sendActionResult(player, RelayProtocol.C2S_START_HOSTING, true, sessionName);
        sendHostState(player, session);
    }

    private static void handleJoinSession(ServerPlayerEntity player, DataInputStream input) throws IOException {
        String sessionId = input.readUTF();
        String password = input.readUTF();
        RelaySession session = SESSIONS_BY_ID.get(sessionId);
        if (session == null) {
            sendActionResult(player, RelayProtocol.C2S_JOIN_SESSION, false, "That session is no longer available");
            return;
        }

        if (session.hostUuid.equals(player.getUuid())) {
            sendActionResult(player, RelayProtocol.C2S_JOIN_SESSION, false, "That session is already yours");
            return;
        }

        if (!session.password.equals(password == null ? "" : password)) {
            sendActionResult(player, RelayProtocol.C2S_JOIN_SESSION, false, "Incorrect password");
            return;
        }

        leaveSession(player, false);
        stopHosting(player);

        session.viewers.put(player.getUuid(), new ViewerState(player.getUuidAsString(), player.getName().getString(),
                session.defaultAllowClicks, session.defaultAllowTyping));
        SESSION_BY_VIEWER.put(player.getUuid(), session.sessionId);

        sendActionResult(player, RelayProtocol.C2S_JOIN_SESSION, true, session.sessionName);
        sendRemoteState(player, session, session.getViewerAccess(player.getUuid()));
        sendHostState(session.getHostPlayer(), session);
        session.broadcastRemoteState();
        if (session.lastSnapshot != null) {
            sendPanelState(player, session.lastSnapshot);
        }
        if (session.lastFrameBytes != null) {
            sendFrame(player, session.lastFrameWidth, session.lastFrameHeight, session.lastFrameBytes);
        }
    }

    private static void handleViewerPermissionUpdate(ServerPlayerEntity player, DataInputStream input) throws IOException {
        RelaySession session = getHostedSession(player);
        if (session == null) {
            return;
        }

        String viewerId = input.readUTF();
        boolean allowClicks = input.readBoolean();
        boolean allowTyping = input.readBoolean();
        session.updateViewerPermissions(viewerId, allowClicks, allowTyping);
    }

    private static void handleDefaultPermissionUpdate(ServerPlayerEntity player, DataInputStream input) throws IOException {
        RelaySession session = getHostedSession(player);
        if (session == null) {
            return;
        }

        session.defaultAllowClicks = input.readBoolean();
        session.defaultAllowTyping = input.readBoolean();
        sendHostState(player, session);
        session.broadcastRemoteState();
    }

    private static void handlePanelState(ServerPlayerEntity player, DataInputStream input) throws IOException {
        RelaySession session = getHostedSession(player);
        if (session == null) {
            return;
        }

        session.lastSnapshot = RelayProtocol.readPanelSnapshot(input);
        for (ServerPlayerEntity viewer : session.getViewerPlayers()) {
            sendPanelState(viewer, session.lastSnapshot);
        }
    }

    private static void handleFrame(ServerPlayerEntity player, DataInputStream input) throws IOException {
        RelaySession session = getHostedSession(player);
        if (session == null) {
            return;
        }

        session.lastFrameWidth = input.readInt();
        session.lastFrameHeight = input.readInt();
        int length = input.readInt();
        session.lastFrameBytes = input.readNBytes(length);
        for (ServerPlayerEntity viewer : session.getViewerPlayers()) {
            sendFrame(viewer, session.lastFrameWidth, session.lastFrameHeight, session.lastFrameBytes);
        }
    }

    private static void handleClear(ServerPlayerEntity player) {
        RelaySession session = getHostedSession(player);
        if (session == null) {
            return;
        }

        session.lastSnapshot = null;
        session.lastFrameBytes = null;
        session.lastFrameWidth = 0;
        session.lastFrameHeight = 0;
        for (ServerPlayerEntity viewer : session.getViewerPlayers()) {
            sendClear(viewer);
        }
    }

    private static void handleRemoteMouse(ServerPlayerEntity player, DataInputStream input) throws IOException {
        RelaySession session = getJoinedSession(player);
        if (session == null) {
            return;
        }

        ViewerState state = session.viewers.get(player.getUuid());
        if (state == null || !state.allowClicks) {
            return;
        }

        int button = input.readInt();
        int action = input.readInt();
        double u = input.readDouble();
        double v = input.readDouble();
        sendRemoteMouse(session.getHostPlayer(), button, action, u, v);
    }

    private static void handleRemoteKey(ServerPlayerEntity player, DataInputStream input) throws IOException {
        RelaySession session = getJoinedSession(player);
        if (session == null) {
            return;
        }

        ViewerState state = session.viewers.get(player.getUuid());
        if (state == null || !state.allowTyping) {
            return;
        }

        sendRemoteKey(session.getHostPlayer(), input.readInt(), input.readInt());
    }

    private static void handleRemoteChar(ServerPlayerEntity player, DataInputStream input) throws IOException {
        RelaySession session = getJoinedSession(player);
        if (session == null) {
            return;
        }

        ViewerState state = session.viewers.get(player.getUuid());
        if (state == null || !state.allowTyping) {
            return;
        }

        sendRemoteChar(session.getHostPlayer(), input.readInt());
    }

    private static void handleDisconnect(ServerPlayerEntity player) {
        leaveSession(player, true);
        stopHosting(player);
    }

    private static void stopHosting(ServerPlayerEntity player) {
        String sessionId = SESSION_BY_HOST.remove(player.getUuid());
        if (sessionId == null) {
            return;
        }

        RelaySession session = SESSIONS_BY_ID.remove(sessionId);
        if (session == null) {
            return;
        }

        sendHostState(player, null);

        for (UUID viewerUuid : new ArrayList<>(session.viewers.keySet())) {
            SESSION_BY_VIEWER.remove(viewerUuid);
            ServerPlayerEntity viewer = session.server.getPlayerManager().getPlayer(viewerUuid);
            if (viewer != null) {
                sendRemoteState(viewer, null, new RelayViewerAccess(false, false));
                sendClear(viewer);
            }
        }
    }

    private static void leaveSession(ServerPlayerEntity player, boolean clearRemote) {
        String sessionId = SESSION_BY_VIEWER.remove(player.getUuid());
        if (sessionId == null) {
            return;
        }

        RelaySession session = SESSIONS_BY_ID.get(sessionId);
        if (session == null) {
            if (clearRemote) {
                sendRemoteState(player, null, new RelayViewerAccess(false, false));
                sendClear(player);
            }
            return;
        }

        session.viewers.remove(player.getUuid());
        sendRemoteState(player, null, new RelayViewerAccess(false, false));
        if (clearRemote) {
            sendClear(player);
        }
        sendHostState(session.getHostPlayer(), session);
        session.broadcastRemoteState();
    }

    private static void sendSessionList(ServerPlayerEntity player) {
        List<RelaySessionInfo> sessions = new ArrayList<>();
        for (RelaySession session : SESSIONS_BY_ID.values()) {
            sessions.add(session.toInfo(player.getUuid()));
        }
        sessions.sort(Comparator.comparing(RelaySessionInfo::localHost).reversed().thenComparing(RelaySessionInfo::name, String.CASE_INSENSITIVE_ORDER));

        send(player, output -> {
            output.writeByte(RelayProtocol.S2C_SESSION_LIST);
            output.writeInt(sessions.size());
            for (RelaySessionInfo session : sessions) {
                RelayProtocol.writeSessionInfo(output, session);
            }
        });
    }

    private static void sendHostState(ServerPlayerEntity player, RelaySession session) {
        if (player == null) {
            return;
        }

        send(player, output -> {
            output.writeByte(RelayProtocol.S2C_HOST_STATE);
            RelayProtocol.writeNullableSessionInfo(output, session == null ? null : session.toInfo(player.getUuid()));
            RelayProtocol.writeViewerInfoList(output, session == null ? List.of() : session.getViewerInfos());
        });
    }

    private static void sendRemoteState(ServerPlayerEntity player, RelaySession session, RelayViewerAccess access) {
        if (player == null) {
            return;
        }

        send(player, output -> {
            output.writeByte(RelayProtocol.S2C_REMOTE_STATE);
            RelayProtocol.writeNullableSessionInfo(output, session == null ? null : session.toInfo(player.getUuid()));
            RelayProtocol.writeViewerAccess(output, access);
        });
    }

    private static void sendPanelState(ServerPlayerEntity player, RelayPanelSnapshot snapshot) {
        send(player, output -> {
            output.writeByte(RelayProtocol.S2C_PANEL_STATE);
            RelayProtocol.writePanelSnapshot(output, snapshot);
        });
    }

    private static void sendFrame(ServerPlayerEntity player, int width, int height, byte[] frameBytes) {
        send(player, output -> {
            output.writeByte(RelayProtocol.S2C_FRAME);
            output.writeInt(width);
            output.writeInt(height);
            output.writeInt(frameBytes.length);
            output.write(frameBytes);
        });
    }

    private static void sendClear(ServerPlayerEntity player) {
        send(player, output -> output.writeByte(RelayProtocol.S2C_CLEAR));
    }

    private static void sendActionResult(ServerPlayerEntity player, int actionType, boolean success, String message) {
        send(player, output -> {
            output.writeByte(RelayProtocol.S2C_ACTION_RESULT);
            output.writeByte(actionType);
            output.writeBoolean(success);
            output.writeUTF(message == null ? "" : message);
        });
    }

    private static void sendRemoteMouse(ServerPlayerEntity player, int button, int action, double u, double v) {
        send(player, output -> {
            output.writeByte(RelayProtocol.S2C_REMOTE_MOUSE);
            output.writeInt(button);
            output.writeInt(action);
            output.writeDouble(u);
            output.writeDouble(v);
        });
    }

    private static void sendRemoteKey(ServerPlayerEntity player, int action, int keycode) {
        send(player, output -> {
            output.writeByte(RelayProtocol.S2C_REMOTE_KEY);
            output.writeInt(action);
            output.writeInt(keycode);
        });
    }

    private static void sendRemoteChar(ServerPlayerEntity player, int codepoint) {
        send(player, output -> {
            output.writeByte(RelayProtocol.S2C_REMOTE_CHAR);
            output.writeInt(codepoint);
        });
    }

    private static void send(ServerPlayerEntity player, PacketWriter writer) {
        if (player == null) {
            return;
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            ServerPlayNetworking.send(player, new AutismTVPayloads.ServerToClientPayload(bytes.toByteArray()));
        } catch (IOException exception) {
            AutismTVMod.LOGGER.debug("Failed to send relay packet", exception);
        }
    }

    private static RelaySession getHostedSession(ServerPlayerEntity player) {
        String sessionId = SESSION_BY_HOST.get(player.getUuid());
        return sessionId == null ? null : SESSIONS_BY_ID.get(sessionId);
    }

    private static RelaySession getJoinedSession(ServerPlayerEntity player) {
        String sessionId = SESSION_BY_VIEWER.get(player.getUuid());
        return sessionId == null ? null : SESSIONS_BY_ID.get(sessionId);
    }

    private static String sanitizeName(String value) {
        if (value == null || value.isBlank()) {
            return "AutismTV Session";
        }
        return value.trim();
    }

    private static String sanitizeDescription(String value) {
        return value == null ? "" : value.trim();
    }

    private interface PacketWriter {
        void write(DataOutputStream output) throws IOException;
    }

    private static final class RelaySession {
        private final MinecraftServer server;
        private final UUID hostUuid;
        private final String sessionId = UUID.randomUUID().toString();
        private final String password;
        private final String leaderName;
        private final Map<UUID, ViewerState> viewers = new HashMap<>();
        private String sessionName;
        private String description;
        private boolean defaultAllowClicks;
        private boolean defaultAllowTyping;
        private RelayPanelSnapshot lastSnapshot;
        private byte[] lastFrameBytes;
        private int lastFrameWidth;
        private int lastFrameHeight;

        private RelaySession(MinecraftServer server, String hostUuid, String sessionName, String description, String password, String leaderName,
                             boolean defaultAllowClicks, boolean defaultAllowTyping) {
            this.server = server;
            this.hostUuid = UUID.fromString(hostUuid);
            this.sessionName = sessionName;
            this.description = description;
            this.password = password == null ? "" : password;
            this.leaderName = leaderName;
            this.defaultAllowClicks = defaultAllowClicks;
            this.defaultAllowTyping = defaultAllowTyping;
        }

        private RelaySessionInfo toInfo(UUID perspectivePlayer) {
            return new RelaySessionInfo(this.sessionId, this.sessionName, this.description, this.leaderName, "", 0,
                    !this.password.isEmpty(), this.viewers.size() + 1, this.defaultAllowClicks, this.defaultAllowTyping,
                    this.hostUuid.equals(perspectivePlayer));
        }

        private List<RelayViewerInfo> getViewerInfos() {
            List<RelayViewerInfo> infos = new ArrayList<>(this.viewers.size());
            for (ViewerState state : this.viewers.values()) {
                infos.add(new RelayViewerInfo(state.viewerId, state.viewerName, state.allowClicks, state.allowTyping));
            }
            infos.sort(Comparator.comparing(RelayViewerInfo::viewerName, String.CASE_INSENSITIVE_ORDER));
            return infos;
        }

        private RelayViewerAccess getViewerAccess(UUID viewerUuid) {
            ViewerState state = this.viewers.get(viewerUuid);
            return state == null ? new RelayViewerAccess(false, false) : new RelayViewerAccess(state.allowClicks, state.allowTyping);
        }

        private void updateViewerPermissions(String viewerId, boolean allowClicks, boolean allowTyping) {
            UUID targetUuid = null;
            for (Map.Entry<UUID, ViewerState> entry : this.viewers.entrySet()) {
                if (entry.getValue().viewerId.equals(viewerId)) {
                    targetUuid = entry.getKey();
                    break;
                }
            }
            if (targetUuid == null) {
                return;
            }

            ViewerState current = this.viewers.get(targetUuid);
            this.viewers.put(targetUuid, new ViewerState(current.viewerId, current.viewerName, allowClicks, allowTyping));
            ServerPlayerEntity viewer = this.server.getPlayerManager().getPlayer(targetUuid);
            if (viewer != null) {
                sendRemoteState(viewer, this, new RelayViewerAccess(allowClicks, allowTyping));
            }
            sendHostState(this.getHostPlayer(), this);
        }

        private ServerPlayerEntity getHostPlayer() {
            return this.server.getPlayerManager().getPlayer(this.hostUuid);
        }

        private List<ServerPlayerEntity> getViewerPlayers() {
            ServerPlayerEntity host = this.getHostPlayer();
            if (host == null) {
                return List.of();
            }

            List<ServerPlayerEntity> players = new ArrayList<>();
            for (UUID viewerUuid : this.viewers.keySet()) {
                ServerPlayerEntity viewer = this.server.getPlayerManager().getPlayer(viewerUuid);
                if (viewer != null) {
                    players.add(viewer);
                }
            }
            return players;
        }

        private void broadcastRemoteState() {
            for (ServerPlayerEntity viewer : this.getViewerPlayers()) {
                sendRemoteState(viewer, this, this.getViewerAccess(viewer.getUuid()));
            }
        }
    }

    private record ViewerState(String viewerId, String viewerName, boolean allowClicks, boolean allowTyping) {
    }
}