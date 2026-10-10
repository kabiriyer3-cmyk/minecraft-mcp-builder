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
            "You are connected to a live Minecraft Java Edition 1.21.1 world and can build in it. Aim for highly detailed, professional-looking builds.",
            "Coordinates: +X = east, -X = west, +Y = up, +Z = south, -Z = north.",
            "Before a big build call get_building_guide (topic 'techniques', then the style) and get_player_info (looking_at is a good anchor).",
            "Every block parameter accepts patterns: block states/NBT (minecraft:oak_stairs[facing=north,half=top]) and weighted mixes for texture",
            "('60%minecraft:stone_bricks,25%minecraft:cracked_stone_bricks,15%minecraft:mossy_stone_bricks'). Use search_blocks to find ids and valid states.",
            "Toolbox: fill (replace/hollow/walls/outline/keep + mask), build_cylinder, build_sphere, build_shape (ellipsoid, cone/spire with curve, pyramid, torus),",
            "build_arch (gothic/round arches, doors and windows with fill), build_roof (gable/hip stair roofs with ridge and gables), build_curve (splines with taper: branches, buttresses, bridges),",
            "build_blueprint (ASCII layers + palette: the best way to place intricate designed details), clone_region (copy with rotate/mirror/repeat for symmetry and repeated bays),",
            "replace_blocks (weathering/texture with chance and clustered noise, keeps stair orientation), gradient_fill (colour/age gradients), decorate_surface (moss, vines, lanterns, flowers),",
            "place_blocks / set_block for single blocks, run_command for entities (armor stands, item frames, paintings).",
            "Work in passes: massing -> roofs -> openings -> depth layers (pillars, trims, sills, corbels: never leave flat walls) -> texture pass -> decoration -> review.",
            "Review with view_area (top, slice, south/east/... elevations) after each major step and fix problems. If something looks wrong, call undo.",
            "Build a few blocks away from the player, never on top of them.");

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
