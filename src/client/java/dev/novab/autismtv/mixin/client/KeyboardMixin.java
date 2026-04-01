package dev.novab.autismtv.mixin.client;

import dev.novab.autismtv.client.LocalPanelController;
import dev.novab.autismtv.client.SessionHubScreen;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.glfw.GLFW;

@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void autismtv$forwardFocusedKey(long window, int action, KeyInput keyInput, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (action == GLFW.GLFW_PRESS && keyInput.getKeycode() == GLFW.GLFW_KEY_B && client.currentScreen == null) {
            client.setScreen(new SessionHubScreen(null));
            ci.cancel();
            return;
        }

        if (LocalPanelController.handleFocusedKeyInput(action, keyInput)) {
            ci.cancel();
        }
    }

    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void autismtv$forwardFocusedChar(long window, CharInput charInput, CallbackInfo ci) {
        if (LocalPanelController.handleFocusedCharInput(charInput)) {
            ci.cancel();
        }
    }
}
