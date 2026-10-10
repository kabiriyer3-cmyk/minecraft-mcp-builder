package com.hurma.mcpbuilder.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Helpers to read tool arguments. */
final class Args {
    private Args() {}

    static boolean has(JsonObject a, String key) {
        return a.has(key) && !a.get(key).isJsonNull();
    }

    static int reqInt(JsonObject a, String key) {
        if (!has(a, key)) throw new ToolException("Missing required parameter '" + key + "'");
        try {
            return (int) Math.floor(a.get(key).getAsDouble());
        } catch (Exception e) {
            throw new ToolException("Parameter '" + key + "' must be a number");
        }
    }

    static int optInt(JsonObject a, String key, int def) {
        return has(a, key) ? reqInt(a, key) : def;
    }

    static double optDouble(JsonObject a, String key, double def) {
        if (!has(a, key)) return def;
        try {
            return a.get(key).getAsDouble();
        } catch (Exception e) {
            throw new ToolException("Parameter '" + key + "' must be a number");
        }
    }

    static String reqStr(JsonObject a, String key) {
        if (!has(a, key)) throw new ToolException("Missing required parameter '" + key + "'");
        try {
            return a.get(key).getAsString();
        } catch (Exception e) {
            throw new ToolException("Parameter '" + key + "' must be a string");
        }
    }

    static String optStr(JsonObject a, String key, String def) {
        return has(a, key) ? reqStr(a, key) : def;
    }

    static boolean optBool(JsonObject a, String key, boolean def) {
        if (!has(a, key)) return def;
        try {
            return a.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    static long optSeed(JsonObject a) {
        if (!has(a, "seed")) return System.nanoTime();
        try {
            return a.get("seed").getAsLong();
        } catch (Exception e) {
            return a.get("seed").getAsString().hashCode();
        }
    }

    static JsonObject optObj(JsonObject a, String key) {
        return has(a, key) && a.get(key).isJsonObject() ? a.getAsJsonObject(key) : null;
    }

    static List<String> strList(JsonObject a, String key) {
        if (!has(a, key)) throw new ToolException("Missing required parameter '" + key + "'");
        JsonElement e = a.get(key);
        List<String> out = new ArrayList<>();
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        } else {
            out.add(e.getAsString());
        }
        return out;
    }

    /** Reads a point given as {x,y,z} or [x,y,z]. */
    static int[] point(JsonElement e, String what) {
        try {
            if (e.isJsonArray() && e.getAsJsonArray().size() >= 3) {
                var arr = e.getAsJsonArray();
                return new int[]{(int) Math.floor(arr.get(0).getAsDouble()), (int) Math.floor(arr.get(1).getAsDouble()), (int) Math.floor(arr.get(2).getAsDouble())};
            }
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                return new int[]{reqInt(o, "x"), reqInt(o, "y"), reqInt(o, "z")};
            }
        } catch (ToolException ex) {
            throw ex;
        } catch (Exception ignored) {
        }
        throw new ToolException(what + " must be {x,y,z} or [x,y,z]");
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
