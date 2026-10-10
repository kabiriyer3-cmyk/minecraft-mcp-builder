# MCP Builder (Fabric 1.21.1)

Мод добавляет в Minecraft встроенный **MCP сервер** (Model Context Protocol). Подключаешь его к Claude / Cursor / любому ИИ с поддержкой MCP и просишь построить что угодно: готический собор, японскую пагоду, дерево-гигант. ИИ сам ставит блоки в мире.

## Скачать .jar

Каждый пуш в `main` собирается через GitHub Actions:
- **Releases** → последний `build-N` → `mcpbuilder-*.jar`
- или **Actions** → последний запуск → артефакт `mcpbuilder-jar`

## Установка

1. Fabric Loader 0.16+ для Minecraft 1.21.1
2. Положить в `mods/`: **Fabric API** и `mcpbuilder-*.jar`
3. (опционально) **Mod Menu** для экрана настроек
4. Зайти в мир. Команда `/mcp status` покажет адрес: `http://127.0.0.1:25599/mcp`

## Подключение ИИ

**Claude Code:**
```bash
claude mcp add --transport http minecraft http://127.0.0.1:25599/mcp
```

**Claude Desktop** (`claude_desktop_config.json`, нужен Node.js):
```json
{ "mcpServers": { "minecraft": { "command": "npx", "args": ["-y", "mcp-remote", "http://127.0.0.1:25599/mcp"] } } }
```

**Cursor / Windsurf / VS Code** (Streamable HTTP):
```json
{ "mcpServers": { "minecraft": { "url": "http://127.0.0.1:25599/mcp" } } }
```

Если задан токен: заголовок `Authorization: Bearer <токен>` или URL `...?token=<токен>`.

## Инструменты (v1.1)

**Паттерны везде.** Любой параметр блока понимает состояния/NBT и взвешенные смеси для текстуры:
`60%minecraft:stone_bricks,25%minecraft:cracked_stone_bricks,15%minecraft:mossy_stone_bricks`

| Группа | Инструменты |
|---|---|
| Осмотр | `get_player_info` (+ блок под прицелом), `get_block`, `get_height`, `scan_area`, **`view_area`** (ASCII: сверху, карта высот, срезы, фасады с 4 сторон), **`search_blocks`** |
| База | `set_block`, `place_blocks` (палитры), `fill` (replace/hollow/walls/outline/keep + маска), `clear_area`, `undo`, `run_command` |
| Формы | `build_sphere`, `build_cylinder`, **`build_shape`** (эллипсоид, конус/шпиль с вогнутостью, пирамида, тор), `build_line`, **`build_curve`** (сплайны/безье с сужением: ветки, аркбутаны, мосты) |
| Архитектура | **`build_arch`** (готические/круглые арки, окна, порталы), **`build_roof`** (двускатные и вальмовые крыши из ступенек с коньком и фронтонами) |
| Детали | **`build_blueprint`** (ASCII-слои + палитра, с поворотом), **`clone_region`** (копия с поворотом/зеркалом/повтором) |
| Текстура | **`replace_blocks`** (выветривание: шанс + шум-кластеры, сохраняет ориентацию ступенек), **`gradient_fill`** (градиенты), **`decorate_surface`** (мох, лианы, фонари, цветы) |
| Знания | **`get_building_guide`** (техники детализации + палитры: gothic, medieval, fantasy, japanese, modern, nature, interior) |

## Настройки

Mod Menu → MCP Builder, либо `config/mcpbuilder.json`: `enabled`, `host`, `port`, `authToken`, `targetPlayer`, `maxBlocksPerRequest`, `undoHistorySize`, `allowCommands`, `chatNotifications`, `autoConnectBlocks`.

Команды: `/mcp status`, `/mcp restart`, `/mcp undo [шаги]`.

## Сборка

```bash
gradle build   # Java 21, Gradle 8.10+
```
