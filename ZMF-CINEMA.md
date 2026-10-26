# ZMF Cinema v0.1 — multiplex cinemas for every Minecraft world

ZMF Cinema is part of the EarthLife mod (since 0.5.0). In **ordinary Minecraft worlds** (Default, Large Biomes, Amplified,
Superflat...) 12- to 32-screen multiplex cinemas generate by themselves while you explore. Each one has a lobby with
ticket kiosks, concessions and restrooms, hallways, and real screening rooms — 2D, 3D, 4DX, IMAX, one ScreenX room and
four web cinemas — playing films from **your own Jellyfin servers or video folders**, converted to **red/cyan 3D**
automatically in 3D rooms, with **automatic virtual 4DX** effects and optional **bHaptics TactSuit** vibration.
Earth worlds keep their real-world (OpenStreetMap) theaters, which now also get the new 3D, 4DX and TactSuit features.

ZMF Cinema doesn't host, download or link to any film. Every film plays on each player's own computer from a source
they configured and have the right to play.

## Install (Windows 10/11)

1. Minecraft Java Edition **26.3** with **Fabric Loader** for 26.3 (https://fabricmc.net/use/installer/).
2. In `.minecraft/mods`:
   - `earthlife-0.5.0.jar` (from the *earthlife-mod* artifact of a successful *Fabric mod build* run, or build it: see below)
   - **Fabric API** for 26.3 (0.165.0+26.3 or newer) — https://modrinth.com/mod/fabric-api
   - optional, for the web cinemas: **MCEF Modern** for 26.3 (0.3.3 or newer) — https://modrinth.com/mod/mcef-modern.
     On first use it downloads its Chromium runtime (about 100 MB) into `.minecraft`.
3. **FFmpeg** decodes the films. ZMF finds it on your PATH, or press *B → Quality → FFmpeg: get it* (Windows: BtbN's LGPL
   build from GitHub).
4. optional: **bHaptics Player** running on the same PC with your TactSuit paired.

Build it yourself: `gradle :fabric-mod:build` (JDK 25, internet access to Mojang and Fabric) → `fabric-mod/build/libs/earthlife-0.5.0.jar`.

## Playing

| | |
|---|---|
| **Find a cinema** | Explore: with the default *COMMON* setting there is a candidate site about every 1,100 blocks (on dry, reasonably flat land, away from villages and other multiplexes); a multiplex is built when you come within ~180 blocks. Operators: `/zmfcinema locator` gives a **Cinema Locator** — hold it to see an arrow, the distance and the name of the nearest multiplex (sneak + use: a map with a marker). `/zmfcinema locate nearest` prints the same. |
| **Lobby** | Ticket kiosks by the doors and a staffed ticket counter, a concession counter (popcorn, sodas, candy), snack shelves, lounge sofas, restrooms, a showtime board above the hallway and a marquee on the facade. Big multiplexes have two floors with a grand staircase and balcony. |
| **Menu (key B)** | Showtimes of the multiplex you are in (now playing, pre-show countdowns, next shows, *Coming Soon*), tickets, snacks, your library, 3D/4DX/TactSuit settings, quality presets, diagnostics and — for operators or the singleplayer host — the admin panel. |
| **Tickets** | A ticket costs emeralds in survival (2D: 1, premium rooms: 2; configurable, 0 = free); creative players watch free. The door scans tickets: without one you are turned back at the door of a room that is screening. |
| **Watching** | Walk in, right-click a seat (recliners recline, footrests come out), sneak to stand. A film plays only for the room you are in. In 3D rooms, put on the in-game **3D glasses** (you get a pair with a 3D ticket) and wear real red/cyan glasses (red on the left eye). |
| **4DX rooms** | Sit in a motion seat. The film is analysed while it plays; explosions, impacts, thunder, jump scares, car chases, rain, ocean scenes, snow, fire, take-offs, space battles and magic move the seat and fill the room with virtual wind, mist, rain, snow, embers and coloured light. **END** = emergency stop (seat motion, effects and vest off at once). |
| **Web cinemas A–D** | With MCEF Modern installed: rooms *Web Cinema A* (2D), *B* (3D conversion), *C* (4DX from the picture) and *D* (IMAX). Inside the room press **B** to control the browser: click, scroll and type on the page, address bar, back/forward/reload, bookmarks (YouTube, Twitch, Google, Vimeo, Wikipedia), volume, fullscreen video, browser resolution, *Show to the room* (shares only the page address) and *Back to films*. |
| **Private showings** | In singleplayer (or for everyone/ops, configurable) *Choose film* in the showtimes menu starts any film of your library in any room right away. |

## Your film library

*B → Library*:

- **Jellyfin servers** — add any number of servers (address, user, password; the password only goes to your server and
  the access token stays in `config/earthlife-cinema.json` on your PC, never in the world or in packets). Per server:
  *Test* (name, version, response time), *Libraries* (choose which libraries the cinemas use), *Sign out*.
  Streaming: **direct play** (original file, decoded by FFmpeg on your PC — 4K if your PC can decode it) or **server
  transcoding** (H.264/AAC up to the size you pick; needed for burned-in subtitles). Audio language and subtitle
  language preferences. Watch progress is reported back to Jellyfin (resume points), switchable.
- **Folders** — folders with your own video files. Title and year come from the file name (`Some Film (2012).mkv`);
  `SBS`/`HSBS`/`TAB` in the name marks a 3D file.
- **Share titles for the schedules** (on by default; shared when you join and with *Share now*): only title, year, genre,
  length, 3D layout and a reference go to the world. Multiplex programs are drawn from films **released 2000 or later**
  (`minYear`), distinct within a multiplex, different between multiplexes, rotating daily, kept across restarts. Fewer
  films than rooms → the remaining rooms show *Coming Soon*. Nothing is ever invented.
- Who sees a film: a Jellyfin film plays for every player signed in to that same server; a file plays only on the
  computer it is on. On a dedicated server only operators share titles unless `anyoneCanShareLibrary` is on.

## Room formats

Every multiplex has a constraint-checked mix: 2D 3–5 rooms, 3D 3–10, 4DX 5–10, IMAX 1–6, **exactly one ScreenX**, and four
web cinemas among those rooms. When the minimum counts exceed the room count, rooms combine formats (4DX 3D, IMAX 3D,
4DX IMAX, ScreenX 3D) — a 12-screen multiplex always has at least one combined room. 32 screens = 5 + 10 + 10 + 6 + 1.
Every building gets its own arrangement, style (brick, glass front, modern concrete, futuristic glass, vintage
Hollywood, shopping mall, urban), name, room sizes, ceiling heights, seat colours and lobby layout.

- **IMAX**: biggest rooms (two floors high in large multiplexes), curved screen, stadium seating, speaker arrays.
- **ScreenX**: front screen plus both side walls. Ordinary films have no side footage, so the walls show an
  *atmospheric extension* of the picture's own edges (mirrored, blurred and fading outwards). It carries the scene's
  colour and motion into your side vision; it does not reconstruct scenery that was never filmed.
- **3D**: real 3D files (side-by-side / top-and-bottom) keep their own depth. Ordinary 2D films are converted:
  depth estimated per frame (ground plane, sharpness, motion, colour and sky cues, smoothed over time and reset at
  scene cuts) → left/right views by depth-image-based rendering → Dubois red/cyan encoding (less ghosting than plain
  channel splitting). Controls (*B → 3D*): depth strength, screen plane, pop-out, screen depth, ghosting reduction,
  comfort mode, swap eyes, side-by-side comparison with 2D, performance mode. Anaglyph adds depth to a flat screen;
  colours are partly lost and it can't look holographic. The built-in depth estimator is rule based, not a neural
  network — it's convincing for landscapes and close-ups, weaker for complex overlapping scenes.

## Settings

- `config/zmf-cinema.json` (server/world rules): `rarity` VERY_COMMON (~700 blocks) | COMMON (~1,100) | UNCOMMON (~1,800)
  | RARE (~3,000) | OFF, `naturalGeneration`, `naturalInEarthWorlds`, `ticketEmeralds`, `premiumEmeralds`, `snackEmeralds`,
  `freeInCreative`, `ticketsRequired`, `privateShowings` everyone | singleplayer | ops, `minYear`, `dailyRotation`,
  `anyoneCanShareLibrary`, `buildBlocksPerTick`, `buildDistance`.
- `config/earthlife-cinema.json` (your PC): Jellyfin servers and libraries, folders, streaming, languages, quality
  preset, 3D, 4DX, TactSuit (intensity, safety limit), browser.
- Quality presets (*B → Quality*): LITE (360p, light depth map, few particles), BALANCED (480p), HIGH (720p),
  ULTRA (1080p, finest depth map, two films at once). Only the room you are in decodes a film; a 32-screen multiplex never
  decodes 32 films.
- Resource pack **ZMF Cinema HD (+ PBR maps)** (Options → Resource Packs): 128 px cinema textures with LabPBR normal and
  specular maps for shader packs that read them. `tools/assets/zmf_assets.py --hd-size 1024` (up to 4096) generates
  bigger ones; packs that large make the jar huge for little visible gain on block-sized furniture, so they aren't
  shipped.

## Commands

`/zmfcinema` (menu) · `locate nearest` · `ticket <room>` · `snack <item>` · `room <room> browser <url>` / `room <room> jellyfin` · `info`
Operators / singleplayer host: `generate small|large|mega|<12-32> [style]` · `teleport nearest` · `tp <id>` · `admin` · `reschedule <id>` · `locator`

## bHaptics TactSuit

Through the official bHaptics Player's local API (Java has no bHaptics SDK; the Player's WebSocket API on port 15881 is
the supported route for other languages). *B → 4DX & Suit*: on/off, intensity, **safety limit** (no motor above it;
a duty-cycle limiter halves strength after long continuous buzzing), test patterns (front, back, explosion, heartbeat),
**emergency stop** (also key END), automatic reconnection every 15 s, connected-device detection. Patterns: explosions
(directional), impacts, thunder, heartbeat for scares, engine for chases, turbulence for flights, waves, magic sweep,
rain, shockwave. The vest only vibrates — it can't make wind, water, heat or motion.

## Known limits (v0.1)

- **Web cinemas** need MCEF Modern. Its Chromium has no H.264/AAC decoders: YouTube (VP9/AV1) plays, some sites that
  only offer H.264 video don't. DRM-protected streaming services can't be played or captured. Chromium plays web audio
  itself, so web 4DX works from the picture only, and web pages aren't frame-synchronised between players.
- 4DX recognition is rule based (picture + sound cues with confidence thresholds and cooldowns). It catches clear,
  loud, visual moments; it will miss subtle ones and occasionally misfire.
- Seat roll (sideways tilt of your view) uses an optional camera hook that may not apply on every renderer.
- Cinema staff NPCs, escalators/elevators and a full ticket-seat map are not in v0.1 (the lobby has stairs; you pick any
  free seat).
- Settings screens use buttons instead of sliders.
- See STATUS.md for exactly what the automated in-game tests verified.
