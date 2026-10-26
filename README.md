# EarthLife 0.4.1 — "First Playable Life", now 1:1 across New York, Pennsylvania, New Jersey and Vermont

A Fabric mod for **Minecraft Java Edition 26.3** that adds a new world type, **Earth — 1:1 Real World**: a
block-for-metre copy of the real place, starting in **Williamson, New York**, built from real map and terrain data,
with a life to live in it — residents to talk to, jobs with shifts and wages, LifeCoins, cars you can get into,
airports and flights, movie theaters, real-time events (Halloween week is on now), and a full-screen map on **M**.

**New in 0.4.1** (details in [CHANGELOG.md](CHANGELOG.md)):
- **1:1 New York, Pennsylvania, New Jersey and Vermont** — the whole of the four states from OpenStreetMap and USGS
  terrain, downloaded in packs as you explore. Big cities get the most detail (15 m terrain, sidewalks, street trees),
  towns semi-detail (25 m terrain, sidewalks), the countryside 45 m terrain.
- **Cars** with a real model — body, glass, lights, interior, wheels that turn and steer, and **four doors that swing
  open** when you get in or out — and an **in-car GPS**: a moving map on the dashboard screen plus turn-by-turn
  directions.
- **Movie theaters at their real locations** (every cinema in the four states that is on OpenStreetMap): lobby,
  ticket kiosks, concession stand, auditoriums with **recliners**, **popcorn, drinks and candy** you can eat, and films
  on the big screen from **your Jellyfin server(s)**, **public-domain films from the Internet Archive**, a link or a
  video file — in **2D or red/cyan 3D** with in-game 3D glasses.
- **4D motion auditorium**, open **October 29 – November 3**: motion seats, wind, mist and flashes that follow the
  film, and a **bHaptics TactSuit** (e.g. TactSuit Air) through the bHaptics Player on your PC.

It is an ordinary mod: you keep your normal Minecraft and launcher, normal Survival, and your other worlds are not
touched. **Read [STATUS.md](STATUS.md)** for exactly what is tested, how, and what isn't finished.

## Install

1. Minecraft Java Edition 26.3 (the official launcher).
2. Fabric Loader for 26.3: run the installer from https://fabricmc.net/use/installer/ and pick 26.3.
3. Put two files in your `.minecraft/mods` folder:
   - `earthlife-0.4.1.jar` (from a successful *Fabric mod build* GitHub Actions run with the **earthlife-mod** artifact, or build it yourself below)
   - Fabric API for 26.3 (0.165.0+26.3 or newer) from https://modrinth.com/mod/fabric-api
4. Start the "fabric-loader-26.3" profile in the Minecraft Launcher.

New worlds use the **Northeast map** (NY, PA, NJ, VT), which is downloaded in packs of half a degree (1–60 MB; New York
City and Philadelphia are the big ones) from this repository's GitHub release `regions-v1` the first time you go
somewhere, and cached in `.minecraft/earthlife/regions/` for every world. The mod also still includes the Western New
York starter data (about 12 MB), which works offline (choose *Map data: Western NY starter* in Customize). For
multiplayer, install the same jar on the server; players without the mod can still join, they just don't get the map,
menu, HUD, car models and films.

**To play films** you need **FFmpeg** (the free, open-source media decoder). EarthLife finds it on your PATH, or the
cinema menu offers to download it for you (Windows: BtbN's LGPL build from GitHub; macOS: evermeet.cx; on Linux
install it with your package manager, e.g. `sudo apt install ffmpeg`). FFmpeg is not part of EarthLife or Minecraft.

## Make an Earth world

Singleplayer → Create New World → **World** tab → *World Type*: **Earth — 1:1 Real World** → **Customize**:

| Setting | Choices |
|---|---|
| Starting place | Country / state / town. Towns in New York, Pennsylvania, New Jersey and Vermont (Williamson, NYC, Buffalo, Syracuse, Albany, Philadelphia, Pittsburgh, Newark, Burlington, …) are 1:1 on the Northeast map. Places elsewhere are listed with *(estimated terrain)*. |
| Map data | **1:1 NY, PA, NJ, VT** (downloads as you explore; default) · **Western NY starter** (offline, bundled). Worlds made with 0.4.0 keep the starter data. |
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

Right-click a car to get in through the nearest door (the owner takes the driver's seat); **W/S** drive, **A/D**
steer, **Space** brake, **Sneak** to get out. Set a destination on the LifeMap (double-click) and the dashboard GPS and
the banner at the top of the screen give directions.

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

## Movie theaters

Every cinema mapped on OpenStreetMap in the four states becomes a theater at its real address (the inside is a
generated layout, not the real building's interior): ticket kiosks by the door, a concession counter with popcorn
machines and soda fountains, and auditoriums with stadium rows of recliners — one of them a **4D motion** room.

1. Right-click a **ticket kiosk** (or press K → Cinema). Each auditorium shows what's on. Pick a free one → **Choose
   film**: *Jellyfin* (sign in to your server; several servers are combined into one library), *Internet Archive*
   (search public-domain feature films), *Link* (a direct video URL) or *File* (a video file on your PC — drag it onto
   the window). Choose **2D** or the 3D layout of a 3D file (side-by-side or top/bottom).
2. A ticket costs 12 LC (3D 15, 4D 20 LifeCoins — the in-game currency). Others can join the same showing from the
   kiosk; everyone in the room sees the same moment of the film.
3. Walk in, right-click a recliner to sit (it reclines, the footrest comes out). Sneak to stand.
4. **3D**: put on the **3D glasses** you got with the ticket (head slot). The screen then shows a red/cyan anaglyph
   picture — wear real red/cyan glasses in front of your monitor to see it in depth.
5. **Concessions**: popcorn, large popcorn, nachos, hot dogs, pretzels, cola, lemon-lime and orange soda, slushies,
   water, chocolate, gummy bears, sour worms, licorice, candy mix — real food and drink items.
6. **4D** (open Oct 29 – Nov 3, server time zone): sit in a motion seat. The seat rumbles with the bass, kicks on loud
   hits and sways with fast motion on screen; wind, mist and flashes follow the film. With a **bHaptics TactSuit**
   paired in the **bHaptics Player** app on the same PC, the vest plays the same effects (K → Cinema → *Films & 4D
   settings* → *Test the TactSuit*). A local film can carry an authored effect track (`film.4d.json` next to
   `film.mp4`). Effect strength can be lowered or turned off.

EarthLife does not host, link to or distribute any films. Films are streamed and decoded on each player's own
computer from sources they choose (their own Jellyfin, public-domain films, or links/files they have the right to
play). In multiplayer the server only shares *which* film is playing; a Jellyfin film plays for the players who are
signed in to that server, a local file only for its owner.

## Map and terrain data

**Northeast map (new worlds)** — built by `.github/workflows/region-packs.yml` from the Geofabrik OpenStreetMap
extracts of New York, Pennsylvania, New Jersey and Vermont and the AWS Terrain Tiles, published as the GitHub release
`regions-v1` (index, 1/2° packs of 1/8° cells, places, major roads, cinemas):

- Every road, building (with height, levels, roof shape and colours when mapped), land-cover area, lake and river,
  railway and runway. © OpenStreetMap contributors, ODbL 1.0.
- Terrain per cell: cities every ~15 m, towns ~25 m, countryside ~45 m; areas with no map features ~90 m. The sea and
  Lakes Ontario and Erie come from the terrain's bathymetry/surface, so the coast, NY Harbor, Long Island Sound and the
  Great Lakes shores are real water at their real level.
- Projection: one coordinate system for all four states centred on Williamson (0, 0), azimuthal-equidistant, so
  distances and shapes stay within about 0.1% of the real ones everywhere in the four states.
- Bridges are not modelled yet: roads cross rivers and harbours on a causeway at water level.

**Western NY starter data** — shipped in the jar (`data/earthlife/regions/western-ny/`, built by `.github/workflows/starter-data.yml`):

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
gradle :fabric-mod:build              # -> fabric-mod/build/libs/earthlife-0.4.1.jar
gradle :core:runTests                 # engine tests (also: ./build.sh with only a JDK)
gradle :fabric-mod:runClientGameTest  # in-game tests in a real client (see STATUS.md)
```

GitHub Actions builds the jar and runs core and in-game tests on every push (`.github/workflows/mod-build.yml`); the jar is the **earthlife-mod**
artifact, the in-game test report and screenshots are pushed to the `ci-tests` branch.

## Licence

Code: MIT. Map data: ODbL (OpenStreetMap). Terrain: AWS Terrain Tiles (USGS 3DEP, NOAA, SRTM). The car, seats,
concession items and all other models and textures are original. EarthLife is not affiliated with Mojang or Microsoft,
Jellyfin, the Internet Archive, bHaptics or any cinema chain, and does not include or distribute Minecraft, FFmpeg or
any film. "4DX" is a trademark of CJ 4DPLEX; EarthLife's 4D room is its own effect system.
