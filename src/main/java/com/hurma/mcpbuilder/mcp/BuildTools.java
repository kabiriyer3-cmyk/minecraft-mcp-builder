package com.hurma.mcpbuilder.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hurma.mcpbuilder.McpBuilderMod;
import com.hurma.mcpbuilder.config.McpConfig;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** All MCP tools. Every tool body runs on the Minecraft server thread. */
public class BuildTools {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int AUTO_CONNECT_LIMIT = 100_000;
    private static final int SCAN_MAX_VOLUME = 262_144;
    private static final int SCAN_LIST_LIMIT = 4_000;
    private static final int MAX_RADIUS = 96;
    private static final String DIM_DESC = "Optional dimension id, e.g. minecraft:overworld, minecraft:the_nether. Defaults to the player's dimension.";

    /** Undo history, only touched on the server thread. */
    private static final Deque<EditSession> HISTORY = new ArrayDeque<>();

    public record ToolResult(String text, boolean error) {
        public static ToolResult ok(String text) { return new ToolResult(text, false); }
        public static ToolResult err(String text) { return new ToolResult(text, true); }
    }

    public static class ToolException extends RuntimeException {
        public ToolException(String message) { super(message); }
    }

    @FunctionalInterface
    private interface Handler {
        ToolResult run(MinecraftServer server, JsonObject args);
    }

    private record Tool(String name, String description, JsonObject schema, Handler handler) {}

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public BuildTools() {
        register("get_player_info",
                "Get position, facing direction, dimension, game mode and ground height of the player, plus the list of online players. Call this first to know where to build.",
                schema().opt("player", str("Optional player name. Defaults to the configured target player or the first online player.")).build(),
                this::getPlayerInfo);
        register("get_block",
                "Get the block (with its block state) at a position.",
                schema().pos().dim().build(),
                this::getBlock);
        register("get_height",
                "Get the Y of the highest solid surface block (ignoring leaves) in the X/Z column. Use it to put buildings on the ground.",
                schema().req("x", integer("X")).req("z", integer("Z")).dim().build(),
                this::getHeight);
        register("scan_area",
                "Inspect a cuboid region: returns counts per block type and a list of non-air blocks as 'x y z block' lines (max volume 262144, first 4000 blocks listed).",
                schema().region().opt("include_air", bool("Also list air blocks (default false).")).dim().build(),
                this::scanArea);
        register("set_block",
                "Place a single block. Block ids support states, e.g. minecraft:oak_stairs[facing=east].",
                schema().pos().req("block", str("Block id, e.g. minecraft:stone_bricks")).dim().autoConnect().build(),
                this::setBlock);
        register("place_blocks",
                "Place many blocks in one call (preferred for detailed builds). Each entry is {x,y,z,block} or a compact array [x,y,z,block]. "
                        + "Coordinates are offset by 'origin' if given, so you can design with relative coordinates. "
                        + "'block' may be a key from 'palette' (e.g. palette {\"W\":\"minecraft:oak_planks\"} then block \"W\").",
                schema()
                        .req("blocks", blocksArraySchema())
                        .opt("origin", xyzObject("Optional origin added to every coordinate."))
                        .opt("palette", paletteSchema())
                        .dim().autoConnect().build(),
                this::placeBlocks);
        register("fill",
                "Fill a cuboid between two corners. Modes: replace (default, solid box), hollow (walls/floor/ceiling of block, inside cleared to air), "
                        + "outline (only the shell, inside untouched), keep (only replace air). Optional replace_only limits which existing block is replaced.",
                schema().region()
                        .req("block", str("Block id with optional states"))
                        .opt("mode", enumStr("Fill mode", "replace", "hollow", "outline", "keep"))
                        .opt("replace_only", str("Only replace blocks of this type, e.g. minecraft:grass_block"))
                        .dim().autoConnect().build(),
                this::fill);
        register("clear_area",
                "Set every block in a cuboid to air (clear space before building).",
                schema().region().dim().build(),
                this::clearArea);
        register("build_sphere",
                "Build a sphere or dome. half=top makes a dome, half=bottom a bowl.",
                schema().req("x", integer("Center X")).req("y", integer("Center Y")).req("z", integer("Center Z"))
                        .req("radius", integer("Radius 1.." + MAX_RADIUS))
                        .req("block", str("Block id"))
                        .opt("hollow", bool("Only the shell (default false)"))
                        .opt("half", enumStr("Which part to build", "full", "top", "bottom"))
                        .dim().autoConnect().build(),
                this::buildSphere);
        register("build_cylinder",
                "Build a cylinder (towers, pillars, tubes). x/y/z is the center of the base, it extends 'height' blocks along the axis.",
                schema().req("x", integer("Base center X")).req("y", integer("Base center Y")).req("z", integer("Base center Z"))
                        .req("radius", integer("Radius 1.." + MAX_RADIUS))
                        .req("height", integer("Length along the axis, 1..384"))
                        .req("block", str("Block id"))
                        .opt("hollow", bool("Only the side wall (default false)"))
                        .opt("axis", enumStr("Axis (default y = vertical)", "y", "x", "z"))
                        .dim().autoConnect().build(),
                this::buildCylinder);
        register("build_line",
                "Build a straight 3D line between two points (bridges, cables, beams, diagonal supports).",
                schema().region()
                        .req("block", str("Block id"))
                        .opt("thickness", integer("Line thickness 1..9 (default 1)"))
                        .dim().autoConnect().build(),
                this::buildLine);
        register("undo",
                "Undo the last build operation(s) made through MCP.",
                schema().opt("steps", integer("How many operations to undo (default 1)")).build(),
                this::undo);
        register("run_command",
                "Run a Minecraft server command with operator permissions (e.g. 'time set day', 'weather clear', 'summon minecraft:villager ~ ~ ~', 'tp @p 0 80 0'). Changes made by commands cannot be undone with undo.",
                schema().req("command", str("Command without the leading slash")).build(),
                this::runCommand);
    }

    // ------------------------------------------------------------------ MCP glue

    private void register(String name, String description, JsonObject schema, Handler handler) {
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
            return future.get(120, TimeUnit.SECONDS);
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

    // ------------------------------------------------------------------ read tools

    private ToolResult getPlayerInfo(MinecraftServer server, JsonObject a) {
        JsonObject o = new JsonObject();
        JsonArray online = new JsonArray();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            online.add(p.getName().getString());
        }
        o.add("online_players", online);
        ServerPlayerEntity player = resolvePlayer(server, a);
        if (player == null) {
            o.addProperty("note", "No players online. You can still build using absolute coordinates in the overworld.");
            return ToolResult.ok(GSON.toJson(o));
        }
        ServerWorld world = player.getServerWorld();
        BlockPos pos = player.getBlockPos();
        o.addProperty("player", player.getName().getString());
        o.addProperty("dimension", world.getRegistryKey().getValue().toString());
        JsonObject p = new JsonObject();
        p.addProperty("x", pos.getX());
        p.addProperty("y", pos.getY());
        p.addProperty("z", pos.getZ());
        o.add("block_position", p);
        o.addProperty("yaw", Math.round(player.getYaw() * 10f) / 10.0);
        o.addProperty("pitch", Math.round(player.getPitch() * 10f) / 10.0);
        o.addProperty("facing", player.getHorizontalFacing().asString());
        o.addProperty("ground_y_below_player", world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - 1);
        o.addProperty("world_min_y", world.getBottomY());
        o.addProperty("world_max_y", world.getTopY() - 1);
        o.addProperty("game_mode", player.interactionManager.getGameMode().asString());
        o.addProperty("hint", "facing north = -Z, south = +Z, east = +X, west = -X. Start builds ~5-10 blocks in front of the player.");
        return ToolResult.ok(GSON.toJson(o));
    }

    private ToolResult getBlock(MinecraftServer server, JsonObject a) {
        ServerWorld world = resolveWorld(server, a);
        BlockPos pos = new BlockPos(reqInt(a, "x"), reqInt(a, "y"), reqInt(a, "z"));
        BlockState state = world.getBlockState(pos);
        return ToolResult.ok(pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + BlockArgumentParser.stringifyBlockState(state));
    }

    private ToolResult getHeight(MinecraftServer server, JsonObject a) {
        ServerWorld world = resolveWorld(server, a);
        int x = reqInt(a, "x");
        int z = reqInt(a, "z");
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockState state = world.getBlockState(new BlockPos(x, top, z));
        return ToolResult.ok("Surface at x=" + x + " z=" + z + ": y=" + top + " (" + BlockArgumentParser.stringifyBlockState(state) + "). Build starting at y=" + (top + 1) + ".");
    }

    private ToolResult scanArea(MinecraftServer server, JsonObject a) {
        ServerWorld world = resolveWorld(server, a);
        Region r = Region.of(a);
        if (r.volume() > SCAN_MAX_VOLUME) {
            throw new ToolException("Region too large to scan (" + r.volume() + " blocks, max " + SCAN_MAX_VOLUME + "). Scan smaller parts.");
        }
        boolean includeAir = optBool(a, "include_air", false);
        Map<String, Integer> counts = new TreeMap<>();
        StringBuilder list = new StringBuilder();
        int listed = 0;
        boolean truncated = false;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = r.minY; y <= r.maxY; y++) {
            for (int z = r.minZ; z <= r.maxZ; z++) {
                for (int x = r.minX; x <= r.maxX; x++) {
                    m.set(x, y, z);
                    BlockState state = world.getBlockState(m);
                    String id = Registries.BLOCK.getId(state.getBlock()).toString();
                    counts.merge(id, 1, Integer::sum);
                    if (state.isAir() && !includeAir) continue;
                    if (listed < SCAN_LIST_LIMIT) {
                        list.append(x).append(' ').append(y).append(' ').append(z).append(' ')
                                .append(BlockArgumentParser.stringifyBlockState(state)).append('\n');
                        listed++;
                    } else {
                        truncated = true;
                    }
                }
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("Region ").append(r).append(", volume ").append(r.volume()).append("\nCounts:\n");
        counts.forEach((k, v) -> out.append("  ").append(k).append(": ").append(v).append('\n'));
        out.append("Blocks (x y z block):\n").append(list);
        if (truncated) out.append("... truncated after ").append(SCAN_LIST_LIMIT).append(" blocks, scan a smaller region for full detail.\n");
        return ToolResult.ok(out.toString());
    }

    // ------------------------------------------------------------------ build tools

    private ToolResult setBlock(MinecraftServer server, JsonObject a) {
        ServerWorld world = resolveWorld(server, a);
        int x = reqInt(a, "x"), y = reqInt(a, "y"), z = reqInt(a, "z");
        BlockState state = parseBlock(reqStr(a, "block"), new HashMap<>());
        EditSession es = newSession(world, a);
        return runEdit(server, es, "set_block", () -> es.set(x, y, z, state));
    }

    private ToolResult placeBlocks(MinecraftServer server, JsonObject a) {
        ServerWorld world = resolveWorld(server, a);
        if (!a.has("blocks") || !a.get("blocks").isJsonArray()) {
            throw new ToolException("'blocks' must be an array");
        }
        JsonArray blocks = a.getAsJsonArray("blocks");
        int limit = McpConfig.get().maxBlocksPerRequest;
        if (blocks.size() > limit) {
            throw new ToolException("Too many blocks in one request (" + blocks.size() + " > " + limit + "). Split it into several calls.");
        }
        int ox = 0, oy = 0, oz = 0;
        if (a.has("origin") && a.get("origin").isJsonObject()) {
            JsonObject origin = a.getAsJsonObject("origin");
            ox = optInt(origin, "x", 0);
            oy = optInt(origin, "y", 0);
            oz = optInt(origin, "z", 0);
        }
        Map<String, String> palette = new HashMap<>();
        if (a.has("palette") && a.get("palette").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : a.getAsJsonObject("palette").entrySet()) {
                palette.put(e.getKey(), e.getValue().getAsString());
            }
        }
        Map<String, BlockState> cache = new HashMap<>();
        final int fx = ox, fy = oy, fz = oz;
        EditSession es = newSession(world, a);
        return runEdit(server, es, "place_blocks", () -> {
            int index = 0;
            for (JsonElement e : blocks) {
                int x, y, z;
                String id;
                try {
                    if (e.isJsonArray()) {
                        JsonArray t = e.getAsJsonArray();
                        if (t.size() < 4) throw new ToolException("blocks[" + index + "] must be [x,y,z,block]");
                        x = (int) Math.floor(t.get(0).getAsDouble());
                        y = (int) Math.floor(t.get(1).getAsDouble());
                        z = (int) Math.floor(t.get(2).getAsDouble());
                        id = t.get(3).getAsString();
                    } else if (e.isJsonObject()) {
                        JsonObject t = e.getAsJsonObject();
                        x = reqInt(t, "x");
                        y = reqInt(t, "y");
                        z = reqInt(t, "z");
                        id = reqStr(t, "block");
                    } else {
                        throw new ToolException("blocks[" + index + "] must be an object or array");
                    }
                } catch (ToolException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new ToolException("blocks[" + index + "] is malformed: " + ex.getMessage());
                }
                String resolved = palette.getOrDefault(id, id);
                es.set(x + fx, y + fy, z + fz, parseBlock(resolved, cache));
                index++;
            }
        });
    }

    private ToolResult fill(MinecraftServer server, JsonObject a) {
        ServerWorld world = resolveWorld(server, a);
        Region r = Region.of(a);
        checkVolume(r);
        Map<String, BlockState> cache = new HashMap<>();
        BlockState state = parseBlock(reqStr(a, "block"), cache);
        String mode = optStr(a, "mode", "replace").toLowerCase(Locale.ROOT);
        if (!List.of("replace", "hollow", "outline", "keep").contains(mode)) {
            throw new ToolException("Unknown mode '" + mode + "'. Use replace, hollow, outline or keep.");
        }
        String only = optStr(a, "replace_only", null);
        Block onlyBlock = only == null || only.isBlank() ? null : parseBlock(only, cache).getBlock();
        BlockState air = Blocks.AIR.getDefaultState();
        EditSession es = newSession(world, a);
        return runEdit(server, es, "fill (" + mode + ")", () -> {
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int y = r.minY; y <= r.maxY; y++) {
                for (int z = r.minZ; z <= r.maxZ; z++) {
                    for (int x = r.minX; x <= r.maxX; x++) {
                        boolean edge = x == r.minX || x == r.maxX || y == r.minY || y == r.maxY || z == r.minZ || z == r.maxZ;
                        BlockState target;
                        switch (mode) {
                            case "hollow" -> target = edge ? state : air;
                            case "outline" -> {
                                if (!edge) continue;
                                target = state;
                            }
                            case "keep" -> {
                                if (!world.getBlockState(m.set(x, y, z)).isAir()) continue;
                                target = state;
                            }
                            default -> target = state;
                        }
                        if (onlyBlock != null && !world.getBlockState(m.set(x, y, z)).isOf(onlyBlock)) continue;
                        es.set(x, y, z, target);
                    }
                }
            }
        });
    }

    private ToolResult clearArea(MinecraftServer server, JsonObject a) {
        ServerWorld world = resolveWorld(server, a);
        Region r = Region.of(a);
        checkVolume(r);
        BlockState air = Blocks.AIR.getDefaultState();
        EditSession es = new EditSession(world, McpConfig.get().maxBlocksPerRequest, false);
        return runEdit(server, es, "clear_area", () -> {
            for (int y = r.maxY; y >= r.minY; y--) {
                for (int z = r.minZ; z <= r.maxZ; z++) {
                    for (int x = r.minX; x <= r.maxX; x++) {
                        es.set(x, y, z, air);
                    }
                }
            }
        });
    }

    private ToolResult buildSphere(MinecraftServer server, JsonObject a) {
        ServerWorld world = resolveWorld(server, a);
        int cx = reqInt(a, "x"), cy = reqInt(a, "y"), cz = reqInt(a, "z");
        int radius = reqInt(a, "radius");
        if (radius < 1 || radius > MAX_RADIUS) throw new ToolException("radius must be 1.." + MAX_RADIUS);
        BlockState state = parseBlock(reqStr(a, "block"), new HashMap<>());
        boolean hollow = optBool(a, "hollow", false);
        String half = optStr(a, "half", "full").toLowerCase(Locale.ROOT);
        double rr = radius * (double) radius + radius;
        EditSession es = newSession(world, a);
        return runEdit(server, es, "build_sphere", () -> {
            for (int dy = -radius; dy <= radius; dy++) {
                if (half.equals("top") && dy < 0) continue;
                if (half.equals("bottom") && dy > 0) continue;
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (!inSphere(dx, dy, dz, rr)) continue;
                        if (hollow && inSphere(dx + 1, dy, dz, rr) && inSphere(dx - 1, dy, dz, rr)
                                && inSphere(dx, dy + 1, dz, rr) && inSphere(dx, dy - 1, dz, rr)
                                && inSphere(dx, dy, dz + 1, rr) && inSphere(dx, dy, dz - 1, rr)) continue;
                        es.set(cx + dx, cy + dy, cz + dz, state);
                    }
                }
            }
        });
    }

    private ToolResult buildCylinder(MinecraftServer server, JsonObject a) {
        ServerWorld world = resolveWorld(server, a);
        int cx = reqInt(a, "x"), cy = reqInt(a, "y"), cz = reqInt(a, "z");
        int radius = reqInt(a, "radius");
        int height = reqInt(a, "height");
        if (radius < 1 || radius > MAX_RADIUS) throw new ToolException("radius must be 1.." + MAX_RADIUS);
        if (height < 1 || height > 384) throw new ToolException("height must be 1..384");
        BlockState state = parseBlock(reqStr(a, "block"), new HashMap<>());
        boolean hollow = optBool(a, "hollow", false);
        String axis = optStr(a, "axis", "y").toLowerCase(Locale.ROOT);
        double rr = radius * (double) radius + radius;
        EditSession es = newSession(world, a);
        return runEdit(server, es, "build_cylinder", () -> {
            for (int i = 0; i < height; i++) {
                for (int u = -radius; u <= radius; u++) {
                    for (int v = -radius; v <= radius; v++) {
                        if (!inCircle(u, v, rr)) continue;
                        if (hollow && inCircle(u + 1, v, rr) && inCircle(u - 1, v, rr) && inCircle(u, v + 1, rr) && inCircle(u, v - 1, rr)) continue;
                        switch (axis) {
                            case "x" -> es.set(cx + i, cy + u, cz + v, state);
                            case "z" -> es.set(cx + u, cy + v, cz + i, state);
                            default -> es.set(cx + u, cy + i, cz + v, state);
                        }
                    }
                }
            }
        });
    }

    private ToolResult buildLine(MinecraftServer server, JsonObject a) {
        ServerWorld world = resolveWorld(server, a);
        int x1 = reqInt(a, "x1"), y1 = reqInt(a, "y1"), z1 = reqInt(a, "z1");
        int x2 = reqInt(a, "x2"), y2 = reqInt(a, "y2"), z2 = reqInt(a, "z2");
        int thickness = optInt(a, "thickness", 1);
        if (thickness < 1 || thickness > 9) throw new ToolException("thickness must be 1..9");
        BlockState state = parseBlock(reqStr(a, "block"), new HashMap<>());
        int dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps > 4096) throw new ToolException("Line too long (max 4096 blocks)");
        int lo = -(thickness - 1) / 2;
        int hi = thickness / 2;
        EditSession es = newSession(world, a);
        return runEdit(server, es, "build_line", () -> {
            for (int i = 0; i <= steps; i++) {
                double f = steps == 0 ? 0 : (double) i / steps;
                int px = x1 + (int) Math.round(dx * f);
                int py = y1 + (int) Math.round(dy * f);
                int pz = z1 + (int) Math.round(dz * f);
                for (int ox = lo; ox <= hi; ox++)
                    for (int oy = lo; oy <= hi; oy++)
                        for (int oz = lo; oz <= hi; oz++)
                            es.set(px + ox, py + oy, pz + oz, state);
            }
        });
    }

    private ToolResult undo(MinecraftServer server, JsonObject a) {
        int steps = Math.max(1, optInt(a, "steps", 1));
        if (HISTORY.isEmpty()) return ToolResult.err("Nothing to undo.");
        return ToolResult.ok(undoSteps(server, steps));
    }

    /** Must be called on the server thread. */
    public static String undoSteps(MinecraftServer server, int steps) {
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

    public static void clearHistory() {
        HISTORY.clear();
    }

    private ToolResult runCommand(MinecraftServer server, JsonObject a) {
        if (!McpConfig.get().allowCommands) {
            return ToolResult.err("Running commands is disabled in the MCP Builder settings.");
        }
        String command = reqStr(a, "command").trim();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty()) throw new ToolException("Empty command");
        StringBuilder output = new StringBuilder();
        CommandOutput collector = new CommandOutput() {
            @Override
            public void sendMessage(Text message) {
                output.append(message.getString()).append('\n');
            }

            @Override
            public boolean shouldReceiveFeedback() {
                return true;
            }

            @Override
            public boolean shouldTrackOutput() {
                return true;
            }

            @Override
            public boolean shouldBroadcastConsoleToOps() {
                return false;
            }
        };
        ServerCommandSource source = server.getCommandSource().withOutput(collector).withLevel(4);
        ServerPlayerEntity player = resolvePlayer(server, a);
        if (player != null) {
            source = source.withWorld(player.getServerWorld()).withPosition(player.getPos());
        }
        server.getCommandManager().executeWithPrefix(source, command);
        String text = output.toString().trim();
        notifyPlayers(server, "AI ran /" + command);
        return ToolResult.ok(text.isEmpty() ? "Executed /" + command + " (no output)" : text);
    }

    // ------------------------------------------------------------------ editing core

    private static final class EditSession {
        final ServerWorld world;
        final int limit;
        final boolean autoConnect;
        final ArrayList<BlockPos> positions = new ArrayList<>();
        final ArrayList<BlockState> previous = new ArrayList<>();
        int changed;
        int skipped;

        EditSession(ServerWorld world, int limit, boolean autoConnect) {
            this.world = world;
            this.limit = limit;
            this.autoConnect = autoConnect;
        }

        void set(int x, int y, int z, BlockState state) {
            if (y < world.getBottomY() || y >= world.getTopY()) {
                skipped++;
                return;
            }
            BlockPos pos = new BlockPos(x, y, z);
            BlockState old = world.getBlockState(pos);
            if (old == state) return;
            if (changed >= limit) {
                throw new ToolException("Block limit of " + limit + " per request reached. Split the build into smaller parts or raise the limit in the mod settings.");
            }
            positions.add(pos);
            previous.add(old);
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
            changed++;
        }

        /** Let fences, panes, walls, stairs etc. connect to their neighbours. */
        void finish() {
            if (!autoConnect || positions.size() > AUTO_CONNECT_LIMIT) return;
            for (BlockPos pos : positions) {
                BlockState current = world.getBlockState(pos);
                if (current.isAir()) continue;
                BlockState updated = Block.postProcessState(current, world, pos);
                if (updated != current) {
                    world.setBlockState(pos, updated, Block.NOTIFY_LISTENERS);
                }
            }
        }

        int undo() {
            for (int i = positions.size() - 1; i >= 0; i--) {
                world.setBlockState(positions.get(i), previous.get(i), Block.NOTIFY_LISTENERS);
            }
            return positions.size();
        }
    }

    private EditSession newSession(ServerWorld world, JsonObject a) {
        McpConfig config = McpConfig.get();
        return new EditSession(world, config.maxBlocksPerRequest, optBool(a, "auto_connect", config.autoConnectBlocks));
    }

    private ToolResult runEdit(MinecraftServer server, EditSession es, String what, Runnable body) {
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

    private static void remember(EditSession es) {
        int max = McpConfig.get().undoHistorySize;
        if (es.changed == 0 || max <= 0) return;
        HISTORY.addLast(es);
        while (HISTORY.size() > max) HISTORY.pollFirst();
    }

    private static void notifyPlayers(MinecraftServer server, String message) {
        if (!McpConfig.get().chatNotifications) return;
        server.getPlayerManager().broadcast(
                Text.literal("[MCP] ").formatted(Formatting.AQUA).append(Text.literal(message).formatted(Formatting.GRAY)),
                false);
    }

    private void checkVolume(Region r) {
        int limit = McpConfig.get().maxBlocksPerRequest;
        if (r.volume() > limit) {
            throw new ToolException("Region has " + r.volume() + " blocks, the limit per request is " + limit + ". Split it or raise the limit in the settings.");
        }
    }

    private static boolean inSphere(int x, int y, int z, double rr) {
        return x * (double) x + y * (double) y + z * (double) z <= rr;
    }

    private static boolean inCircle(int u, int v, double rr) {
        return u * (double) u + v * (double) v <= rr;
    }

    private record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Region of(JsonObject a) {
            int x1 = reqInt(a, "x1"), y1 = reqInt(a, "y1"), z1 = reqInt(a, "z1");
            int x2 = reqInt(a, "x2"), y2 = reqInt(a, "y2"), z2 = reqInt(a, "z2");
            return new Region(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }

        long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }

        @Override
        public String toString() {
            return "(" + minX + "," + minY + "," + minZ + ") -> (" + maxX + "," + maxY + "," + maxZ + ")";
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BlockState parseBlock(String id, Map<String, BlockState> cache) {
        String key = id.trim();
        BlockState cached = cache.get(key);
        if (cached != null) return cached;
        try {
            BlockState state = BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), key, false).blockState();
            cache.put(key, state);
            return state;
        } catch (CommandSyntaxException e) {
            throw new ToolException("Invalid block '" + id + "': " + e.getMessage());
        }
    }

    private ServerPlayerEntity resolvePlayer(MinecraftServer server, JsonObject a) {
        String name = optStr(a, "player", McpConfig.get().targetPlayer);
        if (name != null && !name.isBlank()) {
            ServerPlayerEntity named = server.getPlayerManager().getPlayer(name);
            if (named != null) return named;
        }
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        return players.isEmpty() ? null : players.get(0);
    }

    private ServerWorld resolveWorld(MinecraftServer server, JsonObject a) {
        String dim = optStr(a, "dimension", null);
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

    private static int reqInt(JsonObject a, String key) {
        if (!a.has(key) || a.get(key).isJsonNull()) throw new ToolException("Missing required parameter '" + key + "'");
        try {
            return (int) Math.floor(a.get(key).getAsDouble());
        } catch (Exception e) {
            throw new ToolException("Parameter '" + key + "' must be a number");
        }
    }

    private static int optInt(JsonObject a, String key, int def) {
        if (!a.has(key) || a.get(key).isJsonNull()) return def;
        return reqInt(a, key);
    }

    private static String reqStr(JsonObject a, String key) {
        if (!a.has(key) || a.get(key).isJsonNull()) throw new ToolException("Missing required parameter '" + key + "'");
        try {
            return a.get(key).getAsString();
        } catch (Exception e) {
            throw new ToolException("Parameter '" + key + "' must be a string");
        }
    }

    private static String optStr(JsonObject a, String key, String def) {
        if (!a.has(key) || a.get(key).isJsonNull()) return def;
        return reqStr(a, key);
    }

    private static boolean optBool(JsonObject a, String key, boolean def) {
        if (!a.has(key) || a.get(key).isJsonNull()) return def;
        try {
            return a.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ JSON schema helpers

    private static JsonObject prop(String type, String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        if (description != null) o.addProperty("description", description);
        return o;
    }

    private static JsonObject integer(String d) { return prop("integer", d); }

    private static JsonObject str(String d) { return prop("string", d); }

    private static JsonObject bool(String d) { return prop("boolean", d); }

    private static JsonObject enumStr(String d, String... values) {
        JsonObject o = str(d);
        JsonArray arr = new JsonArray();
        for (String v : values) arr.add(v);
        o.add("enum", arr);
        return o;
    }

    private static JsonObject xyzObject(String d) {
        JsonObject o = prop("object", d);
        JsonObject props = new JsonObject();
        props.add("x", integer("X"));
        props.add("y", integer("Y"));
        props.add("z", integer("Z"));
        o.add("properties", props);
        return o;
    }

    private static JsonObject blocksArraySchema() {
        JsonObject item = xyzObject("A block to place");
        item.getAsJsonObject("properties").add("block", str("Block id with optional states, or a palette key"));
        JsonArray required = new JsonArray();
        required.add("x");
        required.add("y");
        required.add("z");
        required.add("block");
        item.add("required", required);
        JsonObject arr = prop("array", "Blocks to place. Compact form [x,y,z,block] is also accepted.");
        arr.add("items", item);
        return arr;
    }

    private static JsonObject paletteSchema() {
        JsonObject o = prop("object", "Optional map of short keys to block ids, e.g. {\"S\":\"minecraft:stone_bricks\",\"G\":\"minecraft:glass\"}");
        o.add("additionalProperties", str(null));
        return o;
    }

    private static SchemaBuilder schema() {
        return new SchemaBuilder();
    }

    private static final class SchemaBuilder {
        private final JsonObject props = new JsonObject();
        private final JsonArray required = new JsonArray();

        SchemaBuilder req(String name, JsonObject p) {
            props.add(name, p);
            required.add(name);
            return this;
        }

        SchemaBuilder opt(String name, JsonObject p) {
            props.add(name, p);
            return this;
        }

        SchemaBuilder pos() {
            return req("x", integer("X")).req("y", integer("Y")).req("z", integer("Z"));
        }

        SchemaBuilder region() {
            return req("x1", integer("First corner X")).req("y1", integer("First corner Y")).req("z1", integer("First corner Z"))
                    .req("x2", integer("Second corner X")).req("y2", integer("Second corner Y")).req("z2", integer("Second corner Z"));
        }

        SchemaBuilder dim() {
            return opt("dimension", str(DIM_DESC));
        }

        SchemaBuilder autoConnect() {
            return opt("auto_connect", bool("Update shapes after placing so fences, panes, walls and stairs connect (default from settings)."));
        }

        JsonObject build() {
            JsonObject s = new JsonObject();
            s.addProperty("type", "object");
            s.add("properties", props);
            s.add("required", required);
            return s;
        }
    }
}
