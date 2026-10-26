# Changelog

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
