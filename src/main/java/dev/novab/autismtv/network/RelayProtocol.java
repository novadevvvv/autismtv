package dev.novab.autismtv.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RelayProtocol {
    public static final byte C2S_REQUEST_SESSION_LIST = 1;
    public static final byte C2S_START_HOSTING = 2;
    public static final byte C2S_STOP_HOSTING = 3;
    public static final byte C2S_JOIN_SESSION = 4;
    public static final byte C2S_LEAVE_SESSION = 5;
    public static final byte C2S_UPDATE_VIEWER_PERMISSIONS = 6;
    public static final byte C2S_UPDATE_DEFAULT_PERMISSIONS = 7;
    public static final byte C2S_PANEL_STATE = 8;
    public static final byte C2S_FRAME = 9;
    public static final byte C2S_CLEAR = 10;
    public static final byte C2S_REMOTE_MOUSE = 11;
    public static final byte C2S_REMOTE_KEY = 12;
    public static final byte C2S_REMOTE_CHAR = 13;

    public static final byte S2C_SESSION_LIST = 21;
    public static final byte S2C_HOST_STATE = 22;
    public static final byte S2C_REMOTE_STATE = 23;
    public static final byte S2C_PANEL_STATE = 24;
    public static final byte S2C_FRAME = 25;
    public static final byte S2C_CLEAR = 26;
    public static final byte S2C_ACTION_RESULT = 27;
    public static final byte S2C_REMOTE_MOUSE = 28;
    public static final byte S2C_REMOTE_KEY = 29;
    public static final byte S2C_REMOTE_CHAR = 30;

    private RelayProtocol() {
    }

    public static void writeNullableSessionInfo(DataOutputStream output, RelaySessionInfo info) throws IOException {
        output.writeBoolean(info != null);
        if (info != null) {
            writeSessionInfo(output, info);
        }
    }

    public static RelaySessionInfo readNullableSessionInfo(DataInputStream input) throws IOException {
        return input.readBoolean() ? readSessionInfo(input) : null;
    }

    public static void writeSessionInfo(DataOutputStream output, RelaySessionInfo info) throws IOException {
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

    public static RelaySessionInfo readSessionInfo(DataInputStream input) throws IOException {
        return new RelaySessionInfo(
                input.readUTF(),
                input.readUTF(),
                input.readUTF(),
                input.readUTF(),
                input.readUTF(),
                input.readInt(),
                input.readBoolean(),
                input.readInt(),
                input.readBoolean(),
                input.readBoolean(),
                input.readBoolean());
    }

    public static void writeViewerInfoList(DataOutputStream output, List<RelayViewerInfo> viewers) throws IOException {
        output.writeInt(viewers.size());
        for (RelayViewerInfo viewer : viewers) {
            output.writeUTF(viewer.viewerId());
            output.writeUTF(viewer.viewerName());
            output.writeBoolean(viewer.allowClicks());
            output.writeBoolean(viewer.allowTyping());
        }
    }

    public static List<RelayViewerInfo> readViewerInfoList(DataInputStream input) throws IOException {
        int count = input.readInt();
        List<RelayViewerInfo> viewers = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            viewers.add(new RelayViewerInfo(input.readUTF(), input.readUTF(), input.readBoolean(), input.readBoolean()));
        }
        return viewers;
    }

    public static void writeViewerAccess(DataOutputStream output, RelayViewerAccess access) throws IOException {
        output.writeBoolean(access.allowClicks());
        output.writeBoolean(access.allowTyping());
    }

    public static RelayViewerAccess readViewerAccess(DataInputStream input) throws IOException {
        return new RelayViewerAccess(input.readBoolean(), input.readBoolean());
    }

    public static void writePanelSnapshot(DataOutputStream output, RelayPanelSnapshot snapshot) throws IOException {
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

    public static RelayPanelSnapshot readPanelSnapshot(DataInputStream input) throws IOException {
        return new RelayPanelSnapshot(
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readBoolean(), input.readDouble());
    }

    public record RelaySessionInfo(String id, String name, String description, String leaderName, String host, int port,
                                   boolean passwordProtected, int participantCount, boolean defaultAllowClicks,
                                   boolean defaultAllowTyping, boolean localHost) {
    }

    public record RelayViewerInfo(String viewerId, String viewerName, boolean allowClicks, boolean allowTyping) {
    }

    public record RelayViewerAccess(boolean allowClicks, boolean allowTyping) {
    }

    public record RelayPanelSnapshot(double centerX, double centerY, double centerZ,
                                     double normalX, double normalY, double normalZ,
                                     double rightX, double rightY, double rightZ,
                                     double upX, double upY, double upZ,
                                     double halfWidth, double halfHeight, double halfThickness,
                                     boolean billboard, double rotationDegrees) {
    }
}