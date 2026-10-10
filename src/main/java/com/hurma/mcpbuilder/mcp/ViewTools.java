package com.hurma.mcpbuilder.mcp;

import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Let the AI "see" what it built, discover blocks and read a building style guide. */
final class ViewTools {
    private static final String SYMBOLS = "#@%&*+=oOxX~^$ABCDEFGHIJKLMNPQRSTUVWYZabcdefghijkmnpqrstuvwyz0123456789?!<>/\\|()[]{};:'\"`,-_";
    private static final int MAX_VIEW_CELLS = 160 * 160;

    private ViewTools() {}

    static void register(BuildTools t) {
        t.add("view_area",
                "See a region as ASCII art to check your work: mode top (top-down, highest block per column), heightmap (height of the highest block, base-36), "
                        + "slice (every Y layer separately, top to bottom), south/north/east/west (elevation as seen from that side, nearest block wins). "
                        + "Rows of top/slice views run north (top) -> south; columns west -> east. A legend maps characters to blocks; '.' = air.",
                Schema.of().region()
                        .opt("mode", Schema.enumStr("View mode (default top)", "top", "heightmap", "slice", "south", "north", "east", "west"))
                        .dim().build(),
                ViewTools::viewArea);
        t.add("search_blocks",
                "Search block ids by words (e.g. 'stairs deepslate', 'glass pane', 'copper', 'lantern'). If the query is an exact id, also lists its block state properties and allowed values.",
                Schema.of().req("query", Schema.str("Words that must all appear in the id, or an exact id"))
                        .opt("limit", Schema.integer("Max results (default 80)")).build(),
                ViewTools::searchBlocks);
        t.add("get_building_guide",
                "Read the building guide: detailing techniques and ready block palettes. Read 'techniques' before any big build, then the style you need.",
                Schema.of().opt("topic", Schema.enumStr("Topic (default techniques)", "techniques", "gothic", "medieval", "fantasy", "japanese", "modern", "nature", "interior")).build(),
                ViewTools::guide);
    }

    // ------------------------------------------------------------------ view

    private static final class Legend {
        final Map<String, Character> map = new LinkedHashMap<>();

        char of(BlockState s) {
            if (s.isAir()) return '.';
            String id = Util.shortId(s);
            Character c = map.get(id);
            if (c == null) {
                c = map.size() < SYMBOLS.length() ? SYMBOLS.charAt(map.size()) : '?';
                map.put(id, c);
            }
            return c;
        }

        String render() {
            StringBuilder sb = new StringBuilder("Legend: . = air");
            map.forEach((id, c) -> sb.append("\n").append(c).append(" = ").append(id));
            return sb.toString();
        }
    }

    static ToolResult viewArea(MinecraftServer server, JsonObject a) {
        ServerWorld world = Ctx.resolveWorld(server, a);
        Region r = Region.of(a);
        String mode = Args.optStr(a, "mode", "top").toLowerCase(Locale.ROOT);
        Legend legend = new Legend();
        StringBuilder out = new StringBuilder();
        BlockPos.Mutable m = new BlockPos.Mutable();
        int w = r.sizeX(), h = r.sizeY(), d = r.sizeZ();
        switch (mode) {
            case "top", "heightmap" -> {
                if ((long) w * d > MAX_VIEW_CELLS) throw new ToolException("View too large (max 160x160 columns)");
                if ((long) w * d * h > 4_000_000) throw new ToolException("Region too tall/large for a top view, narrow the Y range");
                out.append(mode.equals("top") ? "Top view" : "Heightmap (base-36 offset above y=" + r.minY() + ")")
                        .append(" of ").append(r).append(". Rows z=").append(r.minZ()).append("..").append(r.maxZ())
                        .append(" (north->south), columns x=").append(r.minX()).append("..").append(r.maxX()).append(" (west->east)\n");
                for (int z = r.minZ(); z <= r.maxZ(); z++) {
                    for (int x = r.minX(); x <= r.maxX(); x++) {
                        char c = '.';
                        for (int y = r.maxY(); y >= r.minY(); y--) {
                            BlockState s = world.getBlockState(m.set(x, y, z));
                            if (!s.isAir()) {
                                if (mode.equals("top")) {
                                    c = legend.of(s);
                                } else {
                                    int off = y - r.minY();
                                    c = off < 36 ? Character.forDigit(off, 36) : '+';
                                }
                                break;
                            }
                        }
                        out.append(c);
                    }
                    out.append('\n');
                }
            }
            case "slice" -> {
                if ((long) w * d * h > 40_000) throw new ToolException("Slice view too large (max 40000 cells), narrow the region");
                out.append("Slices of ").append(r).append(", each layer: rows north->south, columns west->east (x=").append(r.minX()).append("..").append(r.maxX()).append(")\n");
                for (int y = r.maxY(); y >= r.minY(); y--) {
                    out.append("y=").append(y).append('\n');
                    for (int z = r.minZ(); z <= r.maxZ(); z++) {
                        for (int x = r.minX(); x <= r.maxX(); x++) out.append(legend.of(world.getBlockState(m.set(x, y, z))));
                        out.append('\n');
                    }
                }
            }
            case "south", "north", "east", "west" -> {
                boolean alongX = mode.equals("south") || mode.equals("north");
                int cols = alongX ? w : d;
                if ((long) cols * h > MAX_VIEW_CELLS) throw new ToolException("View too large (max 160x160)");
                if ((long) w * d * h > 4_000_000) throw new ToolException("Region too large for an elevation view");
                out.append("Elevation seen from the ").append(mode).append(" of ").append(r).append(". Rows y=").append(r.maxY()).append("..").append(r.minY()).append(" (top->bottom), columns ");
                switch (mode) {
                    case "south" -> out.append("x=").append(r.minX()).append("..").append(r.maxX());
                    case "north" -> out.append("x=").append(r.maxX()).append("..").append(r.minX());
                    case "east" -> out.append("z=").append(r.maxZ()).append("..").append(r.minZ());
                    default -> out.append("z=").append(r.minZ()).append("..").append(r.maxZ());
                }
                out.append(" (viewer's left -> right)\n");
                for (int y = r.maxY(); y >= r.minY(); y--) {
                    for (int col = 0; col < cols; col++) {
                        char c = '.';
                        int depth = alongX ? d : w;
                        for (int k = 0; k < depth; k++) {
                            int x = r.minX() + k, z = r.minZ() + col;
                            if (mode.equals("south")) {
                                x = r.minX() + col;
                                z = r.maxZ() - k;
                            } else if (mode.equals("north")) {
                                x = r.maxX() - col;
                                z = r.minZ() + k;
                            } else if (mode.equals("east")) {
                                z = r.maxZ() - col;
                                x = r.maxX() - k;
                            }
                            BlockState s = world.getBlockState(m.set(x, y, z));
                            if (!s.isAir()) {
                                c = legend.of(s);
                                break;
                            }
                        }
                        out.append(c);
                    }
                    out.append('\n');
                }
            }
            default -> throw new ToolException("Unknown mode '" + mode + "'");
        }
        if (!mode.equals("heightmap")) out.append(legend.render());
        return ToolResult.ok(out.toString());
    }

    // ------------------------------------------------------------------ search

    static ToolResult searchBlocks(MinecraftServer server, JsonObject a) {
        String q = Args.reqStr(a, "query").toLowerCase(Locale.ROOT).trim();
        int limit = Args.clamp(Args.optInt(a, "limit", 80), 1, 400);
        StringBuilder out = new StringBuilder();
        Identifier exact = Identifier.tryParse(q.contains(":") ? q : "minecraft:" + q);
        if (exact != null && Registries.BLOCK.containsId(exact)) {
            Block block = Registries.BLOCK.get(exact);
            BlockState def = block.getDefaultState();
            out.append(exact).append(" properties:");
            if (def.getProperties().isEmpty()) out.append(" none");
            for (Property<?> p : def.getProperties()) {
                out.append("\n  ").append(p.getName()).append(" = ").append(values(p)).append(" (default ").append(valueName(def, p)).append(")");
            }
            out.append("\n\n");
        }
        String[] words = q.replace("minecraft:", "").split("[\\s_]+");
        List<String> hits = new ArrayList<>();
        for (Identifier id : Registries.BLOCK.getIds()) {
            String s = id.toString();
            boolean all = true;
            for (String word : words) {
                if (!word.isEmpty() && !s.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) hits.add(s);
        }
        Collections.sort(hits);
        out.append(hits.size()).append(" matching blocks");
        if (hits.size() > limit) out.append(" (showing ").append(limit).append(")");
        out.append(":\n");
        for (int i = 0; i < Math.min(limit, hits.size()); i++) out.append(hits.get(i)).append('\n');
        return ToolResult.ok(out.toString());
    }

    private static <T extends Comparable<T>> String values(Property<T> p) {
        return p.getValues().stream().map(p::name).collect(Collectors.joining("|"));
    }

    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) {
        return p.name(s.get(p));
    }

    // ------------------------------------------------------------------ guide

    static ToolResult guide(MinecraftServer server, JsonObject a) {
        String topic = Args.optStr(a, "topic", "techniques").toLowerCase(Locale.ROOT);
        String text = BuildingGuide.get(topic);
        if (text == null) throw new ToolException("Unknown topic. Use techniques, gothic, medieval, fantasy, japanese, modern, nature or interior.");
        return ToolResult.ok(text);
    }
}
