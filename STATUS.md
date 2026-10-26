# EarthLife 0.4 implementation status

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
