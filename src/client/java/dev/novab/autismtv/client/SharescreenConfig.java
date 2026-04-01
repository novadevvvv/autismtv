package dev.novab.autismtv.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SharescreenConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("autismtv/sharescreen-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autismtv-sharescreen.json");
    private static final int MIN_FRAMERATE = 10;
    private static final int MAX_FRAMERATE = 240;
    private static final double MIN_PANEL_DISTANCE = 1.0D;
    private static final double MAX_PANEL_DISTANCE = 12.0D;
    private static final double PANEL_DISTANCE_STEP = 0.25D;
    private static final double MIN_PANEL_WIDTH = 1.5D;
    private static final double MAX_PANEL_WIDTH = 12.0D;
    private static final double PANEL_WIDTH_STEP = 0.1D;
    private static final double MIN_PANEL_ROTATION = -180.0D;
    private static final double MAX_PANEL_ROTATION = 180.0D;
    private static final double PANEL_ROTATION_STEP = 5.0D;
    private static final ResolutionPreset[] RESOLUTIONS = {
            new ResolutionPreset(320, 180),
            new ResolutionPreset(640, 360),
            new ResolutionPreset(854, 480),
            new ResolutionPreset(960, 540),
            new ResolutionPreset(1024, 576),
            new ResolutionPreset(1280, 720),
            new ResolutionPreset(1366, 768),
            new ResolutionPreset(1600, 900),
            new ResolutionPreset(1920, 1080),
            new ResolutionPreset(2560, 1440),
            new ResolutionPreset(3440, 1440),
            new ResolutionPreset(3840, 2160),
            new ResolutionPreset(180, 320),
            new ResolutionPreset(360, 640),
            new ResolutionPreset(480, 854),
            new ResolutionPreset(540, 960),
            new ResolutionPreset(576, 1024),
            new ResolutionPreset(720, 1280),
            new ResolutionPreset(768, 1366),
            new ResolutionPreset(900, 1600),
            new ResolutionPreset(1080, 1920),
            new ResolutionPreset(1440, 2560),
            new ResolutionPreset(1440, 3440),
            new ResolutionPreset(2160, 3840)
    };
    private static final int[] FRAMERATES = {10, 12, 15, 20, 24, 30, 36, 45, 60, 72, 90, 120};
    private static final double[] PANEL_DISTANCES = {2.0D, 3.0D, 4.0D, 5.0D, 6.0D};
    private static final double[] PANEL_WIDTHS = {2.4D, 3.2D, 4.0D, 4.8D, 5.6D};

    private static SharescreenConfig instance;

    private int screenIndex = 1;
    private int resolutionIndex = 1;
    private int framerateIndex = 2;
    private boolean billboard;
    private int distanceIndex = 1;
    private int sizeIndex = 1;
    private int framerate;
    private double panelDistance;
    private double panelWidth;
    private double panelRotationDegrees;

    public static SharescreenConfig get() {
        if (instance == null) {
            load();
        }

        return instance;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                instance = GSON.fromJson(Files.readString(CONFIG_PATH), SharescreenConfig.class);
            } catch (Exception exception) {
                LOGGER.warn("Failed to read sharescreen config, using defaults", exception);
            }
        }

        if (instance == null) {
            instance = new SharescreenConfig();
        }

        instance.sanitize();
        instance.save();
    }

    public void save() {
        this.sanitize();

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (Exception exception) {
            LOGGER.warn("Failed to save sharescreen config", exception);
        }
    }

    public int getScreenIndex() {
        return this.screenIndex;
    }

    public int getCaptureWidth() {
        return RESOLUTIONS[this.resolutionIndex].width;
    }

    public int getCaptureHeight() {
        return RESOLUTIONS[this.resolutionIndex].height;
    }

    public int getFramerate() {
        return this.framerate;
    }

    public boolean isBillboard() {
        return this.billboard;
    }

    public double getPanelDistance() {
        return this.panelDistance;
    }

    public double getPanelWidth() {
        return this.panelWidth;
    }

    public double getPanelHeight() {
        return this.getPanelWidth() * (double) this.getCaptureHeight() / (double) this.getCaptureWidth();
    }

    public double getPanelRotationDegrees() {
        return this.panelRotationDegrees;
    }

    public String cycleScreen() {
        int count = Math.max(1, LocalPanelController.getAvailableScreenCount());
        this.screenIndex = (this.screenIndex + 1) % count;
        this.save();
        return this.getSelectedScreenLabel();
    }

    public String cycleResolution() {
        this.resolutionIndex = (this.resolutionIndex + 1) % RESOLUTIONS.length;
        this.save();
        return this.getResolutionLabel();
    }

    public String cycleFramerate() {
        this.framerateIndex = (this.framerateIndex + 1) % FRAMERATES.length;
        this.framerate = FRAMERATES[this.framerateIndex];
        this.save();
        return this.getFramerateLabel();
    }

    public String toggleBillboard() {
        this.billboard = !this.billboard;
        this.save();
        return this.getBillboardLabel();
    }

    public String cycleDistance() {
        this.distanceIndex = (this.distanceIndex + 1) % PANEL_DISTANCES.length;
        this.panelDistance = PANEL_DISTANCES[this.distanceIndex];
        this.save();
        return this.getDistanceLabel();
    }

    public String cyclePanelSize() {
        this.sizeIndex = (this.sizeIndex + 1) % PANEL_WIDTHS.length;
        this.panelWidth = PANEL_WIDTHS[this.sizeIndex];
        this.save();
        return this.getPanelSizeLabel();
    }

    public void setFramerate(int framerate) {
        this.framerate = clamp(framerate, MIN_FRAMERATE, MAX_FRAMERATE);
        this.save();
    }

    public void setPanelDistance(double panelDistance) {
        this.panelDistance = roundToStep(clamp(panelDistance, MIN_PANEL_DISTANCE, MAX_PANEL_DISTANCE), PANEL_DISTANCE_STEP);
        this.save();
    }

    public void setPanelWidth(double panelWidth) {
        this.panelWidth = roundToStep(clamp(panelWidth, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH), PANEL_WIDTH_STEP);
        this.save();
    }

    public void setPanelRotationDegrees(double panelRotationDegrees) {
        this.panelRotationDegrees = roundToStep(clamp(panelRotationDegrees, MIN_PANEL_ROTATION, MAX_PANEL_ROTATION), PANEL_ROTATION_STEP);
        this.save();
    }

    public double getFramerateProgress() {
        return normalize(this.framerate, MIN_FRAMERATE, MAX_FRAMERATE);
    }

    public double getPanelDistanceProgress() {
        return normalize(this.panelDistance, MIN_PANEL_DISTANCE, MAX_PANEL_DISTANCE);
    }

    public double getPanelWidthProgress() {
        return normalize(this.panelWidth, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
    }

    public double getPanelRotationProgress() {
        return normalize(this.panelRotationDegrees, MIN_PANEL_ROTATION, MAX_PANEL_ROTATION);
    }

    public int framerateFromProgress(double progress) {
        return clamp((int) Math.round(lerp(progress, MIN_FRAMERATE, MAX_FRAMERATE)), MIN_FRAMERATE, MAX_FRAMERATE);
    }

    public double panelDistanceFromProgress(double progress) {
        return roundToStep(lerp(progress, MIN_PANEL_DISTANCE, MAX_PANEL_DISTANCE), PANEL_DISTANCE_STEP);
    }

    public double panelWidthFromProgress(double progress) {
        return roundToStep(lerp(progress, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH), PANEL_WIDTH_STEP);
    }

    public double panelRotationFromProgress(double progress) {
        return roundToStep(lerp(progress, MIN_PANEL_ROTATION, MAX_PANEL_ROTATION), PANEL_ROTATION_STEP);
    }

    public String getSelectedScreenLabel() {
        return LocalPanelController.getDisplayLabel(this.screenIndex);
    }

    public String getResolutionLabel() {
        return this.getCaptureWidth() + "x" + this.getCaptureHeight();
    }

    public String getFramerateLabel() {
        return this.getFramerate() + " FPS";
    }

    public String getBillboardLabel() {
        return this.billboard ? "On" : "Off";
    }

    public String getDistanceLabel() {
        return formatDouble(this.getPanelDistance()) + " blocks";
    }

    public String getPanelSizeLabel() {
        return formatDouble(this.getPanelWidth()) + " wide";
    }

    public String getPanelRotationLabel() {
        return formatDouble(this.panelRotationDegrees) + " deg";
    }

    private void sanitize() {
        int screenCount = Math.max(1, LocalPanelController.getAvailableScreenCount());
        this.screenIndex = clamp(this.screenIndex, 0, screenCount - 1);
        this.resolutionIndex = clamp(this.resolutionIndex, 0, RESOLUTIONS.length - 1);
        this.framerateIndex = clamp(this.framerateIndex, 0, FRAMERATES.length - 1);
        this.distanceIndex = clamp(this.distanceIndex, 0, PANEL_DISTANCES.length - 1);
        this.sizeIndex = clamp(this.sizeIndex, 0, PANEL_WIDTHS.length - 1);

        if (this.framerate <= 0) {
            this.framerate = FRAMERATES[this.framerateIndex];
        }

        if (this.panelDistance <= 0.0D) {
            this.panelDistance = PANEL_DISTANCES[this.distanceIndex];
        }

        if (this.panelWidth <= 0.0D) {
            this.panelWidth = PANEL_WIDTHS[this.sizeIndex];
        }

        this.framerate = clamp(this.framerate, MIN_FRAMERATE, MAX_FRAMERATE);
        this.panelDistance = roundToStep(clamp(this.panelDistance, MIN_PANEL_DISTANCE, MAX_PANEL_DISTANCE), PANEL_DISTANCE_STEP);
        this.panelWidth = roundToStep(clamp(this.panelWidth, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH), PANEL_WIDTH_STEP);
        this.panelRotationDegrees = roundToStep(clamp(this.panelRotationDegrees, MIN_PANEL_ROTATION, MAX_PANEL_ROTATION), PANEL_ROTATION_STEP);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalize(double value, double min, double max) {
        if (max <= min) {
            return 0.0D;
        }

        return clamp((value - min) / (max - min), 0.0D, 1.0D);
    }

    private static double lerp(double progress, double min, double max) {
        return min + (max - min) * clamp(progress, 0.0D, 1.0D);
    }

    private static double roundToStep(double value, double step) {
        return Math.round(value / step) * step;
    }

    private static String formatDouble(double value) {
        if (Math.floor(value) == value) {
            return Integer.toString((int) value);
        }

        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private record ResolutionPreset(int width, int height) {
    }
}