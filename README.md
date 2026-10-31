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

# Guest Book

A guest book for the car (folder `guestbook/`). Passengers write their name, sign the screen with a finger, and can leave a note for the driver. Every signature is kept on a wall, newest first.

- **Sign:** tap *Sign the guest book*. Pick a pen colour, use Undo or Clear, then *Sign the book*.
- **Read:** tap any page on the wall to open it full size, then swipe sideways to leaf through the book.
- **Delete:** open the page and tap *Delete* twice.
- **Back up:** *Save all to gallery* (or *Save picture* on one page) writes PNG pages to `Pictures/Guest Book`, which you can copy off with the file explorer.
- **Car readings:** each signature is stamped with whatever the car shares through the standard Android car API (odometer, outside temperature, gear, model). The small line at the bottom of the wall shows which readings worked (✓) or were refused (✗). New sources (such as the Cityray's own signal service) plug in under `guestbook/.../car/`.

Install `GuestBook.apk` from the **GuestBook-apk** artifact of the latest Actions run.
