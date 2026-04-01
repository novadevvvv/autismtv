package dev.novab.autismtv.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.glfw.GLFW;

public final class LocalPanelController {
    private static final Logger LOGGER = LoggerFactory.getLogger("autismtv/local-panel");
    private static final double PANEL_THICKNESS = 0.05D;
    private static final double PANEL_INTERACTION_REACH = 8.0D;
    private static final Vec3d WORLD_UP = new Vec3d(0.0D, 1.0D, 0.0D);
    private static final Identifier PANEL_TEXTURE_ID = Identifier.of("autismtv", "dynamic/second_monitor");
    private static final Identifier SETTINGS_PREVIEW_TEXTURE_ID = Identifier.of("autismtv", "dynamic/settings_preview");
    private static final RenderLayer PANEL_RENDER_LAYER = RenderLayers.entityCutoutNoCull(PANEL_TEXTURE_ID);
    private static final BufferAllocator PANEL_TEXTURE_BUFFER = new BufferAllocator(PANEL_RENDER_LAYER.getExpectedBufferSize());
    private static final long SETTINGS_PREVIEW_INTERVAL_MS = 250L;
    private static final int SETTINGS_PREVIEW_MAX_EDGE = 512;
    private static final double RESIZE_STEP = 0.25D;
    private static final double RESIZE_HANDLE_INSET_RATIO = 0.12D;
    private static final double HANDLE_LENGTH = 0.18D;
    private static final double HANDLE_THICKNESS = 0.028D;
    private static final double ANCHOR_REACH = 12.0D;
    private static final double ANCHOR_SIZE = 0.22D;

    private static LocalPanel activePanel;
    private static NativeImageBackedTexture panelTexture;
    private static CaptureResources activeCaptureResources;
    private static CaptureScaler panelCaptureScaler;
    private static ScheduledExecutorService captureExecutor;
    private static final AtomicReference<CapturedFrame> latestFrame = new AtomicReference<>();
    private static volatile long lastUploadNanos;
    private static volatile int captureWidth;
    private static volatile int captureHeight;
    private static volatile long captureFrameTimeNanos;
    private static NativeImageBackedTexture settingsPreviewTexture;
    private static CaptureScaler settingsPreviewScaler;
    private static ScheduledExecutorService settingsPreviewExecutor;
    private static final AtomicReference<CapturedFrame> latestSettingsPreviewFrame = new AtomicReference<>();
    private static volatile int settingsPreviewWidth;
    private static volatile int settingsPreviewHeight;
    private static boolean panelFocused;
    private static PanelHit latestInteractionHit;
    private static WindowsInputBridge.TargetWindow activeInputTarget;
    private static boolean remotePanelActive;
    private static boolean remotePanelBillboard;
    private static double remotePanelRotationDegrees;
    private static PanelAnchorManager.PanelAnchor latestTargetedAnchor;
    private static long lastUnsafeCaptureWarningMs;

    static {
        System.setProperty("java.awt.headless", "false");
    }

    private LocalPanelController() {
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, Object registryAccess) {
        dispatcher.register(createPanelCommand());
        dispatcher.register(createAnchorCommand());
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> createPanelCommand() {
        return ClientCommandManager.literal("panel")
                .then(ClientCommandManager.literal("spawn").executes(context -> spawnPanel(context.getSource())))
                .then(ClientCommandManager.literal("align").executes(context -> alignPanelToTargetedAnchor(context.getSource())))
                .then(ClientCommandManager.literal("delete").executes(context -> clearPanel(context.getSource())));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> createAnchorCommand() {
        return ClientCommandManager.literal("anchor")
                .then(ClientCommandManager.literal("spawn").executes(context -> spawnAnchor(context.getSource())))
                .then(ClientCommandManager.literal("delete").executes(context -> deleteTargetedAnchor(context.getSource())))
                .then(ClientCommandManager.literal("align").executes(context -> alignTargetedAnchor(context.getSource())));
    }

    private static int spawnPanel(FabricClientCommandSource source) {
        PlayerEntity player = source.getPlayer();
        CaptureResources captureResources;
        SharescreenConfig config = SharescreenConfig.get();

        try {
            captureResources = createCaptureResources();
        } catch (IllegalStateException exception) {
            source.sendError(Text.literal("No usable display was detected."));
            return 0;
        } catch (AWTException | SecurityException | HeadlessException exception) {
            LOGGER.warn("Failed to initialize second-monitor capture", exception);
            source.sendError(Text.literal("Failed to initialize screen capture: " + exception.getMessage()));
            return 0;
        }

        Vec3d forward = getSnappedHorizontalNormal(player);
        Vec3d eyePos = player.getCameraPosVec(1.0F);
        Vec3d right = WORLD_UP.crossProduct(forward).normalize();
        Vec3d up = forward.crossProduct(right).normalize();
        Vec3d center = eyePos.add(forward.multiply(config.getPanelDistance()));

        stopCapture(MinecraftClient.getInstance());
        clearRemoteShare();
        activePanel = new LocalPanel(center, forward, right, up, config.getPanelWidth() * 0.5D, config.getPanelHeight() * 0.5D, PANEL_THICKNESS * 0.5D);

        try {
            startCapture(MinecraftClient.getInstance(), captureResources);
        } catch (RuntimeException exception) {
            activePanel = null;
            stopCapture(MinecraftClient.getInstance());
            LOGGER.warn("Failed to start second-monitor capture", exception);
            source.sendError(Text.literal("Failed to start second-monitor capture: " + exception.getMessage()));
            return 0;
        }

        publishPanelState();
        source.sendFeedback(Text.literal("Started rendering " + captureResources.label + " on the panel at " + config.getFramerate() + " FPS."));
        return Command.SINGLE_SUCCESS;
    }

    private static int clearPanel(FabricClientCommandSource source) {
        if (activePanel == null) {
            source.sendFeedback(Text.literal("There is no local panel to clear."));
            return 0;
        }

        stopCapture(MinecraftClient.getInstance());
        activePanel = null;
        clearRemoteShare();
        PeerShareTransport.broadcastClear();
        source.sendFeedback(Text.literal("Cleared the local panel."));
        return Command.SINGLE_SUCCESS;
    }

    private static int spawnAnchor(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || source.getPlayer() == null) {
            source.sendError(Text.literal("The client is not ready yet."));
            return 0;
        }

        PanelAnchorManager.PanelAnchor anchor = createAnchorFromCrosshair(source.getPlayer());
        if (anchor == null) {
            source.sendError(Text.literal("Look at a surface or aim somewhere valid to place an anchor."));
            return 0;
        }

        source.sendFeedback(Text.literal("Saved anchor. Look at it later and run /panel align to snap the panel onto it."));
        return Command.SINGLE_SUCCESS;
    }

    private static int deleteTargetedAnchor(FabricClientCommandSource source) {
        TargetedAnchor targetedAnchor = findTargetedAnchor(MinecraftClient.getInstance());
        if (targetedAnchor == null) {
            source.sendError(Text.literal("Look directly at a saved anchor first."));
            return 0;
        }

        if (!PanelAnchorManager.deleteCurrentAnchor(targetedAnchor.anchor().id())) {
            source.sendError(Text.literal("That anchor could not be deleted."));
            return 0;
        }

        latestTargetedAnchor = null;
        source.sendFeedback(Text.literal("Deleted the selected anchor."));
        return Command.SINGLE_SUCCESS;
    }

    private static int alignTargetedAnchor(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerEntity player = source.getPlayer();
        if (client == null || player == null) {
            source.sendError(Text.literal("The client is not ready yet."));
            return 0;
        }

        TargetedAnchor targetedAnchor = findTargetedAnchor(client);
        if (targetedAnchor == null) {
            source.sendError(Text.literal("Look directly at a saved anchor first."));
            return 0;
        }

        AnchorPlacement placement = getAnchorPlacementFromCrosshair(player);
        if (placement == null) {
            source.sendError(Text.literal("Look at a block face to realign that anchor."));
            return 0;
        }

        PanelAnchorManager.PanelAnchor updatedAnchor = PanelAnchorManager.updateCurrentAnchor(targetedAnchor.anchor().id(),
                placement.center(), placement.normal(), placement.right(), placement.up());
        if (updatedAnchor == null) {
            source.sendError(Text.literal("That anchor could not be updated."));
            return 0;
        }

        latestTargetedAnchor = updatedAnchor;
        source.sendFeedback(Text.literal("Aligned the selected anchor to the block center and face."));
        return Command.SINGLE_SUCCESS;
    }

    private static int alignPanelToTargetedAnchor(FabricClientCommandSource source) {
        TargetedAnchor targetedAnchor = findTargetedAnchor(MinecraftClient.getInstance());
        if (targetedAnchor == null) {
            source.sendError(Text.literal("Look directly at a saved anchor first."));
            return 0;
        }

        alignPanelToAnchor(targetedAnchor.anchor());
        source.sendFeedback(Text.literal("Aligned the panel to the selected anchor."));
        return Command.SINGLE_SUCCESS;
    }

    private static PanelAnchorManager.PanelAnchor createAnchorFromCrosshair(PlayerEntity player) {
        AnchorPlacement placement = getAnchorPlacementFromCrosshair(player);
        if (placement == null) {
            return null;
        }

        return PanelAnchorManager.addCurrentAnchor(placement.center(), placement.normal(), placement.right(), placement.up());
    }

    private static AnchorPlacement getAnchorPlacementFromCrosshair(PlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return null;
        }

        HitResult hitResult = client.crosshairTarget;
        Vec3d center;
        Vec3d normal;
        Vec3d right;
        Vec3d up;

        if (hitResult instanceof BlockHitResult blockHitResult && hitResult.getType() == HitResult.Type.BLOCK) {
            Vec3d faceNormal = Vec3d.of(blockHitResult.getSide().getVector()).normalize();
            center = Vec3d.ofCenter(blockHitResult.getBlockPos()).add(faceNormal.multiply(0.501D));
            OrientationBasis basis = createOrientationBasis(faceNormal, getSnappedHorizontalNormal(player));
            normal = basis.normal();
            right = basis.right();
            up = basis.up();
        } else {
            SharescreenConfig config = SharescreenConfig.get();
            Vec3d eyePos = player.getCameraPosVec(1.0F);
            normal = getSnappedHorizontalNormal(player);
            center = eyePos.add(normal.multiply(config.getPanelDistance()));
            right = WORLD_UP.crossProduct(normal).normalize();
            up = normal.crossProduct(right).normalize();
        }

        return new AnchorPlacement(center, normal, right, up);
    }

    private static void alignPanelToAnchor(PanelAnchorManager.PanelAnchor anchor) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }

        if (remotePanelActive) {
            clearRemoteShare();
        }

        if (activeCaptureResources == null) {
            try {
                CaptureResources captureResources = createCaptureResources();
                stopCapture(client);
                startCapture(client, captureResources);
            } catch (Exception exception) {
                LOGGER.warn("Failed to start capture while aligning to an anchor", exception);
                return;
            }
        }

        SharescreenConfig config = SharescreenConfig.get();
        activePanel = new LocalPanel(anchor.center(), anchor.normal(), anchor.right(), anchor.up(), config.getPanelWidth() * 0.5D,
                config.getPanelHeight() * 0.5D, PANEL_THICKNESS * 0.5D);
        publishPanelState();
    }

    public static void render(WorldRenderContext context) {
        LocalPanel panel = activePanel;

        if (context.consumers() == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client.gameRenderer == null || client.gameRenderer.getCamera() == null) {
            return;
        }

        Vec3d cameraPos = client.gameRenderer.getCamera().getCameraPos();
        updatePanelInteractionState(client, panel, cameraPos);
        MatrixStack matrices = context.matrices();
        VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(PANEL_TEXTURE_BUFFER);
        VertexConsumer bodyConsumer = context.consumers().getBuffer(RenderLayers.debugQuads());

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        MatrixStack.Entry entry = matrices.peek();
        drawAnchors(entry, bodyConsumer, cameraPos);

        if (panel != null && panelTexture != null) {
            uploadLatestFrame();
            panel = resolveRenderPanel(panel, cameraPos);
            VertexConsumer screenConsumer = immediate.getBuffer(PANEL_RENDER_LAYER);
            drawPanel(entry, bodyConsumer, screenConsumer, panel);
            if (panelFocused && !remotePanelActive) {
                drawResizeHandles(entry, bodyConsumer, panel, latestInteractionHit != null ? latestInteractionHit.handle() : null);
            }
        }

        immediate.draw(PANEL_RENDER_LAYER);

        matrices.pop();
    }

    private static void drawPanel(MatrixStack.Entry entry, VertexConsumer bodyConsumer, VertexConsumer screenConsumer, LocalPanel panel) {
        Vec3d halfRight = panel.right.multiply(panel.halfWidth);
        Vec3d halfUp = panel.up.multiply(panel.halfHeight);
        Vec3d halfDepth = panel.normal.multiply(panel.halfThickness);

        Vec3d frontBottomLeft = panel.center.subtract(halfRight).subtract(halfUp).add(halfDepth);
        Vec3d frontBottomRight = panel.center.add(halfRight).subtract(halfUp).add(halfDepth);
        Vec3d frontTopRight = panel.center.add(halfRight).add(halfUp).add(halfDepth);
        Vec3d frontTopLeft = panel.center.subtract(halfRight).add(halfUp).add(halfDepth);

        Vec3d backBottomLeft = panel.center.subtract(halfRight).subtract(halfUp).subtract(halfDepth);
        Vec3d backBottomRight = panel.center.add(halfRight).subtract(halfUp).subtract(halfDepth);
        Vec3d backTopRight = panel.center.add(halfRight).add(halfUp).subtract(halfDepth);
        Vec3d backTopLeft = panel.center.subtract(halfRight).add(halfUp).subtract(halfDepth);

        texturedQuad(entry, screenConsumer, frontBottomLeft, frontBottomRight, frontTopRight, frontTopLeft, panel.normal, false);
        texturedQuad(entry, screenConsumer, backBottomRight, backBottomLeft, backTopLeft, backTopRight, panel.normal.multiply(-1.0D), true);

        quad(entry, bodyConsumer, backBottomLeft, frontBottomLeft, frontTopLeft, backTopLeft);
        quad(entry, bodyConsumer, frontBottomRight, backBottomRight, backTopRight, frontTopRight);
        quad(entry, bodyConsumer, frontTopLeft, frontTopRight, backTopRight, backTopLeft);
        quad(entry, bodyConsumer, backBottomLeft, backBottomRight, frontBottomRight, frontBottomLeft);
    }

    private static void quad(MatrixStack.Entry entry, VertexConsumer consumer, Vec3d first, Vec3d second, Vec3d third, Vec3d fourth) {
        quad(entry, consumer, first, second, third, fourth, 0, 0, 0, 255);
    }

    private static void quad(MatrixStack.Entry entry, VertexConsumer consumer, Vec3d first, Vec3d second, Vec3d third, Vec3d fourth,
                             int red, int green, int blue, int alpha) {
        solidVertex(entry, consumer, first, red, green, blue, alpha);
        solidVertex(entry, consumer, second, red, green, blue, alpha);
        solidVertex(entry, consumer, third, red, green, blue, alpha);
        solidVertex(entry, consumer, fourth, red, green, blue, alpha);
    }

    private static void drawResizeHandles(MatrixStack.Entry entry, VertexConsumer consumer, LocalPanel panel, CornerHandle highlightedHandle) {
        double handleLength = Math.min(HANDLE_LENGTH, Math.min(panel.halfWidth, panel.halfHeight) * 0.55D);
        double thickness = Math.min(HANDLE_THICKNESS, handleLength * 0.35D);
        double inset = Math.min(handleLength * 0.35D, Math.min(panel.halfWidth, panel.halfHeight) * RESIZE_HANDLE_INSET_RATIO);

        for (CornerHandle handle : CornerHandle.values()) {
            int red = handle == highlightedHandle ? 214 : 166;
            int green = handle == highlightedHandle ? 177 : 136;
            int blue = handle == highlightedHandle ? 111 : 96;
            int alpha = handle == highlightedHandle ? 255 : 210;
            Vec3d corner = panel.center
                    .add(panel.right.multiply((panel.halfWidth - inset) * handle.rightSign))
                    .add(panel.up.multiply((panel.halfHeight - inset) * handle.upSign))
                    .add(panel.normal.multiply(panel.halfThickness + 0.002D));
            Vec3d alongRight = panel.right.multiply(-handle.rightSign * handleLength);
            Vec3d alongUp = panel.up.multiply(-handle.upSign * handleLength);
            drawHandleSegment(entry, consumer, corner, alongRight, panel.up, thickness, red, green, blue, alpha);
            drawHandleSegment(entry, consumer, corner, alongUp, panel.right, thickness, red, green, blue, alpha);
        }
    }

    private static void drawHandleSegment(MatrixStack.Entry entry, VertexConsumer consumer, Vec3d origin, Vec3d axis, Vec3d thicknessAxis,
                                          double thickness, int red, int green, int blue, int alpha) {
        Vec3d halfThickness = thicknessAxis.multiply(thickness * 0.5D);
        Vec3d startLeft = origin.subtract(halfThickness);
        Vec3d startRight = origin.add(halfThickness);
        Vec3d endLeft = origin.add(axis).subtract(halfThickness);
        Vec3d endRight = origin.add(axis).add(halfThickness);
        quad(entry, consumer, startLeft, startRight, endRight, endLeft, red, green, blue, alpha);
    }

    private static void drawAnchors(MatrixStack.Entry entry, VertexConsumer consumer, Vec3d cameraPos) {
        for (PanelAnchorManager.PanelAnchor anchor : PanelAnchorManager.getCurrentAnchors()) {
            boolean highlighted = latestTargetedAnchor != null && latestTargetedAnchor.id().equals(anchor.id());
            int red = highlighted ? 214 : 170;
            int green = highlighted ? 177 : 145;
            int blue = highlighted ? 111 : 92;
            int alpha = highlighted ? 255 : 190;
            drawAnchor(entry, consumer, anchor, cameraPos, red, green, blue, alpha);
        }
    }

    private static void drawAnchor(MatrixStack.Entry entry, VertexConsumer consumer, PanelAnchorManager.PanelAnchor anchor, Vec3d cameraPos,
                                   int red, int green, int blue, int alpha) {
        Vec3d center = anchor.center();
        Vec3d right = anchor.right();
        Vec3d up = anchor.up();
        Vec3d front = anchor.normal().multiply(0.006D);
        Vec3d halfRight = right.multiply(ANCHOR_SIZE * 0.5D);
        Vec3d halfUp = up.multiply(ANCHOR_SIZE * 0.5D);
        Vec3d bottomLeft = center.subtract(halfRight).subtract(halfUp).add(front);
        Vec3d bottomRight = center.add(halfRight).subtract(halfUp).add(front);
        Vec3d topRight = center.add(halfRight).add(halfUp).add(front);
        Vec3d topLeft = center.subtract(halfRight).add(halfUp).add(front);
        quad(entry, consumer, bottomLeft, bottomRight, topRight, topLeft, red, green, blue, alpha / 3);

        double armLength = ANCHOR_SIZE * 0.62D;
        double thickness = ANCHOR_SIZE * 0.16D;
        drawHandleSegment(entry, consumer, center, right.multiply(armLength), up, thickness, red, green, blue, alpha);
        drawHandleSegment(entry, consumer, center, right.multiply(-armLength), up, thickness, red, green, blue, alpha);
        drawHandleSegment(entry, consumer, center, up.multiply(armLength), right, thickness, red, green, blue, alpha);
        drawHandleSegment(entry, consumer, center, up.multiply(-armLength), right, thickness, red, green, blue, alpha);

        Vec3d toCamera = cameraPos.subtract(center);
        if (toCamera.lengthSquared() > 1.0E-6D) {
            toCamera = toCamera.normalize();
            drawHandleSegment(entry, consumer, center.add(front), toCamera.multiply(ANCHOR_SIZE * 0.24D), up, thickness * 0.7D,
                    red, green, blue, alpha);
        }
    }

    private static void texturedQuad(MatrixStack.Entry entry, VertexConsumer consumer, Vec3d bottomLeft, Vec3d bottomRight,
                                     Vec3d topRight, Vec3d topLeft, Vec3d normal, boolean mirror) {
        if (mirror) {
            texturedVertex(entry, consumer, bottomLeft, 1.0F, 1.0F, normal);
            texturedVertex(entry, consumer, bottomRight, 0.0F, 1.0F, normal);
            texturedVertex(entry, consumer, topRight, 0.0F, 0.0F, normal);
            texturedVertex(entry, consumer, topLeft, 1.0F, 0.0F, normal);
            return;
        }

        texturedVertex(entry, consumer, bottomLeft, 0.0F, 1.0F, normal);
        texturedVertex(entry, consumer, bottomRight, 1.0F, 1.0F, normal);
        texturedVertex(entry, consumer, topRight, 1.0F, 0.0F, normal);
        texturedVertex(entry, consumer, topLeft, 0.0F, 0.0F, normal);
    }

    private static void texturedVertex(MatrixStack.Entry entry, VertexConsumer consumer, Vec3d point, float u, float v, Vec3d normal) {
        consumer.vertex(entry, (float) point.x, (float) point.y, (float) point.z)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
                .normal(entry, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static void solidVertex(MatrixStack.Entry entry, VertexConsumer consumer, Vec3d point) {
        solidVertex(entry, consumer, point, 0, 0, 0, 255);
    }

    private static void solidVertex(MatrixStack.Entry entry, VertexConsumer consumer, Vec3d point, int red, int green, int blue, int alpha) {
        consumer.vertex(entry, (float) point.x, (float) point.y, (float) point.z).color(red, green, blue, alpha);
    }

    private static CaptureResources createCaptureResources() throws AWTException {
        GraphicsDevice[] screens = getAvailableScreens();
        SharescreenConfig config = SharescreenConfig.get();

        if (screens.length == 0) {
            throw new IllegalStateException("No displays found");
        }

        int screenIndex = Math.max(0, Math.min(config.getScreenIndex(), screens.length - 1));
        GraphicsDevice screen = screens[screenIndex];
        Rectangle bounds = screen.getDefaultConfiguration().getBounds();
        return new CaptureResources(new Robot(screen), new Robot(), bounds, formatDisplayLabel(screenIndex, screen));
    }

    private static void startCapture(MinecraftClient client, CaptureResources captureResources) {
        SharescreenConfig config = SharescreenConfig.get();
        activeCaptureResources = captureResources;
        panelCaptureScaler = new CaptureScaler(captureWidth = config.getCaptureWidth(), captureHeight = config.getCaptureHeight(), false);
        latestFrame.set(null);
        lastUploadNanos = 0L;
        captureFrameTimeNanos = 1_000_000_000L / Math.max(1, config.getFramerate());

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, captureWidth, captureHeight, false);
        fillBlack(nativeImage);
        panelTexture = new NativeImageBackedTexture(() -> "autismtv-second-monitor", nativeImage);
        client.getTextureManager().registerTexture(PANEL_TEXTURE_ID, panelTexture);

        int[] firstFrame = capturePixels(captureResources, panelCaptureScaler);
        latestFrame.set(new CapturedFrame(firstFrame));
        uploadLatestFrameForced();

        captureExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "autismtv-second-monitor-capture");
            thread.setDaemon(true);
            return thread;
        });

        captureExecutor.scheduleAtFixedRate(() -> captureFrame(captureResources), 0L, Math.max(1, 1000L / config.getFramerate()), TimeUnit.MILLISECONDS);
    }

    private static void stopCapture(MinecraftClient client) {
        clearPanelFocus(client);
        if (captureExecutor != null) {
            captureExecutor.shutdownNow();
            captureExecutor = null;
        }

        activeCaptureResources = null;
        if (panelCaptureScaler != null) {
            panelCaptureScaler.close();
            panelCaptureScaler = null;
        }
        latestFrame.set(null);
        lastUploadNanos = 0L;
        captureWidth = 0;
        captureHeight = 0;
        captureFrameTimeNanos = 0L;

        if (panelTexture != null) {
            client.getTextureManager().destroyTexture(PANEL_TEXTURE_ID);
            panelTexture.close();
            panelTexture = null;
        }
    }

    private static void captureFrame(CaptureResources captureResources) {
        try {
            if (panelCaptureScaler != null) {
                int[] pixels = capturePixels(captureResources, panelCaptureScaler);
                latestFrame.set(new CapturedFrame(pixels));
                PeerShareTransport.broadcastFrame(captureWidth, captureHeight, pixels);
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("Background screen capture failed", exception);
        }
    }

    private static int[] capturePixels(CaptureResources captureResources, CaptureScaler scaler) {
        BufferedImage rawCapture;

        synchronized (captureResources) {
            rawCapture = captureResources.captureRobot.createScreenCapture(captureResources.bounds);
        }

        return scaler.capture(rawCapture);
    }

    private static void uploadLatestFrame() {
        NativeImageBackedTexture texture = panelTexture;
        CapturedFrame frame = latestFrame.getAndSet(null);
        long now = System.nanoTime();

        if (texture == null || frame == null || now - lastUploadNanos < captureFrameTimeNanos) {
            return;
        }

        NativeImage image = texture.getImage();

        if (image == null) {
            return;
        }

        int[] pixels = frame.argbPixels;
        int index = 0;

        for (int y = 0; y < captureHeight; y++) {
            for (int x = 0; x < captureWidth; x++) {
                image.setColorArgb(x, y, pixels[index++]);
            }
        }

        texture.upload();
        lastUploadNanos = now;
    }

    private static void uploadLatestFrameForced() {
        lastUploadNanos = 0L;
        uploadLatestFrame();
    }

    private static void restartSettingsPreview(MinecraftClient client) throws AWTException {
        CaptureResources captureResources = createCaptureResources();
        stopSettingsPreview(client);
        startSettingsPreview(client, captureResources);
    }

    private static void startSettingsPreview(MinecraftClient client, CaptureResources captureResources) {
        SharescreenConfig config = SharescreenConfig.get();
        int sourceWidth = Math.max(1, config.getCaptureWidth());
        int sourceHeight = Math.max(1, config.getCaptureHeight());
        double scale = SETTINGS_PREVIEW_MAX_EDGE / (double) Math.max(sourceWidth, sourceHeight);

        latestSettingsPreviewFrame.set(null);
        settingsPreviewWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        settingsPreviewHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        settingsPreviewScaler = new CaptureScaler(settingsPreviewWidth, settingsPreviewHeight, true);

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, settingsPreviewWidth, settingsPreviewHeight, false);
        fillBlack(nativeImage);
        settingsPreviewTexture = new NativeImageBackedTexture(() -> "autismtv-settings-preview", nativeImage);
        client.getTextureManager().registerTexture(SETTINGS_PREVIEW_TEXTURE_ID, settingsPreviewTexture);

        int[] firstFrame = capturePixels(captureResources, settingsPreviewScaler);
        latestSettingsPreviewFrame.set(new CapturedFrame(firstFrame));
        uploadLatestSettingsPreviewFrame();

        settingsPreviewExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "autismtv-settings-preview-capture");
            thread.setDaemon(true);
            return thread;
        });

        settingsPreviewExecutor.scheduleAtFixedRate(() -> captureSettingsPreviewFrame(captureResources), SETTINGS_PREVIEW_INTERVAL_MS,
                SETTINGS_PREVIEW_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private static void stopSettingsPreview(MinecraftClient client) {
        if (settingsPreviewExecutor != null) {
            settingsPreviewExecutor.shutdownNow();
            settingsPreviewExecutor = null;
        }

        latestSettingsPreviewFrame.set(null);
        settingsPreviewWidth = 0;
        settingsPreviewHeight = 0;
        if (settingsPreviewScaler != null) {
            settingsPreviewScaler.close();
            settingsPreviewScaler = null;
        }

        if (settingsPreviewTexture != null) {
            client.getTextureManager().destroyTexture(SETTINGS_PREVIEW_TEXTURE_ID);
            settingsPreviewTexture.close();
            settingsPreviewTexture = null;
        }
    }

    private static void captureSettingsPreviewFrame(CaptureResources captureResources) {
        try {
            if (settingsPreviewScaler != null) {
                latestSettingsPreviewFrame.set(new CapturedFrame(capturePixels(captureResources, settingsPreviewScaler)));
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("Background settings preview capture failed", exception);
        }
    }

    private static void uploadLatestSettingsPreviewFrame() {
        NativeImageBackedTexture texture = settingsPreviewTexture;
        CapturedFrame frame = latestSettingsPreviewFrame.getAndSet(null);

        if (texture == null || frame == null || settingsPreviewWidth <= 0 || settingsPreviewHeight <= 0) {
            return;
        }

        NativeImage image = texture.getImage();

        if (image == null) {
            return;
        }

        int[] pixels = frame.argbPixels;
        int index = 0;

        for (int y = 0; y < settingsPreviewHeight; y++) {
            for (int x = 0; x < settingsPreviewWidth; x++) {
                image.setColorArgb(x, y, pixels[index++]);
            }
        }

        texture.upload();
    }

    private static void fillBlack(NativeImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setColorArgb(x, y, 0xFF000000);
            }
        }
    }

    public static boolean hasActivePanel() {
        return activePanel != null;
    }

    public static boolean hasPanelFocus() {
        return panelFocused;
    }

    public static void beginSettingsPreview() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null) {
            return;
        }

        try {
            restartSettingsPreview(client);
        } catch (Exception exception) {
            LOGGER.warn("Failed to start settings screen preview", exception);
            stopSettingsPreview(client);
        }
    }

    public static void endSettingsPreview() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client != null) {
            stopSettingsPreview(client);
        }
    }

    public static boolean handleFocusedKeyInput(int action, KeyInput keyInput) {
        if (!panelFocused) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (!remotePanelActive && isUnsafeSelfCaptureInput(client)) {
            clearPanelFocus(client);
            showUnsafeCaptureWarning(client);
            return true;
        }

        if (remotePanelActive) {
            if (action == GLFW.GLFW_PRESS && keyInput.getKeycode() == GLFW.GLFW_KEY_ESCAPE) {
                clearPanelFocus(client);
                return true;
            }

            if (!PeerShareTransport.canRemoteType() || !shouldForwardKey(keyInput)) {
                return true;
            }

            return PeerShareTransport.sendRemoteKey(action, keyInput.getKeycode());
        }

        if (activeCaptureResources == null) {
            return false;
        }

        if (action == GLFW.GLFW_PRESS && keyInput.getKeycode() == GLFW.GLFW_KEY_ESCAPE) {
            clearPanelFocus(client);
            return true;
        }

        if (!shouldForwardKey(keyInput)) {
            return true;
        }

        if (WindowsInputBridge.isSupported() && activeInputTarget != null) {
            return WindowsInputBridge.sendKey(activeInputTarget, keyInput.getKeycode(), action);
        }

        int awtKey = mapGlfwKeyToAwt(keyInput.getKeycode());

        if (awtKey == KeyEvent.VK_UNDEFINED) {
            return false;
        }

        synchronized (activeCaptureResources) {
            if (action == GLFW.GLFW_PRESS) {
                activeCaptureResources.inputRobot.keyPress(awtKey);
            } else if (action == GLFW.GLFW_RELEASE) {
                activeCaptureResources.inputRobot.keyRelease(awtKey);
            } else if (action == GLFW.GLFW_REPEAT) {
                activeCaptureResources.inputRobot.keyPress(awtKey);
                activeCaptureResources.inputRobot.keyRelease(awtKey);
            }
        }

        return true;
    }

    public static boolean handleFocusedCharInput(CharInput charInput) {
        if (!panelFocused || !charInput.isValidChar()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (!remotePanelActive && isUnsafeSelfCaptureInput(client)) {
            clearPanelFocus(client);
            showUnsafeCaptureWarning(client);
            return true;
        }

        if (remotePanelActive) {
            if ((charInput.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) != 0) {
                return false;
            }

            return PeerShareTransport.canRemoteType() && PeerShareTransport.sendRemoteChar(charInput.codepoint());
        }

        if (activeCaptureResources == null) {
            return false;
        }

        if ((charInput.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) != 0) {
            return false;
        }

        try {
            if (WindowsInputBridge.isSupported() && activeInputTarget != null) {
                return WindowsInputBridge.sendChar(activeInputTarget, charInput.codepoint());
            }

            pasteFocusedText(Character.toString(charInput.codepoint()));
            return true;
        } catch (Exception exception) {
            LOGGER.debug("Failed to forward focused panel text input", exception);
            return false;
        }
    }

    public static boolean handleMouseInput(MouseInput mouseInput, int action) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.currentScreen != null || (!remotePanelActive && activeCaptureResources == null)) {
            return false;
        }

        int button = mouseInput.button();

        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && action == GLFW.GLFW_PRESS) {
            if (panelFocused) {
                clearPanelFocus(client);
                return true;
            }

            if (latestInteractionHit != null) {
                if (!remotePanelActive && isUnsafeSelfCaptureInput(client)) {
                    showUnsafeCaptureWarning(client);
                    return true;
                }
                activatePanelFocus(client, latestInteractionHit);
                return true;
            }

            return false;
        }

        if (!panelFocused) {
            return false;
        }

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return true;
        }

        if (latestInteractionHit != null) {
            if (!remotePanelActive && action == GLFW.GLFW_PRESS && latestInteractionHit.handle() != null) {
                resizeActivePanel(button == GLFW.GLFW_MOUSE_BUTTON_LEFT ? RESIZE_STEP : -RESIZE_STEP);
                return true;
            }

            if (remotePanelActive) {
                if (PeerShareTransport.canRemoteClick()) {
                    PeerShareTransport.sendRemoteMouse(button, action, latestInteractionHit.u, latestInteractionHit.v);
                }
            } else {
                dispatchPanelMouseInput(button, action, latestInteractionHit);
            }
        }

        return true;
    }

    public static boolean hasSettingsPreview() {
        return settingsPreviewTexture != null && settingsPreviewWidth > 0 && settingsPreviewHeight > 0;
    }

    public static int getSettingsPreviewWidth() {
        return settingsPreviewWidth;
    }

    public static int getSettingsPreviewHeight() {
        return settingsPreviewHeight;
    }

    public static void drawSettingsPreview(DrawContext context, int x, int y, int width, int height) {
        if (!hasSettingsPreview()) {
            return;
        }

        uploadLatestSettingsPreviewFrame();
        context.drawTexture(RenderPipelines.GUI_TEXTURED, SETTINGS_PREVIEW_TEXTURE_ID, x, y, 0.0F, 0.0F, width, height,
                settingsPreviewWidth, settingsPreviewHeight);
    }

    public static void onSettingsChanged() {
        SharescreenConfig config = SharescreenConfig.get();

        if (activePanel != null && !remotePanelActive) {
            activePanel = activePanel.withDimensions(config.getPanelWidth() * 0.5D, config.getPanelHeight() * 0.5D);
            publishPanelState();
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (activePanel == null || panelTexture == null || client == null) {
            if (client != null && (settingsPreviewTexture != null || settingsPreviewExecutor != null)) {
                try {
                    restartSettingsPreview(client);
                } catch (Exception exception) {
                    LOGGER.warn("Failed to refresh settings screen preview after settings change", exception);
                    stopSettingsPreview(client);
                }
            }

            return;
        }

        try {
            CaptureResources captureResources = createCaptureResources();
            stopCapture(client);
            startCapture(client, captureResources);
            publishPanelState();
        } catch (Exception exception) {
            LOGGER.warn("Failed to refresh active screen capture after settings change", exception);
            stopCapture(client);
            activePanel = null;
        }

        if (settingsPreviewTexture != null || settingsPreviewExecutor != null) {
            try {
                restartSettingsPreview(client);
            } catch (Exception exception) {
                LOGGER.warn("Failed to refresh settings screen preview after settings change", exception);
                stopSettingsPreview(client);
            }
        }
    }

    public static int getAvailableScreenCount() {
        return getAvailableScreens().length;
    }

    public static String getDisplayLabel(int index) {
        GraphicsDevice[] screens = getAvailableScreens();
        int clampedIndex = Math.max(0, Math.min(index, Math.max(0, screens.length - 1)));

        if (screens.length == 0) {
            return "Monitor 1";
        }

        return formatDisplayLabel(clampedIndex, screens[clampedIndex]);
    }

    private static GraphicsDevice[] getAvailableScreens() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        } catch (HeadlessException exception) {
            LOGGER.warn("Display enumeration failed in headless mode", exception);
            return new GraphicsDevice[0];
        }
    }

    private static String formatDisplayLabel(int index, GraphicsDevice screen) {
        Rectangle bounds = screen.getDefaultConfiguration().getBounds();
        return "Monitor " + (index + 1) + " (" + bounds.width + "x" + bounds.height + ")";
    }

    private static LocalPanel resolveRenderPanel(LocalPanel panel, Vec3d cameraPos) {
        if (remotePanelActive) {
            double rotationDegrees = remotePanelRotationDegrees;
            if (!remotePanelBillboard) {
                return applyPanelRoll(panel, rotationDegrees);
            }

            Vec3d toCamera = cameraPos.subtract(panel.center);
            Vec3d normal = new Vec3d(toCamera.x, 0.0D, toCamera.z);

            if (normal.lengthSquared() < 1.0E-6D) {
                return panel;
            }

            normal = normal.normalize();
            Vec3d right = WORLD_UP.crossProduct(normal).normalize();
            Vec3d up = normal.crossProduct(right).normalize();
            return applyPanelRoll(panel.withOrientation(normal, right, up), rotationDegrees);
        }

        SharescreenConfig config = SharescreenConfig.get();
        LocalPanel resized = panel.withDimensions(config.getPanelWidth() * 0.5D, config.getPanelHeight() * 0.5D);
        double rotationDegrees = config.getPanelRotationDegrees();

        if (!config.isBillboard()) {
            return applyPanelRoll(resized, rotationDegrees);
        }

        Vec3d toCamera = cameraPos.subtract(resized.center);
        Vec3d normal = new Vec3d(toCamera.x, 0.0D, toCamera.z);

        if (normal.lengthSquared() < 1.0E-6D) {
            return resized;
        }

        normal = normal.normalize();
        Vec3d right = WORLD_UP.crossProduct(normal).normalize();
        Vec3d up = normal.crossProduct(right).normalize();
        return applyPanelRoll(resized.withOrientation(normal, right, up), rotationDegrees);
    }

    private static LocalPanel applyPanelRoll(LocalPanel panel, double rotationDegrees) {
        if (Math.abs(rotationDegrees) < 1.0E-6D) {
            return panel;
        }

        double radians = Math.toRadians(rotationDegrees);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        Vec3d rotatedRight = panel.right.multiply(cosine).add(panel.up.multiply(sine)).normalize();
        Vec3d rotatedUp = panel.up.multiply(cosine).subtract(panel.right.multiply(sine)).normalize();
        return panel.withOrientation(panel.normal, rotatedRight, rotatedUp);
    }

    private static Vec3d getSnappedHorizontalNormal(PlayerEntity player) {
        Direction direction = Direction.fromHorizontalDegrees(player.getYaw());
        Vec3d normal = new Vec3d(direction.getOffsetX(), 0.0D, direction.getOffsetZ());

        if (normal.lengthSquared() < 1.0E-6D) {
            Vec3d fallback = player.getRotationVector();
            normal = new Vec3d(fallback.x, 0.0D, fallback.z);
        }

        if (normal.lengthSquared() < 1.0E-6D) {
            return new Vec3d(0.0D, 0.0D, 1.0D);
        }

        return normal.normalize();
    }

    private static void updatePanelInteractionState(MinecraftClient client, LocalPanel panel, Vec3d cameraPos) {
        if (client.player == null || client.options == null || client.currentScreen != null) {
            if (client.currentScreen != null) {
                clearPanelFocus(client);
            }

            latestInteractionHit = null;
            latestTargetedAnchor = null;
            return;
        }

        Vec3d lookDirection = client.player.getRotationVector().normalize();
        latestInteractionHit = panel != null && (remotePanelActive || activeCaptureResources != null) ? raycastPanel(panel, cameraPos, lookDirection) : null;
        TargetedAnchor targetedAnchor = findTargetedAnchor(cameraPos, lookDirection);
        latestTargetedAnchor = targetedAnchor != null ? targetedAnchor.anchor() : null;
    }

    private static TargetedAnchor findTargetedAnchor(MinecraftClient client) {
        if (client == null || client.player == null || client.gameRenderer == null || client.gameRenderer.getCamera() == null) {
            return null;
        }

        Vec3d cameraPos = client.gameRenderer.getCamera().getCameraPos();
        Vec3d lookDirection = client.player.getRotationVector().normalize();
        return findTargetedAnchor(cameraPos, lookDirection);
    }

    private static TargetedAnchor findTargetedAnchor(Vec3d cameraPos, Vec3d lookDirection) {
        TargetedAnchor best = null;

        for (PanelAnchorManager.PanelAnchor anchor : PanelAnchorManager.getCurrentAnchors()) {
            double distance = raycastAnchor(anchor, cameraPos, lookDirection);
            if (distance < 0.0D) {
                continue;
            }

            if (best == null || distance < best.distance()) {
                best = new TargetedAnchor(anchor, distance);
            }
        }

        return best;
    }

    private static double raycastAnchor(PanelAnchorManager.PanelAnchor anchor, Vec3d origin, Vec3d direction) {
        double denominator = direction.dotProduct(anchor.normal());
        if (Math.abs(denominator) < 1.0E-6D) {
            return -1.0D;
        }

        double distance = anchor.center().subtract(origin).dotProduct(anchor.normal()) / denominator;
        if (distance <= 0.0D || distance > ANCHOR_REACH) {
            return -1.0D;
        }

        Vec3d hitPoint = origin.add(direction.multiply(distance));
        Vec3d localHit = hitPoint.subtract(anchor.center());
        double localRight = localHit.dotProduct(anchor.right());
        double localUp = localHit.dotProduct(anchor.up());
        double halfExtent = ANCHOR_SIZE * 0.5D;

        return Math.abs(localRight) <= halfExtent && Math.abs(localUp) <= halfExtent ? distance : -1.0D;
    }

    private static OrientationBasis createOrientationBasis(Vec3d normal, Vec3d preferredHorizontal) {
        Vec3d normalizedNormal = normal.normalize();
        Vec3d right;

        if (Math.abs(normalizedNormal.dotProduct(WORLD_UP)) > 0.98D) {
            Vec3d horizontal = new Vec3d(preferredHorizontal.x, 0.0D, preferredHorizontal.z);
            if (horizontal.lengthSquared() < 1.0E-6D) {
                horizontal = new Vec3d(1.0D, 0.0D, 0.0D);
            }
            right = horizontal.crossProduct(normalizedNormal).normalize();
        } else {
            right = WORLD_UP.crossProduct(normalizedNormal).normalize();
        }

        Vec3d up = normalizedNormal.crossProduct(right).normalize();
        return new OrientationBasis(normalizedNormal, right, up);
    }

    private static PanelHit raycastPanel(LocalPanel panel, Vec3d origin, Vec3d direction) {
        double denominator = direction.dotProduct(panel.normal);

        if (Math.abs(denominator) < 1.0E-6D) {
            return null;
        }

        double distance = panel.center.subtract(origin).dotProduct(panel.normal) / denominator;

        if (distance <= 0.0D || distance > PANEL_INTERACTION_REACH) {
            return null;
        }

        Vec3d hitPoint = origin.add(direction.multiply(distance));
        Vec3d localHit = hitPoint.subtract(panel.center);
        double localRight = localHit.dotProduct(panel.right);
        double localUp = localHit.dotProduct(panel.up);

        if (Math.abs(localRight) > panel.halfWidth || Math.abs(localUp) > panel.halfHeight) {
            return null;
        }

        double normalizedRight = localRight / Math.max(1.0E-6D, panel.halfWidth);
        double normalizedUp = localUp / Math.max(1.0E-6D, panel.halfHeight);
        double u = (localRight + panel.halfWidth) / (panel.halfWidth * 2.0D);
        double v = 1.0D - ((localUp + panel.halfHeight) / (panel.halfHeight * 2.0D));

        if (denominator > 0.0D) {
            u = 1.0D - u;
            normalizedRight = -normalizedRight;
        }

        return new PanelHit(clamp01(u), clamp01(v), detectCornerHandle(normalizedRight, normalizedUp));
    }

    private static CornerHandle detectCornerHandle(double normalizedRight, double normalizedUp) {
        double handleThreshold = 1.0D - (RESIZE_HANDLE_INSET_RATIO * 2.4D);
        if (Math.abs(normalizedRight) < handleThreshold || Math.abs(normalizedUp) < handleThreshold) {
            return null;
        }

        if (normalizedRight >= 0.0D && normalizedUp >= 0.0D) {
            return CornerHandle.TOP_RIGHT;
        }
        if (normalizedRight < 0.0D && normalizedUp >= 0.0D) {
            return CornerHandle.TOP_LEFT;
        }
        if (normalizedRight >= 0.0D) {
            return CornerHandle.BOTTOM_RIGHT;
        }
        return CornerHandle.BOTTOM_LEFT;
    }

    private static ScreenCoordinate getScreenCoordinate(CaptureResources captureResources, PanelHit hit) {
        int maxX = Math.max(0, captureResources.bounds.width - 1);
        int maxY = Math.max(0, captureResources.bounds.height - 1);
        int targetX = captureResources.bounds.x + clamp((int) Math.round(hit.u * maxX), 0, maxX);
        int targetY = captureResources.bounds.y + clamp((int) Math.round(hit.v * maxY), 0, maxY);

        return new ScreenCoordinate(targetX, targetY);
    }

    private static void dispatchPanelMouseInput(int button, int action, PanelHit hit) {
        if (activeCaptureResources == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (isUnsafeSelfCaptureInput(client)) {
            clearPanelFocus(client);
            showUnsafeCaptureWarning(client);
            return;
        }

        ScreenCoordinate target = getScreenCoordinate(activeCaptureResources, hit);

        if (WindowsInputBridge.isSupported()) {
            WindowsInputBridge.TargetWindow targetWindow = WindowsInputBridge.resolveWindowAt(target.x, target.y);

            if (targetWindow != null) {
                activeInputTarget = targetWindow;
                WindowsInputBridge.sendMouseButton(targetWindow, target.x, target.y, button, action);
            }

            return;
        }

        if (action == GLFW.GLFW_PRESS) {
            synchronized (activeCaptureResources) {
                activeCaptureResources.inputRobot.mouseMove(target.x, target.y);
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    activeCaptureResources.inputRobot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    activeCaptureResources.inputRobot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    activeCaptureResources.inputRobot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
                    activeCaptureResources.inputRobot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
                }
            }
        }
    }

    private static void activatePanelFocus(MinecraftClient client, PanelHit hit) {
        if (remotePanelActive) {
            panelFocused = true;
            unpressGameplayKeys(client);
            return;
        }

        if (isUnsafeSelfCaptureInput(client)) {
            clearPanelFocus(client);
            showUnsafeCaptureWarning(client);
            return;
        }

        panelFocused = true;
        ScreenCoordinate target = getScreenCoordinate(activeCaptureResources, hit);
        activeInputTarget = WindowsInputBridge.isSupported() ? WindowsInputBridge.resolveWindowAt(target.x, target.y) : null;
        unpressGameplayKeys(client);
    }

    private static void clearPanelFocus(MinecraftClient client) {
        panelFocused = false;
        activeInputTarget = null;
        latestInteractionHit = null;

        if (client != null) {
            unpressGameplayKeys(client);
        }
    }

    private static void unpressGameplayKeys(MinecraftClient client) {
        if (client == null || client.options == null) {
            return;
        }

        KeyBinding.unpressAll();
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.pickItemKey.setPressed(false);
    }

    private static boolean shouldForwardKey(KeyInput keyInput) {
        int keycode = keyInput.getKeycode();
        int modifiers = keyInput.modifiers();

        if (keycode == GLFW.GLFW_KEY_LEFT_SHIFT || keycode == GLFW.GLFW_KEY_RIGHT_SHIFT
                || keycode == GLFW.GLFW_KEY_LEFT_CONTROL || keycode == GLFW.GLFW_KEY_RIGHT_CONTROL
                || keycode == GLFW.GLFW_KEY_LEFT_ALT || keycode == GLFW.GLFW_KEY_RIGHT_ALT
                || keycode == GLFW.GLFW_KEY_LEFT_SUPER || keycode == GLFW.GLFW_KEY_RIGHT_SUPER) {
            return true;
        }

        if ((modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) != 0) {
            return true;
        }

        return switch (keycode) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE,
                 GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_INSERT, GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_END,
                 GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT,
                 GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_F1, GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3,
                 GLFW.GLFW_KEY_F4, GLFW.GLFW_KEY_F5, GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7, GLFW.GLFW_KEY_F8,
                 GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_F10, GLFW.GLFW_KEY_F11, GLFW.GLFW_KEY_F12 -> true;
            default -> false;
        };
    }

    private static int mapGlfwKeyToAwt(int keycode) {
        return switch (keycode) {
            case GLFW.GLFW_KEY_A -> KeyEvent.VK_A;
            case GLFW.GLFW_KEY_B -> KeyEvent.VK_B;
            case GLFW.GLFW_KEY_C -> KeyEvent.VK_C;
            case GLFW.GLFW_KEY_D -> KeyEvent.VK_D;
            case GLFW.GLFW_KEY_E -> KeyEvent.VK_E;
            case GLFW.GLFW_KEY_F -> KeyEvent.VK_F;
            case GLFW.GLFW_KEY_G -> KeyEvent.VK_G;
            case GLFW.GLFW_KEY_H -> KeyEvent.VK_H;
            case GLFW.GLFW_KEY_I -> KeyEvent.VK_I;
            case GLFW.GLFW_KEY_J -> KeyEvent.VK_J;
            case GLFW.GLFW_KEY_K -> KeyEvent.VK_K;
            case GLFW.GLFW_KEY_L -> KeyEvent.VK_L;
            case GLFW.GLFW_KEY_M -> KeyEvent.VK_M;
            case GLFW.GLFW_KEY_N -> KeyEvent.VK_N;
            case GLFW.GLFW_KEY_O -> KeyEvent.VK_O;
            case GLFW.GLFW_KEY_P -> KeyEvent.VK_P;
            case GLFW.GLFW_KEY_Q -> KeyEvent.VK_Q;
            case GLFW.GLFW_KEY_R -> KeyEvent.VK_R;
            case GLFW.GLFW_KEY_S -> KeyEvent.VK_S;
            case GLFW.GLFW_KEY_T -> KeyEvent.VK_T;
            case GLFW.GLFW_KEY_U -> KeyEvent.VK_U;
            case GLFW.GLFW_KEY_V -> KeyEvent.VK_V;
            case GLFW.GLFW_KEY_W -> KeyEvent.VK_W;
            case GLFW.GLFW_KEY_X -> KeyEvent.VK_X;
            case GLFW.GLFW_KEY_Y -> KeyEvent.VK_Y;
            case GLFW.GLFW_KEY_Z -> KeyEvent.VK_Z;
            case GLFW.GLFW_KEY_0 -> KeyEvent.VK_0;
            case GLFW.GLFW_KEY_1 -> KeyEvent.VK_1;
            case GLFW.GLFW_KEY_2 -> KeyEvent.VK_2;
            case GLFW.GLFW_KEY_3 -> KeyEvent.VK_3;
            case GLFW.GLFW_KEY_4 -> KeyEvent.VK_4;
            case GLFW.GLFW_KEY_5 -> KeyEvent.VK_5;
            case GLFW.GLFW_KEY_6 -> KeyEvent.VK_6;
            case GLFW.GLFW_KEY_7 -> KeyEvent.VK_7;
            case GLFW.GLFW_KEY_8 -> KeyEvent.VK_8;
            case GLFW.GLFW_KEY_9 -> KeyEvent.VK_9;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> KeyEvent.VK_ENTER;
            case GLFW.GLFW_KEY_BACKSPACE -> KeyEvent.VK_BACK_SPACE;
            case GLFW.GLFW_KEY_DELETE -> KeyEvent.VK_DELETE;
            case GLFW.GLFW_KEY_TAB -> KeyEvent.VK_TAB;
            case GLFW.GLFW_KEY_INSERT -> KeyEvent.VK_INSERT;
            case GLFW.GLFW_KEY_HOME -> KeyEvent.VK_HOME;
            case GLFW.GLFW_KEY_END -> KeyEvent.VK_END;
            case GLFW.GLFW_KEY_PAGE_UP -> KeyEvent.VK_PAGE_UP;
            case GLFW.GLFW_KEY_PAGE_DOWN -> KeyEvent.VK_PAGE_DOWN;
            case GLFW.GLFW_KEY_LEFT -> KeyEvent.VK_LEFT;
            case GLFW.GLFW_KEY_RIGHT -> KeyEvent.VK_RIGHT;
            case GLFW.GLFW_KEY_UP -> KeyEvent.VK_UP;
            case GLFW.GLFW_KEY_DOWN -> KeyEvent.VK_DOWN;
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> KeyEvent.VK_SHIFT;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> KeyEvent.VK_CONTROL;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> KeyEvent.VK_ALT;
            case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> KeyEvent.VK_WINDOWS;
            case GLFW.GLFW_KEY_F1 -> KeyEvent.VK_F1;
            case GLFW.GLFW_KEY_F2 -> KeyEvent.VK_F2;
            case GLFW.GLFW_KEY_F3 -> KeyEvent.VK_F3;
            case GLFW.GLFW_KEY_F4 -> KeyEvent.VK_F4;
            case GLFW.GLFW_KEY_F5 -> KeyEvent.VK_F5;
            case GLFW.GLFW_KEY_F6 -> KeyEvent.VK_F6;
            case GLFW.GLFW_KEY_F7 -> KeyEvent.VK_F7;
            case GLFW.GLFW_KEY_F8 -> KeyEvent.VK_F8;
            case GLFW.GLFW_KEY_F9 -> KeyEvent.VK_F9;
            case GLFW.GLFW_KEY_F10 -> KeyEvent.VK_F10;
            case GLFW.GLFW_KEY_F11 -> KeyEvent.VK_F11;
            case GLFW.GLFW_KEY_F12 -> KeyEvent.VK_F12;
            default -> KeyEvent.VK_UNDEFINED;
        };
    }

    private static void pasteFocusedText(String text) throws Exception {
        if (text.isEmpty() || activeCaptureResources == null) {
            return;
        }

        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        String previousText = null;

        try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                previousText = (String) clipboard.getData(DataFlavor.stringFlavor);
            }
        } catch (Exception exception) {
            LOGGER.debug("Failed to read clipboard before focused panel paste", exception);
        }

        clipboard.setContents(new StringSelection(text), null);

        synchronized (activeCaptureResources) {
            activeCaptureResources.inputRobot.keyPress(KeyEvent.VK_CONTROL);
            activeCaptureResources.inputRobot.keyPress(KeyEvent.VK_V);
            activeCaptureResources.inputRobot.keyRelease(KeyEvent.VK_V);
            activeCaptureResources.inputRobot.keyRelease(KeyEvent.VK_CONTROL);
        }

        if (previousText != null) {
            clipboard.setContents(new StringSelection(previousText), null);
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static void resizeActivePanel(double delta) {
        if (activePanel == null || remotePanelActive) {
            return;
        }

        SharescreenConfig config = SharescreenConfig.get();
        config.setPanelWidth(config.getPanelWidth() + delta);
        onSettingsChanged();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isUnsafeSelfCaptureInput(MinecraftClient client) {
        if (client == null || client.getWindow() == null || !client.getWindow().isFullscreen()) {
            return false;
        }

        GraphicsDevice[] screens = getAvailableScreens();
        if (screens.length == 0) {
            return false;
        }

        int captureScreenIndex = Math.max(0, Math.min(SharescreenConfig.get().getScreenIndex(), screens.length - 1));
        int minecraftScreenIndex = getMinecraftWindowScreenIndex(client, screens);
        return minecraftScreenIndex >= 0 && minecraftScreenIndex == captureScreenIndex;
    }

    private static int getMinecraftWindowScreenIndex(MinecraftClient client, GraphicsDevice[] screens) {
        int centerX = client.getWindow().getX() + Math.max(1, client.getWindow().getWidth()) / 2;
        int centerY = client.getWindow().getY() + Math.max(1, client.getWindow().getHeight()) / 2;

        for (int index = 0; index < screens.length; index++) {
            Rectangle bounds = screens[index].getDefaultConfiguration().getBounds();
            if (centerX >= bounds.x && centerX < bounds.x + bounds.width
                    && centerY >= bounds.y && centerY < bounds.y + bounds.height) {
                return index;
            }
        }

        return -1;
    }

    private static void showUnsafeCaptureWarning(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastUnsafeCaptureWarningMs < 1200L) {
            return;
        }

        lastUnsafeCaptureWarningMs = now;
        client.player.sendMessage(Text.literal("Panel input blocked: Minecraft is fullscreen on the captured monitor."), true);
    }

    public static boolean isRemotePanelActive() {
        return remotePanelActive;
    }

    public static void handleRemoteMouseInput(int button, int action, double u, double v) {
        if (activeCaptureResources == null) {
            return;
        }

        dispatchPanelMouseInput(button, action, new PanelHit(clamp01(u), clamp01(v), null));
    }

    public static void handleRemoteKeyInput(int action, int keycode) {
        if (activeCaptureResources == null) {
            return;
        }

        if (WindowsInputBridge.isSupported() && activeInputTarget != null) {
            WindowsInputBridge.sendKey(activeInputTarget, keycode, action);
            return;
        }

        int awtKey = mapGlfwKeyToAwt(keycode);

        if (awtKey == KeyEvent.VK_UNDEFINED) {
            return;
        }

        synchronized (activeCaptureResources) {
            if (action == GLFW.GLFW_PRESS) {
                activeCaptureResources.inputRobot.keyPress(awtKey);
            } else if (action == GLFW.GLFW_RELEASE) {
                activeCaptureResources.inputRobot.keyRelease(awtKey);
            } else if (action == GLFW.GLFW_REPEAT) {
                activeCaptureResources.inputRobot.keyPress(awtKey);
                activeCaptureResources.inputRobot.keyRelease(awtKey);
            }
        }
    }

    public static void handleRemoteCharInput(int codepoint) {
        if (activeCaptureResources == null) {
            return;
        }

        try {
            if (WindowsInputBridge.isSupported() && activeInputTarget != null) {
                WindowsInputBridge.sendChar(activeInputTarget, codepoint);
                return;
            }

            pasteFocusedText(Character.toString(codepoint));
        } catch (Exception exception) {
            LOGGER.debug("Failed to apply remote char input", exception);
        }
    }

    public static PanelSnapshot getActivePanelSnapshot() {
        if (activePanel == null || remotePanelActive) {
            return null;
        }

        return new PanelSnapshot(
                activePanel.center.x, activePanel.center.y, activePanel.center.z,
                activePanel.normal.x, activePanel.normal.y, activePanel.normal.z,
                activePanel.right.x, activePanel.right.y, activePanel.right.z,
                activePanel.up.x, activePanel.up.y, activePanel.up.z,
                activePanel.halfWidth, activePanel.halfHeight, activePanel.halfThickness,
            SharescreenConfig.get().isBillboard(), SharescreenConfig.get().getPanelRotationDegrees());
    }

    public static void applyRemotePanel(PanelSnapshot snapshot) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null) {
            return;
        }

        client.execute(() -> {
            remotePanelActive = true;
            remotePanelBillboard = snapshot.billboard();
            remotePanelRotationDegrees = snapshot.rotationDegrees();
            activePanel = new LocalPanel(
                    new Vec3d(snapshot.centerX(), snapshot.centerY(), snapshot.centerZ()),
                    new Vec3d(snapshot.normalX(), snapshot.normalY(), snapshot.normalZ()),
                    new Vec3d(snapshot.rightX(), snapshot.rightY(), snapshot.rightZ()),
                    new Vec3d(snapshot.upX(), snapshot.upY(), snapshot.upZ()),
                    snapshot.halfWidth(), snapshot.halfHeight(), snapshot.halfThickness());
            clearPanelFocus(client);
        });
    }

    public static void applyRemoteFrame(int width, int height, int[] pixels) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null) {
            return;
        }

        client.execute(() -> {
            ensurePanelTexture(client, width, height);
            captureWidth = width;
            captureHeight = height;
            captureFrameTimeNanos = 0L;
            latestFrame.set(new CapturedFrame(pixels));
        });
    }

    public static void clearRemoteShare() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null) {
            remotePanelActive = false;
            remotePanelBillboard = false;
            remotePanelRotationDegrees = 0.0D;
            return;
        }

        client.execute(() -> {
            remotePanelActive = false;
            remotePanelBillboard = false;
            remotePanelRotationDegrees = 0.0D;
            if (activeCaptureResources == null) {
                activePanel = null;
            }
            clearPanelFocus(client);
        });
    }

    private static void publishPanelState() {
        PanelSnapshot snapshot = getActivePanelSnapshot();

        if (snapshot != null) {
            PeerShareTransport.broadcastPanelState(snapshot);
        }
    }

    private static void ensurePanelTexture(MinecraftClient client, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }

        NativeImage existingImage = panelTexture != null ? panelTexture.getImage() : null;

        if (panelTexture != null && existingImage != null && existingImage.getWidth() == width && existingImage.getHeight() == height) {
            return;
        }

        if (panelTexture != null) {
            client.getTextureManager().destroyTexture(PANEL_TEXTURE_ID);
            panelTexture.close();
        }

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        fillBlack(nativeImage);
        panelTexture = new NativeImageBackedTexture(() -> "autismtv-second-monitor", nativeImage);
        client.getTextureManager().registerTexture(PANEL_TEXTURE_ID, panelTexture);
    }

    public record PanelSnapshot(double centerX, double centerY, double centerZ,
                                double normalX, double normalY, double normalZ,
                                double rightX, double rightY, double rightZ,
                                double upX, double upY, double upZ,
                                double halfWidth, double halfHeight, double halfThickness,
                                boolean billboard, double rotationDegrees) {
    }

    private record LocalPanel(Vec3d center, Vec3d normal, Vec3d right, Vec3d up, double halfWidth, double halfHeight,
                              double halfThickness) {
        private LocalPanel withDimensions(double updatedHalfWidth, double updatedHalfHeight) {
            return new LocalPanel(this.center, this.normal, this.right, this.up, updatedHalfWidth, updatedHalfHeight, this.halfThickness);
        }

        private LocalPanel withOrientation(Vec3d updatedNormal, Vec3d updatedRight, Vec3d updatedUp) {
            return new LocalPanel(this.center, updatedNormal, updatedRight, updatedUp, this.halfWidth, this.halfHeight, this.halfThickness);
        }
    }

    private record CaptureResources(Robot captureRobot, Robot inputRobot, Rectangle bounds, String label) {
    }

    private record PanelHit(double u, double v, CornerHandle handle) {
    }

    private record TargetedAnchor(PanelAnchorManager.PanelAnchor anchor, double distance) {
    }

    private record AnchorPlacement(Vec3d center, Vec3d normal, Vec3d right, Vec3d up) {
    }

    private record OrientationBasis(Vec3d normal, Vec3d right, Vec3d up) {
    }

    private enum CornerHandle {
        TOP_LEFT(-1.0D, 1.0D),
        TOP_RIGHT(1.0D, 1.0D),
        BOTTOM_LEFT(-1.0D, -1.0D),
        BOTTOM_RIGHT(1.0D, -1.0D);

        private final double rightSign;
        private final double upSign;

        CornerHandle(double rightSign, double upSign) {
            this.rightSign = rightSign;
            this.upSign = upSign;
        }
    }

    private record ScreenCoordinate(int x, int y) {
    }

    private static final class CaptureScaler {
        private final int width;
        private final int height;
        private final BufferedImage scaledCapture;
        private final Graphics2D graphics;
        private final int[] pixels;

        private CaptureScaler(int width, int height, boolean highQuality) {
            this.width = width;
            this.height = height;
            this.scaledCapture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            this.graphics = this.scaledCapture.createGraphics();
            this.graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            this.graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            this.graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            this.graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    highQuality ? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            this.pixels = ((DataBufferInt) this.scaledCapture.getRaster().getDataBuffer()).getData();
        }

        private int[] capture(BufferedImage rawCapture) {
            this.graphics.drawImage(rawCapture, 0, 0, this.width, this.height, null);
            return Arrays.copyOf(this.pixels, this.pixels.length);
        }

        private void close() {
            this.graphics.dispose();
        }
    }

    private static final class CapturedFrame {
        private final int[] argbPixels;

        private CapturedFrame(int[] argbPixels) {
            this.argbPixels = argbPixels;
        }
    }
}