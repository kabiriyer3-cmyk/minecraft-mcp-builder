package com.hurma.mcpbuilder.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hurma.mcpbuilder.McpBuilderMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class McpConfig {
    public boolean enabled = true;
    public String host = "127.0.0.1";
    public int port = 25599;
    public String authToken = "";
    public String targetPlayer = "";
    public int maxBlocksPerRequest = 250000;
    public int undoHistorySize = 20;
    public boolean allowCommands = true;
    public boolean chatNotifications = true;
    public boolean autoConnectBlocks = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile McpConfig instance = new McpConfig();

    public static McpConfig get() {
        return instance;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcpbuilder.json");
    }

    public static synchronized void load() {
        Path p = path();
        McpConfig loaded = null;
        if (Files.exists(p)) {
            try (Reader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, McpConfig.class);
            } catch (Exception e) {
                McpBuilderMod.LOGGER.error("Failed to read config, using defaults", e);
            }
        }
        if (loaded == null) {
            loaded = new McpConfig();
        }
        loaded.sanitize();
        instance = loaded;
        save();
    }

    public static synchronized void save() {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            try (Writer writer = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            McpBuilderMod.LOGGER.error("Failed to save config", e);
        }
    }

    public void sanitize() {
        if (host == null || host.isBlank()) host = "127.0.0.1";
        host = host.trim();
        if (port < 1 || port > 65535) port = 25599;
        authToken = authToken == null ? "" : authToken.trim();
        targetPlayer = targetPlayer == null ? "" : targetPlayer.trim();
        if (maxBlocksPerRequest < 1) maxBlocksPerRequest = 1;
        if (maxBlocksPerRequest > 10_000_000) maxBlocksPerRequest = 10_000_000;
        if (undoHistorySize < 0) undoHistorySize = 0;
        if (undoHistorySize > 500) undoHistorySize = 500;
    }
}
