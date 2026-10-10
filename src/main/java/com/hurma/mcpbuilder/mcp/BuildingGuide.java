package com.hurma.mcpbuilder.mcp;

import java.util.Map;

/** Static building knowledge handed to the AI on request. */
final class BuildingGuide {
    private BuildingGuide() {}

    static String get(String topic) {
        return TOPICS.get(topic);
    }

    private static final String TECHNIQUES = """
            DETAILED BUILD WORKFLOW
            1. Plan: get_player_info (use looking_at as anchor), get_height / view_area mode=heightmap to read terrain. Decide footprint, main axis, style palette (3-6 main blocks + 3-6 accent blocks).
            2. Foundation & massing: fill the base (slightly wider plinth, 1-3 blocks), then the main volumes with fill mode=walls/hollow. Use odd widths (7, 9, 11, 15) so centers and ridges are 1 block.
            3. Secondary forms: towers (build_cylinder hollow), buttresses, bay windows, chimneys, annexes. Break boxes: vary heights, setbacks, overhangs.
            4. Roofs: build_roof (steepness 2-3 for gothic/fantasy, 1 for medieval/japanese) with 1-block overhang; spires with build_shape cone curve 1.5-2.5; domes with build_shape ellipsoid half=top.
            5. Openings: build_arch with fill=air for doors, fill=glass panes/stained glass for windows. Repeat with clone_region repeat/step for bays.
            6. DEPTH LAYERS (most important for detail): never leave a flat wall. Add a frame/pillar grid every 3-6 blocks protruding 1 block (logs, stone pillars, polished variants),
               horizontal trim bands (stairs upside-down, slabs, walls) at floor lines and under eaves, window sills (stairs/slabs/trapdoors), lintels, corbels (upside-down stairs) under overhangs,
               and recess windows 1 block into the wall. Use stairs, slabs, walls, fences, trapdoors, buttons, chains, bars as "pixel-detail" blocks.
            7. TEXTURE PASS: replace_blocks on the main wall material with variants (cracked, mossy, chiseled, darker/lighter similar blocks) chance 0.15-0.35, noise_scale 3-6.
               gradient_fill mask='*' to darken the bottom of walls (dirt/moss at base) or bleach the top. Weighted patterns ('70%a,20%b,10%c') can be used directly in fill/shape tools.
            8. Decoration: decorate_surface (moss carpets/grass top, vines/glow lichen on sides, lanterns/chains bottom), interior furniture via build_blueprint, banners, item frames and armor stands via run_command.
            9. REVIEW: view_area from south/east and top; fix gaps, floating blocks, wrong stair facings (stair 'facing' points toward its tall back side). Iterate - good builds take many passes.
            TIPS
            - build_blueprint is the best way to place carefully designed details: design a 5x7 window or a column capital once, then clone_region it along the facade (mirror for symmetry).
            - Stairs facing=north have the tall part on the north side; roof stairs face toward the ridge. half=top makes upside-down stairs (corbels, arch tops).
            - Use auto_connect (default) so fences, panes, walls and stairs shape themselves after placement.
            - Contrast: dark frame + light infill (or vice versa); 2-3 tones of the same hue look rich, many unrelated colors look noisy.
            - Scale: windows 1-3 wide, doors 2-3 wide x 3-4 tall, floors 4-5 blocks high; bigger builds need proportionally thicker details (2-block trims, 3-block pillars).
            - Lighting: hide light in details (lanterns under eaves, candles, shroomlights in trees, sea lanterns behind glass).
            """;

    private static final String GOTHIC = """
            GOTHIC CATHEDRAL / CASTLE
            Main: deepslate_bricks, deepslate_tiles, polished_deepslate, cobbled_deepslate (base), polished_blackstone_bricks, stone_bricks, andesite/tuff for lighter versions.
            Accents: chiseled_deepslate, chiseled_polished_blackstone, polished_blackstone_wall / deepslate_brick_wall (pinnacles, tracery), iron_bars, chains, soul_lantern, candles.
            Glass: purple/magenta/red/blue/black stained glass mixes for rose windows; gray_stained_glass_pane for tall lancets.
            Roofs: deepslate_tile_stairs or blackstone stairs, steepness 2-3, ridge_cap; dark_oak for timber.
            Forms: very tall narrow windows (build_arch width 3-5, sharpness 2-3, pillar_height 6-12), flying buttresses (build_curve from a pier top to the wall, thickness 0-1),
            pinnacles on every buttress (wall blocks + small cone), rose window (build_cylinder axis z radius 4-7 with glass, then tracery spokes with build_line),
            spires with build_shape cone curve 2, gargoyles (upside-down stairs + walls protruding), pointed portals with 3-4 receding arch rings (build_arch ring 1, depth 1, each one bigger).
            Texture: replace_blocks deepslate_bricks -> '60%cracked_deepslate_bricks,40%cobbled_deepslate' chance 0.2 noise 4; moss and vines low on north walls.
            """;

    private static final String MEDIEVAL = """
            MEDIEVAL VILLAGE / TOWN
            Base: cobblestone, mossy_cobblestone, stone_bricks, andesite. Frames: stripped_spruce_log / spruce_log / dark_oak_log. Infill: white/light_gray terracotta,
            calcite, mud_bricks, birch/oak planks. Roofs: spruce_stairs, dark_oak_stairs, brick_stairs, deepslate_tile_stairs (steepness 1-2, overhang 1).
            Details: jettied upper floors (overhang 1 with upside-down stairs as corbels), window boxes (trapdoors + flowers), shutters (trapdoors on window sides),
            chimneys (brick/cobble with campfire smoke), fences/lanterns, hay bales, barrels, composters, signs, banners.
            Texture: cobblestone base with '60%cobblestone,25%mossy_cobblestone,15%andesite', gradient_fill dirt->coarse_dirt->path at street level.
            """;

    private static final String FANTASY = """
            FANTASY / ELVEN / WIZARD
            Wizard tower: calcite, smooth_quartz, amethyst_block, purpur, prismarine; roofs warped/crimson stairs or purpur_stairs with steep cones (curve 2.5) and ellipsoid onion domes.
            Elven: moss_block, stripped_birch_log, white_concrete, light blue glass, leaves, glowstone hidden in leaves, curved bridges (build_curve), giant trees (build_curve trunks thickness 3->1, ellipsoid leaf clusters).
            Dwarven: polished_basalt, deepslate, blackstone, gold_block/raw_gold, copper (all oxidation stages via gradient_fill), lava + iron_bars.
            Floating islands: build_shape cone invert=true (rock), ellipsoid top with grass/dirt, decorate_surface flowers, hanging vines bottom.
            """;

    private static final String JAPANESE = """
            JAPANESE TEMPLE / PAGODA
            Frame: stripped_dark_oak_log, dark_oak_log, mangrove/crimson planks for red pillars, red_terracotta, red_concrete. Walls: white/light_gray concrete, birch planks, paper (white wool, bamboo).
            Roofs: deepslate_tile_stairs or dark_prismarine_stairs, steepness 1, wide overhang 2-3, upturned corners (add one stair half=top raised at each corner),
            multi-tier pagoda: each tier smaller, roof then a short wall level, repeat with clone_region or loop. Accents: lanterns, bamboo, cherry_leaves, cherry planks, stone lanterns (wall + lantern + slab), torii gate (red pillars + two beams).
            Gardens: gravel paths, moss, water, lily pads, cherry trees (build_curve trunk + ellipsoid pink leaves), stepping stones.
            """;

    private static final String MODERN = """
            MODERN / CONTEMPORARY
            White_concrete, smooth_quartz, light_gray_concrete, polished_andesite, black_concrete, glass / tinted_glass, stripped oak or spruce slats (trapdoors as louvers), smooth_stone_slab.
            Forms: cantilevers, flat roofs with slab edge, big glass walls, horizontal window strips, pools (water + sea_lantern floor), terraces with glass pane railings.
            Contrast: one dark accent (black concrete / deepslate) + wood warmth. Keep lines clean; texture pass only very subtle (light_gray_concrete_powder 5%).
            """;

    private static final String NATURE = """
            NATURE / TERRAIN / TREES
            Trees: build_curve trunks (thickness 2->0.5, mode spline, 3-6 points) with branches as more curves; leaves as several overlapping ellipsoids (radius 3-6) with mix '80%oak_leaves,20%azalea_leaves';
            roots with build_curve going down into the ground. Use *_wood blocks (bark on all sides) for trunks.
            Rocks: ellipsoids of '50%stone,25%andesite,15%cobblestone,10%mossy_cobblestone', replace_blocks top surface with moss, decorate_surface grass/ferns.
            Paths: gradient/replace with dirt_path, coarse_dirt, gravel, rooted_dirt at noise_scale 3. Water features: fill water, lily pads via decorate_surface on water ('on'='minecraft:water').
            Note: leaves placed by the tool are not persistent=true unless you write minecraft:oak_leaves[persistent=true] - always add persistent=true so they don't decay.
            """;

    private static final String INTERIOR = """
            INTERIOR DESIGN
            Floors: planks with a border of stripped logs, carpets as rugs. Walls: different material than the outside, with wainscoting (stairs/trapdoors lower 1-2 blocks).
            Furniture: chairs (stairs + trapdoor sides / signs as armrests), tables (fence + pressure plate, or slab on a block), beds, bookshelves + chiseled bookshelves, lecterns,
            kitchens (smokers, barrels, cauldrons, campfire under a chimney), lights (lanterns on chains, candles in groups, end rods). Chests with loot: minecraft:chest[facing=north]{LootTable:"minecraft:chests/village/village_plains_house"}.
            Use build_blueprint per room, one layer per height level. Leave walkways 2 blocks wide and 3 high.
            """;

    private static final Map<String, String> TOPICS = Map.of(
            "techniques", TECHNIQUES,
            "gothic", GOTHIC,
            "medieval", MEDIEVAL,
            "fantasy", FANTASY,
            "japanese", JAPANESE,
            "modern", MODERN,
            "nature", NATURE,
            "interior", INTERIOR);
}
