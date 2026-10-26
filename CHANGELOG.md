# Changelog

## 0.4.1 — Northeast 1:1, cars with doors, movie theaters (2026-10-26)

### Added
- **1:1 New York, Pennsylvania, New Jersey and Vermont** (new worlds; *Map data* in Customize):
  - `region-packs` GitHub workflow: Geofabrik OSM state extracts → 1/8° cells (roads with names, buildings with
    height/levels/roof/colours, land cover, water with islands, POIs) + AWS Terrain Tiles per cell with a sea / Lake
    Ontario / Lake Erie mask → 1/2° zip packs, index with SHA-256, places, state-wide major roads and cinemas, published
    as the `regions-v1` release.
  - Detail tiers per cell: CITY (big cities; ~15 m terrain, 2.5 m sidewalks, street trees), TOWN (~25 m terrain,
    1.5 m sidewalks), RURAL (~45 m), TERRAIN (no map features, ~90 m).
  - In the game: packs download on demand (prefetched around players, cached in `.minecraft/earthlife/regions/`,
    shared by client and integrated server), cells load into an LRU, tiles are generated from them; sea and Great Lakes
    fill to their real surface (no more "everything below 74 m floods" in Northeast worlds).
  - One Williamson-centred azimuthal-equidistant projection for all four states (scale within ~0.1%).
  - LifeMap/GPS on the Northeast map: places of all four states in search, major roads when zoomed out, streets and
    buildings per area, long routes over the major-road network joined to local streets.
  - New start towns: NYC, Buffalo, Syracuse, Albany, Ithaca, Lake Placid, Philadelphia, Pittsburgh, Harrisburg, Erie,
    Scranton, Newark, Jersey City, Atlantic City, Princeton, Burlington, Montpelier.
- **Cars**: original sedan, taxi and crew-cab pickup models drawn by the client — body, glass, head/tail/brake/reverse
  lights, interior (seats, dashboard, steering wheel, centre console), wheels that roll and steer — with **four hinged
  doors** that swing open when you get in (nearest door; the owner takes the driver's seat) and when you get out (you
  step out beside the door). Seats sit you inside the cabin at a real eye height.
- **In-car GPS**: heading-up moving map on the dashboard screen (route, car arrow, next turn, distance, speed) and a
  turn-by-turn banner (street names, remaining distance and time), chimes before turns, automatic re-routing.
- **Movie theaters** at every OpenStreetMap cinema in the four states (Northeast worlds build each one when its map
  area loads; starter worlds get those within 70 km): glass entrance, ticket kiosks, concession counter, corridor,
  stadium auditoriums with recliners, one 4D motion auditorium; the big screen is an entity drawn by the client.
- **Cinema player** (client): FFmpeg decodes the film to the screen texture, sound plays through Minecraft's OpenAL
  device and is the film's clock; sources: Jellyfin (sign-in, several servers combined), Internet Archive public-domain
  feature films (search), direct links, local files. Showings are shared: everyone in the room sees the same moment.
- **3D**: side-by-side / top-and-bottom 3D films become red/cyan anaglyph while you wear the **3D glasses** item
  (camera overlay with thin lens rims), 2D otherwise.
- **4D motion auditorium** open **Oct 29 – Nov 3**: motion seats; seat rumble, kicks, sway (view motion), wind, mist and
  flashes derived from the film's sound and picture, or from an authored `film.4d.json`; **bHaptics TactSuit** frames
  through the local bHaptics Player.
- Blocks and items (original models and pixel art): cinema recliner, 4D motion seat, popcorn machine, soda fountain,
  ticket kiosk; popcorn, large popcorn, nachos, hot dog, soft pretzel, cola, lemon-lime soda, orange soda, blue
  raspberry slushie, bottled water, chocolate bar, gummy bears, sour gummy worms, licorice twists, candy mix (all
  edible / drinkable); 3D glasses; movie tickets. Creative tab "EarthLife Cinema".
- Menu tab **Cinema** (showtimes, concessions, route to the nearest theater) and *Films & 4D settings* (FFmpeg,
  Jellyfin, picture quality, volume, 4D strength, TactSuit test). Commands: `/earthlife cinema ...`.

### Changed
- Tiles store a water surface per column (cache format ELT3, generator version 4: cached tiles are regenerated).
- Roads crossing water run on a causeway just above the water instead of at the bottom.
- CI: the jar is uploaded before the logs branch is published; version-independent jar check; FFmpeg and synthetic
  test films for the in-game cinema checks; car, cinema and region-pack checks added.

### Known limits
- Bridges and tunnels aren't modelled (causeways at water level). Building interiors are gameplay layouts.
- Films need FFmpeg on the player's computer. Jellyfin films play for players signed in to that server; local files only
  for their owner. The bHaptics integration uses the Player's local WebSocket API and has not been tested with a
  physical suit in CI.
- Pack downloads happen while you explore; a slow connection can pause chunk generation in a new area.

## 0.4.0 — "First Playable Life" (2026-10-25)

First version that is an actual, compiled Fabric mod (`earthlife-0.4.0.jar`) running in Minecraft Java 26.3.

### Added
- **Earth — 1:1 Real World** world type in Create New World, with a **Customize** screen: start country/state/town,
  quality, buildings/roads/real terrain/transport/simulation toggles, start time, scenario, day length. Settings are
  saved per world (inside the overworld generator in `level.dat`); Default and other world types are unaffected.
- **Western NY starter dataset** in the jar: OpenStreetMap roads/buildings/land use/water (26k buildings, 11.5k roads)
  and 20 m / 60 m terrain grids from AWS Terrain Tiles, with Lake Ontario turned into a real lake. Built reproducibly by
  the *Starter dataset* GitHub workflow (`tools/fetch_osm.py`, `osm_to_earthlife.py`, `terrain_tiles.py`).
- New vertical datum (Y 63 = 74 m, Lake Ontario) and a 1,024-block-tall overworld dimension type mirrored from the
  26.3 vanilla overworld. Ores are placed by the generator; vanilla villages/decoration are off in Earth worlds.
- Safe spawn in the start town (dry, open, solid ground, not on a building) and world spawn set there.
- **LifeMap (M)**: tile-rendered map from the same data as the world; arrow, XYZ, lat/lon, elevation, 9 zoom levels,
  layers, search (towns/streets/places/waypoints), waypoints, home/work, road routing, residents, events, opt-in
  location sharing for multiplayer, scale bar and attribution. `/earthlife map` opens it.
- **EarthLife menu (K)** with Life, Jobs (J), Wallet (L), Nearby (N), Events, Travel, Vehicles and Help tabs.
  Keybinds in an *EarthLife* controls category.
- **Resident dialogue** with choices; **scenarios** (New Life, Career Starter, Traveler, Normal Survival) with guided
  objectives and rewards; welcome card on first join; HUD with LifeCoins, clock, job, place, event and objectives.
- **Halloween**: costumed residents, trick-or-treat (candy), costume and pumpkin-carving contests, Events tab.
- Commands: help, map, location, coordinates, town, jobs, vehicles, time, data status, world info, version,
  waypoint, home/work set, objectives, event costume/carving, book … auto; operator-only admin reload, region status,
  debug, teleport, halloween, pay.
- Automated **in-game test suite** (Fabric client game tests) run on GitHub Actions in a real 26.3 client, with a
  JSON report and screenshots; 9 new engine tests (57 total).

### Changed
- Ported every Minecraft call to the 26.3 API (Identifier, world clocks, PermissionSet, GuiGraphicsExtractor,
  KeyMappingHelper, HUD element registry, SDL key codes, ColorCollection blocks, EntityTypes, new ChunkGenerator).
- Player and resident state (scenario, objectives, waypoints, candy, contests) saved per world in
  `earthlife/players/<uuid>.json`, written atomically.
- Per-world managers (flights, residents, vehicles, events) are recreated for each world, so nothing leaks between
  worlds in one session. Creative/spectator are no longer forced back to Survival.
- EarthLife packets are only sent to clients that have the mod.

### Removed
- The custom launcher, Microsoft sign-in code and Windows `.exe` packaging (out of scope: EarthLife is installed into a
  normal Fabric setup and uses your normal Minecraft launcher).

### Fixed
- A permission check on the `/earthlife time` commands threw while Minecraft built the command list, which kicked
  the player from **every** singleplayer world on join (found by the in-game tests).

## 0.3.0 and earlier
Engine, data pipeline and (uncompiled) Fabric code for buildings, landmarks, real-time events, airports, airliners,
jobs, residents, vehicles and the time system; a custom launcher. See git history.
