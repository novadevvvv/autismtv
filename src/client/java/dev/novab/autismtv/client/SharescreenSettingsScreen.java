package dev.novab.autismtv.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;

public final class SharescreenSettingsScreen extends Screen {
    private static final int BUTTON_GAP = 12;
    private static final int PREVIEW_PADDING = 12;
    private static final int CARD_PADDING = 18;
    private static final int HEADER_HEIGHT = 82;
    private static final int CONTROL_ROW_SPACING = 42;
    private static final int CARD_GAP = 16;
    private static final int MIN_PREVIEW_HEIGHT = 220;
    private static final long ENTRANCE_ANIMATION_MS = 220L;

    private final Screen parent;
    private final SharescreenConfig config;
    private long openedAtMs;

    private ButtonWidget screenButton;
    private ButtonWidget resolutionButton;
    private ButtonWidget billboardButton;
    private ConfigSlider framerateSlider;
    private ConfigSlider distanceSlider;
    private ConfigSlider panelSizeSlider;
    private ConfigSlider rotationSlider;

    public SharescreenSettingsScreen(Screen parent) {
        super(Text.literal("Sharescreen Settings"));
        this.parent = parent;
        this.config = SharescreenConfig.get();
    }

    @Override
    protected void init() {
        if (this.openedAtMs == 0L) {
            this.openedAtMs = System.currentTimeMillis();
        }

        LayoutMetrics metrics = this.getLayoutMetrics();
        int left = metrics.leftButtonX;
        int right = metrics.rightButtonX;
        int top = metrics.controlsTop;

        LocalPanelController.beginSettingsPreview();

        this.screenButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            this.config.cycleScreen();
            LocalPanelController.onSettingsChanged();
            this.refreshLabels();
        }).dimensions(left, top, metrics.buttonWidth, 20).build());

        this.resolutionButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            this.config.cycleResolution();
            LocalPanelController.onSettingsChanged();
            this.refreshLabels();
        }).dimensions(right, top, metrics.buttonWidth, 20).build());

        this.billboardButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            this.config.toggleBillboard();
            LocalPanelController.onSettingsChanged();
            this.refreshLabels();
        }).dimensions(left, top + CONTROL_ROW_SPACING, metrics.fullControlWidth, 20).build());

        this.framerateSlider = this.addDrawableChild(new ConfigSlider(metrics.fullControlX, top + (CONTROL_ROW_SPACING * 2), metrics.fullControlWidth,
                "Framerate", this.config.getFramerateProgress(), value -> this.config.framerateFromProgress(value),
                value -> value + " FPS", value -> {
            this.config.setFramerate((int) value);
            LocalPanelController.onSettingsChanged();
        }));

        this.distanceSlider = this.addDrawableChild(new ConfigSlider(metrics.fullControlX, top + (CONTROL_ROW_SPACING * 3), metrics.fullControlWidth,
                "Spawn Distance", this.config.getPanelDistanceProgress(), value -> this.config.panelDistanceFromProgress(value),
                value -> this.config.getDistanceLabel(), value -> this.config.setPanelDistance(value)));

        this.panelSizeSlider = this.addDrawableChild(new ConfigSlider(metrics.fullControlX, top + (CONTROL_ROW_SPACING * 4), metrics.fullControlWidth,
                "Panel Size", this.config.getPanelWidthProgress(), value -> this.config.panelWidthFromProgress(value),
                value -> this.config.getPanelSizeLabel(), value -> {
            this.config.setPanelWidth(value);
            LocalPanelController.onSettingsChanged();
        }));

        this.rotationSlider = this.addDrawableChild(new ConfigSlider(metrics.fullControlX, top + (CONTROL_ROW_SPACING * 5), metrics.fullControlWidth,
                "Rotation", this.config.getPanelRotationProgress(), value -> this.config.panelRotationFromProgress(value),
                value -> this.config.getPanelRotationLabel(), value -> {
            this.config.setPanelRotationDegrees(value);
            LocalPanelController.onSettingsChanged();
        }));

        ButtonWidget doneButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Return"), button -> this.close())
                .dimensions(metrics.contentLeft, metrics.footerY, metrics.contentWidth, 20)
                .build());
        doneButton.setAlpha(0.72F);

        this.styleActionButton(this.screenButton);
        this.styleActionButton(this.resolutionButton);
        this.styleActionButton(this.billboardButton);
        this.styleActionButton(this.framerateSlider);
        this.styleActionButton(this.distanceSlider);
        this.styleActionButton(this.panelSizeSlider);
        this.styleActionButton(this.rotationSlider);
        this.refreshLabels();
    }

    @Override
    public void close() {
        LocalPanelController.endSettingsPreview();
        this.client.setScreen(this.parent);
    }

    @Override
    public void removed() {
        LocalPanelController.endSettingsPreview();
        super.removed();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        LayoutMetrics metrics = this.getLayoutMetrics();
        this.renderCustomBackground(context);
        this.renderHeaderCard(context, metrics);
        this.renderControlCard(context, metrics);
        this.renderPreviewCard(context, metrics);
        this.updateWidgetAnimations();
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderCustomBackground(DrawContext context) {
        context.fillGradient(0, 0, this.width, this.height, 0xFF0B1016, 0xFF07090D);
        context.fillGradient(0, 0, this.width, this.height / 2, 0x2F3DAA7A, 0x00000000);
        context.fill(this.width / 2 - 220, -20, this.width / 2 + 220, 140, 0x0FD6B16F);
        context.fill(-40, this.height - 180, this.width / 3, this.height + 40, 0x143E8A77);
        context.fill(this.width - 280, this.height - 260, this.width + 40, this.height + 20, 0x123A4F80);
    }

    private void renderHeaderCard(DrawContext context, LayoutMetrics metrics) {
        drawPanel(context, metrics.contentLeft, 16, metrics.contentWidth, HEADER_HEIGHT, 0xD711151C, 0xFF45636D);
        context.fill(metrics.contentLeft + 16, 28, metrics.contentLeft + 22, 74, 0xFFD6B16F);
        context.drawTextWithShadow(this.textRenderer, this.title, metrics.contentLeft + 34, 28, 0xFFF3F5F7);
        drawWrappedText(context, Text.literal("Dial in the panel, capture target, and focus workflow."), metrics.contentLeft + 34, 46,
                metrics.contentWidth - 240, 0xFFB8C3CC, 10);
        drawWrappedText(context, Text.literal("Middle-click the panel to arm remote input. Corner arrows resize it and rotation updates live."),
                metrics.contentLeft + 34, 58, metrics.contentWidth - 240, 0xFF91A0AA, 10);
        this.drawStatusChip(context, metrics.contentLeft + metrics.contentWidth - 176, 28, 160,
            LocalPanelController.hasPanelFocus() ? "Focus: Armed" : "Focus: Middle-click panel",
                LocalPanelController.hasPanelFocus() ? 0xFF1F7A5C : 0xFF4C5F6D);
    }

    private void renderControlCard(DrawContext context, LayoutMetrics metrics) {
        drawPanel(context, metrics.controlCardX, metrics.controlCardY, metrics.controlCardWidth, metrics.controlCardHeight, 0xD70F141A, 0xFF33424B);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Control Rack"), metrics.controlCardX + CARD_PADDING, metrics.controlCardY + 12, 0xFFF2F4F5);
        context.drawTextWithShadow(this.textRenderer, Text.literal("A tighter interface for tuning the panel feed."), metrics.controlCardX + CARD_PADDING,
                metrics.controlCardY + 28, 0xFF90A0AA);

        this.drawControlSlot(context, this.screenButton, "Capture display");
        this.drawControlSlot(context, this.resolutionButton, "Landscape and portrait presets");
        this.drawControlSlot(context, this.billboardButton, "World-facing behavior");
        this.drawControlSlot(context, this.framerateSlider, "Render cadence");
        this.drawControlSlot(context, this.distanceSlider, "Spawn offset");
        this.drawControlSlot(context, this.panelSizeSlider, "Physical panel width");
        this.drawControlSlot(context, this.rotationSlider, "Panel roll angle");
    }

    private void renderPreviewCard(DrawContext context, LayoutMetrics metrics) {
        PreviewLayout previewLayout = this.getPreviewLayout(metrics);
        drawPanel(context, metrics.previewCardX, metrics.previewCardY, metrics.previewCardWidth, metrics.previewCardHeight, 0xD70E1319, 0xFF3D505D);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Live Preview"), metrics.previewCardX + CARD_PADDING, metrics.previewCardY + 12, 0xFFF2F4F5);
        context.drawTextWithShadow(this.textRenderer, Text.literal(this.config.getResolutionLabel()), metrics.previewCardX + CARD_PADDING, metrics.previewCardY + 28, 0xFFD6B16F);
        drawWrappedText(context, Text.literal("Sharper capture, larger viewport, and focus status."), metrics.previewCardX + CARD_PADDING,
            metrics.previewCardY + 44, metrics.previewCardWidth - 180, 0xFF92A0A9, 10);

        this.drawStatusChip(context, metrics.previewCardX + metrics.previewCardWidth - 136, metrics.previewCardY + 12, 120,
                LocalPanelController.hasSettingsPreview() ? "Signal live" : "No signal", LocalPanelController.hasSettingsPreview() ? 0xFF1B7D62 : 0xFF6C5344);

        fillRect(context, previewLayout.frameX, previewLayout.frameY, previewLayout.frameWidth, previewLayout.frameHeight, 0xE0080A0E);
        drawRectBorder(context, previewLayout.frameX, previewLayout.frameY, previewLayout.frameWidth, previewLayout.frameHeight, 0xFF52646F);

        if (LocalPanelController.hasSettingsPreview()) {
            LocalPanelController.drawSettingsPreview(context, previewLayout.previewX, previewLayout.previewY, previewLayout.previewWidth, previewLayout.previewHeight);
        } else {
            fillRect(context, previewLayout.previewX, previewLayout.previewY, previewLayout.previewWidth, previewLayout.previewHeight, 0xFF000000);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Preview unavailable"), previewLayout.frameX + (previewLayout.frameWidth / 2),
                    previewLayout.previewY + (previewLayout.previewHeight / 2) - 4, 0xFFD0D5DA);
        }

        drawWrappedText(context, Text.literal("Portrait presets resize the panel geometry automatically."), metrics.previewCardX + CARD_PADDING,
            metrics.previewCardY + metrics.previewCardHeight - 34, metrics.previewCardWidth - (CARD_PADDING * 2), 0xFF90A0AA, 10);
    }

    private void refreshLabels() {
        this.screenButton.setMessage(Text.literal("Screen: " + this.config.getSelectedScreenLabel()));
        this.resolutionButton.setMessage(Text.literal("Resolution: " + this.config.getResolutionLabel()));
        this.billboardButton.setMessage(Text.literal("Billboard: " + this.config.getBillboardLabel()));
        this.framerateSlider.refreshMessage();
        this.distanceSlider.refreshMessage();
        this.panelSizeSlider.refreshMessage();
        this.rotationSlider.refreshMessage();
    }

    private void styleActionButton(ClickableWidget widget) {
        widget.setAlpha(0.68F);
    }

    private void updateWidgetAnimations() {
        float entrance = getEntranceProgress();
        for (ClickableWidget widget : this.children().stream().filter(ClickableWidget.class::isInstance).map(ClickableWidget.class::cast).toList()) {
            float hoverBoost = widget.isHovered() ? 0.14F : 0.0F;
            widget.setAlpha(Math.min(0.96F, 0.54F + (entrance * 0.24F) + hoverBoost));
        }
    }

    private void drawControlSlot(DrawContext context, ClickableWidget widget, String subtitle) {
        int slotX = widget.getX() - 4;
        int slotY = widget.getY() - 6;
        int slotWidth = widget.getWidth() + 8;
        int slotHeight = widget.getHeight() + 12;
        int background = widget.isHovered() ? 0xAA26343D : 0x88303A43;

        fillRect(context, slotX, slotY, slotWidth, slotHeight, background);
        drawRectBorder(context, slotX, slotY, slotWidth, slotHeight, 0x664F6874);
        context.drawTextWithShadow(this.textRenderer, Text.literal(subtitle), slotX + 10, slotY - 12, 0xFF8FA0AB);
    }

    private PreviewLayout getPreviewLayout(LayoutMetrics metrics) {
        int sourceWidth = LocalPanelController.hasSettingsPreview() ? LocalPanelController.getSettingsPreviewWidth() : this.config.getCaptureWidth();
        int sourceHeight = LocalPanelController.hasSettingsPreview() ? LocalPanelController.getSettingsPreviewHeight() : this.config.getCaptureHeight();
        int maxInnerWidth = Math.max(60, metrics.previewCardWidth - (CARD_PADDING * 2) - (PREVIEW_PADDING * 2));
        int maxInnerHeight = Math.max(80, metrics.previewCardHeight - 114);
        double scale = Math.min(maxInnerWidth / (double) Math.max(1, sourceWidth), maxInnerHeight / (double) Math.max(1, sourceHeight));
        int previewWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int previewHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        int frameWidth = previewWidth + (PREVIEW_PADDING * 2);
        int frameHeight = previewHeight + (PREVIEW_PADDING * 2);
        int frameX = metrics.previewCardX + (metrics.previewCardWidth - frameWidth) / 2;
        int frameY = metrics.previewCardY + 66 + Math.max(0, (maxInnerHeight - frameHeight) / 2);
        int previewX = frameX + PREVIEW_PADDING;
        int previewY = frameY + PREVIEW_PADDING;
        return new PreviewLayout(frameX, frameY, frameWidth, frameHeight, previewX, previewY, previewWidth, previewHeight);
    }

    private LayoutMetrics getLayoutMetrics() {
        int contentWidth = Math.min(this.width - 24, 960);
        int contentLeft = (this.width - contentWidth) / 2;
        int contentTop = HEADER_HEIGHT + 34;
        int footerY = this.height - 30;
        int contentBottom = footerY - 12;
        int availableHeight = Math.max(320, contentBottom - contentTop);
        boolean stacked = this.width < 920 || this.height < 720;
        int controlCardWidth = stacked ? contentWidth : Math.min(380, contentWidth / 2 - 8);
        int previewCardWidth = stacked ? contentWidth : contentWidth - controlCardWidth - CARD_GAP;
        int controlCardX = contentLeft;
        int previewCardX = stacked ? contentLeft : controlCardX + controlCardWidth + CARD_GAP;
        int controlCardY = contentTop;
        int controlCardHeight = 318;
        int previewCardY = stacked ? controlCardY + controlCardHeight + CARD_GAP : contentTop;
        int previewCardHeight = stacked ? Math.max(MIN_PREVIEW_HEIGHT, contentBottom - previewCardY) : availableHeight;
        int buttonWidth = (controlCardWidth - (CARD_PADDING * 2) - BUTTON_GAP) / 2;
        int leftButtonX = controlCardX + CARD_PADDING;
        int rightButtonX = leftButtonX + buttonWidth + BUTTON_GAP;
        int fullControlX = controlCardX + CARD_PADDING;
        int fullControlWidth = controlCardWidth - (CARD_PADDING * 2);
        int controlsTop = controlCardY + 58;
        return new LayoutMetrics(contentLeft, contentWidth, footerY, controlCardX, controlCardY, controlCardWidth, controlCardHeight,
            previewCardX, previewCardY, previewCardWidth, previewCardHeight, leftButtonX, rightButtonX, controlsTop, buttonWidth,
            fullControlX, fullControlWidth);
    }

    private void drawStatusChip(DrawContext context, int x, int y, int width, String label, int accentColor) {
        fillRect(context, x, y, width, 18, 0xCC0B0E12);
        drawRectBorder(context, x, y, width, 18, accentColor);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label), x + width / 2, y + 5, 0xFFF2F4F5);
    }

    private void drawWrappedText(DrawContext context, Text text, int x, int y, int maxWidth, int color, int lineHeight) {
        List<OrderedText> wrapped = this.textRenderer.wrapLines(text, Math.max(40, maxWidth));
        int drawY = y;
        for (OrderedText line : wrapped) {
            context.drawTextWithShadow(this.textRenderer, line, x, drawY, color);
            drawY += lineHeight;
        }
    }

    private float getEntranceProgress() {
        long elapsed = System.currentTimeMillis() - this.openedAtMs;
        return Math.max(0.0F, Math.min(1.0F, elapsed / (float) ENTRANCE_ANIMATION_MS));
    }

    private static void drawPanel(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor) {
        fillRect(context, x, y, width, height, fillColor);
        drawRectBorder(context, x, y, width, height, borderColor);
    }

    private static void fillRect(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + height, color);
    }

    private static void drawRectBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    private record PreviewLayout(int frameX, int frameY, int frameWidth, int frameHeight, int previewX, int previewY,
                                 int previewWidth, int previewHeight) {
    }

    private record LayoutMetrics(int contentLeft, int contentWidth, int footerY, int controlCardX, int controlCardY,
                                 int controlCardWidth, int controlCardHeight, int previewCardX, int previewCardY,
                                 int previewCardWidth, int previewCardHeight, int leftButtonX, int rightButtonX,
                                 int controlsTop, int buttonWidth, int fullControlX, int fullControlWidth) {
    }

    private interface SliderValueSupplier {
        double get(double progress);
    }

    private interface SliderLabelFormatter {
        String format(double value);
    }

    private interface SliderValueApplier {
        void apply(double value);
    }

    private static final class ConfigSlider extends SliderWidget {
        private final String label;
        private final SliderValueSupplier supplier;
        private final SliderLabelFormatter formatter;
        private final SliderValueApplier applier;
        private double lastAppliedValue = Double.NaN;

        private ConfigSlider(int x, int y, int width, String label, double progress, SliderValueSupplier supplier,
                             SliderLabelFormatter formatter, SliderValueApplier applier) {
            super(x, y, width, 20, Text.empty(), progress);
            this.label = label;
            this.supplier = supplier;
            this.formatter = formatter;
            this.applier = applier;
            this.refreshMessage();
        }

        private void refreshMessage() {
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            double actualValue = this.supplier.get(this.value);
            this.setMessage(Text.literal(this.label + ": " + this.formatter.format(actualValue)));
        }

        @Override
        protected void applyValue() {
            double actualValue = this.supplier.get(this.value);

            if (Double.compare(actualValue, this.lastAppliedValue) == 0) {
                return;
            }

            this.lastAppliedValue = actualValue;
            this.applier.apply(actualValue);
            this.updateMessage();
        }
    }
}