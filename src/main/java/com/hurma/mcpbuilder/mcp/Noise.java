package com.hurma.mcpbuilder.mcp;

/** Deterministic 3D value noise used for natural-looking textures. */
final class Noise {
    private Noise() {}

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    private static double hash(int x, int y, int z, long seed) {
        long h = seed;
        h = mix(h ^ (x * 0x9E3779B97F4A7C15L));
        h = mix(h ^ (y * 0xC2B2AE3D27D4EB4FL));
        h = mix(h ^ (z * 0x165667B19E3779F9L));
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    static double value(double x, double y, double z, long seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        double fx = smooth(x - x0), fy = smooth(y - y0), fz = smooth(z - z0);
        double c00 = lerp(fx, hash(x0, y0, z0, seed), hash(x0 + 1, y0, z0, seed));
        double c10 = lerp(fx, hash(x0, y0 + 1, z0, seed), hash(x0 + 1, y0 + 1, z0, seed));
        double c01 = lerp(fx, hash(x0, y0, z0 + 1, seed), hash(x0 + 1, y0, z0 + 1, seed));
        double c11 = lerp(fx, hash(x0, y0 + 1, z0 + 1, seed), hash(x0 + 1, y0 + 1, z0 + 1, seed));
        return lerp(fz, lerp(fy, c00, c10), lerp(fy, c01, c11));
    }

    /** Fractal noise in [0,1), contrast-stretched so weighted picks stay balanced. */
    static double fbm(int x, int y, int z, double scale, long seed) {
        double s = Math.max(0.5, scale);
        double v = value(x / s, y / s, z / s, seed) * 0.6
                + value(x / (s * 0.5), y / (s * 0.5), z / (s * 0.5), seed + 1013) * 0.3
                + value(x / (s * 0.25), y / (s * 0.25), z / (s * 0.25), seed + 2027) * 0.1;
        v = (v - 0.5) * 2.2 + 0.5;
        return Math.min(0.999999, Math.max(0.0, v));
    }
}
