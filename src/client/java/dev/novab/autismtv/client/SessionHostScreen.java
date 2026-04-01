package dev.novab.autismtv.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class SessionHostScreen extends Screen {
    private static final int CARD_PADDING = 18;

    private final Screen parent;
    private TextFieldWidget nameField;
    private TextFieldWidget passwordField;

    public SessionHostScreen(Screen parent) {
        super(Text.literal("Create Session"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 190;
        int top = this.height / 2 - 46;

        this.nameField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, left, top, 380, 20, Text.literal("Session name")));
        this.nameField.setMaxLength(48);
        this.nameField.setText("AutismTV Session");

        this.passwordField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, left, top + 34, 380, 20, Text.literal("Password")));
        this.passwordField.setMaxLength(64);
        this.passwordField.setPlaceholder(Text.literal("Leave blank for public LAN join"));

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Create"), button -> this.createSession())
            .dimensions(left, top + 74, 186, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> this.close())
            .dimensions(left + 194, top + 74, 186, 20).build());
    }

    private void createSession() {
        try {
            PeerShareTransport.startHosting(this.nameField.getText(), "", this.passwordField.getText(), PeerShareTransport.getDefaultPort(), true, true);
            LocalPanelController.onSettingsChanged();
            this.client.setScreen(new SessionHubScreen(this.parent, SessionHubScreen.HubTab.MY_SESSIONS));
        } catch (Exception exception) {
            this.passwordField.setSuggestion("Create failed: " + exception.getMessage());
        }
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cardX = this.width / 2 - 220;
        int cardY = this.height / 2 - 82;
        int cardWidth = 440;
        int cardHeight = 190;

        context.fillGradient(0, 0, this.width, this.height, 0xFF0B1016, 0xFF07090D);
        context.fillGradient(0, 0, this.width, this.height / 2, 0x2F3DAA7A, 0x00000000);
        context.fill(this.width / 2 - 280, this.height / 2 - 140, this.width / 2 + 280, this.height / 2 + 40, 0x0FD6B16F);

        drawPanel(context, cardX, cardY, cardWidth, cardHeight, 0xD711151C, 0xFF45636D);
        context.fill(cardX + 16, cardY + 14, cardX + 22, cardY + 62, 0xFFD6B16F);
        context.drawTextWithShadow(this.textRenderer, this.title, cardX + 34, cardY + 14, 0xFFF3F5F7);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Name the session and optionally protect it with a password."),
            cardX + 34, cardY + 32, 0xFFB8C3CC);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Anyone on the same LAN should see it automatically in the B menu."),
            cardX + 34, cardY + 48, 0xFF91A0AA);
        super.render(context, mouseX, mouseY, delta);
    }

        private static void drawPanel(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor) {
        context.fill(x, y, x + width, y + height, fillColor);
        context.fill(x, y, x + width, y + 1, borderColor);
        context.fill(x, y + height - 1, x + width, y + height, borderColor);
        context.fill(x, y, x + 1, y + height, borderColor);
        context.fill(x + width - 1, y, x + width, y + height, borderColor);
        }
}