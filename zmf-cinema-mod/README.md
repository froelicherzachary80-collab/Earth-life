# ZMF Cinema 0.1.0 — standalone Fabric mod for Minecraft Java 26.3

Multiplex cinemas for ordinary Minecraft worlds, **without EarthLife**. Everything from ZMF Cinema v0.1 (see
[../ZMF-CINEMA.md](../ZMF-CINEMA.md)) plus the **ZMF Cinema Pass**.

## The ZMF Cinema Pass

- Every player gets one the **first time they join a world** (survival or creative, singleplayer or server).
- **Use it (right-click)** → you're taken to the doors of the **nearest multiplex** (up to 3,000 blocks away, from the
  natural cinema sites of the world seed). If none stands there yet, it is built for you and you arrive when the doors
  open (a few seconds). If there's no suitable land nearby, a new multiplex is opened 150–500 blocks away.
- **Sneak + use** → back to where you were.
- While you hold it, the top of the screen shows an arrow, the distance and the name of the nearest multiplex.
- Works from the Nether (coordinates ×8) and the End (from the overworld spawn).
- Lost it? `/zmfcinema pass` gives a new one. `/zmfcinema go` and `/zmfcinema back` do the same as the pass.

## Install

`.minecraft/mods`: `zmfcinema-0.1.0.jar` + **Fabric API** for 26.3; optional **MCEF Modern** (web cinemas). FFmpeg plays
the films (the menu offers to download it on Windows). Don't install it together with EarthLife 0.5.0, which already
contains ZMF Cinema.

Build: `gradle :zmf-cinema-mod:build` → `zmf-cinema-mod/build/libs/zmfcinema-0.1.0.jar`. CI: *ZMF Cinema mod build*
workflow (artifact **zmf-cinema-mod**).

## Keys and commands

**B** showtimes / menu (inside a web cinema: browser) · **END** 4DX + TactSuit emergency stop.
`/zmfcinema` · `go` · `back` · `pass` · `locate nearest` · `ticket <room>` · `snack <item>` · `film pick|start|join|sit|pause|resume|stop|seek ...` ·
operators / singleplayer host: `generate small|large|mega|<12-32> [style]`, `teleport nearest`, `tp <id>`, `admin`, `reschedule <id>`, `locator`.

Settings: `config/zmf-cinema.json` (`giveStarterPass`, `passRange`, rarity, ticket prices...) and
`config/zmfcinema-client.json` (Jellyfin, folders, 3D, 4DX, TactSuit, quality). World data: `<world>/zmfcinema/`.
