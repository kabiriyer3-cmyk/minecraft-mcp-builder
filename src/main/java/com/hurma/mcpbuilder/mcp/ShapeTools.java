package com.hurma.mcpbuilder.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Geometric shapes, arches, roofs and curves. */
final class ShapeTools {
    private static final int MAX_RADIUS = 96;

    private ShapeTools() {}

    @FunctionalInterface
    private interface Inside {
        boolean test(int dx, int dy, int dz);
    }

    static void register(BuildTools t) {
        t.add("build_sphere",
                "Build a sphere, dome (half=top) or bowl (half=bottom). Supports weighted patterns for texture.",
                Schema.of().req("x", Schema.integer("Center X")).req("y", Schema.integer("Center Y")).req("z", Schema.integer("Center Z"))
                        .req("radius", Schema.integer("Radius 1.." + MAX_RADIUS))
                        .req("block", Schema.str(Schema.PATTERN_DESC))
                        .opt("hollow", Schema.bool("Only the shell (default false)"))
                        .opt("half", Schema.enumStr("Which part to build", "full", "top", "bottom"))
                        .opt("mask", Schema.str("Only replace these existing blocks. " + Schema.MASK_DESC))
                        .dim().autoConnect().seed().build(),
                ShapeTools::buildSphere);
        t.add("build_cylinder",
                "Build a cylinder (towers, pillars, tubes). x/y/z is the center of the base, it extends 'height' blocks along the axis.",
                Schema.of().req("x", Schema.integer("Base center X")).req("y", Schema.integer("Base center Y")).req("z", Schema.integer("Base center Z"))
                        .req("radius", Schema.integer("Radius 1.." + MAX_RADIUS))
                        .req("height", Schema.integer("Length along the axis, 1..384"))
                        .req("block", Schema.str(Schema.PATTERN_DESC))
                        .opt("hollow", Schema.bool("Only the side wall (default false)"))
                        .opt("axis", Schema.enumStr("Axis (default y = vertical)", "y", "x", "z"))
                        .opt("mask", Schema.str("Only replace these existing blocks. " + Schema.MASK_DESC))
                        .dim().autoConnect().seed().build(),
                ShapeTools::buildCylinder);
        t.add("build_shape",
                "Build advanced solids: ellipsoid (domes, onion domes, organic blobs; radius/radius_y/radius_z), cone (spires; 'curve'>1 makes concave gothic spires), "
                        + "pyramid (stepped), torus (rings, fountains; tube_radius). x/y/z = center (ellipsoid, torus) or base center (cone, pyramid).",
                Schema.of().req("shape", Schema.enumStr("Shape type", "ellipsoid", "cone", "pyramid", "torus"))
                        .req("x", Schema.integer("X")).req("y", Schema.integer("Y")).req("z", Schema.integer("Z"))
                        .req("radius", Schema.integer("Main radius (X radius for ellipsoid, base radius for cone/pyramid, ring radius for torus)"))
                        .opt("radius_y", Schema.integer("Ellipsoid Y radius (default = radius)"))
                        .opt("radius_z", Schema.integer("Ellipsoid Z radius (default = radius)"))
                        .opt("height", Schema.integer("Cone/pyramid height (default = 2*radius)"))
                        .opt("tube_radius", Schema.integer("Torus tube radius (default 2)"))
                        .opt("curve", Schema.number("Cone profile exponent: 1 straight, 1.5-3 concave (needle-like spire), 0.5 convex (default 1)"))
                        .opt("invert", Schema.bool("Cone/pyramid pointing down (default false)"))
                        .opt("half", Schema.enumStr("Ellipsoid part", "full", "top", "bottom"))
                        .req("block", Schema.str(Schema.PATTERN_DESC))
                        .opt("hollow", Schema.bool("Only the shell (default false)"))
                        .opt("mask", Schema.str("Only replace these existing blocks. " + Schema.MASK_DESC))
                        .dim().autoConnect().seed().build(),
                ShapeTools::buildShape);
        t.add("build_line",
                "Build a straight 3D line between two points (beams, cables, diagonal braces).",
                Schema.of().region()
                        .req("block", Schema.str(Schema.PATTERN_DESC))
                        .opt("thickness", Schema.integer("Line thickness 1..9 (default 1)"))
                        .dim().autoConnect().seed().build(),
                ShapeTools::buildLine);
        t.add("build_curve",
                "Build a smooth curve through/along points with optional taper: tree branches and roots, flying buttresses, bridges, cables, tentacles, rivers. "
                        + "mode=spline passes through all points (Catmull-Rom), mode=bezier uses them as control points.",
                Schema.of().req("points", Schema.pointArray("2..64 points {x,y,z} (compact [x,y,z] also accepted)"))
                        .req("block", Schema.str(Schema.PATTERN_DESC))
                        .opt("mode", Schema.enumStr("Curve type (default spline)", "spline", "bezier"))
                        .opt("thickness", Schema.number("Brush radius at the start: 0 = single block line (default 0)"))
                        .opt("end_thickness", Schema.number("Brush radius at the end (default = thickness), use for tapering"))
                        .opt("mask", Schema.str("Only replace these existing blocks. " + Schema.MASK_DESC))
                        .dim().autoConnect().seed().build(),
                ShapeTools::buildCurve);
        t.add("build_arch",
                "Build an arch in a wall plane: gothic pointed (sharpness 2), round (1), or anything between; with optional straight pillars, ring thickness, depth and a fill "
                        + "for the opening (e.g. minecraft:air to carve a doorway, glass panes for a window, purple+black stained glass mix for tracery). "
                        + "x/y/z = lowest, minimum-coordinate block of the opening.",
                Schema.of().pos()
                        .req("width", Schema.integer("Opening width in blocks (1..64)"))
                        .opt("axis", Schema.enumStr("Direction the arch spans (default x). Depth goes along the other horizontal axis (+).", "x", "z"))
                        .opt("pillar_height", Schema.integer("Straight vertical part below the curve (default 0)"))
                        .opt("sharpness", Schema.number("1 = round, 2 = equilateral gothic, 3 = lancet (default 2)"))
                        .opt("ring", Schema.integer("Ring (frame) thickness 1..8 (default 1)"))
                        .opt("depth", Schema.integer("Depth along the wall normal 1..32 (default 1)"))
                        .req("block", Schema.str("Pattern for the ring/frame. " + Schema.PATTERN_DESC))
                        .opt("fill", Schema.str("Optional pattern for the opening, e.g. minecraft:air or glass"))
                        .dim().autoConnect().seed().build(),
                ShapeTools::buildArch);
        t.add("build_roof",
                "Build a proper stair roof over a rectangular footprint: style gable (ridge + triangular gable walls) or hip (slopes on 4 sides, corners auto-connect). "
                        + "Stairs are oriented automatically; body/slab blocks are derived from the stairs if not given. steepness 2-3 gives steep gothic roofs.",
                Schema.of()
                        .req("x1", Schema.integer("Footprint corner X (include overhang)")).req("z1", Schema.integer("Footprint corner Z"))
                        .req("x2", Schema.integer("Opposite corner X")).req("z2", Schema.integer("Opposite corner Z"))
                        .req("y", Schema.integer("Y of the lowest roof layer (usually the top of the walls + 1)"))
                        .req("stairs", Schema.str("Stairs pattern, e.g. minecraft:deepslate_tile_stairs or '80%minecraft:spruce_stairs,20%minecraft:dark_oak_stairs'"))
                        .opt("style", Schema.enumStr("Roof style (default gable)", "gable", "hip"))
                        .opt("ridge_axis", Schema.enumStr("Gable ridge direction (default: the longer side)", "x", "z"))
                        .opt("steepness", Schema.integer("Blocks of rise per step 1..4 (default 1)"))
                        .opt("block", Schema.str("Full block pattern under the stairs / ridge (default derived from stairs)"))
                        .opt("slab", Schema.str("Slab used for the ridge cap (default derived from stairs)"))
                        .opt("ridge_cap", Schema.bool("Put a slab cap on the ridge (default true)"))
                        .opt("gable", Schema.str("Pattern to fill the triangular gable ends (gable style), e.g. plaster/planks"))
                        .opt("gable_inset", Schema.integer("Gable wall inset from the footprint edge along the ridge (default 0, use overhang size)"))
                        .dim().seed().build(),
                ShapeTools::buildRoof);
    }

    // ------------------------------------------------------------------ helpers

    private static void voxelize(EditSession es, int cx, int cy, int cz, int rx, int yMin, int yMax, int rz,
                                 Inside in, boolean hollow, Pattern pattern, Mask mask) {
        for (int dy = yMin; dy <= yMax; dy++) {
            for (int dz = -rz; dz <= rz; dz++) {
                for (int dx = -rx; dx <= rx; dx++) {
                    if (!in.test(dx, dy, dz)) continue;
                    if (hollow && in.test(dx + 1, dy, dz) && in.test(dx - 1, dy, dz) && in.test(dx, dy + 1, dz)
                            && in.test(dx, dy - 1, dz) && in.test(dx, dy, dz + 1) && in.test(dx, dy, dz - 1)) continue;
                    if (mask != null && !mask.test(es.get(cx + dx, cy + dy, cz + dz))) continue;
                    es.set(cx + dx, cy + dy, cz + dz, pattern);
                }
            }
        }
    }

    private static int radius(JsonObject a, String key, int def) {
        int r = Args.optInt(a, key, def);
        if (r < 0 || r > MAX_RADIUS) throw new ToolException(key + " must be 0.." + MAX_RADIUS);
        return r;
    }

    // ------------------------------------------------------------------ tools

    static ToolResult buildSphere(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        int cx = Args.reqInt(a, "x"), cy = Args.reqInt(a, "y"), cz = Args.reqInt(a, "z");
        int r = radius(a, "radius", 1);
        if (r < 1) throw new ToolException("radius must be >= 1");
        Pattern pattern = Pattern.parse(Args.reqStr(a, "block"));
        boolean hollow = Args.optBool(a, "hollow", false);
        String half = Args.optStr(a, "half", "full").toLowerCase(Locale.ROOT);
        Mask mask = Mask.parse(Args.optStr(a, "mask", null));
        double rr = r * (double) r + r;
        EditSession es = Ctx.session(world, a);
        int yMin = half.equals("top") ? 0 : -r;
        int yMax = half.equals("bottom") ? 0 : r;
        return Ctx.runEdit(server, es, "build_sphere", () -> voxelize(es, cx, cy, cz, r, yMin, yMax, r,
                (dx, dy, dz) -> dx * (double) dx + dy * (double) dy + dz * (double) dz <= rr, hollow, pattern, mask));
    }

    static ToolResult buildCylinder(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        int cx = Args.reqInt(a, "x"), cy = Args.reqInt(a, "y"), cz = Args.reqInt(a, "z");
        int r = radius(a, "radius", 1);
        int height = Args.reqInt(a, "height");
        if (r < 1) throw new ToolException("radius must be >= 1");
        if (height < 1 || height > 384) throw new ToolException("height must be 1..384");
        Pattern pattern = Pattern.parse(Args.reqStr(a, "block"));
        boolean hollow = Args.optBool(a, "hollow", false);
        String axis = Args.optStr(a, "axis", "y").toLowerCase(Locale.ROOT);
        Mask mask = Mask.parse(Args.optStr(a, "mask", null));
        double rr = r * (double) r + r;
        EditSession es = Ctx.session(world, a);
        return Ctx.runEdit(server, es, "build_cylinder", () -> {
            for (int i = 0; i < height; i++) {
                for (int u = -r; u <= r; u++) {
                    for (int v = -r; v <= r; v++) {
                        if (u * (double) u + v * (double) v > rr) continue;
                        if (hollow && inCircle(u + 1, v, rr) && inCircle(u - 1, v, rr) && inCircle(u, v + 1, rr) && inCircle(u, v - 1, rr)) continue;
                        int x = cx + u, y = cy + i, z = cz + v;
                        if (axis.equals("x")) {
                            x = cx + i;
                            y = cy + u;
                            z = cz + v;
                        } else if (axis.equals("z")) {
                            x = cx + u;
                            y = cy + v;
                            z = cz + i;
                        }
                        if (mask != null && !mask.test(es.get(x, y, z))) continue;
                        es.set(x, y, z, pattern);
                    }
                }
            }
        });
    }

    private static boolean inCircle(int u, int v, double rr) {
        return u * (double) u + v * (double) v <= rr;
    }

    static ToolResult buildShape(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        String shape = Args.reqStr(a, "shape").toLowerCase(Locale.ROOT);
        int cx = Args.reqInt(a, "x"), cy = Args.reqInt(a, "y"), cz = Args.reqInt(a, "z");
        int r = radius(a, "radius", 1);
        if (r < 1) throw new ToolException("radius must be >= 1");
        Pattern pattern = Pattern.parse(Args.reqStr(a, "block"));
        boolean hollow = Args.optBool(a, "hollow", false);
        Mask mask = Mask.parse(Args.optStr(a, "mask", null));
        EditSession es = Ctx.session(world, a);
        if (shape.equals("ellipsoid")) {
            int ry = radius(a, "radius_y", r), rz = radius(a, "radius_z", r);
            if (ry < 1 || rz < 1) throw new ToolException("radius_y and radius_z must be >= 1");
            String half = Args.optStr(a, "half", "full").toLowerCase(Locale.ROOT);
            double ax = r + 0.5, ay = ry + 0.5, az = rz + 0.5;
            int yMin = half.equals("top") ? 0 : -ry;
            int yMax = half.equals("bottom") ? 0 : ry;
            return Ctx.runEdit(server, es, "build_shape (ellipsoid)", () -> voxelize(es, cx, cy, cz, r, yMin, yMax, rz,
                    (dx, dy, dz) -> (dx / ax) * (dx / ax) + (dy / ay) * (dy / ay) + (dz / az) * (dz / az) <= 1.0, hollow, pattern, mask));
        }
        if (shape.equals("cone") || shape.equals("pyramid")) {
            int h = Args.optInt(a, "height", r * 2);
            if (h < 1 || h > 384) throw new ToolException("height must be 1..384");
            double curve = Args.clamp(Args.optDouble(a, "curve", 1.0), 0.2, 5.0);
            boolean invert = Args.optBool(a, "invert", false);
            boolean square = shape.equals("pyramid");
            Inside in = (dx, dy, dz) -> {
                if (dy < 0 || dy >= h) return false;
                int level = invert ? (h - 1 - dy) : dy;
                double rad = r * Math.pow(1.0 - level / (double) h, curve);
                if (square) return Math.max(Math.abs(dx), Math.abs(dz)) <= rad + 0.5;
                return dx * (double) dx + dz * (double) dz <= (rad + 0.5) * (rad + 0.5);
            };
            return Ctx.runEdit(server, es, "build_shape (" + shape + ")", () -> voxelize(es, cx, cy, cz, r, 0, h - 1, r, in, hollow, pattern, mask));
        }
        if (shape.equals("torus")) {
            int tube = radius(a, "tube_radius", 2);
            if (tube < 1) throw new ToolException("tube_radius must be >= 1");
            double tt = (tube + 0.5) * (tube + 0.5);
            int outer = r + tube;
            return Ctx.runEdit(server, es, "build_shape (torus)", () -> voxelize(es, cx, cy, cz, outer, -tube, tube, outer,
                    (dx, dy, dz) -> {
                        double q = Math.sqrt(dx * (double) dx + dz * (double) dz) - r;
                        return q * q + dy * (double) dy <= tt;
                    }, hollow, pattern, mask));
        }
        throw new ToolException("Unknown shape '" + shape + "'. Use ellipsoid, cone, pyramid or torus.");
    }

    static ToolResult buildLine(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        int x1 = Args.reqInt(a, "x1"), y1 = Args.reqInt(a, "y1"), z1 = Args.reqInt(a, "z1");
        int x2 = Args.reqInt(a, "x2"), y2 = Args.reqInt(a, "y2"), z2 = Args.reqInt(a, "z2");
        int thickness = Args.optInt(a, "thickness", 1);
        if (thickness < 1 || thickness > 9) throw new ToolException("thickness must be 1..9");
        Pattern pattern = Pattern.parse(Args.reqStr(a, "block"));
        int dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps > 4096) throw new ToolException("Line too long (max 4096 blocks)");
        int lo = -(thickness - 1) / 2;
        int hi = thickness / 2;
        EditSession es = Ctx.session(world, a);
        return Ctx.runEdit(server, es, "build_line", () -> {
            for (int i = 0; i <= steps; i++) {
                double f = steps == 0 ? 0 : (double) i / steps;
                int px = x1 + (int) Math.round(dx * f);
                int py = y1 + (int) Math.round(dy * f);
                int pz = z1 + (int) Math.round(dz * f);
                for (int ox = lo; ox <= hi; ox++)
                    for (int oy = lo; oy <= hi; oy++)
                        for (int oz = lo; oz <= hi; oz++)
                            es.set(px + ox, py + oy, pz + oz, pattern);
            }
        });
    }

    static ToolResult buildCurve(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        if (!a.has("points") || !a.get("points").isJsonArray()) throw new ToolException("'points' must be an array");
        JsonArray arr = a.getAsJsonArray("points");
        if (arr.size() < 2 || arr.size() > 64) throw new ToolException("points must contain 2..64 points");
        List<double[]> pts = new ArrayList<>();
        int i0 = 0;
        for (JsonElement e : arr) {
            int[] p = Args.point(e, "points[" + i0++ + "]");
            pts.add(new double[]{p[0], p[1], p[2]});
        }
        String mode = Args.optStr(a, "mode", "spline").toLowerCase(Locale.ROOT);
        double t0 = Args.clamp(Args.optDouble(a, "thickness", 0), 0, 16);
        double t1 = Args.clamp(Args.optDouble(a, "end_thickness", t0), 0, 16);
        Pattern pattern = Pattern.parse(Args.reqStr(a, "block"));
        Mask mask = Mask.parse(Args.optStr(a, "mask", null));

        List<double[]> samples = new ArrayList<>();
        if (mode.equals("bezier")) {
            double len = 0;
            for (int i = 1; i < pts.size(); i++) len += dist(pts.get(i - 1), pts.get(i));
            int steps = (int) Math.max(8, Math.ceil(len * 3));
            if (steps > 40_000) throw new ToolException("Curve too long");
            for (int s = 0; s <= steps; s++) samples.add(bezier(pts, s / (double) steps));
        } else {
            for (int i = 0; i < pts.size() - 1; i++) {
                double[] p0 = pts.get(Math.max(i - 1, 0)), p1 = pts.get(i), p2 = pts.get(i + 1), p3 = pts.get(Math.min(i + 2, pts.size() - 1));
                int steps = (int) Math.max(4, Math.ceil(dist(p1, p2) * 3));
                if (samples.size() + steps > 40_000) throw new ToolException("Curve too long");
                for (int s = 0; s < steps; s++) samples.add(catmull(p0, p1, p2, p3, s / (double) steps));
            }
            samples.add(pts.get(pts.size() - 1));
        }
        EditSession es = Ctx.session(world, a);
        return Ctx.runEdit(server, es, "build_curve", () -> {
            Set<Long> done = new HashSet<>();
            int n = samples.size();
            for (int k = 0; k < n; k++) {
                double[] p = samples.get(k);
                double rad = t0 + (t1 - t0) * (n == 1 ? 0 : k / (double) (n - 1));
                int bx = (int) Math.floor(p[0] + 0.5), by = (int) Math.floor(p[1] + 0.5), bz = (int) Math.floor(p[2] + 0.5);
                int R = (int) Math.ceil(rad);
                double rr = rad * rad + 0.3;
                for (int ox = -R; ox <= R; ox++)
                    for (int oy = -R; oy <= R; oy++)
                        for (int oz = -R; oz <= R; oz++) {
                            if (ox * ox + oy * oy + oz * oz > rr) continue;
                            int x = bx + ox, y = by + oy, z = bz + oz;
                            if (!done.add(BlockPos.asLong(x, y, z))) continue;
                            if (mask != null && !mask.test(es.get(x, y, z))) continue;
                            es.set(x, y, z, pattern);
                        }
            }
        });
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] bezier(List<double[]> pts, double t) {
        double[][] tmp = new double[pts.size()][];
        for (int i = 0; i < pts.size(); i++) tmp[i] = pts.get(i).clone();
        for (int level = pts.size() - 1; level > 0; level--) {
            for (int i = 0; i < level; i++) {
                for (int c = 0; c < 3; c++) tmp[i][c] = tmp[i][c] + (tmp[i + 1][c] - tmp[i][c]) * t;
            }
        }
        return tmp[0];
    }

    private static double[] catmull(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            out[c] = 0.5 * ((2 * p1[c]) + (-p0[c] + p2[c]) * t + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * t2 + (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * t3);
        }
        return out;
    }

    static ToolResult buildArch(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        int x = Args.reqInt(a, "x"), y = Args.reqInt(a, "y"), z = Args.reqInt(a, "z");
        int width = Args.reqInt(a, "width");
        if (width < 1 || width > 64) throw new ToolException("width must be 1..64");
        boolean alongX = !Args.optStr(a, "axis", "x").equalsIgnoreCase("z");
        int pillar = Args.clamp(Args.optInt(a, "pillar_height", 0), 0, 128);
        double sharp = Args.clamp(Args.optDouble(a, "sharpness", 2.0), 1.0, 4.0);
        int ring = Args.clamp(Args.optInt(a, "ring", 1), 1, 8);
        int depth = Args.clamp(Args.optInt(a, "depth", 1), 1, 32);
        Pattern ringP = Pattern.parse(Args.reqStr(a, "block"));
        String fillText = Args.optStr(a, "fill", null);
        Pattern fillP = fillText == null || fillText.isBlank() ? null : Pattern.parse(fillText);
        double half = width / 2.0;
        double R = half * sharp;
        double c = half - R;
        int top = (int) Math.ceil(Math.sqrt(Math.max(0, (R + ring) * (R + ring) - c * c))) + 1;
        EditSession es = Ctx.session(world, a);
        return Ctx.runEdit(server, es, "build_arch", () -> {
            for (int j = -pillar; j <= top; j++) {
                for (int i = -ring; i <= width - 1 + ring; i++) {
                    double u = i + 0.5 - half;
                    double v = j + 0.5;
                    boolean open = archInside(u, v, 0, half, R, c);
                    boolean outer = archInside(u, v, ring, half, R, c);
                    Pattern target = open ? fillP : (outer ? ringP : null);
                    if (target == null) continue;
                    for (int k = 0; k < depth; k++) {
                        int wx = alongX ? x + i : x + k;
                        int wz = alongX ? z + k : z + i;
                        es.set(wx, y + pillar + j, wz, target);
                    }
                }
            }
        });
    }

    private static boolean archInside(double u, double v, double d, double half, double R, double c) {
        if (v < 0) return Math.abs(u) <= half + d;
        double rr = (R + d) * (R + d);
        return (u - c) * (u - c) + v * v <= rr && (u + c) * (u + c) + v * v <= rr;
    }

    // ------------------------------------------------------------------ roofs

    static ToolResult buildRoof(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        int x1 = Math.min(Args.reqInt(a, "x1"), Args.reqInt(a, "x2"));
        int x2 = Math.max(Args.reqInt(a, "x1"), Args.reqInt(a, "x2"));
        int z1 = Math.min(Args.reqInt(a, "z1"), Args.reqInt(a, "z2"));
        int z2 = Math.max(Args.reqInt(a, "z1"), Args.reqInt(a, "z2"));
        int y = Args.reqInt(a, "y");
        if (x2 - x1 > 160 || z2 - z1 > 160) throw new ToolException("Roof footprint too large (max 161x161)");
        String style = Args.optStr(a, "style", "gable").toLowerCase(Locale.ROOT);
        Pattern stairs = Pattern.parse(Args.reqStr(a, "stairs"));
        if (!(stairs.first().state().getBlock() instanceof StairsBlock)) {
            throw new ToolException("'stairs' must be a stairs block, e.g. minecraft:dark_oak_stairs");
        }
        Identifier stairsId = Registries.BLOCK.getId(stairs.first().state().getBlock());
        String bodyText = Args.optStr(a, "block", null);
        Pattern body = bodyText != null ? Pattern.parse(bodyText) : derive(stairsId, "s", "_planks", "", "_block");
        if (body == null) body = Pattern.of(stairs.first().state());
        String slabText = Args.optStr(a, "slab", null);
        Pattern slab = slabText != null ? Pattern.parse(slabText) : derive(stairsId, "_slab");
        if (slab == null) slab = body;
        String gableText = Args.optStr(a, "gable", null);
        Pattern gable = gableText == null || gableText.isBlank() ? null : Pattern.parse(gableText);
        int inset = Math.max(0, Args.optInt(a, "gable_inset", 0));
        int steep = Args.clamp(Args.optInt(a, "steepness", 1), 1, 4);
        boolean cap = Args.optBool(a, "ridge_cap", true);
        String axis = Args.optStr(a, "ridge_axis", (x2 - x1) >= (z2 - z1) ? "x" : "z").toLowerCase(Locale.ROOT);
        Pattern fBody = body, fSlab = slab;
        EditSession es = Ctx.session(world, a, true);
        if (style.equals("hip")) {
            return Ctx.runEdit(server, es, "build_roof (hip)", () -> hipRoof(es, x1, z1, x2, z2, y, steep, stairs, fBody, fSlab, cap));
        }
        boolean ridgeX = !axis.equals("z");
        return Ctx.runEdit(server, es, "build_roof (gable)", () -> gableRoof(es, x1, z1, x2, z2, y, steep, ridgeX, stairs, fBody, fSlab, cap, gable, inset));
    }

    /** Finds a sibling block of a stairs block, e.g. deepslate_tile_stairs -> deepslate_tiles / deepslate_tile_slab. */
    private static Pattern derive(Identifier stairsId, String... suffixes) {
        String path = stairsId.getPath();
        if (!path.endsWith("_stairs")) return null;
        String base = path.substring(0, path.length() - "_stairs".length());
        for (String suffix : suffixes) {
            Identifier id = Identifier.of(stairsId.getNamespace(), base + suffix);
            if (Registries.BLOCK.containsId(id)) return Pattern.of(Registries.BLOCK.get(id).getDefaultState());
        }
        return null;
    }

    private static void stair(EditSession es, Pattern stairs, int x, int y, int z, Direction facing) {
        Pattern.Entry e = stairs.pick(es.random);
        BlockState st = e.state();
        if (st.contains(Properties.HORIZONTAL_FACING)) st = st.with(Properties.HORIZONTAL_FACING, facing);
        es.set(x, y, z, e.withState(st));
    }

    private static void gableRoof(EditSession es, int x1, int z1, int x2, int z2, int y, int steep, boolean ridgeX,
                                  Pattern stairs, Pattern body, Pattern slab, boolean cap, Pattern gable, int inset) {
        int uMin = ridgeX ? x1 : z1, uMax = ridgeX ? x2 : z2;
        int wMin = ridgeX ? z1 : x1, wMax = ridgeX ? z2 : x2;
        Direction upFromMin = ridgeX ? Direction.SOUTH : Direction.EAST;
        Direction upFromMax = upFromMin.getOpposite();
        for (int i = 0; ; i++) {
            int wl = wMin + i, wr = wMax - i;
            if (wl > wr) break;
            int yBase = y + i * steep;
            int yTop = yBase + steep - 1;
            for (int u = uMin; u <= uMax; u++) {
                if (wl == wr) {
                    for (int yy = yBase; yy <= yTop; yy++) put(es, ridgeX, u, wl, yy, body);
                    if (cap) put(es, ridgeX, u, wl, yTop + 1, slab);
                } else {
                    for (int yy = yBase; yy < yTop; yy++) {
                        put(es, ridgeX, u, wl, yy, body);
                        put(es, ridgeX, u, wr, yy, body);
                    }
                    putStair(es, ridgeX, u, wl, yTop, stairs, upFromMin);
                    putStair(es, ridgeX, u, wr, yTop, stairs, upFromMax);
                    if (cap && wl + 1 == wr) {
                        put(es, ridgeX, u, wl, yTop + 1, slab);
                        put(es, ridgeX, u, wr, yTop + 1, slab);
                    }
                }
            }
            if (gable != null) {
                for (int w = wl + 1; w <= wr - 1; w++) {
                    for (int yy = yBase; yy <= yTop; yy++) {
                        put(es, ridgeX, uMin + inset, w, yy, gable);
                        if (uMax - inset != uMin + inset) put(es, ridgeX, uMax - inset, w, yy, gable);
                    }
                }
            }
        }
    }

    private static void put(EditSession es, boolean ridgeX, int u, int w, int y, Pattern p) {
        if (ridgeX) es.set(u, y, w, p);
        else es.set(w, y, u, p);
    }

    private static void putStair(EditSession es, boolean ridgeX, int u, int w, int y, Pattern stairs, Direction facing) {
        if (ridgeX) stair(es, stairs, u, y, w, facing);
        else stair(es, stairs, w, y, u, facing);
    }

    private static void hipRoof(EditSession es, int x1, int z1, int x2, int z2, int y, int steep,
                                Pattern stairs, Pattern body, Pattern slab, boolean cap) {
        for (int i = 0; ; i++) {
            int xa = x1 + i, xb = x2 - i, za = z1 + i, zb = z2 - i;
            if (xa > xb || za > zb) break;
            int yBase = y + i * steep;
            int yTop = yBase + steep - 1;
            if (xa == xb || za == zb) {
                for (int x = xa; x <= xb; x++) {
                    for (int z = za; z <= zb; z++) {
                        for (int yy = yBase; yy <= yTop; yy++) es.set(x, yy, z, body);
                        if (cap) es.set(x, yTop + 1, z, slab);
                    }
                }
                break;
            }
            for (int x = xa; x <= xb; x++) {
                column(es, x, za, yBase, yTop, stairs, body, Direction.SOUTH);
                column(es, x, zb, yBase, yTop, stairs, body, Direction.NORTH);
            }
            for (int z = za + 1; z <= zb - 1; z++) {
                column(es, xa, z, yBase, yTop, stairs, body, Direction.EAST);
                column(es, xb, z, yBase, yTop, stairs, body, Direction.WEST);
            }
        }
    }

    private static void column(EditSession es, int x, int z, int yBase, int yTop, Pattern stairs, Pattern body, Direction facing) {
        for (int yy = yBase; yy < yTop; yy++) es.set(x, yy, z, body);
        stair(es, stairs, x, yTop, z, facing);
    }
}
