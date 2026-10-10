package com.hurma.mcpbuilder.mcp;

import com.google.gson.JsonObject;
import com.hurma.mcpbuilder.config.McpConfig;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Shared state and helpers for all tools. Everything here runs on the server thread. */
final class Ctx {
    static final Deque<EditSession> HISTORY = new ArrayDeque<>();

    private Ctx() {}

    static ServerPlayerEntity resolvePlayer(MinecraftServer server, JsonObject a) {
        String name = Args.optStr(a, "player", McpConfig.get().targetPlayer);
        if (name != null && !name.isBlank()) {
            ServerPlayerEntity named = server.getPlayerManager().getPlayer(name);
            if (named != null) return named;
        }
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        return players.isEmpty() ? null : players.get(0);
    }

    static ServerWorld resolveWorld(MinecraftServer server, JsonObject a) {
        String dim = Args.optStr(a, "dimension", null);
        if (dim != null && !dim.isBlank()) {
            Identifier id = Identifier.tryParse(dim.trim());
            if (id == null) throw new ToolException("Invalid dimension id: " + dim);
            ServerWorld world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
            if (world == null) throw new ToolException("Unknown dimension: " + dim);
            return world;
        }
        ServerPlayerEntity player = resolvePlayer(server, a);
        if (player != null) return player.getServerWorld();
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) throw new ToolException("Overworld is not loaded");
        return overworld;
    }

    static EditSession session(ServerWorld world, JsonObject a) {
        return session(world, a, Args.optBool(a, "auto_connect", McpConfig.get().autoConnectBlocks));
    }

    static EditSession session(ServerWorld world, JsonObject a, boolean autoConnect) {
        return new EditSession(world, McpConfig.get().maxBlocksPerRequest, autoConnect, Args.optSeed(a));
    }

    static void checkVolume(long volume) {
        int limit = McpConfig.get().maxBlocksPerRequest;
        if (volume > limit) {
            throw new ToolException("Region has " + volume + " blocks, the limit per request is " + limit + ". Split it or raise the limit in the settings.");
        }
    }

    static ToolResult runEdit(MinecraftServer server, EditSession es, String what, Runnable body) {
        try {
            body.run();
        } catch (ToolException e) {
            if (es.changed > 0) {
                es.finish();
                remember(es);
                notifyPlayers(server, what + " stopped early: " + es.changed + " blocks placed");
                return ToolResult.err(e.getMessage() + " Partial result kept: " + es.changed + " blocks were placed (use undo to revert).");
            }
            throw e;
        }
        es.finish();
        remember(es);
        String msg = what + ": " + es.changed + " blocks changed" + (es.skipped > 0 ? ", " + es.skipped + " skipped (outside world height)" : "");
        if (es.changed > 0) notifyPlayers(server, msg);
        return ToolResult.ok(msg + (es.changed > 0 && McpConfig.get().undoHistorySize > 0 ? ". Can be undone with undo." : "."));
    }

    static void remember(EditSession es) {
        int max = McpConfig.get().undoHistorySize;
        if (es.changed == 0 || max <= 0) return;
        HISTORY.addLast(es);
        while (HISTORY.size() > max) HISTORY.pollFirst();
    }

    static void notifyPlayers(MinecraftServer server, String message) {
        if (!McpConfig.get().chatNotifications) return;
        server.getPlayerManager().broadcast(
                Text.literal("[MCP] ").formatted(Formatting.AQUA).append(Text.literal(message).formatted(Formatting.GRAY)),
                false);
    }

    static String undoSteps(MinecraftServer server, int steps) {
        int done = 0;
        int blocks = 0;
        while (done < steps && !HISTORY.isEmpty()) {
            blocks += HISTORY.pollLast().undo();
            done++;
        }
        if (done == 0) return "Nothing to undo.";
        String msg = "Undid " + done + " operation(s), restored " + blocks + " blocks. " + HISTORY.size() + " left in history.";
        notifyPlayers(server, msg);
        return msg;
    }
}
