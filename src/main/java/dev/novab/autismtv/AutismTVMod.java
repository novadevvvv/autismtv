package dev.novab.autismtv;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AutismTVMod implements ModInitializer {
    public static final String MOD_ID = "autismtv";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("AutismTV local panel client mod initialized");
    }
}
