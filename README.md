# Living Villages

*[Tiếng Việt](README.vi.md)*

A server-side Fabric mod for Minecraft 1.21.1 that lets villages grow into walled citadels on their own. Each village has a leader who decides what it needs next: a house when beds run out, a farm when farmers are missing, a workshop when villagers have no job, a pen for the animals. A villager becomes the builder, walks to a free spot near the bell and builds it block by block. As the village levels up, it surrounds itself with a palisade fence, then a stone wall with towers and arched gates, then outer rings, lighting up dark spots with torches and keeping its streets tidy. The designs come from the village's own building pool, so they always match the village style; with Better Village installed, Better Village designs are used.

No new blocks, items, entities or textures. Players do not need the mod on their client.

## What's new since 0.1

Version 0.1 only built houses when a village ran out of beds. Now:

- **Trees are felled** on building sites (no drops) and saplings are replanted around the new building.
- **Needs, mood and a chief:** housing, food, jobs and safety from 0 to 100, a village mood, and a chief chosen among the villagers.
- **Building by need:** the chief builds a house, a farm, a workshop for a missing profession, or an animal pen, whichever the village lacks most.
- **Village levels:** Hamlet, Village, Town, City, each allowing more buildings and a wider building radius.
- **Every profession has a job:** breeding and shearing, smokers, cauldrons, fishing, healing, clerics curing zombie villagers, smiths making the village safer or faster to build, cartographers widening the building radius.
- **Names:** village names, family names by household, villager and guard names, a title when you walk into a village.
- **Speech:** villagers say short lines about what really happens in their village.
- **Chronicle and graves:** the village keeps a chronicle (also as a book on the librarian's lectern) and gives named villagers a grave.
- **Level-up fireworks:** a fireworks show over the square when the village reaches a new level.
- **Material board:** trade the materials the village needs to its chief for emeralds and make it build faster.
- **Roads:** new buildings get a road to the village streets, laid by a mason.
- **Palisade:** from Village level, a wooden fence ring grows around the village, moving outward as new buildings are added. Gates appear where roads cross. Masons build and maintain it.
- **Stone wall with towers and gates:** at Town level the palisade is replaced by a stone wall with 5×5 towers (spiral staircase inside), arched gates and battlements. At City level the wall gains embrasures on alternating merlons.
- **Buildings inside the walls:** once a city wall stands, houses and workshops are placed inside it; farms and pens go outside near the gates.
- **Outer rings:** at the highest level a second ring of walls expands around the first.
- **Torches:** dark spots on roads, along the wall and on the bell square get a wall torch automatically.
- **Village care:** villagers mend potholes in roads, sweep snow off paths and cut wild plants near doors. Garden fences around mod-built houses are optional (`homeFences`).

Every feature has a switch in the config; with all of them off the mod behaves like 0.1. Worlds from 0.1 load without losing anything.

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
- **Building designs** are read at runtime from the pool `minecraft:village/<style>/houses` and sorted by what is inside them: a **workshop** has a job-site block (it is a workshop for that profession), a **farm** has farmland and a composter, a **pen** is a fenced enclosure without beds, a **house** has a bed. Other pieces (decorations, meeting points) are never built. Datapacks and mods that change these pools are picked up automatically.
- **What to build** is decided by the leader, at most once per Minecraft day (`cooldownTicks`) and only with at least 2 adult villagers. The first unmet need wins:
  1. housing below `needThreshold` → a house;
  2. food below `needThreshold` → a farm (not while a composter still waits for a farmer: then the village lacks people, not farms);
  3. jobs below `needThreshold` → a workshop for the profession the village has fewest of;
  4. animal keepers (shepherd, butcher, leatherworker, fletcher) but none of their animals nearby → a pen, which gets a pair of those animals when finished;
  5. otherwise nothing (`status` says why: the village is fine, or it must grow before building more).
- **Village levels:** Hamlet, Village, Town, City. A level needs a number of adults and of different professions (Town also a librarian, City a librarian and a cleric) and sets how many buildings the mod may build (4 / 10 / 20 / 32) and how far from the bell (48 / 64 / 80 / 96 blocks). The level is computed as soon as a village is found, so an existing vanilla village often starts above Hamlet. Levels only go up; nearby players see a title when a village levels up, and fireworks go up over the square (launched from open ground away from anyone, exploding high, so nobody is hurt and nothing burns).
- **Professions at work** (during their working hours): shepherds breed and shear sheep, butchers breed pigs and put raw meat and charcoal into their own smoker, leatherworkers breed cows and refill their own cauldron, fletchers breed chickens, fishermen fish at nearby water and put cod or salmon into their own barrel, clerics heal hurt villagers and cure zombie villagers in the village (at most once a day each; never named or leashed ones, or ones kept by a player), cartographers walk around the edge of the village. Armorers and weaponsmiths make the village safer, toolsmiths make it build faster (+10% each, up to +30%), a cartographer widens the building radius by 16. Animals are bred only up to a limit per kind that grows with the level, and are never killed; named or leashed animals are left alone.
- **Names:** every village gets a name that fits its style. Each house is a household with a family name, and villagers are named after the household of their bed. Names set with a name tag or by another mod are never replaced. Guard Villagers guards are named "Guard <name>". Walking into a village shows its name, level, population and mood as a title.
- **Speech:** now and then a villager near a player says a short line above their head, picked from what really happens: unmet needs, a building going up, a new baby, a death, their own work, the leader's plans, the mood, or a greeting. The text is a vanilla text display that disappears after a few seconds; leftover text (for example after a crash) is removed as soon as it loads.
- **Chronicle:** the village writes down what happens: births ("The Tran family has a new baby"), deaths and their cause, villagers turned into zombies or cured, villagers joining the guards, new buildings, level ups, new chiefs, raids won or lost and deliveries. The last `chronicleMaxEntries` entries are kept. Every librarian keeps a written book of the chronicle on their own lectern while it is empty; a lectern holding another book is never touched, and a book taken away is replaced at the next entry. Big events (level up, raid) are announced in chat, small ones on the action bar. Named villagers and named guards who die get a grave (a stone wall and a sign with name, profession and day) in a 7×7 graveyard near the edge of the village; babies, zombified villagers and new guards get none.
- **Material board:** the village asks for the three materials used most by the building it is building or has planned next, on a sign by the bell and as trades with the chief (material → emeralds, one use each). Every delivery cheers the village up and goes into the chronicle; when everything is delivered, that building goes up twice as fast and the next one starts without waiting. The village never needs the board: it only speeds things up.
- **Roads:** a finished building is linked from its door to the nearest village road within `roadSearchRadius` (dirt path, smooth sandstone, and Regrowth's roads), or to the bell. A mason walks along and paves it (at half speed without a mason). Only grass, dirt, coarse dirt and podzol become dirt path, and sand becomes smooth sandstone in deserts; water, buildings and plots are avoided. A way that cannot be found is skipped.
- **Palisade and walls:** from Village level, masons build a wooden fence ring around the village (the outer edge of all its plots and POIs). Gates open where roads cross. When the village reaches Town level the palisade is replaced by a stone wall: same polygon, higher, with 5×5 towers at the corners and along the wall (spiral staircase inside, door facing inward, crenellations), and arched gates. At City level the wall gains embrasures at alternating merlons. At the highest level a second ring expands outward. The wall material is stone bricks (or cut sandstone in deserts). Blocks a player breaks are left open; blocks lost to explosions or mobs are repaired. The mod never touches player-placed blocks. Set `wallsEnabled = false` to disable entirely.
- **Torches:** every `torchIntervalTicks` ticks a dark cell on a road, the bell square, the inside of the wall or a wall block gets a wall torch. Light above `torchLightLevel` is left alone.
- **Village care:** every `roadCareIntervalTicks` ticks a random awake villager fixes a few things near them: fills a pothole (natural ground flanked by road blocks), raises a road segment sunk below its neighbours, sweeps snow off roads, cuts wild plants on roads or near doors. `homeFences = true` adds a wooden fence around each mod-built house.
- **The building site** is flat, dry, natural ground at least 12 blocks from the bell and within the building radius of the village level (64 blocks with levels off). Inside a city wall, houses and workshops are placed within the wall; farms and pens go outside near a gate. The mod never builds over paths, buildings, beds, bells, job sites or another house's plot. Small slopes get a foundation (cobblestone, or sandstone in deserts).
- **Trees:** up to 4 natural trees growing inside the footprint are felled first, with no item drops, and sites without trees are preferred. A tree counts as natural only if it has natural (non-persistent) leaves, so log houses and trees decorated with player-placed leaves are never touched. Giant trees and trees with a bee nest stay. After the house is done, one sapling of the same kind per felled tree is replanted 3–8 blocks away; a farmer walks there to plant it if the village has one.
- **Build order:** foundation, then clearing plants and levelling earth, then the structure layer by layer from the bottom, then doors, beds, torches, carpets and other decorations. A block is only ever placed into air or a replaceable block such as grass. Anything a player put in the way is left alone. Chests get no loot.
- **The builder:** unemployed villagers are chosen first, then masons, then anyone except nitwits and children. The builder must be within `builderReach` blocks of the work, and swings its arm and holds the block it places. If the builder dies or cannot reach the site for 60 seconds, another villager takes over. If nobody is available, the house builds itself at half speed.
- **Building pauses** at night, during raids, and when no player is within `activeRange` blocks. Nothing is simulated while a village is far away. Progress is saved, so a house under construction continues after a restart.
- **Needs, mood and leader:** each village tracks four needs from 0 to 100 (housing: free beds; food: share of farmers; jobs: unemployed villagers against free job sites; safety: iron golems and Guard Villagers guards, minus recent monster attacks) and a mood made of them plus fading events (a new building cheers the village up, a death saddens it). The adult with the highest profession level becomes the leader (villagers with a job first: vanilla does not let anyone trade with a jobless villager, and the material board trades through the chief; a jobless chief gives way as soon as a villager with a job can lead), titled "Chief <name>" (visible when you look at them). A name given with a name tag is kept. The leader never works as a builder and is replaced when they die, are converted or stay away too long.
- If the bell is broken, the village becomes inactive and its project pauses. A bell placed again nearby revives the village.

## Language

Players do not need the mod, so all texts are produced on the server in the language set by `language` in the config: `vi_vn` (default) or `en_us`.

## Commands

All commands require permission level 2 (operator), except `chronicle` and `board`, which every player may use. "Nearest village" means the nearest registered village within `activeRange` of you.

| Command | Effect |
|---|---|
| `/livingvillages status` | Nearest village: name, bell position, style, villagers, beds (total/free), buildings built and the limit, level, current project (design, progress, builder), cooldown left, the four needs as bars, mood, what the professions add, and the leader with what they want to build and why |
| `/livingvillages list` | All villages known in this dimension |
| `/livingvillages build` | Start what the leader wants now (a house if nothing is needed), ignoring cooldown and past failed searches (the building limit still applies) |
| `/livingvillages build instant` | Same, but place the whole building at once (or finish the running project at once). For testing |
| `/livingvillages cancel` | Stop the current project. Blocks already placed stay |
| `/livingvillages templates [style]` | List the building designs, grouped by kind, for the nearest village or for a style: `plains`, `desert`, `savanna`, `snowy`, `taiga` |
| `/livingvillages rename <name>` | Rename the nearest village |
| `/livingvillages chronicle` | The last 10 chronicle entries of the nearest village (every player) |
| `/livingvillages board` | The material requests of the nearest village (every player) |
| `/livingvillages board place` | Place the material board again (it is placed only once on its own) |
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
| `manageIntervalTicks` | `40` | How often each village updates its needs, level and leader and decides what to build |
| `scanRadius` | `64` | Bell search radius around each player |
| `villageRadius` | `48` | Radius around the bell for counting villagers and beds (widened automatically to cover houses the mod built) |
| `villageMergeRadius` | `48` | Bells closer than this belong to the same village |
| `activeRange` | `128` | A village only works while a player is this close |
| `freeBedThreshold` | `0` | Start building when free beds ≤ this |
| `maxHousesPerVillage` | `10` | Buildings the mod builds per village, at most. Only used when levels are off; otherwise the level decides |
| `cooldownTicks` | `24000` | Wait after a finished house (24000 = one day) |
| `minBuildDistance` / `maxBuildDistance` | `12` / `64` | Distance from the bell for new buildings (with levels on, the level's radius replaces the maximum) |
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
| `needThreshold` | `40` | A need below this counts as unmet, and the leader builds for it |
| `leaderAbsentTicks` | `12000` | A leader away this long is replaced |
| `workTimeoutTicks` | `600` | How long a villager may take to walk to a task before it is dropped (a sapling is then planted without them) |
| `levelsEnabled` | `true` | Village levels (needs `needsEnabled`); off = the limit is `maxHousesPerVillage` |
| `levelRequirements` | 8 adults, 3 professions / 20, 6, librarian / 35, 10, librarian and cleric | What Village, Town and City need |
| `levelMaxBuildings` | `[4, 10, 20, 32]` | Buildings the mod may build at each level |
| `buildRadiusByLevel` | `[48, 64, 80, 96]` | Farthest distance from the bell at each level |
| `levelCanDecrease` | `false` | Let a village lose a level when it no longer qualifies |
| `workEnabled` | `true` | Profession work |
| `workIntervalTicks` | `600` | How often villagers may pick a task |
| `workChance` | `0.5` | Chance to pick a task each time (×1.25 when happy, ×0.75 when miserable) |
| `professionWork` | all `true` | Switch per profession |
| `maxAnimalsPerType` | `[6, 8, 12, 16]` | Animals of one kind the village breeds up to, by level |
| `maxFishInBarrel` | `16` | Fish a fisherman keeps in their barrel |
| `toolsmithBuildSpeedBonus` / `toolsmithBuildSpeedMax` | `0.1` / `0.3` | Build speed per toolsmith, and the most in total |
| `cartographerRadiusBonus` | `16` | Extra building radius with a cartographer |
| `smithSafetyBonus` / `smithSafetyMax` | `5` / `20` | Safety per armorer or weaponsmith, and the most in total |
| `clericCureZombies` | `true` | Clerics cure zombie villagers |
| `clericCuresPerDay` | `1` | Cures per cleric per day |
| `clericCureRange` | `4` | Distance the cleric keeps from the zombie villager |
| `nameVillagers` / `nameGuards` | `true` / `true` | Give names to villagers and guards |
| `showEntryTitle` | `true` | Title when a player walks into a village |
| `greetingCooldownTicks` | `6000` | Time before the same village greets the same player again |
| `voiceEnabled` | `true` | Villagers talk |
| `voiceRange` | `24` | Villagers this close to a player may talk |
| `voiceIntervalTicks` / `voiceChance` | `200` / `0.35` | How often a village may say something, and the chance (twice for nitwits) |
| `maxBubblesPerVillage` | `2` | Lines shown at once per village |
| `bubbleDurationTicks` | `80` | How long a line stays |
| `chronicleEnabled` | `true` | Chronicle, its books and graves (needs `needsEnabled`) |
| `announceEvents` | `true` | Tell nearby players about events (chat or action bar) |
| `chronicleMaxEntries` | `100` | Entries kept per village |
| `gravesEnabled` | `true` | Graves for named villagers and guards |
| `levelUpFireworks` | `true` | Fireworks when a village reaches a new level |
| `boardEnabled` | `true` | Material board and its trades (switching it off takes the trades back from the chief) |
| `maxRequests` | `3` | Materials asked for at once (1–3) |
| `requestExpireDays` | `3` | Undelivered requests are renewed after this many days |
| `itemsPerEmerald` | wood 16, stone 16, glass 8, wool 8, default 8 | Items per emerald by kind |
| `buildRoads` | `true` | Roads from new buildings |
| `roadSearchRadius` | `32` | How far to look for a road to join |
| `roadBlocks` | `dirt_path`, `smooth_sandstone` | Blocks that count as road (add a road block of another mod here) |
| `roadMaxNodes` | `4000` | Most steps the road search tries |
| `wallsEnabled` | `true` | Palisade and stone walls |
| `deferToRegrowth` | `false` | When true and Regrowth is loaded, skip walls, fences and torches (let Regrowth do them) |
| `palisadeMinLevel` | `1` | Village level needed for the palisade |
| `cityWallMinLevel` | `2` | Village level needed for the stone wall |
| `cityWallHeight` | `3` | Normal wall height above the ground |
| `cityWallHeightMax` | `4` | Wall height with battlements (City level) |
| `towerSpacing` | `32` | Columns between towers |
| `wallMargin` | `6` | Margin added around plots for the wall polygon |
| `gateWidth` | `3` | Gate opening width |
| `maxWallStep` | `3` | Largest height difference allowed between adjacent wall columns |
| `wallBlocksPerSecond` | `1.0` | Rate at which each mason builds wall blocks |
| `outerRingMinLevel` | `3` | Village level that triggers an outer ring |
| `maxRings` | `3` | Most wall rings at once |
| `ringExpansion` | `24` | How much each outer ring expands the polygon |
| `torchesEnabled` | `true` | Auto-place torches in dark spots |
| `torchLightLevel` | `7` | Do not place a torch if light is above this |
| `torchSpacing` | `6` | Minimum distance between mod-placed torches |
| `torchIntervalTicks` | `200` | How often to look for a dark spot to light |
| `roadCareEnabled` | `true` | Fill potholes, sweep snow, raise sunk road segments |
| `grassCuttingEnabled` | `true` | Cut wild plants on roads and near doors |
| `roadCareIntervalTicks` | `200` | How often to do care work |
| `careBlocksPerRun` | `4` | Blocks fixed per care run |
| `homeFences` | `false` | Garden fences around mod-built houses |
| `homeFenceGap` | `1` | Fence distance from the edge of the house |

## Compatibility

- **Better Village:** supported with no extra setup. Its designs are used automatically and sorted the same way. Better Village puts beds in most work buildings, so a job site always makes a building a workshop; beds in workshops, farms and pens still count for housing once built.
- **Regrowth:** its walls, fences, paths and torches are treated like any other block. Sites are not placed on them, and a block Regrowth puts on a construction site is simply skipped. Its roads (dirt path, smooth sandstone in deserts) are village roads: new roads join them and never replace them.
- **Guard Villagers:** guards are not villagers: they never become builders or leaders and do not count as population, but they count for safety, get names and say their own lines. Clerics only heal villagers, since Guard Villagers already heals its guards.
- Datapacks that edit the village house pools also work. Designs larger than 24×24 or taller than 20 blocks are ignored.

## Performance

- All work is spread over intervals, limited to villages near players, and never loads chunks. House designs are cached per pool.
- Measured on a dedicated server with 3 villages building at once: about **0.02–0.05 ms per tick** on average.
- Occasional short spikes (a few ms every several seconds) happen when a placed block crosses the builder's path. Vanilla then recalculates that path, exactly as it does when a player places a block. The first house after a server start also loads the house designs once.

## Known limitations

- Villages on steep ground may find no site. After `maxSiteFailures` failed searches such a village stops trying until `/livingvillages build`.
- Only the five vanilla village styles are supported.
- A road still being laid, a fireworks show and a cure in progress are not saved: after a restart the road is dropped and the show ends.
- The chronicle, graves, level-up titles and speech were tested on a dedicated server without a real player; Guard Villagers could not be loaded in the development environment, so guard names, lines and graves are untested.
- Wall columns over water, lava or very steep ground are left as weak points; the wall still stands, those columns are just open.
- The spiral staircase inside towers was designed so villagers can walk up; this was tested headless. Actual pathfinding up the stairs may vary with other mods.

## Removing the mod

Buildings that were built stay as ordinary blocks, and villagers keep the names the mod gave them. A line of speech that was showing when the mod was removed stays in the air; remove it with `/kill @e[tag=livingvillages_bubble]`. Material board trades still on a chief stay there and become ordinary one-use trades that vanilla restocks; turn `boardEnabled` off and load the village once before removing the mod to take them back. The mod's only data is `data/livingvillages.dat` in each dimension folder, which can be deleted. Removing the mod does not damage the world.
