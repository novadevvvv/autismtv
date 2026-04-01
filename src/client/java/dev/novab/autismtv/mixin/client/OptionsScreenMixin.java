package dev.novab.autismtv.mixin.client;

import dev.novab.autismtv.client.SessionHubScreen;
import dev.novab.autismtv.client.SessionHubScreen.HubTab;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
    protected OptionsScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void autismtv$addSharescreenSettingsButton(CallbackInfo ci) {
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Session Hub"), button -> this.client.setScreen(new SessionHubScreen((Screen) (Object) this, HubTab.MY_SESSIONS)))
            .dimensions(this.width / 2 - 100, this.height - 52, 200, 20)
                .build());
    }
}