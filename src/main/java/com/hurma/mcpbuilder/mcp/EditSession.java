package com.hurma.mcpbuilder.mcp;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Random;

/** Records every change of one tool call so it can be undone. */
final class EditSession {
    static final int AUTO_CONNECT_LIMIT = 150_000;

    final ServerWorld world;
    final int limit;
    final boolean autoConnect;
    final long seed;
    final Random random;
    private final ArrayList<BlockPos> positions = new ArrayList<>();
    private final ArrayList<BlockState> previous = new ArrayList<>();
    int changed;
    int skipped;

    EditSession(ServerWorld world, int limit, boolean autoConnect, long seed) {
        this.world = world;
        this.limit = limit;
        this.autoConnect = autoConnect;
        this.seed = seed;
        this.random = new Random(seed);
    }

    BlockState get(int x, int y, int z) {
        return world.getBlockState(new BlockPos(x, y, z));
    }

    void set(int x, int y, int z, Pattern pattern) {
        Pattern.Entry e = pattern.pick(random);
        place(x, y, z, e.state(), e);
    }

    void set(int x, int y, int z, Pattern.Entry entry) {
        place(x, y, z, entry.state(), entry);
    }

    void set(int x, int y, int z, BlockState state) {
        place(x, y, z, state, null);
    }

    private void place(int x, int y, int z, BlockState state, Pattern.Entry data) {
        if (y < world.getBottomY() || y >= world.getTopY()) {
            skipped++;
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState old = world.getBlockState(pos);
        boolean hasData = data != null && data.nbt() != null;
        if (!hasData && old == state) return;
        if (changed >= limit) {
            throw new ToolException("Block limit of " + limit + " per request reached. Split the build into smaller parts or raise the limit in the mod settings.");
        }
        positions.add(pos);
        previous.add(old);
        if (hasData) {
            new BlockStateArgument(state, data.properties(), data.nbt()).setBlockState(world, pos, Block.NOTIFY_LISTENERS);
        } else {
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }
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
