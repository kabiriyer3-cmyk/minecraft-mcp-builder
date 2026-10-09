package com.hurma.mcpbuilder.mcp;

import com.hurma.mcpbuilder.McpBuilderMod;
import com.hurma.mcpbuilder.config.McpConfig;
import net.minecraft.server.MinecraftServer;

public final class McpServerManager {
    private static volatile MinecraftServer server;
    private static McpHttpServer http;

    private McpServerManager() {}

    public static MinecraftServer getServer() {
        return server;
    }

    public static synchronized void onServerStarted(MinecraftServer started) {
        server = started;
        start();
    }

    public static synchronized void onServerStopping() {
        stop();
        BuildTools.clearHistory();
        server = null;
    }

    /** (Re)starts the HTTP endpoint using the current config. */
    public static synchronized void start() {
        stop();
        McpConfig config = McpConfig.get();
        if (server == null || !config.enabled) {
            return;
        }
        try {
            McpHttpServer created = new McpHttpServer(config.host, config.port, new McpProtocolHandler(new BuildTools()));
            created.start();
            http = created;
            McpBuilderMod.LOGGER.info("MCP server listening on {}", endpoint());
        } catch (Exception e) {
            McpBuilderMod.LOGGER.error("Could not start MCP server on {}:{}", config.host, config.port, e);
        }
    }

    public static synchronized void stop() {
        if (http != null) {
            http.stop();
            http = null;
            McpBuilderMod.LOGGER.info("MCP server stopped");
        }
    }

    /** Called after settings change: apply them if a world is currently running. */
    public static synchronized void applySettings() {
        if (server != null) {
            start();
        }
    }

    public static synchronized boolean isRunning() {
        return http != null;
    }

    public static String endpoint() {
        McpConfig c = McpConfig.get();
        String host = c.host.equals("0.0.0.0") ? "127.0.0.1" : c.host;
        return "http://" + host + ":" + c.port + "/mcp";
    }
}
