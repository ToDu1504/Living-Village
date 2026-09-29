# Living Villages

*[Tiếng Việt](README.vi.md)*

A server-side Fabric mod for Minecraft 1.21.1 that lets villages grow on their own. When a village runs out of free beds, a villager becomes a builder, walks to a free spot near the bell and builds a new house block by block. The house comes from the village's own house pool, so it always matches the village style. If Better Village is installed, the new houses use Better Village designs.

No new blocks, items, entities or textures. Players do not need the mod on their client.

## Requirements

- Minecraft **1.21.1**
- Fabric Loader **0.16.0** or newer
- Fabric API (built against 0.116.17+1.21.1)
- Java 21

## Installation

1. Install Fabric Loader for 1.21.1.
2. Put `livingvillages-<version>.jar` and Fabric API into the `mods` folder. On a dedicated server, only the server needs the mod.
3. Start the game once. `config/livingvillages.json` is created with default values.

## How it works

- **Villages** are found by their bell (the "meeting point"). Each bell is one village. The village style (plains, desert, savanna, snowy, taiga) is taken from its villagers the first time it is seen and never changes. Jungle and swamp villagers count as plains.
- **A new house starts** when all of these are true:
  - the village has at least 2 adult villagers;
  - it has no free bed (see `freeBedThreshold`);
  - it has built fewer than `maxHousesPerVillage` houses;
  - one Minecraft day (`cooldownTicks`) has passed since its last house.
- **House designs** are read at runtime from the pool `minecraft:village/<style>/houses`. Only houses with a bed and no job-site block are used, so no smithies or farms. Datapacks and mods that change these pools are picked up automatically.
- **The building site** is flat, dry, natural ground within 12–64 blocks of the bell. The mod never builds over paths, buildings, beds, bells, job sites or another house's plot. Small slopes get a foundation (cobblestone, or sandstone in deserts).
- **Trees:** up to 4 natural trees growing inside the footprint are felled first, with no item drops, and sites without trees are preferred. A tree counts as natural only if it has natural (non-persistent) leaves, so log houses and trees decorated with player-placed leaves are never touched. Giant trees and trees with a bee nest stay. After the house is done, one sapling of the same kind per felled tree is replanted 3–8 blocks away; a farmer walks there to plant it if the village has one.
- **Build order:** foundation, then clearing plants and levelling earth, then the structure layer by layer from the bottom, then doors, beds, torches, carpets and other decorations. A block is only ever placed into air or a replaceable block such as grass. Anything a player put in the way is left alone. Chests get no loot.
- **The builder:** unemployed villagers are chosen first, then masons, then anyone except nitwits and children. The builder must be within `builderReach` blocks of the work, and swings its arm and holds the block it places. If the builder dies or cannot reach the site for 60 seconds, another villager takes over. If nobody is available, the house builds itself at half speed.
- **Building pauses** at night, during raids, and when no player is within `activeRange` blocks. Nothing is simulated while a village is far away. Progress is saved, so a house under construction continues after a restart.
- **Needs, mood and leader:** each village tracks four needs from 0 to 100 (housing: free beds; food: share of farmers; jobs: unemployed villagers against free job sites; safety: iron golems and Guard Villagers guards, minus recent monster attacks) and a mood made of them plus fading events (a new building cheers the village up, a death saddens it). The adult with the highest profession level becomes the leader, titled "Chief <name>" (visible when you look at them). A name given with a name tag is kept. The leader never works as a builder and is replaced when they die, are converted or stay away too long.
- If the bell is broken, the village becomes inactive and its project pauses. A bell placed again nearby revives the village.

## Language

Players do not need the mod, so all texts are produced on the server in the language set by `language` in the config: `vi_vn` (default) or `en_us`.

## Commands

All commands require permission level 2 (operator). "Nearest village" means the nearest registered village within `activeRange` of you.

| Command | Effect |
|---|---|
| `/livingvillages status` | Nearest village: bell position, style, villagers, beds (total/free), houses built, current project (house, progress, builder), cooldown left, the four needs as bars, mood, and the leader with what they want to do |
| `/livingvillages list` | All villages known in this dimension |
| `/livingvillages build` | Start a house now in the nearest village, ignoring beds and cooldown (the house limit still applies) |
| `/livingvillages build instant` | Same, but place the whole house at once (or finish the running project at once). For testing |
| `/livingvillages cancel` | Stop the current project. Blocks already placed stay |
| `/livingvillages templates [style]` | List the house designs found for the nearest village, or for a style: `plains`, `desert`, `savanna`, `snowy`, `taiga` |
| `/livingvillages pause` / `resume` | Stop or restart the whole mod (saved as `enabled` in the config) |
| `/livingvillages speed <0.1–10>` | Build speed multiplier (saved in the config) |
| `/livingvillages reload` | Reload `config/livingvillages.json` |

## Configuration

`config/livingvillages.json`. Values out of range fall back to the default with a warning in the log. If the file cannot be parsed, defaults are used and the file is left untouched so you can fix it. After editing, run `/livingvillages reload`.

| Key | Default | Meaning |
|---|---|---|
| `language` | `vi_vn` | Language of all texts: `vi_vn` or `en_us` |
| `enabled` | `true` | Master switch (`pause`/`resume`) |
| `scanIntervalTicks` | `100` | How often to look for bells around players |
| `manageIntervalTicks` | `40` | How often each village checks whether to start a house |
| `scanRadius` | `64` | Bell search radius around each player |
| `villageRadius` | `48` | Radius around the bell for counting villagers and beds (widened automatically to cover houses the mod built) |
| `villageMergeRadius` | `48` | Bells closer than this belong to the same village |
| `activeRange` | `128` | A village only works while a player is this close |
| `freeBedThreshold` | `0` | Start building when free beds ≤ this |
| `maxHousesPerVillage` | `10` | Houses the mod builds per village, at most |
| `cooldownTicks` | `24000` | Wait after a finished house (24000 = one day) |
| `minBuildDistance` / `maxBuildDistance` | `12` / `64` | Distance from the bell for new houses |
| `siteAttempts` | `48` | Candidate spots tried per search |
| `margin` | `2` | Free border around a house |
| `maxHeightDifference` | `3` | Largest ground height difference allowed on a site |
| `templateYOffset` | `0` | Extra vertical shift for houses. The floor height is detected automatically, so normally keep 0 |
| `maxSiteFailures` | `5` | After this many failed searches in a row, the village stops until `/livingvillages build` |
| `blocksPerSecond` | `2.0` | Base build speed |
| `speedMultiplier` | `1.0` | Multiplier on the build speed (`speed` command) |
| `maxBlocksPerTickGlobal` | `4` | Blocks placed per tick across the whole server, at most |
| `builderReach` | `8` | How close (horizontally) the builder must be to place a block |
| `builderStuckTicks` | `1200` | Time a builder may take to reach the work before it is replaced |
| `workStartTime` / `workEndTime` | `1000` / `11000` | Working hours (time of day in ticks) |
| `maxSkippedRatio` | `0.1` | Cancel a project when more than this share of its blocks is blocked |
| `showBuilderHeldItem` | `true` | Builder holds the block it places |
| `debugLogging` | `false` | Extra log lines about villages, sites and builders |
| `allowTreeClearing` | `true` | Fell natural trees inside building sites |
| `maxTreeLogs` | `40` | Trees with more logs than this are never felled |
| `maxTreesPerSite` | `4` | Most trees felled for one house |
| `replantSaplings` | `true` | Replant one sapling per felled tree |
| `needsEnabled` | `true` | Needs, mood and leader (off = behaves like 0.1) |
| `farmersPerVillager` | `0.25` | Share of farmers for full food |
| `defendersPerVillager` | `0.1` | Golems/guards per adult for full safety |
| `attackPenalty` | `10` | Safety lost per recent monster attack on a villager |
| `attackHalfLifeTicks` | `24000` | Recent attacks fade by half over this time |
| `moodEffectTicks` | `72000` | Mood effects of events fade to nothing over this time |
| `needThreshold` | `40` | A need below this counts as unmet (used from v2 stage 3) |
| `leaderAbsentTicks` | `12000` | A leader away this long is replaced |
| `workTimeoutTicks` | `600` | How long a villager may take to walk to a task (e.g. replanting) before it is done without them |

## Compatibility

- **Better Village:** supported with no extra setup. Its house designs are used automatically. Houses with job sites (smithies, libraries…) are skipped even though Better Village gives them beds.
- **Regrowth:** its walls, fences, paths and torches are treated like any other block. Sites are not placed on them, and a block Regrowth puts on a construction site is simply skipped.
- **Guard Villagers:** guards are not villagers, so they are never chosen as builders.
- Datapacks that edit the village house pools also work. Designs larger than 24×24 or taller than 20 blocks are ignored.

## Performance

- All work is spread over intervals, limited to villages near players, and never loads chunks. House designs are cached per pool.
- Measured on a dedicated server with 3 villages building at once: about **0.02–0.05 ms per tick** on average.
- Occasional short spikes (a few ms every several seconds) happen when a placed block crosses the builder's path. Vanilla then recalculates that path, exactly as it does when a player places a block. The first house after a server start also loads the house designs once.

## Known limitations

- Villages on steep ground may find no site. After `maxSiteFailures` failed searches such a village stops trying until `/livingvillages build`.
- Only the five vanilla village styles are supported.

## Removing the mod

Houses that were built stay as ordinary blocks, and villagers keep the names the mod gave them. The mod's only data is `data/livingvillages.dat` in each dimension folder, which can be deleted. Removing the mod does not damage the world.
