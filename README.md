# Housing Logs

Housing Logs is a client-side Minecraft Forge mod that records block changes (place/break/change) inside user-defined cuboid areas.

It is designed for **Hypixel Housing** workflows: logging is only active while the client detects that you are currently in Housing.

## Supported versions

- Minecraft: **1.8.9**
- Forge: **1.8.9-11.15.1.2318**
- Side: **Client only**

## What it does

- Lets you define named 3D areas by coordinates.
- Logs block updates inside those areas to:
	- a machine-readable **JSON Lines** file (`.jsonl`), and
	- a human-readable **text** log (`.log`).
- Optionally renders an in-world bounding-box highlight for selected areas.

## Installation

1. Install Minecraft Forge for **1.8.9**.
2. Download the mod JAR from this repository’s releases (or build it yourself).
3. Place the JAR into your `.minecraft/mods` folder.
4. Launch Minecraft 1.8.9 with the Forge profile.

## Usage

All commands use the prefix `/hlog`.

### Define an area

Create or update a named cuboid area:

`/hlog add <name> <x1> <y1> <z1> <x2> <y2> <z2> [#RRGGBB|#RRGGBBAA]`

- `<name>` is case-insensitive for lookups (e.g. `Test` and `test` refer to the same area).
- The color is optional and defaults to `#FFFF00FF` (RGBA).

### Manage areas

- List current areas and status: `/hlog list`
- Remove one area: `/hlog remove <name>`
- Remove all areas: `/hlog clear`

### Enable/disable logging

- Enable logging: `/hlog on`
- Disable logging: `/hlog off`

### Highlight an area in-world

- Toggle highlight: `/hlog highlight <name>`
- Explicitly set: `/hlog highlight <name> on|off`

### Find the output files

Run:

`/hlog path`

This prints the full paths to the config + log files in chat.

## Output files

The mod writes files under your Minecraft directory (typically `.minecraft/config`):

- `config/hitlist-blockaudit-area.json`
	- Stores the global enabled flag + your saved area definitions (name, min/max, color, highlight).
- `config/hitlist-blockaudit-log.jsonl`
	- One JSON object per line.
	- Fields include: `tsMs`, `area`, `action` (`PLACE`/`BREAK`/`CHANGE`), `x`, `y`, `z`, `oldBlock`, `oldMeta`, `newBlock`, `newMeta`, plus optional `playerName`/`playerUuid` when attribution succeeds.
- `config/hitlist-blockaudit.log`
	- Human-readable log lines intended for quick viewing (e.g. tailing the file).

## Notes and limitations

- Player attribution is **best-effort**. Minecraft servers generally do not send definitive “who placed this block” information to clients.
- Break attribution is attempted via break animation packets when available; placement/break may also be guessed heuristically based on nearby players’ look direction and distance.
- Logging is only performed when the mod detects you are in **Housing**.

## Building from source

- Run `./gradlew build`.
- The distributable JAR is produced under `build/libs/`.

