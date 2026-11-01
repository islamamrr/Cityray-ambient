# Car Ambient

A full-screen ambient clock for the Geely Cityray's vertical 13" screen, with a now-playing card.

- **Themes:** Minimal, Gauge, Aurora. Swipe left or right to switch.
- **Dim:** double-tap anywhere outside the music controls.
- **Now playing:** shows title, artist and art from whichever app is playing, with previous / play-pause / next.
- Keeps the screen on, hides the system bars, and nudges the layout a few pixels each minute to avoid burn-in.

## Build

GitHub Actions builds the APK on every push. Download it from the run's **Artifacts** section.

## Install

```
adb install -r CarAmbient-debug.apk
```

## Music access

Showing what's playing needs notification access. If the car's settings screen hides that option, grant it over ADB:

```
adb shell cmd notification allow_listener dev.carapps.ambient/dev.carapps.ambient.MediaListenerService
```

---

# Car Player

A full-screen remote for whatever music app is playing (built around Spotify). It lives in the `player/` folder and builds as its own APK (`CarPlayer-apk` in the run's Artifacts).

- **Now playing:** big cover, title, artist, seek bar, previous / play-pause / next. Colours come from the cover. Swipe the cover to skip, tap it to play or pause.
- **App buttons:** extra buttons the music app publishes itself (for Spotify: like, shuffle, repeat), drawn with the app's own icons. Only shown if the app sends them.
- **Up next:** the app's queue. The tab only appears when the app shares its queue.
- **Sources:** apps that currently have music loaded. Tap the app name at the top to get there.
- **Play from cold:** with nothing playing, "Resume music" sends a play key to the last music app.
- **Driving mode:** when the gear leaves P (read through `carlib`), the player hides the tabs and seek bar, makes the buttons bigger and stops the background moving.
- **Updates:** builds from `main` are published as the `player-latest` release. The app checks it on launch, and Sources shows "Update available" with one-tap install.

It needs notification access too. If settings hide it:

```
adb shell cmd notification allow_listener dev.carapps.player/dev.carapps.player.MediaListenerService
```

## Car data (`carlib`) and Car Probe (`probe`)

`carlib` is a small shared library for reading car data on the Cityray head unit. It uses the ECARX car API that ships in the head unit's system image, and looks it up at runtime, so apps still run (without car data) on a phone.

- `EcarxCar` reads sensors: ignition, gear, engine state, speed, RPM, fuel, odometer, steering angle. It's read-only.
- `EcarxKeys` listens to steering-wheel keys. While it holds a key, the head unit skips that key's normal action, so always call `release()`.

`Car Probe` is a debug app built on `carlib`. It shows every sensor's raw value live, logs each change, and can listen to the wheel buttons for 20 seconds. We use it to learn what each raw value means (for example, which gear number is P) before building features on top. GitHub Actions uploads it as the **CarProbe-apk** artifact.
