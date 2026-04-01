package dev.novab.autismtv.mixin.client;

import dev.novab.autismtv.client.LocalPanelController;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void autismtv$handlePanelMouse(long window, MouseInput mouseInput, int action, CallbackInfo ci) {
        if (LocalPanelController.handleMouseInput(mouseInput, action)) {
            ci.cancel();
        }
    }
}