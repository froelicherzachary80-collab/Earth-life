# EARTHLIFE — Reality Simulator (V0.3 "Real World")

A launcher + Fabric mod that turns your own copy of Minecraft: Java Edition into a life simulator on a
1 block = 1 metre voxel Earth, starting in Williamson, NY, with LifeCoins and working airliners.
No Minecraft files are included; the launcher downloads them from Mojang after verifying you own the game.

**Read STATUS.md first** — it says exactly what is tested, what is written but uncompiled, and what is missing.

## Layout

```
core/         Engine-independent Java 21 (no deps): geo projection, tiles, streaming, ledger, rewards, flight sim, booking
launcher/     EarthLife launcher (Java 21, no deps): MS sign-in, install/repair, Java runtimes, launch, mods, saves, Swing UI
fabric-mod/   Minecraft side: VoxelEarth chunk generator, airliner entity, flights, commands, seatback + wallet screens
tools/        Data pipeline (Python): OSM → features, OurAirports → airports, USGS DEM → elevation grid
data/         airports.json (approximate fallback for KSDC/KROC until you import OurAirports)
```

## 1. Build & test core + launcher (any OS, JDK 21+)

```bash
./build.sh            # compiles, runs all tests, writes dist/earthlife-launcher.jar
```

## 1b. Windows: build EarthLife.exe

**On your PC** (Windows 10/11, JDK 21 from https://adoptium.net): double-click `packaging\windows\build-exe.bat`.
You get `dist\windows\EarthLife\EarthLife.exe` (portable, Java bundled) and a zip of it. If WiX Toolset 3.14 is installed
(`winget install WiXToolset.WiXToolset`), you also get `EarthLife-0.3.0.exe`, an installer with Start-menu and desktop shortcuts.

**Or on GitHub**: push the repo; the *Windows EXE* workflow (`.github/workflows/windows-exe.yml`) builds both on a Windows
runner and attaches them as the `EarthLife-windows` artifact (and to a release when you push a `v*` tag).

Put `earthlife-mod.jar` in the repo root before building and it's bundled next to the launcher.

## 2. Generate real Western NY data (on your PC — needs internet)

```bash
# Roads, buildings, landuse, water, railways, airports, town/village names for Rochester → Wolcott → Canandaigua.
# If Overpass times out, split the bbox into 2-4 pieces (e.g. --bbox 42.85,-77.75,43.30,-77.27 and ...,-77.27,...,-76.80).
python3 tools/fetch_osm.py -o raw/western-ny.osm.json
python3 tools/osm_to_earthlife.py raw/western-ny*.osm.json --origin 43.2237,-77.1861 \
        -o regions/western-ny/features.json --towns-out regions/western-ny/towns.json --landmarks-out regions/western-ny/osm-landmarks.json
# Snap the hand-entered landmarks to their real OSM positions/sizes (marks them verified)
python3 tools/match_landmarks.py data/landmarks.json raw/western-ny*.osm.json --origin 43.2237,-77.1861 -o data/landmarks.json
# Elevation (optional but recommended): USGS 3DEP 1/3" tiles n44w078 + n44w077 (+ n43w078/n43w077 for the south),
# merged into one GeoTIFF (e.g. gdal_merge.py). 10 m cells keep the grid ~250 MB for the whole region.
pip install rasterio numpy
python3 tools/dem_to_grid.py western-ny.tif --origin 43.2237,-77.1861 --extent 45000 --cell 10 -o regions/western-ny/dem.eldg
# Real runway coordinates
curl -LO https://davidmegginson.github.io/ourairports-data/airports.csv
curl -LO https://davidmegginson.github.io/ourairports-data/runways.csv
python3 tools/import_ourairports.py airports.csv runways.csv --bbox 42.85,-78.25,43.30,-76.80 -o data/airports.json
```

Copy `regions/western-ny/features.json` (and `dem.eldg`) to `<EarthLife>/instances/earthlife/config/earthlife/regions/western-ny/`,
and `towns.json`, `data/airports.json` and `landmarks.json` (merge in `osm-landmarks.json` entries you want) to `.../config/earthlife/`. Every airport in that file with a runway of 900 m or more gets a generated terminal.
(`<EarthLife>` is `%APPDATA%\EarthLife` on Windows.)


## 3. Build the Fabric mod (needs Gradle + internet)

1. Look up the Loom, Fabric Loader and Fabric API versions for 26.3 at https://fabricmc.net/develop and put them in `fabric-mod/gradle.properties`.
2. `gradle :fabric-mod:build` → `fabric-mod/build/libs/earthlife-mod-0.3.0.jar`
3. Fix any API mismatches (start with `McCompat.java`, `earthlife.accesswidener`, `AirplaneEntity`'s save/hurt methods).
4. Copy the jar next to the launcher as `earthlife-mod.jar`.

## 4. Sign-in (no setup needed)

EarthLife uses the **official Minecraft Launcher** for sign-in. Press **Sign in & Play** or **PLAY** in EarthLife. It adds an
"EarthLife" installation (Fabric + the EarthLife mod, its own game folder) to the official launcher and opens it.
Sign in there with your Microsoft account if asked, choose **EarthLife** next to the Play button, and press Play.
Your existing installations are kept; a backup of `launcher_profiles.json` is saved alongside it.
Requires the Minecraft Launcher (minecraft.net/download or the Xbox app). (Advanced: `login-direct` still supports your own Azure app id.)

## 5. Play

`EarthLife.exe` → **Day length** (optional) → **PLAY** → the Minecraft Launcher opens → select **EarthLife** → Play.
First time: Create New World → World Type **EarthLife (Voxel Earth)**, name it `EarthLife Williamson`.

| Do | How |
|---|---|
| Wallet, tickets, job & time buttons | `[L]` |
| Seatback map on a flight | `[M]` |
| Change day length | `/earthlife time realtime` · `/earthlife time speed 120` (1 day = 2 h) · `/earthlife time vanilla` |
| Fly | `/earthlife flights` → `/earthlife book EL101-0 3C` → walk down the jet bridge at gate A1 when boarding opens |
| In the cabin | WASD walk · Jump at your row to sit/stand · walk out the door at the gate |
| Work | `/earthlife job list` → `/earthlife job apply janitor Williamson` (or `checkin_agent KROC`) → `/earthlife job clockin` during your shift |
| Licences | `/earthlife license driver` (driving test) · `/earthlife license pilot` (after 3 flights, 1,500 LC) |
| Vehicles | `/earthlife vehicle shop` → `buy car red` → `summon car` → right-click; W/S/A/D, Space = handbrake, Sneak = exit |
| Bus | Bus 1 Williamson ↔ Sodus ↔ Ontario ↔ Webster ↔ Rochester, 5 LC; right-click to board |
| Talk | Right-click any resident; friendships build over days |
| Sell crops | hold them and `/earthlife sell` |
| Towns / landmarks | `/earthlife towns` · `/earthlife landmarks` |
| Real events | `/earthlife events` (today + 7 days, real local times) · `/earthlife events buy 3` · `/earthlife events status` |

**Live events setup** (launcher "Live events" button, or CLI):
`EarthLife.exe events ticketmaster <your free key>` (developer.ticketmaster.com) · `events ics https://…/calendar.ics` ·
`events teams "Rochester Red Wings,Syracuse Mets"` · `events mlb off`. Holidays and Red Wings games work with no setup.

## Data licences

Map data © OpenStreetMap contributors (ODbL 1.0) — attribution is carried in each region file and shown in-game
(`/earthlife whereami`, F3). OurAirports and USGS 3DEP are public domain. LifeCoins are fictional and have no cash value.
Event data: MLB Stats API (© MLB Advanced Media, personal non-commercial use), Ticketmaster Discovery API (their terms
and attribution apply, your own key), and any iCal feeds you add. In-game concert music uses vanilla Minecraft discs; no real recordings are played.
