package com.hurma.mcpbuilder.mcp;

import com.google.gson.JsonObject;

/** Inclusive cuboid. */
record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    static Region of(JsonObject a) {
        int x1 = Args.reqInt(a, "x1"), y1 = Args.reqInt(a, "y1"), z1 = Args.reqInt(a, "z1");
        int x2 = Args.reqInt(a, "x2"), y2 = Args.reqInt(a, "y2"), z2 = Args.reqInt(a, "z2");
        return new Region(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    int sizeX() { return maxX - minX + 1; }

    int sizeY() { return maxY - minY + 1; }

    int sizeZ() { return maxZ - minZ + 1; }

    long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    boolean isEdge(int x, int y, int z) {
        return x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
    }

    @Override
    public String toString() {
        return "(" + minX + "," + minY + "," + minZ + ") -> (" + maxX + "," + maxY + "," + maxZ + ")";
    }
}
