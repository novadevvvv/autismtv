package dev.novab.autismtv.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PanelAnchorManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("autismtv/panel-anchors");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type STORE_TYPE = new TypeToken<Map<String, List<StoredAnchor>>>() { } .getType();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autismtv-panel-anchors.json");

    private static Map<String, List<StoredAnchor>> anchorsByContext;

    private PanelAnchorManager() {
    }

    static synchronized List<PanelAnchor> getCurrentAnchors() {
        ensureLoaded();
        String contextKey = getCurrentContextKey();
        List<StoredAnchor> storedAnchors = anchorsByContext.getOrDefault(contextKey, List.of());
        List<PanelAnchor> anchors = new ArrayList<>(storedAnchors.size());

        for (StoredAnchor storedAnchor : storedAnchors) {
            anchors.add(storedAnchor.toRuntime());
        }

        return anchors;
    }

    static synchronized PanelAnchor addCurrentAnchor(Vec3d center, Vec3d normal, Vec3d right, Vec3d up) {
        ensureLoaded();
        PanelAnchor anchor = new PanelAnchor(UUID.randomUUID().toString(), center, normal, right, up);
        String contextKey = getCurrentContextKey();
        List<StoredAnchor> storedAnchors = new ArrayList<>(anchorsByContext.getOrDefault(contextKey, List.of()));
        storedAnchors.add(StoredAnchor.fromRuntime(anchor));
        anchorsByContext.put(contextKey, storedAnchors);
        save();
        return anchor;
    }

    static synchronized boolean deleteCurrentAnchor(String anchorId) {
        ensureLoaded();
        String contextKey = getCurrentContextKey();
        List<StoredAnchor> storedAnchors = new ArrayList<>(anchorsByContext.getOrDefault(contextKey, List.of()));
        boolean removed = storedAnchors.removeIf(anchor -> anchor.id().equals(anchorId));
        if (!removed) {
            return false;
        }

        anchorsByContext.put(contextKey, storedAnchors);
        save();
        return true;
    }

    static synchronized PanelAnchor updateCurrentAnchor(String anchorId, Vec3d center, Vec3d normal, Vec3d right, Vec3d up) {
        ensureLoaded();
        String contextKey = getCurrentContextKey();
        List<StoredAnchor> storedAnchors = new ArrayList<>(anchorsByContext.getOrDefault(contextKey, List.of()));
        PanelAnchor updatedAnchor = new PanelAnchor(anchorId, center, normal, right, up);

        for (int index = 0; index < storedAnchors.size(); index++) {
            if (storedAnchors.get(index).id().equals(anchorId)) {
                storedAnchors.set(index, StoredAnchor.fromRuntime(updatedAnchor));
                anchorsByContext.put(contextKey, storedAnchors);
                save();
                return updatedAnchor;
            }
        }

        return null;
    }

    private static void ensureLoaded() {
        if (anchorsByContext != null) {
            return;
        }

        if (!Files.exists(CONFIG_PATH)) {
            anchorsByContext = new HashMap<>();
            return;
        }

        try {
            String json = Files.readString(CONFIG_PATH);
            Map<String, List<StoredAnchor>> loaded = GSON.fromJson(json, STORE_TYPE);
            anchorsByContext = loaded != null ? new HashMap<>(loaded) : new HashMap<>();
        } catch (Exception exception) {
            LOGGER.warn("Failed to read panel anchors, starting empty", exception);
            anchorsByContext = new HashMap<>();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(anchorsByContext, STORE_TYPE));
        } catch (Exception exception) {
            LOGGER.warn("Failed to save panel anchors", exception);
        }
    }

    private static String getCurrentContextKey() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return "menu";
        }

        try {
            IntegratedServer server = client.getServer();
            if (server != null) {
                return "singleplayer:" + server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            }
        } catch (Exception exception) {
            LOGGER.debug("Failed to resolve singleplayer anchor context", exception);
        }

        ServerInfo serverInfo = client.getCurrentServerEntry();
        if (serverInfo != null && serverInfo.address != null && !serverInfo.address.isBlank()) {
            return "server:" + serverInfo.address.trim().toLowerCase(java.util.Locale.ROOT);
        }

        return "menu";
    }

    record PanelAnchor(String id, Vec3d center, Vec3d normal, Vec3d right, Vec3d up) {
    }

    private record StoredAnchor(String id, double centerX, double centerY, double centerZ,
                                double normalX, double normalY, double normalZ,
                                double rightX, double rightY, double rightZ,
                                double upX, double upY, double upZ) {
        private static StoredAnchor fromRuntime(PanelAnchor anchor) {
            return new StoredAnchor(anchor.id(), anchor.center().x, anchor.center().y, anchor.center().z,
                    anchor.normal().x, anchor.normal().y, anchor.normal().z,
                    anchor.right().x, anchor.right().y, anchor.right().z,
                    anchor.up().x, anchor.up().y, anchor.up().z);
        }

        private PanelAnchor toRuntime() {
            return new PanelAnchor(this.id,
                    new Vec3d(this.centerX, this.centerY, this.centerZ),
                    new Vec3d(this.normalX, this.normalY, this.normalZ),
                    new Vec3d(this.rightX, this.rightY, this.rightZ),
                    new Vec3d(this.upX, this.upY, this.upZ));
        }
    }
}