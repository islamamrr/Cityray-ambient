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

## Tumbler (`tumbler`)

A safecracking game where the car's steering wheel is the dial of a safe. Turn the wheel, listen to the clicks get heavier as you near each secret number, and hold still on the heaviest click to slide the bolt. Each pin must be approached from the direction its arrow shows, and the next pin turns the other way.

- **Vaults:** an endless run of vaults. Later ones add more pins, a narrower sweet spot, decoy clicks that jam the lock, and a worn dial with slack when you change direction. Your total haul is saved.
- **Pass the wheel:** 2 to 4 players crack the same vault in turn; fastest wins.
- **Stethoscope:** every click is also drawn on screen, so it still works with the sound low.
- **Wheel input:** reads the steering angle (sensor 0x101000). A one-time calibration (straight, then half a turn left) measures its units and sign. Without a wheel signal, drag the dial with a finger. Arrow keys and media next / previous nudge the dial by one number.
- Park only: it pauses behind a "Shift to Park" screen when the car leaves P and steps aside on the shift. It only reads car data.

GitHub Actions uploads it as the **Tumbler-apk** artifact.
