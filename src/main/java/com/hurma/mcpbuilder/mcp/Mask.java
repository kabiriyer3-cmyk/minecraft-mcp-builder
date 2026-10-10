package com.hurma.mcpbuilder.mcp;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Filter for existing blocks: "minecraft:stone,#minecraft:logs", "!air", "solid", ... */
public final class Mask {
    private final List<Predicate<BlockState>> tests;
    private final boolean invert;

    private Mask(List<Predicate<BlockState>> tests, boolean invert) {
        this.tests = tests;
        this.invert = invert;
    }

    /** @return null when the text is empty (= match everything). */
    public static Mask parse(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        boolean inv = false;
        if (t.startsWith("!")) {
            inv = true;
            t = t.substring(1).trim();
        }
        List<Predicate<BlockState>> list = new ArrayList<>();
        for (String part : Pattern.splitTopLevel(t)) {
            String p = part.trim();
            if (!p.isEmpty()) list.add(parseOne(p));
        }
        if (list.isEmpty()) return null;
        return new Mask(list, inv);
    }

    private static Predicate<BlockState> parseOne(String p) {
        switch (p.toLowerCase()) {
            case "air":
                return BlockState::isAir;
            case "*":
            case "any":
            case "existing":
                return s -> !s.isAir();
            case "solid":
                return BlockState::isOpaque;
            case "liquid":
            case "fluid":
                return s -> !s.getFluidState().isEmpty();
            default:
                break;
        }
        if (p.startsWith("#")) {
            Identifier id = Identifier.tryParse(p.substring(1));
            if (id == null) throw new ToolException("Invalid tag in mask: " + p);
            TagKey<Block> tag = TagKey.of(RegistryKeys.BLOCK, id);
            return s -> s.isIn(tag);
        }
        try {
            BlockArgumentParser.BlockResult r = BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), p, false);
            Block block = r.blockState().getBlock();
            Map<Property<?>, Comparable<?>> props = r.properties();
            if (props.isEmpty()) return s -> s.isOf(block);
            return s -> {
                if (!s.isOf(block)) return false;
                for (Map.Entry<Property<?>, Comparable<?>> e : props.entrySet()) {
                    if (!Objects.equals(s.getEntries().get(e.getKey()), e.getValue())) return false;
                }
                return true;
            };
        } catch (CommandSyntaxException e) {
            throw new ToolException("Invalid block in mask '" + p + "': " + e.getMessage());
        }
    }

    public boolean test(BlockState state) {
        boolean any = false;
        for (Predicate<BlockState> t : tests) {
            if (t.test(state)) {
                any = true;
                break;
            }
        }
        return invert != any;
    }
}
