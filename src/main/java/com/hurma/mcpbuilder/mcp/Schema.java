package com.hurma.mcpbuilder.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Small JSON-schema builder for tool input schemas. */
final class Schema {
    static final String DIM_DESC = "Optional dimension id, e.g. minecraft:overworld, minecraft:the_nether. Defaults to the player's dimension.";
    static final String PATTERN_DESC = "Block pattern: a block id with optional states/NBT (minecraft:oak_stairs[facing=north], minecraft:chest[facing=east]{LootTable:\"minecraft:chests/simple_dungeon\"}) "
            + "or a weighted mix for texture, e.g. '60%minecraft:stone_bricks,25%minecraft:cracked_stone_bricks,15%minecraft:mossy_stone_bricks'.";
    static final String MASK_DESC = "Mask of existing blocks to affect: comma list of block ids (optionally with states), #tags (#minecraft:logs), "
            + "'air', 'solid', 'liquid', '*' (any non-air). Prefix with ! to invert, e.g. '!air'.";

    private final JsonObject props = new JsonObject();
    private final JsonArray required = new JsonArray();

    static Schema of() {
        return new Schema();
    }

    Schema req(String name, JsonObject p) {
        props.add(name, p);
        required.add(name);
        return this;
    }

    Schema opt(String name, JsonObject p) {
        props.add(name, p);
        return this;
    }

    Schema pos() {
        return req("x", integer("X")).req("y", integer("Y")).req("z", integer("Z"));
    }

    Schema region() {
        return req("x1", integer("First corner X")).req("y1", integer("First corner Y")).req("z1", integer("First corner Z"))
                .req("x2", integer("Second corner X")).req("y2", integer("Second corner Y")).req("z2", integer("Second corner Z"));
    }

    Schema dim() {
        return opt("dimension", str(DIM_DESC));
    }

    Schema seed() {
        return opt("seed", integer("Optional random seed so patterns/noise are reproducible"));
    }

    Schema autoConnect() {
        return opt("auto_connect", bool("Update shapes after placing so fences, panes, walls and stairs connect (default from settings)."));
    }

    JsonObject build() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", props);
        s.add("required", required);
        return s;
    }

    static JsonObject prop(String type, String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        if (description != null) o.addProperty("description", description);
        return o;
    }

    static JsonObject integer(String d) { return prop("integer", d); }

    static JsonObject number(String d) { return prop("number", d); }

    static JsonObject str(String d) { return prop("string", d); }

    static JsonObject bool(String d) { return prop("boolean", d); }

    static JsonObject enumStr(String d, String... values) {
        JsonObject o = str(d);
        JsonArray arr = new JsonArray();
        for (String v : values) arr.add(v);
        o.add("enum", arr);
        return o;
    }

    static JsonObject strArray(String d) {
        JsonObject o = prop("array", d);
        o.add("items", str(null));
        return o;
    }

    static JsonObject xyzObject(String d) {
        JsonObject o = prop("object", d);
        JsonObject p = new JsonObject();
        p.add("x", integer("X"));
        p.add("y", integer("Y"));
        p.add("z", integer("Z"));
        o.add("properties", p);
        return o;
    }

    static JsonObject pointArray(String d) {
        JsonObject o = prop("array", d);
        o.add("items", xyzObject("Point"));
        return o;
    }

    static JsonObject stringMap(String d) {
        JsonObject o = prop("object", d);
        o.add("additionalProperties", str(null));
        return o;
    }
}
