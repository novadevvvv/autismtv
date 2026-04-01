package dev.novab.autismtv.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class SessionNotificationCenter {
    private static final long DURATION_MS = 2800L;
    private static final long ANIMATION_MS = 180L;
    private static final List<Notification> NOTIFICATIONS = new ArrayList<>();

    private SessionNotificationCenter() {
    }

    static synchronized void info(String message) {
        push(message, 0xFF45636D, 1.0F);
    }

    static synchronized void success(String message) {
        push(message, 0xFF1F7A5C, 1.1F);
    }

    static synchronized void error(String message) {
        push(message, 0xFF9E4A4A, 0.85F);
    }

    private static void push(String message, int accentColor, float pitch) {
        long now = System.currentTimeMillis();
        NOTIFICATIONS.add(new Notification(message, accentColor, now, now + DURATION_MS));
        if (NOTIFICATIONS.size() > 4) {
            NOTIFICATIONS.remove(0);
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getSoundManager() != null) {
            client.getSoundManager().play(new PositionedSoundInstance(SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.MASTER, 0.8F, pitch,
                    Random.create(), 0.0D, 0.0D, 0.0D));
        }
    }

    static synchronized void render(DrawContext context, TextRenderer textRenderer, int screenWidth, int screenHeight) {
        long now = System.currentTimeMillis();
        Iterator<Notification> iterator = NOTIFICATIONS.iterator();

        while (iterator.hasNext()) {
            if (iterator.next().expiresAtMs() <= now) {
                iterator.remove();
            }
        }

        int y = screenHeight - 34;
        for (int index = NOTIFICATIONS.size() - 1; index >= 0; index--) {
            Notification notification = NOTIFICATIONS.get(index);
            List<OrderedText> wrappedLines = textRenderer.wrapLines(Text.literal(notification.message()), 256);
            int contentHeight = Math.max(1, wrappedLines.size()) * 12;
            int width = 280;
            int height = contentHeight + 16;
            float visibility = getVisibility(now, notification.createdAtMs(), notification.expiresAtMs());
            int slideOffset = Math.round((1.0F - visibility) * 28.0F);
            int x = screenWidth - width - 16 + slideOffset;
            int boxY = y - height + 22;
            int fillAlpha = Math.max(96, Math.min(216, Math.round(visibility * 216.0F)));
            int fillColor = (fillAlpha << 24) | 0x10141A;
            context.fill(x, boxY, x + width, boxY + height, fillColor);
            context.fill(x, boxY, x + width, boxY + 1, notification.accentColor());
            context.fill(x, boxY + height - 1, x + width, boxY + height, notification.accentColor());
            context.fill(x, boxY, x + 1, boxY + height, notification.accentColor());
            context.fill(x + width - 1, boxY, x + width, boxY + height, notification.accentColor());

            int lineY = boxY + 7;
            for (OrderedText line : wrappedLines) {
                context.drawTextWithShadow(textRenderer, line, x + 10, lineY, 0xFFF2F4F5);
                lineY += 12;
            }

            int progressWidth = Math.max(0, Math.round((width - 2) * getLifetimeProgress(now, notification.expiresAtMs())));
            context.fill(x + 1, boxY + height - 2, x + 1 + progressWidth, boxY + height - 1, notification.accentColor());
            y = boxY - 8;
        }
    }

    private static float getVisibility(long now, long createdAtMs, long expiresAtMs) {
        float fadeIn = Math.min(1.0F, (now - createdAtMs) / (float) ANIMATION_MS);
        float fadeOut = Math.min(1.0F, (expiresAtMs - now) / (float) ANIMATION_MS);
        return Math.max(0.0F, Math.min(fadeIn, fadeOut));
    }

    private static float getLifetimeProgress(long now, long expiresAtMs) {
        float remaining = Math.max(0.0F, expiresAtMs - now);
        return Math.max(0.0F, Math.min(1.0F, remaining / (float) DURATION_MS));
    }

    private record Notification(String message, int accentColor, long createdAtMs, long expiresAtMs) {
    }
}