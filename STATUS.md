# EarthLife 0.5.0 / ZMF Cinema v0.1 status

## ZMF Cinema v0.1 — what is verified

Evidence: GitHub Actions *Fabric mod build* runs on branch `zmf-cinema-v0.1`. The jar compiles against the real
Minecraft 26.3 + Fabric API (`ci-logs-zmf/RESULT`: `earthlife-0.5.0.jar`); 87 engine tests pass (24 new); the in-game
suite **Test 17 - ZMF Cinema** runs in a real 26.3 client on a CI machine (software rendering, no GPU), report in
`ci-tests-zmf/earthlife-test-report.json`, screenshots in `ci-tests-zmf/screenshots/`. Automated checks, not a human play
session. Latest ZMF run [40360611718](https://github.com/froelicherzachary80-collab/Earth-life/actions/runs/40360611718): build + in-game tests **success** (26 passed, 2 blocked by missing hardware/services). Full suite (Earth + ZMF) run [40355874657](https://github.com/froelicherzachary80-collab/Earth-life/actions/runs/40355874657): all EarthLife checks still pass except "Upper New York Bay (sea level)", which already fails on `main`.

| Requested acceptance test | Status | Evidence / limit |
|---|---|---|
| 1. Minecraft 26.3 starts with the mod | Passed | `earthlife 0.5.0` loaded in 26.3 (Test 2). |
| 2. Existing world loads without damage | Passed | Earth save/reload (Test 12) and ZMF world reload; MCEF absent or present. ZMF only writes `<world>/zmfcinema/`. |
| 3. 12-screen multiplex, all rooms physically exist | Passed | 12/12 rooms, 781/781 seat blocks, 12 open doors, 12 screen walls, allocation valid (2 combined rooms). |
| 4. 20-screen multiplex, valid random allocation | Passed | 20/20 rooms, two floors, allocation validator passed; arrangements differ between buildings. |
| 5. 32-screen multiplex, 32 unique accessible rooms | Passed | 32 unique ids, 2,986/2,986 seats, 32 doors; 5 2D / 10 3D / 10 4DX / 6 IMAX / 1 ScreenX. |
| 6. Locate a naturally generated cinema with the Locator | Passed | Natural site (20 screens) found from the seed grid, built, `/zmfcinema locate nearest` and the Locator HUD point to it; map with marker. Tested in a superflat world; terrain grading on hills/coasts is engine-tested, not screenshot-verified. |
| 7. Connect to a real Jellyfin server | **Not verified** | No Jellyfin server on CI. Login/token/stream code is the 0.4.1 code (which worked against real servers before) plus new endpoints (logout, test, libraries, transcoding, PlaybackInfo, progress, posters) that are **untested** until you try them at home. |
| 8. Different films assigned to rooms | Passed | Library folder shared (titles only), 6 films from 2000+ (1999 film excluded) distinct across 12 rooms, 6 *Coming Soon*; programs differ between multiplexes; kept after reload. |
| 9. Real web page on a Minecraft screen | Passed | MCEF Modern 0.3.x from Modrinth in the test instance; Chromium started, `https://example.com/` rendered on room A's screen. Clicking/typing in the control screen is not automated. |
| 10. Real red/cyan stereo from 2D frames | Passed | 2D test film in a 3D room: AUTO3D, mean disparity 2.8 px, 5.8 ms/frame. Engine tests check near objects get crossed disparity. Depth quality on real films judged by eye only. |
| 11. Detect a test-film event and move a 4DX seat | Passed | Synthetic clip (fireball + boom at 4.0 s): EXPLOSION detected at 4.04 s (confidence 0.75), seat pitch up to 5.2°. Known false positive: vivid purple/green colour bars also register as "magic". Real films not benchmarked. |
| 12. Automatic atmospheric effect | Passed | Explosion shockwave/smoke particles emitted (30 emissions) for the detected event. |
| 13. bHaptics connectivity | Partly | Protocol verified against a stand-in bHaptics Player (frames, device list, safety limit 50 respected, emergency stop). **No real TactSuit/Player tested.** |
| 14. Room + film assignments persist | Passed | Rooms, formats and the same film program after reloading the world. |
| 15. Not decoding all streams | Passed | 10 showings in a 32-screen multiplex: 0 films decoding in the hallway, exactly 1 inside a room. |

Also passed: ticket bought with emeralds and scanned at the door (turned away without one), showtimes menu, admin panel,
IMAX room playback, ScreenX side walls textured, 3D/4DX/TactSuit settings screens open.

**Not done / not verified in v0.1:** cinema staff NPCs; escalators/elevators (stairs only); seat-map selection; 4K PBR
texture tier (HD pack is 128 px; the generator can make larger); real Jellyfin and real TactSuit (above); camera roll on
all renderers; FPS benchmarks on real hardware (CI has no GPU — no FPS numbers are claimed); browser input automation;
H.264-only web video (MCEF limitation).

## EarthLife 0.4 implementation status (unchanged)

Validated against commit `f58b6fe` in [Fabric mod build run 40255905619](https://github.com/froelicherzachary80-collab/Earth-life/actions/runs/40255905619). Its build and client game test jobs concluded successfully. The older [Windows EXE run 40236123159](https://github.com/froelicherzachary80-collab/Earth-life/actions/runs/40236123159) tests a different, launcher-oriented commit; it is not evidence of a Fabric jar. The source and test report on `ci-tests` are the evidence for this version.

**Delivery issue fixed after that run:** the build workflow checked out an orphan `ci-logs` branch, deleting `fabric-mod/build/libs`, before uploading `earthlife-mod`. The run reported a successful compile and `ci-logs/RESULT` listed `earthlife-0.4.0.jar`, but its artifacts list contained only `earthlife-ingame-tests`. The workflow now verifies and uploads the jar before changing branches, with `if-no-files-found: error`. A new run must finish before a downloadable jar can be claimed.

## Test status

The [machine-readable in-game report](https://github.com/froelicherzachary80-collab/Earth-life/blob/ci-tests/earthlife-test-report.json) records these results on Minecraft 26.3 in a virtual-display CI client. These are automated checks, not an extended human play session.

| Requested test | Status | Evidence and limit |
|---|---|---|
| 1. Fabric compilation | Passed | CI `:fabric-mod:build`; jar listed in `ci-logs/RESULT`. Distribution artifact in that run was missing. |
| 2. Mod loading | Passed | Fabric loaded `earthlife 0.4.0`; five key mappings registered. |
| 3. Earth world type | Passed | Preset listed and Customize screen opened; settings written into generator. |
| 4. Earth world creation | Passed | World and generator loaded; 1024-block height and Williamson spawn checked. |
| 5. Geographic scale | Passed for samples | Williamson–Sodus distance within 0.262%; one OSM building footprint and one road segment checked. Worldwide accuracy untested. |
| 6. M map | Passed | Screen opened; report counted roads/buildings and checked zoom/layers. |
| 7. Player location | Passed | Moving 361 blocks changed latitude/longitude and map follow position. |
| 8. Tile loading | Passed for starter/offline path | 247 cached tiles, distant estimated fallback, offline startup and 64-chunk benchmark. Network refresh/corruption recovery untested. |
| 9. Survival | Passed for sampled actions | Mining, placing, crafting, hunger, caves/ores and mob list checked. |
| 10. Life systems | Passed for sampled actions | Dialogue, janitor work and wages, wallet ledger, vehicle ownership/summon and Halloween interaction. Other jobs/vehicles untested in game. |
| 11. Aviation | Passed for sampled flight | Terminal, boarding, taxi movement and arrival taxi phase checked. Full manual end-to-end passenger flow untested. |
| 12. Save/reload | Passed for sampled state | Balance, job, waypoint, vehicle ownership, scenario, objectives and placed block persisted. |
| 13. World separation | Passed | Vanilla Default world retained noise generator, no Earth services or map HUD. |
| 14. Dedicated server | Passed for smoke test | Server booted Earth world and client joined with world info. Long multiplayer session untested. |

## Scope and limitations

- The bundled detailed geographic coverage is the starter Western New York box; land outside it may use lower-detail elevation and explicitly estimated buildings. One block per meter is local projected scale; global flat-map distortion and coordinate limits remain.
- The test report does not prove every menu path, all nine jobs, all vehicle types, all flights, external live event feeds, every interior, or long-term performance. Those remain partially implemented or unverified, not completed claims.
- A generated private building interior is a gameplay layout, not a reconstruction of its real interior.
- `LifeCoins` are in-game currency only. Ordinary Minecraft and Fabric Loader/Fabric API are installed separately.

The next workflow run should show both `earthlife-mod` and `earthlife-ingame-tests` artifacts. Download the jar from `earthlife-mod`; the Actions artifact itself is a ZIP containing the real jar.
