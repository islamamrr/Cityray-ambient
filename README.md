# Car Ambient

A full-screen ambient clock for the Geely Cityray's vertical 13" screen, with a now-playing card.

- **Themes:** Minimal, Gauge, Aurora. Swipe left or right to switch.
- **Dim:** double-tap anywhere outside the music controls.
- **Now playing:** shows title, artist and art from whichever app is playing, with previous / play-pause / next.
- **Park aware:** on the Cityray, shifting out of Park sends the app to the background, so the car's normal screen comes back.
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

## Car data (`carlib`) and Car Probe (`probe`)

`carlib` is a small shared library for reading car data on the Cityray head unit. It uses the ECARX car API that ships in the head unit's system image, and looks it up at runtime, so apps still run (without car data) on a phone.

- `ParkWatcher` reports the current gear (P, R, N, D). Gear values are confirmed in the car.
- `EcarxCar` reads sensors: ignition, gear, engine state, speed, RPM, fuel, odometer, steering angle. It's read-only.
- `EcarxKeys` listens to steering-wheel keys. While it holds a key, the head unit skips that key's normal action, so always call `release()`.

See [CAR_NOTES.md](CAR_NOTES.md) for every hook we know and how sure we are about each one.

`Car Probe` is a debug app built on `carlib`. It shows every sensor's raw value live, logs each change, and can listen to the wheel buttons for 20 seconds. We use it to learn what each raw value means (for example, which gear number is P) before building features on top. GitHub Actions uploads it as the **CarProbe-apk** artifact.
