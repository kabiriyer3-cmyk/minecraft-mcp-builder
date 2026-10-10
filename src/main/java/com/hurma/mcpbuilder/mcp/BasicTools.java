package com.hurma.mcpbuilder.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hurma.mcpbuilder.config.McpConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Inspection, simple placement, undo and commands. */
final class BasicTools {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int SCAN_MAX_VOLUME = 262_144;
    private static final int SCAN_LIST_LIMIT = 4_000;

    private BasicTools() {}

    static void register(BuildTools t) {
        t.add("get_player_info",
                "Get position, facing direction, the block the player is looking at, dimension, game mode and ground height, plus online players. Call this first to know where to build.",
                Schema.of().opt("player", Schema.str("Optional player name. Defaults to the configured target player or the first online player.")).build(),
                BasicTools::getPlayerInfo);
        t.add("get_block", "Get the block (with its block state) at a position.",
                Schema.of().pos().dim().build(), BasicTools::getBlock);
        t.add("get_height",
                "Get the Y of the highest solid surface block (ignoring leaves) in the X/Z column. Use it to put buildings on the ground.",
                Schema.of().req("x", Schema.integer("X")).req("z", Schema.integer("Z")).dim().build(), BasicTools::getHeight);
        t.add("scan_area",
                "Inspect a cuboid region: counts per block type and a list of non-air blocks as 'x y z block[states]' lines (max volume 262144, first 4000 listed). For a visual overview prefer view_area.",
                Schema.of().region().opt("include_air", Schema.bool("Also list air blocks (default false).")).dim().build(), BasicTools::scanArea);
        t.add("set_block", "Place a single block (with states/NBT).",
                Schema.of().pos().req("block", Schema.str(Schema.PATTERN_DESC)).dim().autoConnect().build(), BasicTools::setBlock);
        t.add("place_blocks",
                "Place many individual blocks in one call. Each entry is {x,y,z,block} or compact [x,y,z,block]. Coordinates are offset by 'origin'. "
                        + "'block' may be a key of 'palette' (values are patterns, so a key can mean a weighted texture mix). For layered designs build_blueprint is usually more compact.",
                Schema.of()
                        .req("blocks", blocksArraySchema())
                        .opt("origin", Schema.xyzObject("Optional origin added to every coordinate."))
                        .opt("palette", Schema.stringMap("Optional map of short keys to block patterns, e.g. {\"W\":\"minecraft:oak_planks\",\"S\":\"70%minecraft:stone_bricks,30%minecraft:cracked_stone_bricks\"}"))
                        .dim().autoConnect().seed().build(),
                BasicTools::placeBlocks);
        t.add("fill",
                "Fill a cuboid between two corners with a block pattern. Modes: replace (solid box), hollow (shell, inside cleared to air), outline (shell only, inside untouched), "
                        + "walls (4 side walls only, no floor/ceiling), keep (only replace air). 'mask' limits which existing blocks are replaced.",
                Schema.of().region()
                        .req("block", Schema.str(Schema.PATTERN_DESC))
                        .opt("mode", Schema.enumStr("Fill mode (default replace)", "replace", "hollow", "outline", "walls", "keep"))
                        .opt("mask", Schema.str(Schema.MASK_DESC))
                        .dim().autoConnect().seed().build(),
                BasicTools::fill);
        t.add("clear_area", "Set every block in a cuboid to air (clear space before building).",
                Schema.of().region().opt("mask", Schema.str("Optional: only clear these blocks. " + Schema.MASK_DESC)).dim().build(),
                BasicTools::clearArea);
        t.add("undo", "Undo the last build operation(s) made through MCP.",
                Schema.of().opt("steps", Schema.integer("How many operations to undo (default 1)")).build(), BasicTools::undo);
        t.add("run_command",
                "Run a Minecraft server command with operator permissions (e.g. 'time set night', 'summon minecraft:armor_stand ~ ~ ~', "
                        + "'summon minecraft:item_frame 10 64 5 {Facing:3b,Item:{id:\"minecraft:map\",count:1}}'). Use it for entities, paintings, item frames, banners on mobs, particles... Command changes cannot be undone.",
                Schema.of().req("command", Schema.str("Command without the leading slash")).build(), BasicTools::runCommand);
    }

    private static JsonObject blocksArraySchema() {
        JsonObject item = Schema.xyzObject("A block to place");
        item.getAsJsonObject("properties").add("block", Schema.str("Block pattern or palette key"));
        JsonArray required = new JsonArray();
        required.add("x");
        required.add("y");
        required.add("z");
        required.add("block");
        item.add("required", required);
        JsonObject arr = Schema.prop("array", "Blocks to place. Compact form [x,y,z,block] is also accepted.");
        arr.add("items", item);
        return arr;
    }

    // ------------------------------------------------------------------ read

    static ToolResult getPlayerInfo(MinecraftServer server, JsonObject a) {
        JsonObject o = new JsonObject();
        JsonArray online = new JsonArray();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) online.add(p.getName().getString());
        o.add("online_players", online);
        ServerPlayerEntity player = Ctx.resolvePlayer(server, a);
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
        HitResult hit = player.raycast(160, 1.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bh = (BlockHitResult) hit;
            BlockPos lp = bh.getBlockPos();
            JsonObject look = new JsonObject();
            look.addProperty("x", lp.getX());
            look.addProperty("y", lp.getY());
            look.addProperty("z", lp.getZ());
            look.addProperty("block", Util.stateString(world.getBlockState(lp)));
            look.addProperty("face", bh.getSide().asString());
            o.add("looking_at", look);
        }
        o.addProperty("ground_y_below_player", world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - 1);
        o.addProperty("world_min_y", world.getBottomY());
        o.addProperty("world_max_y", world.getTopY() - 1);
        o.addProperty("game_mode", player.interactionManager.getGameMode().asString());
        o.addProperty("hint", "facing north = -Z, south = +Z, east = +X, west = -X. Start builds ~5-10 blocks in front of the player or at looking_at.");
        return ToolResult.ok(GSON.toJson(o));
    }

    static ToolResult getBlock(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        BlockPos pos = new BlockPos(Args.reqInt(a, "x"), Args.reqInt(a, "y"), Args.reqInt(a, "z"));
        return ToolResult.ok(pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + Util.stateString(world.getBlockState(pos)));
    }

    static ToolResult getHeight(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        int x = Args.reqInt(a, "x");
        int z = Args.reqInt(a, "z");
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockState state = world.getBlockState(new BlockPos(x, top, z));
        return ToolResult.ok("Surface at x=" + x + " z=" + z + ": y=" + top + " (" + Util.stateString(state) + "). Build starting at y=" + (top + 1) + ".");
    }

    static ToolResult scanArea(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        Region r = Region.of(a);
        if (r.volume() > SCAN_MAX_VOLUME) {
            throw new ToolException("Region too large to scan (" + r.volume() + " blocks, max " + SCAN_MAX_VOLUME + "). Scan smaller parts or use view_area.");
        }
        boolean includeAir = Args.optBool(a, "include_air", false);
        Map<String, Integer> counts = new TreeMap<>();
        StringBuilder list = new StringBuilder();
        int listed = 0;
        boolean truncated = false;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = r.minY(); y <= r.maxY(); y++) {
            for (int z = r.minZ(); z <= r.maxZ(); z++) {
                for (int x = r.minX(); x <= r.maxX(); x++) {
                    BlockState state = world.getBlockState(m.set(x, y, z));
                    counts.merge(Registries.BLOCK.getId(state.getBlock()).toString(), 1, Integer::sum);
                    if (state.isAir() && !includeAir) continue;
                    if (listed < SCAN_LIST_LIMIT) {
                        list.append(x).append(' ').append(y).append(' ').append(z).append(' ').append(Util.stateString(state)).append('\n');
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

    // ------------------------------------------------------------------ write

    static ToolResult setBlock(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        int x = Args.reqInt(a, "x"), y = Args.reqInt(a, "y"), z = Args.reqInt(a, "z");
        Pattern pattern = Pattern.parse(Args.reqStr(a, "block"));
        EditSession es = Ctx.session(world, a);
        return Ctx.runEdit(server, es, "set_block", () -> es.set(x, y, z, pattern));
    }

    static ToolResult placeBlocks(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        if (!a.has("blocks") || !a.get("blocks").isJsonArray()) throw new ToolException("'blocks' must be an array");
        JsonArray blocks = a.getAsJsonArray("blocks");
        int limit = McpConfig.get().maxBlocksPerRequest;
        if (blocks.size() > limit) {
            throw new ToolException("Too many blocks in one request (" + blocks.size() + " > " + limit + "). Split it into several calls.");
        }
        JsonObject origin = Args.optObj(a, "origin");
        int ox = origin == null ? 0 : Args.optInt(origin, "x", 0);
        int oy = origin == null ? 0 : Args.optInt(origin, "y", 0);
        int oz = origin == null ? 0 : Args.optInt(origin, "z", 0);
        Map<String, String> palette = new HashMap<>();
        JsonObject pal = Args.optObj(a, "palette");
        if (pal != null) {
            for (Map.Entry<String, JsonElement> e : pal.entrySet()) palette.put(e.getKey(), e.getValue().getAsString());
        }
        Map<String, Pattern> cache = new HashMap<>();
        EditSession es = Ctx.session(world, a);
        return Ctx.runEdit(server, es, "place_blocks", () -> {
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
                        x = Args.reqInt(t, "x");
                        y = Args.reqInt(t, "y");
                        z = Args.reqInt(t, "z");
                        id = Args.reqStr(t, "block");
                    } else {
                        throw new ToolException("blocks[" + index + "] must be an object or array");
                    }
                } catch (ToolException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new ToolException("blocks[" + index + "] is malformed: " + ex.getMessage());
                }
                es.set(x + ox, y + oy, z + oz, Pattern.cached(palette.getOrDefault(id, id), cache));
                index++;
            }
        });
    }

    static ToolResult fill(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        Region r = Region.of(a);
        Ctx.checkVolume(r.volume());
        Pattern pattern = Pattern.parse(Args.reqStr(a, "block"));
        String mode = Args.optStr(a, "mode", "replace").toLowerCase(Locale.ROOT);
        if (!List.of("replace", "hollow", "outline", "walls", "keep").contains(mode)) {
            throw new ToolException("Unknown mode '" + mode + "'. Use replace, hollow, outline, walls or keep.");
        }
        Mask mask = Mask.parse(Args.optStr(a, "mask", Args.optStr(a, "replace_only", null)));
        BlockState air = Blocks.AIR.getDefaultState();
        EditSession es = Ctx.session(world, a);
        return Ctx.runEdit(server, es, "fill (" + mode + ")", () -> {
            for (int y = r.minY(); y <= r.maxY(); y++) {
                for (int z = r.minZ(); z <= r.maxZ(); z++) {
                    for (int x = r.minX(); x <= r.maxX(); x++) {
                        boolean edge = r.isEdge(x, y, z);
                        boolean side = x == r.minX() || x == r.maxX() || z == r.minZ() || z == r.maxZ();
                        if (mode.equals("outline") && !edge) continue;
                        if (mode.equals("walls") && !side) continue;
                        if (mask != null || mode.equals("keep")) {
                            BlockState old = es.get(x, y, z);
                            if (mode.equals("keep") && !old.isAir()) continue;
                            if (mask != null && !mask.test(old)) continue;
                        }
                        if (mode.equals("hollow") && !edge) es.set(x, y, z, air);
                        else es.set(x, y, z, pattern);
                    }
                }
            }
        });
    }

    static ToolResult clearArea(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        Region r = Region.of(a);
        Ctx.checkVolume(r.volume());
        Mask mask = Mask.parse(Args.optStr(a, "mask", null));
        BlockState air = Blocks.AIR.getDefaultState();
        EditSession es = Ctx.session(world, a, false);
        return Ctx.runEdit(server, es, "clear_area", () -> {
            for (int y = r.maxY(); y >= r.minY(); y--) {
                for (int z = r.minZ(); z <= r.maxZ(); z++) {
                    for (int x = r.minX(); x <= r.maxX(); x++) {
                        if (mask != null && !mask.test(es.get(x, y, z))) continue;
                        es.set(x, y, z, air);
                    }
                }
            }
        });
    }

    static ToolResult undo(MinecraftServer server, JsonObject a) {
        int steps = Math.max(1, Args.optInt(a, "steps", 1));
        if (Ctx.HISTORY.isEmpty()) return ToolResult.err("Nothing to undo.");
        return ToolResult.ok(Ctx.undoSteps(server, steps));
    }

    static ToolResult runCommand(MinecraftServer server, JsonObject a) {
        if (!McpConfig.get().allowCommands) {
            return ToolResult.err("Running commands is disabled in the MCP Builder settings.");
        }
        String command = Args.reqStr(a, "command").trim();
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
        ServerPlayerEntity player = Ctx.resolvePlayer(server, a);
        if (player != null) {
            source = source.withWorld(player.getServerWorld()).withPosition(player.getPos());
        }
        server.getCommandManager().executeWithPrefix(source, command);
        String text = output.toString().trim();
        Ctx.notifyPlayers(server, "AI ran /" + command);
        return ToolResult.ok(text.isEmpty() ? "Executed /" + command + " (no output)" : text);
    }
}
