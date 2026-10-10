package com.hurma.mcpbuilder.mcp;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A block pattern: one block or a weighted mix, e.g. "70%minecraft:stone_bricks,30%minecraft:mossy_stone_bricks".
 * Entries may carry block states and NBT (chests, signs, banners ...).
 */
public final class Pattern {
    public record Entry(BlockState state, Set<Property<?>> properties, NbtCompound nbt, double weight) {
        Entry withState(BlockState newState) {
            return new Entry(newState, properties, nbt, weight);
        }
    }

    private static final java.util.regex.Pattern WEIGHT = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*[%*]\\s*(.+)$");

    private final List<Entry> entries;
    private final double total;

    private Pattern(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        double t = 0;
        for (Entry e : entries) t += e.weight();
        this.total = t;
    }

    public static Pattern of(BlockState state) {
        return new Pattern(List.of(new Entry(state, Set.of(), null, 1)));
    }

    public static Pattern parse(String text) {
        if (text == null || text.isBlank()) throw new ToolException("Empty block pattern");
        List<Entry> list = new ArrayList<>();
        for (String part : splitTopLevel(text)) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            double weight = 1;
            java.util.regex.Matcher m = WEIGHT.matcher(p);
            if (m.matches()) {
                weight = Double.parseDouble(m.group(1));
                p = m.group(2).trim();
            }
            if (weight <= 0) continue;
            list.add(parseEntry(p, weight));
        }
        if (list.isEmpty()) throw new ToolException("Empty block pattern: " + text);
        return new Pattern(list);
    }

    /** Parses with a per-request cache (place_blocks may repeat the same string thousands of times). */
    public static Pattern cached(String text, Map<String, Pattern> cache) {
        Pattern p = cache.get(text);
        if (p == null) {
            p = parse(text);
            cache.put(text, p);
        }
        return p;
    }

    private static Entry parseEntry(String id, double weight) {
        try {
            BlockArgumentParser.BlockResult r = BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), id, true);
            return new Entry(r.blockState(), r.properties().keySet(), r.nbt(), weight);
        } catch (CommandSyntaxException e) {
            throw new ToolException("Invalid block '" + id + "': " + e.getMessage() + ". Use search_blocks to find valid ids.");
        }
    }

    /** Splits on commas that are not inside [...], {...} or quotes. */
    public static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) quoted = !quoted;
            if (!quoted) {
                if (c == '[' || c == '{') depth++;
                if (c == ']' || c == '}') depth--;
                if (c == ',' && depth == 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        parts.add(cur.toString());
        return parts;
    }

    public int size() {
        return entries.size();
    }

    public Entry first() {
        return entries.get(0);
    }

    public Entry pick(Random random) {
        if (entries.size() == 1) return entries.get(0);
        return pickAt(random.nextDouble());
    }

    /** Picks using a value in [0,1) (e.g. noise) so neighbouring blocks form clusters. */
    public Entry pickAt(double u) {
        if (entries.size() == 1) return entries.get(0);
        double target = u * total;
        double acc = 0;
        for (Entry e : entries) {
            acc += e.weight();
            if (target < acc) return e;
        }
        return entries.get(entries.size() - 1);
    }
}
