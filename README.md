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

## Spark Duel (`sparkduel`)

A reflex game for 2 to 4 players sharing the screen. Each player owns a colored pad along the bottom. Every round brings a new rule for when to hit your pad; get it right first to score, tap at the wrong moment and you lose a point. First to 3, 5 or 7 wins.

Rounds rotate through five rules:

- **Gold Rush:** colors flicker; hit your pad the instant the core turns gold.
- **Your Color:** the core cycles through the players' colors; hit your pad only on yours.
- **Only GO:** words flash in random colors; hit your pad only on the word GO.
- **Count:** the core flashes on an uneven beat; hit your pad on flash number N, not before or after.
- **Blind Ring:** a ring closes in on the core and vanishes partway; everyone gets one tap, and the closest to the moment it would touch wins.

The shared `gamekit` module holds the full-screen game activity and drawing helpers. GitHub Actions uploads the APK as the **SparkDuel-apk** artifact.

```
adb install -r SparkDuel-debug.apk
```
