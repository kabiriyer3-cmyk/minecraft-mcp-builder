package com.hurma.mcpbuilder;

import com.hurma.mcpbuilder.command.McpCommand;
import com.hurma.mcpbuilder.config.McpConfig;
import com.hurma.mcpbuilder.mcp.McpServerManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McpBuilderMod implements ModInitializer {
    public static final String MOD_ID = "mcpbuilder";
    public static final Logger LOGGER = LoggerFactory.getLogger("MCP Builder");

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
    }

    @Override
    public void onInitialize() {
        McpConfig.load();
        ServerLifecycleEvents.SERVER_STARTED.register(McpServerManager::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> McpServerManager.onServerStopping());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> McpCommand.register(dispatcher));
        LOGGER.info("MCP Builder initialized");
    }
}
