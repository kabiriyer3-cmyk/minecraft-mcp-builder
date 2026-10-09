# MCP Builder (Fabric 1.21.1)

Мод добавляет в Minecraft встроенный **MCP сервер** (Model Context Protocol). Подключаешь его к Claude / Cursor / любому ИИ с поддержкой MCP и просишь: «построй средневековый замок перед игроком», «сделай японскую пагоду», «маяк на скале», и ИИ сам ставит блоки в мире.

## Включить автосборку (один раз)

Файл GitHub Actions лежит в `ci/build.yml`. Открой его на GitHub → карандаш (Edit) → в поле имени файла сделай путь `.github/workflows/build.yml` → Commit. Сборка запустится сразу.

## Скачать .jar

Каждый пуш в `main` собирается через GitHub Actions:
- **Releases** → последний `build-N` → `mcpbuilder-1.0.0.jar`
- или **Actions** → последний запуск → артефакт `mcpbuilder-jar`

## Установка

1. Fabric Loader 0.16+ для Minecraft 1.21.1
2. Положить в `mods/`: **Fabric API** и `mcpbuilder-*.jar`
3. (опционально) **Mod Menu** для экрана настроек
4. Зайти в мир. Команда `/mcp status` покажет адрес: `http://127.0.0.1:25599/mcp`

MCP сервер запускается, когда загружен мир (одиночная игра или выделенный сервер), и выключается при выходе.

## Подключение ИИ

**Claude Code:**
```bash
claude mcp add --transport http minecraft http://127.0.0.1:25599/mcp
```

**Claude Desktop** (`claude_desktop_config.json`, нужен Node.js):
```json
{
  "mcpServers": {
    "minecraft": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://127.0.0.1:25599/mcp"]
    }
  }
}
```

**Cursor / Windsurf / VS Code и др.** (Streamable HTTP):
```json
{
  "mcpServers": {
    "minecraft": { "url": "http://127.0.0.1:25599/mcp" }
  }
}
```

Если в настройках задан токен: добавь заголовок `Authorization: Bearer <токен>` или используй URL `http://127.0.0.1:25599/mcp?token=<токен>` (кнопка «Копировать MCP URL» в настройках копирует готовую ссылку).

## Инструменты для ИИ

| Инструмент | Что делает |
|---|---|
| `get_player_info` | позиция, взгляд, измерение, высота земли |
| `get_block`, `get_height`, `scan_area` | осмотр мира и рельефа |
| `set_block`, `place_blocks` | установка блоков (пачками, с `origin` и `palette`) |
| `fill` | кубоиды: replace / hollow / outline / keep |
| `clear_area` | очистка зоны |
| `build_sphere`, `build_cylinder`, `build_line` | сферы, купола, башни, балки |
| `undo` | откат последних построек |
| `run_command` | любая команда (`time set day`, `summon` ...), можно выключить |

Поддерживаются состояния блоков: `minecraft:oak_stairs[facing=north,half=top]`. Заборы, панели, стены и ступеньки автоматически соединяются.

## Настройки

Mod Menu → MCP Builder → Configure, либо файл `config/mcpbuilder.json`:

| Параметр | По умолчанию | |
|---|---|---|
| `enabled` | `true` | включить MCP сервер |
| `host` | `127.0.0.1` | `0.0.0.0` = доступ из сети (ставь токен!) |
| `port` | `25599` | порт |
| `authToken` | пусто | токен доступа |
| `targetPlayer` | пусто | вокруг какого игрока строить |
| `maxBlocksPerRequest` | `250000` | лимит блоков за один вызов |
| `undoHistorySize` | `20` | сколько операций можно откатить |
| `allowCommands` | `true` | разрешить `run_command` |
| `chatNotifications` | `true` | сообщения в чат о постройках |
| `autoConnectBlocks` | `true` | соединение заборов/стёкол/ступенек |

Команды в игре: `/mcp status`, `/mcp restart` (перечитать конфиг), `/mcp undo [шаги]`.

## Сборка локально

```bash
gradle build   # Java 21, Gradle 8.10+
```
Готовый jar: `build/libs/`.
