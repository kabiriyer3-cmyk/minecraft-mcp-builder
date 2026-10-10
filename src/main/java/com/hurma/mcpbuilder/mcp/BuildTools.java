package com.hurma.mcpbuilder.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hurma.mcpbuilder.McpBuilderMod;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Registry of all MCP tools. Every tool body runs on the Minecraft server thread. */
public class BuildTools {
    @FunctionalInterface
    public interface Handler {
        ToolResult run(MinecraftServer server, JsonObject args);
    }

    private record Tool(String name, String description, JsonObject schema, Handler handler) {}

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public BuildTools() {
        BasicTools.register(this);
        ShapeTools.register(this);
        DetailTools.register(this);
        ViewTools.register(this);
    }

    void add(String name, String description, JsonObject schema, Handler handler) {
        tools.put(name, new Tool(name, description, schema, handler));
    }

    public JsonObject listTools() {
        JsonArray array = new JsonArray();
        for (Tool tool : tools.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", tool.name());
            o.addProperty("description", tool.description());
            o.add("inputSchema", tool.schema());
            array.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("tools", array);
        return result;
    }

    public JsonObject callTool(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        Tool tool = tools.get(name);
        ToolResult r = tool == null ? ToolResult.err("Unknown tool: " + name) : execute(tool, args);

        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", r.text());
        content.add(text);
        result.add("content", content);
        result.addProperty("isError", r.error());
        return result;
    }

    private ToolResult execute(Tool tool, JsonObject args) {
        MinecraftServer server = McpServerManager.getServer();
        if (server == null) {
            return ToolResult.err("No Minecraft world is loaded right now. Ask the player to open a world.");
        }
        CompletableFuture<ToolResult> future = server.submit((Supplier<ToolResult>) () -> {
            try {
                return tool.handler().run(server, args);
            } catch (ToolException e) {
                return ToolResult.err(e.getMessage());
            } catch (Exception e) {
                McpBuilderMod.LOGGER.warn("MCP tool {} failed", tool.name(), e);
                return ToolResult.err(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
        try {
            return future.get(180, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ToolResult.err("Timed out waiting for the server thread (is the game frozen?)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.err("Interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return ToolResult.err("Tool failed: " + cause.getMessage());
        }
    }

    /** Must be called on the server thread. */
    public static String undoSteps(MinecraftServer server, int steps) {
        return Ctx.undoSteps(server, steps);
    }

    public static void clearHistory() {
        Ctx.HISTORY.clear();
    }
}
