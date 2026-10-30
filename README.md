<p align="center"><img src="src/main/resources/assets/origami/icon.png" alt="Origami" width="128"></p>

# Origami

English | [日本語](README.ja.md)

Origami is a Fabric mod that runs **Paper / Spigot plugins** on a Fabric server (and in single-player) by translating the Bukkit API to Fabric and Minecraft calls. It is a pure API translation layer: **it uses no Mixins**, so it does not conflict with other mods such as Sodium or Lithium.

> **Status: early (0.1.0).** Minecraft 26.2 only. Many Bukkit APIs are implemented, but not all. See [Limitations](#limitations).

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.5 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25

## Installation

1. Put `origami-<version>.jar` and Fabric API into the `mods/` folder.
2. Put your plugins (`.jar`) into the `plugins/` folder in the game directory (the server root for a dedicated server).
3. Start the game or server. Plugins are loaded on server start and enabled in dependency order.

Download: see [Releases](https://github.com/29kiyo/Origami/releases).

## What works

Tested with Origami's own test plugins and with these real plugins:

| Plugin | Result |
|---|---|
| Vault 2.1.1 | Works (ServicesManager) |
| EssentialsX 2.22.1-dev+27 | Main commands work (home, warp, tpa, kit, pay, ban, and more) |
| LuckPerms 5.5.71 | Works (permissions, groups, Vault integration) |
| WorldEdit 7.4.6 (Bukkit) | Loads; selection commands only. Editing needs a NMS adapter and is not supported. Use the Fabric version of WorldEdit |

Implemented areas: plugin loading (`plugin.yml`, dependency order, isolated class loaders), commands (Brigadier bridge, tab completion, permissions), events (priorities, cancellation), `BukkitScheduler`, players, worlds, blocks (basic), and `ItemStack` / `ItemMeta` / inventories (partial).

Also checked: Origami runs together with Sodium, Sodium Extra, Lithium and FerriteCore without crashes.

## Limitations

Origami deliberately does not do the following:

- Plugins that call Minecraft internals directly (`net.minecraft.*`, NMS) or cast to CraftBukkit classes. They are detected and refused with a clear message.
- Raw packet manipulation (for example ProtocolLib-based plugins).
- `paper-plugin.yml`-only plugins and the `libraries:` section of `plugin.yml`.
- Overriding vanilla / other mods' commands with the same name. Use `plugin:command`, a plugin's own alias, or `commandAliases` (see below).

Known gaps that would need Mixins (currently not implemented): events before vanilla commands, `PlayerKickEvent`, editing chat or join/quit messages, and actually hiding players (`/vanish` has no visual effect).

Other gaps: non-player entities, `Block#getState` / `getBlockData`, `PlayerTeleportEvent`, custom inventories, and Paper-format item serialization. Calling an unimplemented method throws an exception and logs a warning once.

## Configuration

`config/origami.json`:

| Key | Default | Description |
|---|---|---|
| `language` | `en_us` | Log language (`en_us`, `ja_jp`) |
| `recordUnimplementedCalls` | `false` | Count unimplemented API calls and write them to `config/origami-unimplemented.json` on shutdown (useful for bug reports) |
| `debug` | `false` | Extra logging |
| `nmsDetection` | `error` | What to do with plugins that reference NMS / CraftBukkit: `error`, `warn` or `off`. Some plugins ship unused NMS classes; `warn` lets them load |
| `commandAliases` | `{}` | Extra command names, e.g. `{"xgive": "give"}`. Only adds new names; it never overrides existing commands |

## Building

Run `./gradlew build` with JDK 25. The jar is written to `build/libs/`.

## License

Origami is licensed under the [GNU GPL v3](LICENSE).

The release jar bundles the Paper API and its libraries as nested jars, each under its own license. The full texts and sources are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), which is also shipped inside the jar (`THIRD_PARTY_NOTICES_origami.md`).

| Bundled | License |
|---|---|
| Paper API | GPL-3.0 |
| Adventure (8 modules), option | MIT |
| SnakeYAML | Apache-2.0 |
| bungeecord-chat | BSD 3-Clause style license **with an additional clause**: it may not be used for commercial software hosting services without written permission from the author. Please read it if you redistribute Origami or use it in a commercial hosting service |

Origami is not affiliated with or endorsed by PaperMC, SpigotMC, Mojang or Microsoft. "Minecraft" is a trademark of Mojang AB.
