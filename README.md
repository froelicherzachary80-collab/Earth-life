# EarthLife 0.4 — "First Playable Life"

A Fabric mod for **Minecraft Java Edition 26.3** that adds a new world type, **Earth — 1:1 Real World**: a
block-for-metre copy of the real place, starting in **Williamson, New York**, built from real map and terrain data,
with a life to live in it — residents to talk to, jobs with shifts and wages, LifeCoins, vehicles, airports and
flights, real-time events (Halloween week is on now), and a full-screen interactive map on **M**.

It is an ordinary mod: you keep your normal Minecraft and launcher, normal Survival, and your other worlds are not
touched. **Read [STATUS.md](STATUS.md)** for exactly what is tested, how, and what isn't finished.

## Install

1. Minecraft Java Edition 26.3 (the official launcher).
2. Fabric Loader for 26.3: run the installer from https://fabricmc.net/use/installer/ and pick 26.3.
3. Put two files in your `.minecraft/mods` folder:
   - `earthlife-0.4.0.jar` (from a successful *Fabric mod build* GitHub Actions run with the **earthlife-mod** artifact, or build it yourself below)
   - Fabric API for 26.3 (0.165.0+26.3 or newer) from https://modrinth.com/mod/fabric-api
4. Start the "fabric-loader-26.3" profile in the Minecraft Launcher.

The mod includes the Western New York starter map data (about 12 MB), so it works offline. For multiplayer, install
the same jar on the server; players without the mod can still join, they just don't get the map, menu and HUD.

## Make an Earth world

Singleplayer → Create New World → **World** tab → *World Type*: **Earth — 1:1 Real World** → **Customize**:

| Setting | Choices |
|---|---|
| Starting place | Country / state / town. The Western NY towns (Williamson, Pultneyville, Sodus, Marion, Ontario, Webster, …) have real roads, buildings and terrain. Other towns are listed with *(estimated terrain)*. |
| Quality | Low (no trees, no window rows) · Medium · High · Ultra (Medium, High and Ultra generate the same detail in 0.4) |
| Buildings / Roads / Real terrain | on/off. Real terrain off = flat land at the town's height. |
| Transport & airports / Residents & economy | on/off |
| Start time | Real time · morning · noon · evening · night |
| Scenario | **New Life** (moved to town with 250 LC, guided objectives) · **Career Starter** (a store job on day one) · **Traveler** (start at the airport, 1,500 LC) · **Normal Survival** |
| Day length | 20 min (vanilla) · 1 hour · 2 hours · real time (24 h) |

Settings are stored in that world's `level.dat` (inside the overworld generator), so every world keeps its own.
You spawn on solid open ground in the centre of your start town, never in water or on a roof.

## Keys (Options → Controls → Key Binds → *EarthLife*, all rebindable)

| Key | Opens |
|---|---|
| **M** | LifeMap: you (arrow), XYZ, latitude/longitude, elevation, towns, streets, buildings, water, airports, landmarks, residents, events, your waypoints/home/work, players who share their location. Scroll = zoom (9 levels, 4 px/m down to 64 m/px), drag = pan, click = select, double-click = road route, search box for towns/streets/places, Layers panel. In flight, M opens the seatback map instead. |
| **K** | EarthLife menu: Life (objectives, home, waypoints, privacy), Jobs, Wallet, Nearby, Events, Travel, Vehicles, Help |
| **J** | Jobs tab · **L** Wallet tab · **N** Nearby tab |

Right-click a resident to talk; the conversation offers choices (chat, ask about their job, ask where to find work,
directions to the town centre, give a gift, and "Trick or treat!" during Halloween week).

## Commands

`/earthlife help` lists them. Everyone: `map` (with `route home|work|town <name>|airport <ICAO>|geo <lat> <lon>|clear`,
`share on|off`), `location`, `coordinates`, `towns`, `town <name>`, `landmarks`, `wallet`, `claim hourly|daily|weekly`,
`jobs`, `job list|apply <job> <employer>|clockin|clockout|tasks|quit`, `vehicles`, `vehicle shop|buy|summon`,
`flights`, `book <flight> auto`, `events`, `event costume|carving`, `time`, `objectives`, `waypoint add|set|remove|list`,
`home set`, `work set`, `data status`, `world info`, `version`.
Operators only: `admin reload`, `admin region status`, `admin debug`, `admin teleport town <name>|<lat> <lon>`,
`admin halloween on|off`, `admin pay <amount>`. Routes are directions only — EarthLife never teleports players for navigation.

## Playing a life

- **Work**: apply in Jobs (J). Janitor (18–23 h), store clerk (8–16 h), construction, delivery and bus driver (need a
  driving test), airport check-in and gate agents, pilot (flight school), farmer (sell crops). Go to work during your
  shift, clock in, do the tasks you're given (clean, restock barrels, check in travelers, deliver…), clock out: pay =
  hours + tasks, recorded in your ledger.
- **LifeCoins** (LC) come from work, objectives, hourly/daily/weekly play rewards and contests, and are spent on
  vehicles, flights and event tickets. They are a fictional in-game currency: not Minecoins, not real money, can't be
  bought or cashed out.
- **Halloween week (Oct 24 – Nov 1, real calendar)**: residents wear costumes; trick-or-treat them in the evening
  (game time 17:00–23:00) for candy; costume contest at any town centre (wear a carved pumpkin, mob head or dyed
  leather); pumpkin-carving contest judged around your home.
- **Travel**: buy a car in Vehicles and summon it; Bus 1 runs Williamson ↔ Rochester; flights from Williamson–Sodus
  (KSDC) and Rochester (KROC): book in Travel, walk down the jet bridge when boarding opens.

## Map and terrain data

Shipped in the jar (`data/earthlife/regions/western-ny/`, built by `.github/workflows/starter-data.yml`):

- Roads, buildings, land use, water, railways and aeroways from **OpenStreetMap** for the box 43.12–43.33 N,
  76.94–77.47 W (Williamson, Pultneyville, Sodus, Sodus Point, Marion, Ontario, Walworth, east Webster): about 26,000
  buildings and 11,500 roads. © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright).
- Elevation from **AWS Terrain Tiles** (Mapzen; sources include USGS 3DEP and SRTM) at 20 m for that box and 60 m for
  the whole Western NY region (Rochester to Wolcott, the lake to Canandaigua/Geneva). Lake Ontario's flat surface is
  turned into a lake bed by the pipeline.
- Outside the starter box the world is still at real scale with real terrain from the region grid, but buildings are
  estimated (marked *estimated* in `/earthlife location` and F3).

Scale: 1 block = 1 m horizontally and vertically. Y 63 (vanilla sea level) = 74 m above sea level, the surface of
Lake Ontario. The Earth overworld is 1,024 blocks tall (Y −64 to 959); the Nether and End are vanilla.

To build data for another area, see `tools/` (`fetch_osm.py`, `osm_to_earthlife.py`, `terrain_tiles.py`) and put the
result in `config/earthlife/regions/<region>/`.

## Build from source

Needs JDK 25 and Gradle 9.8+ with internet access (Fabric and Mojang Maven):

```
gradle :fabric-mod:build              # -> fabric-mod/build/libs/earthlife-0.4.0.jar
gradle :core:runTests                 # engine tests (also: ./build.sh with only a JDK)
gradle :fabric-mod:runClientGameTest  # in-game tests in a real client (see STATUS.md)
```

GitHub Actions builds the jar and runs core and in-game tests on every push (`.github/workflows/mod-build.yml`); the jar is the **earthlife-mod**
artifact, the in-game test report and screenshots are pushed to the `ci-tests` branch.

## Licence

Code: MIT. Map data: ODbL (OpenStreetMap). EarthLife is not affiliated with Mojang or Microsoft and does not
include or distribute Minecraft.
