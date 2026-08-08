# Orbit — Command Cheat Sheet

## 🧰 One-time setup (Windows)

Nothing to install for the packaged app — the MSI ships with ffmpeg and
yt-dlp built in, and the dev build downloads them automatically on first
launch. Manual install is only a fallback:

```powershell
winget install --id Gyan.FFmpeg        # required for desktop playback
winget install --id yt-dlp.yt-dlp      # fallback player path (recommended)
```
> Reopen the app/terminal after installing (PATH refresh). Orbit also looks in
> winget's Links folder and its own tools folder, not just PATH. To force a
> specific binary use the env vars `ORBIT_FFMPEG` / `ORBIT_YTDLP`.

## 🔑 Secrets (required once per machine)

Copy `secrets.properties.example` to `secrets.properties` (repo root) and fill
in the Firebase/Google values. The file is gitignored; builds generate code
from it. Android additionally needs `app/google-services.json` (also
gitignored) and `local.properties` with the `RELEASE_*` signing entries.

## 🖥️ Desktop app

```powershell
.\gradlew.bat :desktop:run             # run the desktop app
.\gradlew.bat :desktop:packageMsi      # build the Windows installer (.msi)
.\gradlew.bat :desktop:build           # compile/check only
```
> `packageMsi` runs `:desktop:fetchTools` first, which downloads ffmpeg+yt-dlp
> (~200 MB, cached) into `desktop/resources/` so the installer is fully
> self-contained.

## 📱 Android app

```powershell
.\gradlew.bat :app:assembleDebug       # debug APK
.\gradlew.bat :app:assembleRelease     # signed release APK -> app/build/outputs/apk/release/
.\gradlew.bat :app:installDebug        # install on the connected phone
```
> Release signing passwords live in `local.properties`
> (RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD).

## 🧱 Build housekeeping

```powershell
.\gradlew.bat clean                    # wipe build outputs
.\gradlew.bat :core:build              # compile only the shared core
```
- On a machine without the Android SDK (CI/cloud): set `SKIP_ANDROID=1` to
  build only :core/:desktop.

## 🚀 Publishing a release (the in-app updater reads GitHub releases)

```powershell
.\gradlew.bat :app:assembleRelease :desktop:packageMsi
git add -A
git commit -m "vX.Y.Z"
git tag vX.Y.Z
git push origin main --tags
# then create the GitHub release for that tag and attach
# app-release.apk + Orbit-X.Y.Z.msi as assets
# (repo: UpdateRepository.kt -> REPO constant)
```

## ⌨️ Desktop keyboard shortcuts

| Key | Action |
|---|---|
| `Space` | Play / Pause |
| `← / →` | Seek 10s back / forward |
| `Ctrl + ← / →` | Previous / Next track |
| `↑ / ↓` | Volume |
| `8` | 8D on/off |
| `/` | Jump to Search |
| `Ctrl + D` | Download current song |
| `Ctrl + Q` | Toggle queue panel |

(The system tray icon also offers play/pause/next, even while minimized.)

## 🩺 Troubleshooting

| Problem | Fix |
|---|---|
| Desktop won't play a song | Read the error in the bottom bar + check the log: `%TEMP%\orbit-desktop.log` |
| "ffmpeg not found" | Settings → Playback tools → "Download missing tools", or the winget command above |
| Direct stream returns 403 | Install yt-dlp — the player falls back to it automatically |
| yt-dlp warns about a "JS runtime" | `winget install DenoLand.Deno` (optional) |
| Minified release APK crashes | Set `isMinifyEnabled` + `isShrinkResources` to `false` in `app/build.gradle.kts` |
| Wrong/broken ffmpeg picked up (e.g. Anaconda's) | Orbit validates candidates and skips broken ones; to force a path set `ORBIT_FFMPEG` |
