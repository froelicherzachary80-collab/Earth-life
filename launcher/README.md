# EarthLife Launcher

## Microsoft sign-in setup (one time)

1. Go to https://entra.microsoft.com → App registrations → New registration.
   - Supported account types: **Personal Microsoft accounts only**.
   - No redirect URI needed. Under Authentication, set **Allow public client flows = Yes** (device code).
2. Copy the **Application (client) ID** into `EARTHLIFE_MSA_CLIENT_ID` or `launcher.json`:
   ```json
   { "msaClientId": "00000000-0000-0000-0000-000000000000", "gameVersion": "26.3", "memoryMb": 6144 }
   ```
3. Mojang only lets approved client IDs use the Minecraft services API. Until approval,
   `login_with_xbox` returns HTTP 403 and the launcher reports it. Apply through Mojang's
   third-party launcher access form (search "Minecraft API access request").

The launcher never asks for your password: you sign in on microsoft.com/link with the code it shows.
The refresh token is saved in `<EarthLife>/auth/refresh_token`; `logout` deletes it.

## What install does

1. Requires a verified Java Edition owner (entitlements check).
2. Downloads the version JSON from Mojang's manifest, the Fabric profile from meta.fabricmc.net,
   then the client jar, libraries, natives and assets: every file is SHA‑1 checked.
3. Installs Mojang's own Java runtime for that version.
4. Copies `earthlife-mod.jar` into `instances/earthlife/mods`.

Running install again is the repair tool: intact files are skipped, damaged ones re-downloaded.

## Windows .exe

```
gradle :launcher:installDist
jpackage --type exe --name EarthLife --input launcher/build/install/launcher/lib --main-jar launcher.jar --win-shortcut --win-menu
```
