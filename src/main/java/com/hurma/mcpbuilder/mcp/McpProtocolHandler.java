package com.hurma.mcpbuilder.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hurma.mcpbuilder.McpBuilderMod;

import java.util.List;

/** JSON-RPC 2.0 / MCP message dispatcher. */
public class McpProtocolHandler {
    private static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final String INSTRUCTIONS = String.join("\n",
            "You are connected to a live Minecraft Java Edition 1.21.1 world and can build in it.",
            "Coordinates: +X = east, -X = west, +Y = up, +Z = south, -Z = north.",
            "Workflow: 1) call get_player_info to learn where the player stands and which way they face;",
            "2) use get_height / scan_area to inspect the terrain; 3) build a few blocks in front of the player, never on top of them;",
            "4) use fill for floors, walls and boxes, build_sphere / build_cylinder / build_line for shapes,",
            "and place_blocks for details (batch hundreds of blocks per call, use 'origin' with relative coordinates and a 'palette' to save tokens);",
            "5) if something looks wrong, call undo.",
            "Block ids use command syntax with optional states, e.g. minecraft:oak_stairs[facing=north,half=bottom], minecraft:lantern[hanging=true], minecraft:spruce_slab[type=top].",
            "For great-looking builds: mix several materials, add depth to walls (pillars, frames), real roofs with stairs and slabs, windows, lighting and decoration.");

    private final BuildTools tools;

    public McpProtocolHandler(BuildTools tools) {
        this.tools = tools;
    }

    /** @return the JSON response, or null when nothing has to be returned (notifications only). */
    public String handle(String body) {
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (Exception e) {
            return error(JsonNull.INSTANCE, -32700, "Parse error").toString();
        }
        if (root.isJsonArray()) {
            JsonArray responses = new JsonArray();
            for (JsonElement element : root.getAsJsonArray()) {
                JsonObject r = handleOne(element);
                if (r != null) responses.add(r);
            }
            return responses.isEmpty() ? null : responses.toString();
        }
        JsonObject r = handleOne(root);
        return r == null ? null : r.toString();
    }

    private JsonObject handleOne(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return error(JsonNull.INSTANCE, -32600, "Invalid Request");
        }
        JsonObject request = element.getAsJsonObject();
        JsonElement id = request.get("id");
        boolean notification = id == null || id.isJsonNull();
        if (!request.has("method")) {
            return null; // a response sent by the client, ignore
        }
        String method = request.get("method").getAsString();
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();

        try {
            JsonElement result;
            switch (method) {
                case "initialize" -> result = initialize(params);
                case "ping" -> result = new JsonObject();
                case "tools/list" -> result = tools.listTools();
                case "tools/call" -> result = tools.callTool(params);
                case "resources/list" -> {
                    JsonObject o = new JsonObject();
                    o.add("resources", new JsonArray());
                    result = o;
                }
                case "resources/templates/list" -> {
                    JsonObject o = new JsonObject();
                    o.add("resourceTemplates", new JsonArray());
                    result = o;
                }
                case "prompts/list" -> {
                    JsonObject o = new JsonObject();
                    o.add("prompts", new JsonArray());
                    result = o;
                }
                default -> {
                    if (notification) return null;
                    return error(id, -32601, "Method not found: " + method);
                }
            }
            if (notification) return null;
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", id);
            response.add("result", result);
            return response;
        } catch (Exception e) {
            McpBuilderMod.LOGGER.warn("MCP method {} failed", method, e);
            if (notification) return null;
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "";
        String version = SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(1);

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", version);
        JsonObject capabilities = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        capabilities.add("tools", toolsCap);
        result.add("capabilities", capabilities);
        JsonObject info = new JsonObject();
        info.addProperty("name", "minecraft-mcp-builder");
        info.addProperty("title", "Minecraft MCP Builder");
        info.addProperty("version", McpBuilderMod.version());
        result.add("serverInfo", info);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        response.add("error", err);
        return response;
    }
}
