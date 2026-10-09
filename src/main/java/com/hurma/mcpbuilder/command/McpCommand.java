package com.hurma.mcpbuilder.command;

import com.hurma.mcpbuilder.config.McpConfig;
import com.hurma.mcpbuilder.mcp.BuildTools;
import com.hurma.mcpbuilder.mcp.McpServerManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public final class McpCommand {
    private McpCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("mcp")
                .executes(ctx -> status(ctx.getSource()))
                .then(CommandManager.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(CommandManager.literal("restart")
                        .requires(src -> src.hasPermissionLevel(2))
                        .executes(ctx -> {
                            McpConfig.load();
                            McpServerManager.start();
                            return status(ctx.getSource());
                        }))
                .then(CommandManager.literal("undo")
                        .requires(src -> src.hasPermissionLevel(2))
                        .executes(ctx -> undo(ctx.getSource(), 1))
                        .then(CommandManager.argument("steps", IntegerArgumentType.integer(1, 500))
                                .executes(ctx -> undo(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "steps"))))));
    }

    private static int status(ServerCommandSource source) {
        boolean running = McpServerManager.isRunning();
        String msg = "[MCP] " + (running ? "running at " : "stopped, endpoint would be ") + McpServerManager.endpoint();
        source.sendFeedback(() -> Text.literal(msg), false);
        return 1;
    }

    private static int undo(ServerCommandSource source, int steps) {
        String msg = BuildTools.undoSteps(source.getServer(), steps);
        source.sendFeedback(() -> Text.literal("[MCP] " + msg), false);
        return 1;
    }
}
