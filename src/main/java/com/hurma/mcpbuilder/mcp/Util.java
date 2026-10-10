package com.hurma.mcpbuilder.mcp;

import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockRotation;

final class Util {
    private Util() {}

    /** Copies every property of {@code from} that {@code to} also has (keeps stair facing when re-texturing etc.). */
    static BlockState keepStates(BlockState from, BlockState to) {
        BlockState result = to;
        for (Property<?> p : from.getProperties()) {
            result = copyProperty(from, result, p);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(BlockState from, BlockState to, Property<T> property) {
        return to.contains(property) ? to.with(property, from.get(property)) : to;
    }

    static int normalizeRotation(int degrees) {
        int r = ((degrees % 360) + 360) % 360;
        if (r % 90 != 0) throw new ToolException("rotate must be 0, 90, 180 or 270");
        return r;
    }

    /** Rotates a horizontal offset clockwise (seen from above). */
    static int[] rotate(int dx, int dz, int degrees) {
        return switch (degrees) {
            case 90 -> new int[]{-dz, dx};
            case 180 -> new int[]{-dx, -dz};
            case 270 -> new int[]{dz, -dx};
            default -> new int[]{dx, dz};
        };
    }

    static BlockRotation blockRotation(int degrees) {
        return switch (degrees) {
            case 90 -> BlockRotation.CLOCKWISE_90;
            case 180 -> BlockRotation.CLOCKWISE_180;
            case 270 -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
    }

    static String stateString(BlockState state) {
        return BlockArgumentParser.stringifyBlockState(state);
    }

    static String shortId(BlockState state) {
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
