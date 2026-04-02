package dev.novab.autismtv.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

public final class AutismTVClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.setProperty("java.awt.headless", "false");
        SharescreenConfig.get();
        PeerShareTransport.initialize();
        ServerRelayTransport.initialize();
        ClientCommandRegistrationCallback.EVENT.register(LocalPanelController::registerCommands);
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(LocalPanelController::render);
    }
}
