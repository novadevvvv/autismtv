package dev.novab.autismtv.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class SessionExtraSettingsScreen extends Screen {
    private final Screen parent;

    public SessionExtraSettingsScreen(Screen parent) {
        super(Text.literal("Extra Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> this.close())
                .dimensions(this.width / 2 - 90, this.height / 2 + 24, 180, 20).build());
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fillGradient(0, 0, this.width, this.height, 0xFF0C1017, 0xFF05070A);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 14, 0xFFF3F5F7);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Nothing here yet."), this.width / 2, this.height / 2 + 2, 0xFF9FB0BC);
        super.render(context, mouseX, mouseY, delta);
    }
}