# Cityray car notes

These notes cover what we know about reading car data on the 2026 Geely Cityray head unit (G426_J1, Android 11, build `ecarx-userdebug 11 RQ3A.211001.001`). The code in `carlib` carries the same labels.

| Label | Meaning |
| --- | --- |
| **CONFIRMED** | Measured in the car, and we checked what the value means |
| **OBSERVED** | Seen working in the car, but the meaning isn't pinned down yet |
| **GUESS** | Found in other apps or in ECARX interface names, not tested yet |
| **NOT_AVAILABLE** | Tested in the car and it doesn't work |

Last in-car test: 2026-10-31, parked, engine probably off.

## How apps reach the car

The head unit's system image ships the ECARX "AdaptAPI" (`com.ecarx.xui.adaptapi.*`). Any app can load it at runtime. It doesn't need root, a system signature, or `<uses-library>`. `carlib` finds it by reflection, so the same APK still runs on a phone without car data. **CONFIRMED**: Car Probe found it and connected.

- `Car.create(context)` returns `ICar`. From there, `getSensorManager()` gives `ISensor`, `getICarFunction()` gives `ICarFunction`, and `getCarInfoManager()` gives `ICarInfo`.
- `Input.create(context)` returns `Input`, which offers `requestKeysInterception(int[], IKeyCallback)` for steering-wheel keys.

## Sensors (`CarSensor`, `ISensor`)

| Sensor | Id | Label | What we saw |
| --- | --- | --- | --- |
| Gear | 0x200200 | CONFIRMED | Event. +0x40 P, +0x10 R, +0x20 N, +0x30 D |
| Ignition | 0x200100 | OBSERVED | Event. Read +7 the whole time; other states not mapped |
| Speed | 0x100100 | OBSERVED | Active, read 0 while parked |
| Speed (cluster) | 0x101A00 | OBSERVED | Active, read 0 while parked |
| RPM | 0x100900 | OBSERVED | Packed integer (see below), read 1 |
| Fuel level | 0x100600 | OBSERVED | Packed integer, about 25020 and falling. GUESS: millilitres |
| Odometer | 0x100700 | OBSERVED | Active but read 0, so recheck it |
| Steering angle | 0x101000 | OBSERVED | Active, read 0 |
| Steering angle speed | 0x101100 | OBSERVED | Active, read 0 |
| Engine state | 0x201600 | NOT_AVAILABLE | notavailable, and listener refused |
| Acceleration | 0x101700 | NOT_AVAILABLE | notavailable, and listener refused |

**Packed integers (OBSERVED):** RPM and fuel send an integer inside the float's bits, so they show up as tiny values like `1.4E-45`. `CarDecode.packedInt()` unpacks them.

Sensors push about 10 times a second even when nothing changes, so only act on real changes.

## Car functions (`CarFunction`, `ICarFunction`), all GUESS

These come from the button app (`com.aleksan.button`), which reads and sets them on Cityray cars through the gbinder helper. `carlib` only reads them, with `getFunctionValue(id, zone)`. Zones are 1 driver, 4 passenger, 16 rear left, 64 rear right, and 0x20000000 trunk.

Climate power 0x10010100, auto 0x10010200, max cooling 0x10010400, manual fan 0x10020100, auto fan 0x10020200, recirculation 0x10030100, windshield defrost 0x10040200, glass heating 0x10040300, seat ventilation 0x10050100, seat heating 0x10050200, set temperature 0x10060100, unknown 0x10070100, steering wheel heating 0x10090100, front window heating 0x10090800 / 0x10142300 / 0x10141B00 (the button app picks one by car type: "cityray", "official", "parallel import"), fragrance 0x100B0300, auto hold 0x20060400 (name confirmed by the ECARX interface), wipers 0x200C0100, trunk 0x21020100, cabin light 0x21051300, driving mode 0x22010100.

## Car info (`CarInfo`, `ICarInfo`), all GUESS

These come from the ECARX interface: fuel types 0x100100, vehicle types 0x100200, fuel tank capacity 0x200100 (float), EV battery capacity 0x200200 (float).

## Steering-wheel keys (`EcarxKeys`)

- Interception takes the key away from the head unit until `release()` is called. **OBSERVED**: the input API was found.
- Codes are in the 200000 range. GUESS: gbinder intercepts 200082 and 200164, and the button app special-cases 200003. No button-to-code map yet, because that test was postponed.

## GlyCar (`GlyCar`), GUESS

Geely's own car API is `com.geely.os.car.GlyCar` (one build calls it `com.geely.p003os.car.GlyCar`). It has `create(Context)`, `getIntProperty(id)`, `registerSingleCarEventCallback(callback, int[])` and the callback `onChangeEvent(...)`. The button app uses it for ignition (0x200100). The callback's argument layout is unknown, so Car Probe logs the raw arguments.

## Geely launcher app list (`GeelyAppStore`), GUESS

Geely's launcher reads the provider at `content://com.geely.appstore.AppstoreProvider/appInfo` (columns: `package_name`, `apk_name`, `apk_icon`, ...). It needs the `com.geely.appstore.APPSTORE_PROVIDER` permission. The gappstorage app seems to use it to show sideloaded apps in the launcher grid. `carlib` only reads it.

## Known but not in the code

- **Geely OneOS SDK** (`com.geely.lib.oneosapi`, including MediaCenter now-playing, KeyInputManager and AppStoreManager). gbinder bundles this whole client SDK (about 1,200 classes). It is not in the system image, so we can't call it without Geely's SDK. Car Ambient keeps using notification access for now-playing.
- **gbinder app intents** (`com.salat.gbinder.GET_INT_PROPERTY`, `LISTEN_PROPERTY_CHANGES`, ...). These only work when the gbinder app is installed. We don't need them, because we call the same API directly.
- **Indoor and outdoor temperature, turn signals.** The button app reads these, but we haven't extracted the ids.

## Installing apps on the car

- Islam installs with Kamel's in-car installer (`com.example.kamelauto`). It turns on network ADB from the head unit itself and installs APKs.
- GitHub Actions builds every APK. Download them from the run's Artifacts section.

## Safety rules

- Our apps read car data. They don't change car settings unless Islam decides otherwise.
- Don't install the platform-signed Settings app from Kamel's bundle, because it can leave the head unit stuck.
- Kamel's installer was flagged by Google. Install only the parts you need, and skip the stealth app-hider.
- None of the apps we learned from are open source. We use the API names (interoperability) and never copy their code.
- A Geely OTA update can move any of this. Re-run Car Probe after updates.
