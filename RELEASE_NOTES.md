First public **alpha** of Origami for Minecraft 26.2. Expect missing APIs.

Origami runs Paper / Spigot plugins on Fabric through a Bukkit API translation layer. It uses no Mixins.

- Plugin loading from `plugins/` (`plugin.yml`, dependency order, isolated class loaders)
- Commands (Brigadier bridge, tab completion, permissions) and events (priorities, cancellation)
- `BukkitScheduler`, players, worlds, blocks (basic), `ItemStack` / `ItemMeta` / inventories (partial)
- Tested with Vault, EssentialsX and LuckPerms. WorldEdit (Bukkit) loads, selection commands only
- Runs together with Sodium, Lithium and FerriteCore without crashes

Known gaps and unsupported plugins are listed in the README on GitHub.
