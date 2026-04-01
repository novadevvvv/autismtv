package dev.novab.autismtv.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

public final class SessionPermissionsScreen extends Screen {
    private static final int CARD_PADDING = 18;

    private final Screen parent;
    private List<PeerShareTransport.ViewerInfo> viewers = List.of();
    private int selectedIndex = -1;
    private ButtonWidget clicksButton;
    private ButtonWidget typingButton;
    private long lastRefreshMs;

    public SessionPermissionsScreen(Screen parent) {
        super(Text.literal("Viewer Permissions"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.refreshViewers(true);
        int left = this.width / 2 - 390;
        int contentTop = 108;
        int listWidth = 390;
        int sideX = left + listWidth + 16;

        this.clicksButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), button -> this.toggleClicks())
                .dimensions(sideX + CARD_PADDING, contentTop + 92, 340, 20).build());

        this.typingButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), button -> this.toggleTyping())
                .dimensions(sideX + CARD_PADDING, contentTop + 120, 340, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> this.close())
                .dimensions(sideX + CARD_PADDING, this.height - 60, 340, 20).build());
        this.refreshButtons();
    }

    private void refreshViewers(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - this.lastRefreshMs < 750L) {
            return;
        }

        this.lastRefreshMs = now;
        this.viewers = PeerShareTransport.getConnectedViewers();

        if (this.viewers.isEmpty()) {
            this.selectedIndex = -1;
        } else if (this.selectedIndex < 0 || this.selectedIndex >= this.viewers.size()) {
            this.selectedIndex = 0;
        }

        this.refreshButtons();
    }

    private void refreshButtons() {
        boolean enabled = this.selectedIndex >= 0 && this.selectedIndex < this.viewers.size();
        this.clicksButton.active = enabled;
        this.typingButton.active = enabled;

        if (!enabled) {
            this.clicksButton.setMessage(Text.literal("Viewer Click Control: No viewer selected"));
            this.typingButton.setMessage(Text.literal("Viewer Typing Control: No viewer selected"));
            return;
        }

        PeerShareTransport.ViewerInfo viewer = this.viewers.get(this.selectedIndex);
        this.clicksButton.setMessage(Text.literal("Viewer Click Control: " + (viewer.allowClicks() ? "Allowed" : "Blocked")));
        this.typingButton.setMessage(Text.literal("Viewer Typing Control: " + (viewer.allowTyping() ? "Allowed" : "Blocked")));
    }

    private void toggleClicks() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.viewers.size()) {
            return;
        }

        PeerShareTransport.ViewerInfo viewer = this.viewers.get(this.selectedIndex);
        PeerShareTransport.updateViewerPermissions(viewer.viewerId(), !viewer.allowClicks(), viewer.allowTyping());
        this.refreshViewers(true);
    }

    private void toggleTyping() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.viewers.size()) {
            return;
        }

        PeerShareTransport.ViewerInfo viewer = this.viewers.get(this.selectedIndex);
        PeerShareTransport.updateViewerPermissions(viewer.viewerId(), viewer.allowClicks(), !viewer.allowTyping());
        this.refreshViewers(true);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) {
            return true;
        }

        int left = this.width / 2 - 390 + CARD_PADDING;
        int top = 108 + 44;
        int width = 390 - (CARD_PADDING * 2);

        for (int index = 0; index < Math.min(8, this.viewers.size()); index++) {
            int rowY = top + (index * 30);
            if (click.x() >= left && click.x() <= left + width && click.y() >= rowY && click.y() <= rowY + 24) {
                this.selectedIndex = index;
                this.refreshButtons();
                return true;
            }
        }

        return false;
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.refreshViewers(false);
        int left = this.width / 2 - 390;
        int contentTop = 108;
        int listWidth = 390;
        int sideX = left + listWidth + 16;

        context.fillGradient(0, 0, this.width, this.height, 0xFF0B1016, 0xFF07090D);
        context.fillGradient(0, 0, this.width, this.height / 2, 0x2F3DAA7A, 0x00000000);

        drawPanel(context, left, 18, 796, 76, 0xD711151C, 0xFF45636D);
        context.fill(left + 16, 30, left + 22, 74, 0xFFD6B16F);
        context.drawTextWithShadow(this.textRenderer, this.title, left + 34, 30, 0xFFF3F5F7);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Choose a connected viewer and decide whether they can click or type."), left + 34, 48, 0xFFB8C3CC);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Permissions now apply per viewer instead of to the whole session."), left + 34, 64, 0xFF91A0AA);

        drawPanel(context, left, contentTop, listWidth, this.height - contentTop - 26, 0xD70F141A, 0xFF33424B);
        drawPanel(context, sideX, contentTop, 390, this.height - contentTop - 26, 0xD70E1319, 0xFF3D505D);

        context.drawTextWithShadow(this.textRenderer, Text.literal("Connected Viewers"), left + CARD_PADDING, contentTop + 12, 0xFFF2F4F5);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Select someone from the live session list."), left + CARD_PADDING, contentTop + 28, 0xFF90A0AA);

        int rowX = left + CARD_PADDING;
        int rowY = contentTop + 44;
        int rowWidth = listWidth - (CARD_PADDING * 2);

        if (this.viewers.isEmpty()) {
            fillRect(context, rowX, rowY, rowWidth, 60, 0x6612181F);
            drawRectBorder(context, rowX, rowY, rowWidth, 60, 0x664F6874);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No viewers connected"), rowX + rowWidth / 2, rowY + 18, 0xFFD0D5DA);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Open a session on another client and join it first."), rowX + rowWidth / 2, rowY + 34, 0xFF8FA0AB);
        } else {
            for (int index = 0; index < Math.min(8, this.viewers.size()); index++) {
                PeerShareTransport.ViewerInfo viewer = this.viewers.get(index);
                int topY = rowY + (index * 30);
                int fill = this.selectedIndex == index ? 0xAA26343D : 0x88303A43;
                fillRect(context, rowX, topY, rowWidth, 24, fill);
                drawRectBorder(context, rowX, topY, rowWidth, 24, this.selectedIndex == index ? 0xFFD6B16F : 0x664F6874);
                context.drawTextWithShadow(this.textRenderer, Text.literal(viewer.viewerName()), rowX + 10, topY + 8, 0xFFF2F4F5);
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal("click " + (viewer.allowClicks() ? "on" : "off") + "  |  type " + (viewer.allowTyping() ? "on" : "off")),
                        rowX + 160, topY + 8, 0xFF8FA0AB);
            }
        }

        context.drawTextWithShadow(this.textRenderer, Text.literal("Permission Controls"), sideX + CARD_PADDING, contentTop + 12, 0xFFF2F4F5);
        context.drawTextWithShadow(this.textRenderer, Text.literal(this.selectedIndex >= 0 && this.selectedIndex < this.viewers.size()
                ? "Selected viewer: " + this.viewers.get(this.selectedIndex).viewerName()
                : "Select a viewer to enable controls"), sideX + CARD_PADDING, contentTop + 28, 0xFF90A0AA);

        super.render(context, mouseX, mouseY, delta);
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
}