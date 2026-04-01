package dev.novab.autismtv.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.MinecraftClient;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PeerShareTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger("autismtv/peer-share");
    private static final int DEFAULT_PORT = 51234;
    private static final int DISCOVERY_PORT = 51235;
    private static final int PROTOCOL_VERSION = 5;
    private static final int MESSAGE_META = 1;
    private static final int MESSAGE_PANEL_STATE = 2;
    private static final int MESSAGE_FRAME = 3;
    private static final int MESSAGE_CLEAR = 4;
    private static final int MESSAGE_REMOTE_MOUSE = 5;
    private static final int MESSAGE_REMOTE_KEY = 6;
    private static final int MESSAGE_REMOTE_CHAR = 7;
    private static final long DISCOVERY_TTL_MS = 4_000L;
    private static final String VIEWER_ID = UUID.randomUUID().toString();
    private static final Path LOCAL_SESSION_REGISTRY = Path.of(System.getProperty("java.io.tmpdir"), "autismtv-local-sessions.txt");

    private static final Map<String, DiscoveredSession> DISCOVERED_SESSIONS = new ConcurrentHashMap<>();

    private static DatagramSocket discoverySocket;
    private static ExecutorService discoveryExecutor;
    private static HostSession hostSession;
    private static ClientSession clientSession;
    private static volatile SessionInfo currentRemoteSession;
    private static volatile ViewerAccess currentViewerAccess = new ViewerAccess(false, false);

    private PeerShareTransport() {
    }

    static int getDefaultPort() {
        return DEFAULT_PORT;
    }

    static synchronized void initialize() {
        if (discoveryExecutor != null) {
            return;
        }

        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            discoverySocket = socket;
            discoveryExecutor = Executors.newSingleThreadExecutor(runnable -> createThread(runnable, "autismtv-peer-discovery"));
            discoveryExecutor.execute(PeerShareTransport::discoveryLoop);
        } catch (SocketException exception) {
            LOGGER.warn("Failed to initialize peer discovery", exception);
        }
    }

    static synchronized String startHosting(String sessionName, String description, String password, int port,
                                            boolean defaultAllowClicks, boolean defaultAllowTyping) throws IOException {
        disconnect();
        stopHosting();
        hostSession = new HostSession(sessionName, description, password, port, defaultAllowClicks, defaultAllowTyping);
        return hostSession.getBindAddress();
    }

    static synchronized void stopHosting() {
        if (hostSession != null) {
            hostSession.close();
            hostSession = null;
        }
    }

    static synchronized SessionInfo getHostedSessionInfo() {
        return hostSession != null ? hostSession.getSessionInfo(true) : null;
    }

    static synchronized List<ViewerInfo> getConnectedViewers() {
        return hostSession != null ? hostSession.getViewerInfos() : List.of();
    }

    static synchronized void updateViewerPermissions(String viewerId, boolean allowClicks, boolean allowTyping) {
        if (hostSession != null) {
            hostSession.updateViewerPermissions(viewerId, allowClicks, allowTyping);
        }
    }

    static synchronized void updateDefaultPermissions(boolean allowClicks, boolean allowTyping) {
        if (hostSession != null) {
            hostSession.updateDefaultPermissions(allowClicks, allowTyping);
        }
    }

    static synchronized void join(SessionInfo session, String password) throws IOException {
        stopHosting();
        disconnect();
        clientSession = new ClientSession(session.host(), session.port(), password == null ? "" : password);
    }

    static synchronized void join(String host, int port, String password) throws IOException {
        stopHosting();
        disconnect();
        clientSession = new ClientSession(host, port, password == null ? "" : password);
    }

    static synchronized void disconnect() {
        if (clientSession != null) {
            clientSession.close();
            clientSession = null;
        }

        currentRemoteSession = null;
        currentViewerAccess = new ViewerAccess(false, false);
        LocalPanelController.clearRemoteShare();
    }

    static synchronized SessionInfo getCurrentRemoteSession() {
        return currentRemoteSession;
    }

    static synchronized boolean canRemoteClick() {
        return currentViewerAccess.allowClicks();
    }

    static synchronized boolean canRemoteType() {
        return currentViewerAccess.allowTyping();
    }

    static synchronized List<SessionInfo> getDiscoveredSessions() {
        long now = System.currentTimeMillis();
        List<SessionInfo> sessions = new ArrayList<>();

        for (Map.Entry<String, DiscoveredSession> entry : DISCOVERED_SESSIONS.entrySet()) {
            if (now - entry.getValue().lastSeenMs() > DISCOVERY_TTL_MS) {
                DISCOVERED_SESSIONS.remove(entry.getKey());
                continue;
            }

            sessions.add(entry.getValue().session());
        }

        sessions.addAll(readLocalSessions(now));

        SessionInfo hosted = getHostedSessionInfo();
        if (hosted != null) {
            sessions.removeIf(session -> session.id().equals(hosted.id()));
            sessions.add(hosted);
        }

        sessions.sort(Comparator.comparing(SessionInfo::localHost).reversed().thenComparing(SessionInfo::name, String.CASE_INSENSITIVE_ORDER));
        List<SessionInfo> deduped = new ArrayList<>();

        for (SessionInfo session : sessions) {
            boolean exists = deduped.stream().anyMatch(existing -> existing.id().equals(session.id()));
            if (!exists) {
                deduped.add(session);
            }
        }

        return deduped;
    }

    static synchronized void broadcastPanelState(LocalPanelController.PanelSnapshot snapshot) {
        if (hostSession != null) {
            hostSession.broadcastPanelState(snapshot);
        }
    }

    static synchronized void broadcastFrame(int width, int height, int[] argbPixels) {
        if (hostSession != null) {
            hostSession.broadcastFrame(width, height, argbPixels);
        }
    }

    static synchronized void broadcastClear() {
        if (hostSession != null) {
            hostSession.broadcastClear();
        }
    }

    static synchronized boolean sendRemoteMouse(int button, int action, double u, double v) {
        return clientSession != null && clientSession.sendRemoteMouse(button, action, u, v);
    }

    static synchronized boolean sendRemoteKey(int action, int keycode) {
        return clientSession != null && clientSession.sendRemoteKey(action, keycode);
    }

    static synchronized boolean sendRemoteChar(int codepoint) {
        return clientSession != null && clientSession.sendRemoteChar(codepoint);
    }

    private static void discoveryLoop() {
        byte[] buffer = new byte[2048];

        while (discoverySocket != null && !discoverySocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                discoverySocket.receive(packet);
                String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                String[] parts = message.split("\\|", -1);

                if (parts.length != 10 || !Objects.equals(parts[0], "AUTISMTV")) {
                    continue;
                }

                SessionInfo session = new SessionInfo(parts[1], decodeField(parts[2]), decodeField(parts[3]), decodeField(parts[4]),
                        packet.getAddress().getHostAddress(), Integer.parseInt(parts[5]), "1".equals(parts[6]), Integer.parseInt(parts[7]),
                        "1".equals(parts[8]), "1".equals(parts[9]), false);
                DISCOVERED_SESSIONS.put(session.id(), new DiscoveredSession(session, System.currentTimeMillis()));
            } catch (Exception exception) {
                if (discoverySocket != null && !discoverySocket.isClosed()) {
                    LOGGER.debug("Discovery receive failed", exception);
                }
            }
        }
    }

    private static final class HostSession implements Closeable {
        private final String sessionId = UUID.randomUUID().toString();
        private final String password;
        private final ServerSocket serverSocket;
        private final ExecutorService acceptExecutor;
        private final ExecutorService broadcastExecutor;
        private final Map<String, ViewerState> viewerStates = new ConcurrentHashMap<>();
        private final Map<String, ViewerConnection> viewerConnections = new ConcurrentHashMap<>();
        private volatile String sessionName;
        private volatile String description;
        private final String leaderName;
        private volatile boolean defaultAllowClicks;
        private volatile boolean defaultAllowTyping;
        private volatile boolean closed;

        private HostSession(String sessionName, String description, String password, int port,
                            boolean defaultAllowClicks, boolean defaultAllowTyping) throws IOException {
            this.sessionName = sessionName == null || sessionName.isBlank() ? "AutismTV Session" : sessionName.trim();
            this.description = description == null ? "" : description.trim();
            this.password = password == null ? "" : password;
            this.leaderName = getViewerName();
            this.defaultAllowClicks = defaultAllowClicks;
            this.defaultAllowTyping = defaultAllowTyping;
            this.serverSocket = new ServerSocket(port);
            this.acceptExecutor = Executors.newSingleThreadExecutor(runnable -> createThread(runnable, "autismtv-peer-host"));
            this.broadcastExecutor = Executors.newSingleThreadExecutor(runnable -> createThread(runnable, "autismtv-peer-advertise"));
            this.acceptExecutor.execute(this::acceptLoop);
            this.broadcastExecutor.execute(this::broadcastLoop);
        }

        private SessionInfo getSessionInfo(boolean localHost) {
            return new SessionInfo(this.sessionId, this.sessionName, this.description, this.leaderName, getSelfAddress(), this.serverSocket.getLocalPort(),
                    !this.password.isEmpty(), this.viewerStates.size() + 1, this.defaultAllowClicks, this.defaultAllowTyping, localHost);
        }

        private String getBindAddress() {
            return getSelfAddress() + ":" + this.serverSocket.getLocalPort();
        }

        private List<ViewerInfo> getViewerInfos() {
            List<ViewerInfo> viewers = new ArrayList<>();

            for (ViewerState state : this.viewerStates.values()) {
                viewers.add(new ViewerInfo(state.viewerId(), state.viewerName(), state.allowClicks(), state.allowTyping()));
            }

            viewers.sort(Comparator.comparing(ViewerInfo::viewerName, String.CASE_INSENSITIVE_ORDER));
            return viewers;
        }

        private void updateViewerPermissions(String viewerId, boolean allowClicks, boolean allowTyping) {
            ViewerState current = this.viewerStates.get(viewerId);
            if (current == null) {
                return;
            }

            ViewerState updated = new ViewerState(current.viewerId(), current.viewerName(), allowClicks, allowTyping);
            this.viewerStates.put(viewerId, updated);
            ViewerConnection connection = this.viewerConnections.get(viewerId);
            if (connection != null) {
                connection.sendMeta(this.getSessionInfo(false), new ViewerAccess(allowClicks, allowTyping));
            }
        }

        private void updateDefaultPermissions(boolean allowClicks, boolean allowTyping) {
            this.defaultAllowClicks = allowClicks;
            this.defaultAllowTyping = allowTyping;
            this.broadcastMetaToAll();
        }

        private void acceptLoop() {
            while (!this.closed) {
                try {
                    Socket socket = this.serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    int version = input.readInt();
                    String passwordAttempt = input.readUTF();
                    String viewerId = input.readUTF();
                    String viewerName = input.readUTF();

                    if (version != PROTOCOL_VERSION || !this.password.equals(passwordAttempt)) {
                        output.writeBoolean(false);
                        output.writeUTF(version != PROTOCOL_VERSION ? "Protocol mismatch" : "Incorrect password");
                        output.flush();
                        socket.close();
                        continue;
                    }

                    output.writeBoolean(true);
                    output.flush();

                    ViewerState state = new ViewerState(viewerId, viewerName, this.defaultAllowClicks, this.defaultAllowTyping);
                    this.viewerStates.put(viewerId, state);

                    ViewerConnection connection = new ViewerConnection(viewerId, socket, input, output, this);
                    this.viewerConnections.put(viewerId, connection);
                    connection.start();
                    connection.sendMeta(this.getSessionInfo(false), new ViewerAccess(state.allowClicks(), state.allowTyping()));
                    this.broadcastMetaToAll();

                    LocalPanelController.PanelSnapshot snapshot = LocalPanelController.getActivePanelSnapshot();
                    if (snapshot != null) {
                        connection.sendPanelState(snapshot);
                    }
                } catch (IOException exception) {
                    if (!this.closed) {
                        LOGGER.warn("Peer host accept failed", exception);
                    }
                }
            }
        }

        private void broadcastLoop() {
            while (!this.closed) {
                broadcastDiscovery();
                writeLocalSession(this.getSessionInfo(false));

                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void broadcastDiscovery() {
            SessionInfo session = this.getSessionInfo(false);
            String payload = String.join("|", "AUTISMTV", session.id(), encodeField(session.name()), encodeField(session.description()),
                    encodeField(session.leaderName()), Integer.toString(session.port()), session.passwordProtected() ? "1" : "0",
                    Integer.toString(session.participantCount()), session.defaultAllowClicks() ? "1" : "0", session.defaultAllowTyping() ? "1" : "0");
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);

            sendDiscoveryPacket(data, "255.255.255.255");
            sendDiscoveryPacket(data, "127.0.0.1");
        }

        private void broadcastMetaToAll() {
            SessionInfo sessionInfo = this.getSessionInfo(false);
            for (ViewerConnection connection : this.viewerConnections.values()) {
                ViewerAccess access = this.getViewerAccess(connection.viewerId);
                connection.sendMeta(sessionInfo, access);
            }
        }

        private void sendDiscoveryPacket(byte[] data, String host) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(host), DISCOVERY_PORT));
            } catch (IOException exception) {
                LOGGER.debug("Failed to broadcast discovery to {}", host, exception);
            }
        }

        private void broadcastPanelState(LocalPanelController.PanelSnapshot snapshot) {
            for (ViewerConnection connection : this.viewerConnections.values()) {
                connection.sendPanelState(snapshot);
            }
        }

        private void broadcastFrame(int width, int height, int[] argbPixels) {
            byte[] frameBytes = encodeFrame(width, height, argbPixels);
            if (frameBytes == null) {
                return;
            }

            for (ViewerConnection connection : this.viewerConnections.values()) {
                connection.sendFrame(width, height, frameBytes);
            }
        }

        private void broadcastClear() {
            for (ViewerConnection connection : this.viewerConnections.values()) {
                connection.sendClear();
            }
        }

        private ViewerAccess getViewerAccess(String viewerId) {
            ViewerState state = this.viewerStates.get(viewerId);
            return state == null ? new ViewerAccess(false, false) : new ViewerAccess(state.allowClicks(), state.allowTyping());
        }

        private void removeViewer(String viewerId) {
            this.viewerStates.remove(viewerId);
            this.viewerConnections.remove(viewerId);
            if (!this.closed) {
                this.broadcastMetaToAll();
            }
        }

        @Override
        public void close() {
            this.closed = true;
            removeLocalSession(this.sessionId);
            try {
                this.serverSocket.close();
            } catch (IOException exception) {
                LOGGER.debug("Failed to close host socket", exception);
            }
            this.acceptExecutor.shutdownNow();
            this.broadcastExecutor.shutdownNow();
            for (ViewerConnection connection : new ArrayList<>(this.viewerConnections.values())) {
                connection.close();
            }
            this.viewerStates.clear();
            this.viewerConnections.clear();
        }
    }

    private static final class ClientSession implements Closeable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final ExecutorService readExecutor;
        private volatile boolean closed;

        private ClientSession(String host, int port, String password) throws IOException {
            this.socket = new Socket(host, port);
            this.socket.setTcpNoDelay(true);
            this.input = new DataInputStream(new BufferedInputStream(this.socket.getInputStream()));
            this.output = new DataOutputStream(new BufferedOutputStream(this.socket.getOutputStream()));
            this.output.writeInt(PROTOCOL_VERSION);
            this.output.writeUTF(password);
            this.output.writeUTF(VIEWER_ID);
            this.output.writeUTF(getViewerName());
            this.output.flush();

            boolean accepted = this.input.readBoolean();
            if (!accepted) {
                String reason = this.input.readUTF();
                this.socket.close();
                throw new IOException(reason);
            }

            this.readExecutor = Executors.newSingleThreadExecutor(runnable -> createThread(runnable, "autismtv-peer-client"));
            this.readExecutor.execute(this::readLoop);
        }

        private void readLoop() {
            try {
                while (!this.closed) {
                    int messageType = this.input.readUnsignedByte();
                    if (messageType == MESSAGE_META) {
                        currentRemoteSession = readSessionInfo(this.input);
                        currentViewerAccess = readViewerAccess(this.input);
                    } else if (messageType == MESSAGE_PANEL_STATE) {
                        LocalPanelController.applyRemotePanel(readPanelSnapshot(this.input));
                    } else if (messageType == MESSAGE_FRAME) {
                        int width = this.input.readInt();
                        int height = this.input.readInt();
                        int length = this.input.readInt();
                        byte[] data = this.input.readNBytes(length);
                        int[] pixels = decodeFrame(data, width, height);
                        if (pixels != null) {
                            LocalPanelController.applyRemoteFrame(width, height, pixels);
                        }
                    } else if (messageType == MESSAGE_CLEAR) {
                        LocalPanelController.clearRemoteShare();
                    } else {
                        throw new IOException("Unknown message type: " + messageType);
                    }
                }
            } catch (EOFException exception) {
                LOGGER.info("Peer session closed");
            } catch (IOException exception) {
                if (!this.closed) {
                    LOGGER.warn("Peer client failed", exception);
                }
            } finally {
                close();
                currentRemoteSession = null;
                currentViewerAccess = new ViewerAccess(false, false);
                LocalPanelController.clearRemoteShare();
            }
        }

        private boolean sendRemoteMouse(int button, int action, double u, double v) {
            return this.send(writer -> {
                writer.writeByte(MESSAGE_REMOTE_MOUSE);
                writer.writeInt(button);
                writer.writeInt(action);
                writer.writeDouble(u);
                writer.writeDouble(v);
            });
        }

        private boolean sendRemoteKey(int action, int keycode) {
            return this.send(writer -> {
                writer.writeByte(MESSAGE_REMOTE_KEY);
                writer.writeInt(action);
                writer.writeInt(keycode);
            });
        }

        private boolean sendRemoteChar(int codepoint) {
            return this.send(writer -> {
                writer.writeByte(MESSAGE_REMOTE_CHAR);
                writer.writeInt(codepoint);
            });
        }

        private boolean send(PacketWriter writer) {
            synchronized (this.output) {
                try {
                    writer.write(this.output);
                    this.output.flush();
                    return true;
                } catch (IOException exception) {
                    close();
                    return false;
                }
            }
        }

        @Override
        public void close() {
            this.closed = true;
            try {
                this.socket.close();
            } catch (IOException exception) {
                LOGGER.debug("Failed to close client socket", exception);
            }
            this.readExecutor.shutdownNow();
        }
    }

    private static final class ViewerConnection implements Closeable {
        private final String viewerId;
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final HostSession owner;
        private final ExecutorService readExecutor;
        private volatile boolean closed;

        private ViewerConnection(String viewerId, Socket socket, DataInputStream input, DataOutputStream output, HostSession owner) {
            this.viewerId = viewerId;
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.owner = owner;
            this.readExecutor = Executors.newSingleThreadExecutor(runnable -> createThread(runnable, "autismtv-peer-viewer"));
        }

        private void start() {
            this.readExecutor.execute(this::readLoop);
        }

        private void readLoop() {
            try {
                while (!this.closed) {
                    int messageType = this.input.readUnsignedByte();
                    ViewerAccess access = this.owner.getViewerAccess(this.viewerId);

                    if (messageType == MESSAGE_REMOTE_MOUSE) {
                        int button = this.input.readInt();
                        int action = this.input.readInt();
                        double u = this.input.readDouble();
                        double v = this.input.readDouble();
                        if (access.allowClicks()) {
                            LocalPanelController.handleRemoteMouseInput(button, action, u, v);
                        }
                    } else if (messageType == MESSAGE_REMOTE_KEY) {
                        int action = this.input.readInt();
                        int keycode = this.input.readInt();
                        if (access.allowTyping()) {
                            LocalPanelController.handleRemoteKeyInput(action, keycode);
                        }
                    } else if (messageType == MESSAGE_REMOTE_CHAR) {
                        int codepoint = this.input.readInt();
                        if (access.allowTyping()) {
                            LocalPanelController.handleRemoteCharInput(codepoint);
                        }
                    } else {
                        throw new IOException("Unexpected viewer message type: " + messageType);
                    }
                }
            } catch (EOFException exception) {
                LOGGER.debug("Viewer disconnected");
            } catch (IOException exception) {
                if (!this.closed) {
                    LOGGER.debug("Viewer read failed", exception);
                }
            } finally {
                close();
            }
        }

        private void sendMeta(SessionInfo sessionInfo, ViewerAccess access) {
            this.send(writer -> {
                writer.writeByte(MESSAGE_META);
                writeSessionInfo(writer, sessionInfo);
                writeViewerAccess(writer, access);
            });
        }

        private void sendPanelState(LocalPanelController.PanelSnapshot snapshot) {
            this.send(writer -> {
                writer.writeByte(MESSAGE_PANEL_STATE);
                writePanelSnapshot(writer, snapshot);
            });
        }

        private void sendFrame(int width, int height, byte[] bytes) {
            this.send(writer -> {
                writer.writeByte(MESSAGE_FRAME);
                writer.writeInt(width);
                writer.writeInt(height);
                writer.writeInt(bytes.length);
                writer.write(bytes);
            });
        }

        private void sendClear() {
            this.send(writer -> writer.writeByte(MESSAGE_CLEAR));
        }

        private void send(PacketWriter writer) {
            synchronized (this.output) {
                try {
                    writer.write(this.output);
                    this.output.flush();
                } catch (IOException exception) {
                    close();
                }
            }
        }

        @Override
        public void close() {
            this.closed = true;
            this.owner.removeViewer(this.viewerId);
            try {
                this.socket.close();
            } catch (IOException exception) {
                LOGGER.debug("Failed to close viewer socket", exception);
            }
            this.readExecutor.shutdownNow();
        }
    }

    private static void writeSessionInfo(DataOutputStream output, SessionInfo info) throws IOException {
        output.writeUTF(info.id());
        output.writeUTF(info.name());
        output.writeUTF(info.description());
        output.writeUTF(info.leaderName());
        output.writeUTF(info.host());
        output.writeInt(info.port());
        output.writeBoolean(info.passwordProtected());
        output.writeInt(info.participantCount());
        output.writeBoolean(info.defaultAllowClicks());
        output.writeBoolean(info.defaultAllowTyping());
        output.writeBoolean(info.localHost());
    }

    private static SessionInfo readSessionInfo(DataInputStream input) throws IOException {
        return new SessionInfo(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readInt(), input.readBoolean(),
                input.readInt(), input.readBoolean(), input.readBoolean(), input.readBoolean());
    }

    private static void writeViewerAccess(DataOutputStream output, ViewerAccess access) throws IOException {
        output.writeBoolean(access.allowClicks());
        output.writeBoolean(access.allowTyping());
    }

    private static ViewerAccess readViewerAccess(DataInputStream input) throws IOException {
        return new ViewerAccess(input.readBoolean(), input.readBoolean());
    }

    private static void writePanelSnapshot(DataOutputStream output, LocalPanelController.PanelSnapshot snapshot) throws IOException {
        output.writeDouble(snapshot.centerX());
        output.writeDouble(snapshot.centerY());
        output.writeDouble(snapshot.centerZ());
        output.writeDouble(snapshot.normalX());
        output.writeDouble(snapshot.normalY());
        output.writeDouble(snapshot.normalZ());
        output.writeDouble(snapshot.rightX());
        output.writeDouble(snapshot.rightY());
        output.writeDouble(snapshot.rightZ());
        output.writeDouble(snapshot.upX());
        output.writeDouble(snapshot.upY());
        output.writeDouble(snapshot.upZ());
        output.writeDouble(snapshot.halfWidth());
        output.writeDouble(snapshot.halfHeight());
        output.writeDouble(snapshot.halfThickness());
        output.writeBoolean(snapshot.billboard());
        output.writeDouble(snapshot.rotationDegrees());
    }

    private static LocalPanelController.PanelSnapshot readPanelSnapshot(DataInputStream input) throws IOException {
        return new LocalPanelController.PanelSnapshot(
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readDouble(),
            input.readBoolean(), input.readDouble());
    }

    private static byte[] encodeFrame(int width, int height, int[] argbPixels) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            for (int index = 0; index < pixels.length && index < argbPixels.length; index++) {
                pixels[index] = argbPixels[index] & 0x00FFFFFF;
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", output);
            return output.toByteArray();
        } catch (IOException exception) {
            LOGGER.debug("Failed to encode frame", exception);
            return null;
        }
    }

    private static int[] decodeFrame(byte[] data, int width, int height) {
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
            if (decoded == null) {
                return null;
            }

            BufferedImage converted = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = converted.createGraphics();
            graphics.drawImage(decoded, 0, 0, width, height, null);
            graphics.dispose();
            return ((DataBufferInt) converted.getRaster().getDataBuffer()).getData().clone();
        } catch (IOException exception) {
            LOGGER.debug("Failed to decode frame", exception);
            return null;
        }
    }

    private static String getSelfAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception exception) {
            return "127.0.0.1";
        }
    }

    private static String getViewerName() {
        try {
            return Objects.requireNonNullElse(MinecraftClient.getInstance().getSession().getUsername(), "Viewer");
        } catch (Exception exception) {
            return "Viewer";
        }
    }

    private static List<SessionInfo> readLocalSessions(long now) {
        List<SessionInfo> sessions = new ArrayList<>();

        if (!Files.exists(LOCAL_SESSION_REGISTRY)) {
            return sessions;
        }

        try {
            List<String> lines = Files.readAllLines(LOCAL_SESSION_REGISTRY, StandardCharsets.UTF_8);
            List<String> retained = new ArrayList<>();

            for (String line : lines) {
                String[] parts = line.split("\t", -1);
                if (parts.length != 11) {
                    continue;
                }

                long lastSeen = Long.parseLong(parts[10]);
                if (now - lastSeen > DISCOVERY_TTL_MS) {
                    continue;
                }

                retained.add(line);
                sessions.add(new SessionInfo(parts[0], decodeField(parts[1]), decodeField(parts[2]), decodeField(parts[3]), parts[4], Integer.parseInt(parts[5]),
                        Boolean.parseBoolean(parts[6]), Integer.parseInt(parts[7]), Boolean.parseBoolean(parts[8]), Boolean.parseBoolean(parts[9]), false));
            }

            Files.write(LOCAL_SESSION_REGISTRY, retained, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception exception) {
            LOGGER.debug("Failed to read local session registry", exception);
        }

        return sessions;
    }

    private static synchronized void writeLocalSession(SessionInfo session) {
        try {
            List<String> lines = Files.exists(LOCAL_SESSION_REGISTRY)
                    ? new ArrayList<>(Files.readAllLines(LOCAL_SESSION_REGISTRY, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            String prefix = session.id() + "\t";
            lines.removeIf(line -> line.startsWith(prefix));
                lines.add(String.join("\t", session.id(), encodeField(session.name()), encodeField(session.description()), encodeField(session.leaderName()),
                    session.host(), Integer.toString(session.port()), Boolean.toString(session.passwordProtected()), Integer.toString(session.participantCount()),
                    Boolean.toString(session.defaultAllowClicks()), Boolean.toString(session.defaultAllowTyping()), Long.toString(System.currentTimeMillis())));
            Files.write(LOCAL_SESSION_REGISTRY, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception exception) {
            LOGGER.debug("Failed to write local session registry", exception);
        }
    }

    private static synchronized void removeLocalSession(String sessionId) {
        if (!Files.exists(LOCAL_SESSION_REGISTRY)) {
            return;
        }

        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(LOCAL_SESSION_REGISTRY, StandardCharsets.UTF_8));
            lines.removeIf(line -> line.startsWith(sessionId + "\t"));
            Files.write(LOCAL_SESSION_REGISTRY, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception exception) {
            LOGGER.debug("Failed to remove local session registry entry", exception);
        }
    }

    private static Thread createThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String encodeField(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private interface PacketWriter {
        void write(DataOutputStream output) throws IOException;
    }

    private record DiscoveredSession(SessionInfo session, long lastSeenMs) {
    }

    record SessionInfo(String id, String name, String description, String leaderName, String host, int port, boolean passwordProtected,
                       int participantCount, boolean defaultAllowClicks, boolean defaultAllowTyping, boolean localHost) {
    }

    record ViewerInfo(String viewerId, String viewerName, boolean allowClicks, boolean allowTyping) {
    }

    private record ViewerState(String viewerId, String viewerName, boolean allowClicks, boolean allowTyping) {
    }

    private record ViewerAccess(boolean allowClicks, boolean allowTyping) {
    }
}