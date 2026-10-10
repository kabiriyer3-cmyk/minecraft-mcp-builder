package com.hurma.mcpbuilder.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Texturing, gradients, decoration, blueprints and cloning: the tools that make builds look detailed. */
final class DetailTools {
    private DetailTools() {}

    static void register(BuildTools t) {
        t.add("replace_blocks",
                "Re-texture / weather existing blocks in a region. Replaces blocks matching 'mask' with 'block' pattern, optionally only a 'chance' fraction, "
                        + "clustered with 'noise_scale' (3-8 gives natural patches of moss/cracks). keep_states preserves stair/slab orientation, so "
                        + "'stone_brick_stairs' -> 'mossy_stone_brick_stairs' keeps facing. Typical: mask=minecraft:stone_bricks, block='60%minecraft:cracked_stone_bricks,40%minecraft:mossy_stone_bricks', chance=0.25, noise_scale=4.",
                Schema.of().region()
                        .req("block", Schema.str(Schema.PATTERN_DESC))
                        .opt("mask", Schema.str("Which existing blocks to replace (default '*' = any non-air). " + Schema.MASK_DESC))
                        .opt("chance", Schema.number("Fraction of matching blocks to replace 0..1 (default 1)"))
                        .opt("noise_scale", Schema.number("0 = pure random scatter; >0 = clustered noise with this feature size in blocks"))
                        .opt("keep_states", Schema.bool("Copy matching block states (facing, half, axis...) from the old block (default true)"))
                        .dim().seed().build(),
                DetailTools::replaceBlocks);
        t.add("gradient_fill",
                "Blend a list of block patterns across a region along an axis with dithering: color/age gradients on walls (dark base -> light top), "
                        + "sunset terracotta, ombre glass, rusty copper. Use mask='*' to recolor only an existing build instead of filling solid.",
                Schema.of().region()
                        .req("blocks", Schema.strArray("Ordered patterns from the low end to the high end of the axis (2..16)"))
                        .opt("axis", Schema.enumStr("Gradient axis (default y)", "x", "y", "z"))
                        .opt("dither", Schema.number("0 = hard bands, 0.3-0.6 = soft blend, 1 = very noisy (default 0.4)"))
                        .opt("noise_scale", Schema.number("Use clustered noise for dithering with this feature size (default 0 = random)"))
                        .opt("mask", Schema.str("Only affect these existing blocks, e.g. '*' (default: fill every cell). " + Schema.MASK_DESC))
                        .opt("keep_states", Schema.bool("Copy matching block states from the old block (default true)"))
                        .dim().seed().build(),
                DetailTools::gradientFill);
        t.add("decorate_surface",
                "Scatter decoration on exposed faces of existing blocks in a region: face=top (grass, flowers, moss carpets, candles, rubble slabs), "
                        + "bottom (hanging lanterns, chains, spore blossoms, cobwebs, pointed dripstone), sides (vines, glow lichen, ladders, wall torches, banners: "
                        + "facing is set away from the wall automatically).",
                Schema.of().region()
                        .req("block", Schema.str(Schema.PATTERN_DESC))
                        .opt("face", Schema.enumStr("Which exposed face to decorate (default top)", "top", "bottom", "sides"))
                        .opt("on", Schema.str("Support blocks to decorate (default 'solid'). " + Schema.MASK_DESC))
                        .opt("chance", Schema.number("Density 0..1 (default 0.25)"))
                        .opt("noise_scale", Schema.number("0 = random, >0 = clustered patches"))
                        .dim().autoConnect().seed().build(),
                DetailTools::decorateSurface);
        t.add("build_blueprint",
                "Build a detailed module from ASCII layers (best tool for intricate details like windows, facades, statues, furniture, trims). "
                        + "'layers' go bottom -> top; each layer is an array of strings (rows) running north -> south (+Z); each character is one block along +X (west -> east). "
                        + "' ' or '.' = leave unchanged; every other char must be in 'palette' (values are patterns, e.g. '_':'minecraft:air'). "
                        + "Origin x/y/z is the north-west-bottom corner. 'rotate' turns the design (and block states) clockwise around the origin.",
                Schema.of().pos()
                        .req("layers", layersSchema())
                        .req("palette", Schema.stringMap("Map single characters to block patterns, e.g. {\"S\":\"minecraft:stone_bricks\",\"s\":\"minecraft:stone_brick_stairs[facing=north]\",\"G\":\"minecraft:glass_pane\"}"))
                        .opt("rotate", Schema.integer("0, 90, 180 or 270 degrees clockwise (default 0)"))
                        .dim().autoConnect().seed().build(),
                DetailTools::buildBlueprint);
        t.add("clone_region",
                "Copy a region to another place with optional rotation, mirroring and repetition. Use it for symmetry (build one half, mirror it), "
                        + "repeating facade bays, rows of columns/buttresses/windows, or duplicating towers. 'to' is where the minimum corner of the result goes. "
                        + "With repeat>1 every copy is shifted by 'step' (default: the offset between source and 'to'). Block entity contents (chest items) are not copied.",
                Schema.of().region()
                        .req("to", Schema.xyzObject("Destination of the minimum corner of the (transformed) copy"))
                        .opt("rotate", Schema.integer("0, 90, 180 or 270 degrees clockwise (default 0)"))
                        .opt("mirror", Schema.enumStr("Mirror before rotating (default none)", "none", "x", "z"))
                        .opt("repeat", Schema.integer("Number of copies 1..64 (default 1)"))
                        .opt("step", Schema.xyzObject("Offset between consecutive copies"))
                        .opt("skip_air", Schema.bool("Do not copy air blocks (default true)"))
                        .opt("mask", Schema.str("Only copy source blocks matching this mask. " + Schema.MASK_DESC))
                        .dim().autoConnect().build(),
                DetailTools::cloneRegion);
    }

    private static JsonObject layersSchema() {
        JsonObject o = Schema.prop("array", "Layers bottom -> top. Each layer: array of row strings (north -> south). A single string with newlines is also accepted per layer.");
        JsonObject inner = Schema.prop("array", "Rows of one layer");
        inner.add("items", Schema.str(null));
        o.add("items", inner);
        return o;
    }

    private static Pattern.Entry pickEntry(EditSession es, Pattern p, double noiseScale, int x, int y, int z) {
        if (noiseScale > 0 && p.size() > 1) return p.pickAt(Noise.fbm(x, y, z, noiseScale, es.seed + 31));
        return p.pick(es.random);
    }

    private static boolean passes(EditSession es, double chance, double noiseScale, int x, int y, int z) {
        if (chance >= 1) return true;
        if (chance <= 0) return false;
        if (noiseScale > 0) return Noise.fbm(x, y, z, noiseScale, es.seed) < chance;
        return es.random.nextDouble() < chance;
    }

    // ------------------------------------------------------------------ replace / texture

    static ToolResult replaceBlocks(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        Region r = Region.of(a);
        Ctx.checkVolume(r.volume());
        Pattern pattern = Pattern.parse(Args.reqStr(a, "block"));
        Mask mask = Mask.parse(Args.optStr(a, "mask", "*"));
        double chance = Args.clamp(Args.optDouble(a, "chance", 1.0), 0, 1);
        double noise = Math.max(0, Args.optDouble(a, "noise_scale", 0));
        boolean keep = Args.optBool(a, "keep_states", true);
        EditSession es = Ctx.session(world, a, false);
        return Ctx.runEdit(server, es, "replace_blocks", () -> {
            for (int y = r.minY(); y <= r.maxY(); y++) {
                for (int z = r.minZ(); z <= r.maxZ(); z++) {
                    for (int x = r.minX(); x <= r.maxX(); x++) {
                        BlockState old = es.get(x, y, z);
                        if (mask != null && !mask.test(old)) continue;
                        if (!passes(es, chance, noise, x, y, z)) continue;
                        Pattern.Entry e = pickEntry(es, pattern, noise, x, y, z);
                        if (keep && e.nbt() == null) e = e.withState(Util.keepStates(old, e.state()));
                        es.set(x, y, z, e);
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ gradient

    static ToolResult gradientFill(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        Region r = Region.of(a);
        Ctx.checkVolume(r.volume());
        List<String> texts = Args.strList(a, "blocks");
        if (texts.size() < 2 || texts.size() > 16) throw new ToolException("'blocks' must contain 2..16 patterns");
        List<Pattern> patterns = new ArrayList<>();
        for (String s : texts) patterns.add(Pattern.parse(s));
        String axis = Args.optStr(a, "axis", "y").toLowerCase(Locale.ROOT);
        double dither = Args.clamp(Args.optDouble(a, "dither", 0.4), 0, 1);
        double noise = Math.max(0, Args.optDouble(a, "noise_scale", 0));
        Mask mask = Mask.parse(Args.optStr(a, "mask", null));
        boolean keep = Args.optBool(a, "keep_states", true);
        int lo = axis.equals("x") ? r.minX() : axis.equals("z") ? r.minZ() : r.minY();
        int hi = axis.equals("x") ? r.maxX() : axis.equals("z") ? r.maxZ() : r.maxY();
        int n = patterns.size();
        EditSession es = Ctx.session(world, a, false);
        return Ctx.runEdit(server, es, "gradient_fill", () -> {
            for (int y = r.minY(); y <= r.maxY(); y++) {
                for (int z = r.minZ(); z <= r.maxZ(); z++) {
                    for (int x = r.minX(); x <= r.maxX(); x++) {
                        BlockState old = es.get(x, y, z);
                        if (mask != null && !mask.test(old)) continue;
                        int c = axis.equals("x") ? x : axis.equals("z") ? z : y;
                        double t = hi == lo ? 0 : (c - lo) / (double) (hi - lo);
                        double jitter = noise > 0 ? (Noise.fbm(x, y, z, noise, es.seed) - 0.5) * 2 : (es.random.nextDouble() - 0.5) * 2;
                        double f = t * (n - 1) + jitter * dither;
                        int idx = Args.clamp((int) Math.round(f), 0, n - 1);
                        Pattern.Entry e = patterns.get(idx).pick(es.random);
                        if (keep && e.nbt() == null && !old.isAir()) e = e.withState(Util.keepStates(old, e.state()));
                        es.set(x, y, z, e);
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ decoration

    static ToolResult decorateSurface(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        Region r = Region.of(a);
        Ctx.checkVolume(r.volume());
        Pattern pattern = Pattern.parse(Args.reqStr(a, "block"));
        String face = Args.optStr(a, "face", "top").toLowerCase(Locale.ROOT);
        Mask support = Mask.parse(Args.optStr(a, "on", "solid"));
        double chance = Args.clamp(Args.optDouble(a, "chance", 0.25), 0, 1);
        double noise = Math.max(0, Args.optDouble(a, "noise_scale", 0));
        EditSession es = Ctx.session(world, a);
        return Ctx.runEdit(server, es, "decorate_surface (" + face + ")", () -> {
            List<int[]> targets = new ArrayList<>();
            for (int y = r.minY(); y <= r.maxY(); y++) {
                for (int z = r.minZ(); z <= r.maxZ(); z++) {
                    for (int x = r.minX(); x <= r.maxX(); x++) {
                        BlockState s = es.get(x, y, z);
                        if (s.isAir() || (support != null && !support.test(s))) continue;
                        if (face.equals("sides")) {
                            for (Direction d : Direction.Type.HORIZONTAL) {
                                int tx = x + d.getOffsetX(), tz = z + d.getOffsetZ();
                                if (!es.get(tx, y, tz).isAir()) continue;
                                if (!passes(es, chance, noise, tx, y, tz)) continue;
                                targets.add(new int[]{tx, y, tz, d.ordinal()});
                            }
                        } else {
                            int ty = face.equals("bottom") ? y - 1 : y + 1;
                            if (!es.get(x, ty, z).isAir()) continue;
                            if (!passes(es, chance, noise, x, ty, z)) continue;
                            targets.add(new int[]{x, ty, z, -1});
                        }
                    }
                }
            }
            for (int[] t : targets) {
                Pattern.Entry e = pattern.pick(es.random);
                BlockState st = e.state();
                if (t[3] >= 0) {
                    Direction out = Direction.values()[t[3]];
                    Direction wall = out.getOpposite();
                    if (st.contains(Properties.HORIZONTAL_FACING)) st = st.with(Properties.HORIZONTAL_FACING, out);
                    BooleanProperty side = switch (wall) {
                        case NORTH -> Properties.NORTH;
                        case SOUTH -> Properties.SOUTH;
                        case EAST -> Properties.EAST;
                        default -> Properties.WEST;
                    };
                    if (st.contains(side)) st = st.with(side, true);
                } else {
                    if (st.contains(Properties.HANGING)) st = st.with(Properties.HANGING, face.equals("bottom"));
                }
                es.set(t[0], t[1], t[2], e.withState(st));
            }
        });
    }

    // ------------------------------------------------------------------ blueprint

    static ToolResult buildBlueprint(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        int ox = Args.reqInt(a, "x"), oy = Args.reqInt(a, "y"), oz = Args.reqInt(a, "z");
        int rotation = Util.normalizeRotation(Args.optInt(a, "rotate", 0));
        BlockRotation blockRot = Util.blockRotation(rotation);
        JsonObject pal = Args.optObj(a, "palette");
        if (pal == null) throw new ToolException("'palette' is required");
        Map<Character, Pattern> palette = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : pal.entrySet()) {
            if (e.getKey().length() != 1) throw new ToolException("Palette keys must be single characters, got '" + e.getKey() + "'");
            palette.put(e.getKey().charAt(0), Pattern.parse(e.getValue().getAsString()));
        }
        if (!a.has("layers") || !a.get("layers").isJsonArray()) throw new ToolException("'layers' must be an array");
        List<List<String>> layers = new ArrayList<>();
        long cells = 0;
        for (JsonElement layer : a.getAsJsonArray("layers")) {
            List<String> rows = new ArrayList<>();
            if (layer.isJsonArray()) {
                for (JsonElement row : layer.getAsJsonArray()) rows.add(row.getAsString());
            } else {
                for (String row : layer.getAsString().split("\n", -1)) rows.add(row);
            }
            for (String row : rows) cells += row.length();
            layers.add(rows);
        }
        Ctx.checkVolume(cells);
        for (int ly = 0; ly < layers.size(); ly++) {
            List<String> rows = layers.get(ly);
            for (int rz = 0; rz < rows.size(); rz++) {
                String row = rows.get(rz);
                for (int cx = 0; cx < row.length(); cx++) {
                    char ch = row.charAt(cx);
                    if (ch == ' ' || ch == '.') continue;
                    if (!palette.containsKey(ch)) {
                        throw new ToolException("Unknown character '" + ch + "' at layer " + ly + ", row " + rz + ", column " + cx + ". Add it to the palette.");
                    }
                }
            }
        }
        EditSession es = Ctx.session(world, a);
        return Ctx.runEdit(server, es, "build_blueprint", () -> {
            for (int ly = 0; ly < layers.size(); ly++) {
                List<String> rows = layers.get(ly);
                for (int rz = 0; rz < rows.size(); rz++) {
                    String row = rows.get(rz);
                    for (int cx = 0; cx < row.length(); cx++) {
                        char ch = row.charAt(cx);
                        if (ch == ' ' || ch == '.') continue;
                        Pattern.Entry e = palette.get(ch).pick(es.random);
                        int[] t = Util.rotate(cx, rz, rotation);
                        BlockState st = rotation == 0 ? e.state() : e.state().rotate(blockRot);
                        es.set(ox + t[0], oy + ly, oz + t[1], e.withState(st));
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ clone

    static ToolResult cloneRegion(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        Region src = Region.of(a);
        JsonObject to = Args.optObj(a, "to");
        if (to == null) throw new ToolException("'to' {x,y,z} is required");
        int tx = Args.reqInt(to, "x"), ty = Args.reqInt(to, "y"), tz = Args.reqInt(to, "z");
        int rotation = Util.normalizeRotation(Args.optInt(a, "rotate", 0));
        String mirror = Args.optStr(a, "mirror", "none").toLowerCase(Locale.ROOT);
        int repeat = Args.clamp(Args.optInt(a, "repeat", 1), 1, 64);
        JsonObject step = Args.optObj(a, "step");
        int sx = step != null ? Args.optInt(step, "x", 0) : tx - src.minX();
        int sy = step != null ? Args.optInt(step, "y", 0) : ty - src.minY();
        int sz = step != null ? Args.optInt(step, "z", 0) : tz - src.minZ();
        boolean skipAir = Args.optBool(a, "skip_air", true);
        Mask mask = Mask.parse(Args.optStr(a, "mask", null));
        if (src.volume() > 2_000_000) throw new ToolException("Source region too large (max 2,000,000 blocks)");
        Ctx.checkVolume(src.volume() * repeat);

        int w = src.sizeX(), h = src.sizeY(), d = src.sizeZ();
        BlockState[] buffer = new BlockState[(int) src.volume()];
        int idx = 0;
        for (int y = 0; y < h; y++)
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++)
                    buffer[idx++] = world.getBlockState(new net.minecraft.util.math.BlockPos(src.minX() + x, src.minY() + y, src.minZ() + z));

        BlockRotation blockRot = Util.blockRotation(rotation);
        BlockMirror blockMirror = mirror.equals("x") ? BlockMirror.FRONT_BACK : mirror.equals("z") ? BlockMirror.LEFT_RIGHT : BlockMirror.NONE;
        int minTX = Integer.MAX_VALUE, minTZ = Integer.MAX_VALUE;
        for (int[] corner : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) {
            int[] t = Util.rotate(corner[0], corner[1], rotation);
            minTX = Math.min(minTX, t[0]);
            minTZ = Math.min(minTZ, t[1]);
        }
        int shiftX = -minTX, shiftZ = -minTZ;
        EditSession es = Ctx.session(world, a);
        return Ctx.runEdit(server, es, "clone_region x" + repeat, () -> {
            for (int k = 0; k < repeat; k++) {
                int bx = tx + k * sx, by = ty + k * sy, bz = tz + k * sz;
                int i = 0;
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < d; z++) {
                        for (int x = 0; x < w; x++) {
                            BlockState st = buffer[i++];
                            if (skipAir && st.isAir()) continue;
                            if (mask != null && !mask.test(st)) continue;
                            int mx = mirror.equals("x") ? w - 1 - x : x;
                            int mz = mirror.equals("z") ? d - 1 - z : z;
                            int[] t = Util.rotate(mx, mz, rotation);
                            BlockState out = st.mirror(blockMirror).rotate(blockRot);
                            es.set(bx + t[0] + shiftX, by + y, bz + t[1] + shiftZ, out);
                        }
                    }
                }
            }
        });
    }
}
