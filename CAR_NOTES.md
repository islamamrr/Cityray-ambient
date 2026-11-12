# Cityray car notes

These notes cover what we know about reading car data on the 2026 Geely Cityray head unit (G426_J1, Android 11, build `ecarx-userdebug 11 RQ3A.211001.001`). The code in `carlib` carries the same labels.

| Label | Meaning |
| --- | --- |
| **CONFIRMED** | Measured in the car, and we checked what the value means |
| **OBSERVED** | Seen working in the car, but the meaning isn't pinned down yet |
| **GUESS** | Found in other apps or in ECARX interface names, not tested yet |
| **NOT_AVAILABLE** | Tested in the car and it doesn't work |

Last in-car test: 2026-11-10, full scan while driving (Probe 0.7). Before that, 2026-11-08 (gear, keys, ignition). Earlier: 2026-10-31, parked, engine probably off. Last full APK pass: 2026-11-10 (button app 2.7.2 and 2.7.4, gbinder 4.3.1, gappstorage 1.0, BackFree 1.3.0).

## Library map (`carlib`, package `dev.carapps.car`)

| Class | What it does |
| --- | --- |
| `EcarxCar` | Main route. Sensors, car functions, car info, the W4M sensor group, and `scan()` over the whole catalog |
| `EcarxCatalog` | Every id the ECARX AdaptAPI names: ~150 sensors, ~850 functions, ~50 info items, 6 sensor groups, 342 key codes, with value names. From `assets/ecarx_catalog.json` |
| `CarSensor`, `CarFunction`, `CarInfo` | The short, curated lists: ids we tested or that apps rely on, each with a confidence label |
| `CarDecode` | Raw value to meaning: gear, ignition, packed integers, fuel, turn signals |
| `EcarxKeys` | Steering-wheel keys |
| `ParkWatcher` | "Is the car in Park?" for apps |
| `GlyCar` | Geely's own car API, a second route (ignition) |
| `AndroidCar` | Android's standard car API, a third route (gear, speed, steering, turn signals) |
| `AutolinkCarProxy`, `VendorSignal` | Autolink's car proxy service and vendor signal ids, for turn signals |
| `GeelyAppStore` | Geely launcher's app list |

Use the curated enums in apps. Use the catalog to name things in debug tools and to find new ids: Car Probe 0.7's **Scan everything** asks the car about every catalog id and lists the ones it supports. The catalog's source and rebuild steps are in `tools/catalog/README.md`.

## How apps reach the car

The head unit's system image ships the ECARX "AdaptAPI" (`com.ecarx.xui.adaptapi.*`). Any app can load it at runtime. It doesn't need root, a system signature, or `<uses-library>`. `carlib` finds it by reflection, so the same APK still runs on a phone without car data. **CONFIRMED**: Car Probe found it and connected.

- `Car.create(context)` returns `ICar`. From there, `getSensorManager()` gives `ISensor`, `getICarFunction()` gives `ICarFunction`, and `getCarInfoManager()` gives `ICarInfo`.
- `Input.create(context)` returns `Input`, which offers `requestKeysInterception(int[], IKeyCallback)` for steering-wheel keys.

## Sensors (`CarSensor`, `ISensor`)

| Sensor | Id | Label | What we saw |
| --- | --- | --- | --- |
| Gear | 0x200200 | CONFIRMED | Event. +0x30 P, +0x40 R, +0x10 N, +0x20 D (re-checked 2026-11-08; the 10-31 map was off by one) |
| Ignition | 0x200100 | OBSERVED | Event. Seen +3, +5, +7 (OFF, ON, DRIVING); +7 while driving. See below |
| Speed | 0x100100 | OBSERVED | Plain float, km/h. 15.8 while driving |
| Speed (cluster) | 0x101A00 | OBSERVED | Plain float, km/h. 16.7 at the same moment, a little higher like a speedometer |
| Fuel level | 0x100600 | OBSERVED | Packed integer in millilitres: 16061 at 28 % |
| Fuel level % | 0x404500 | OBSERVED | Plain float, 28.0 |
| Range | 0x100800 | OBSERVED | 195.0 km (same as 0x101800, fuel range) |
| Outdoor / indoor temp | 0x100B00 / 0x100C00 | OBSERVED | 17.5 / 18.7 °C |
| Average fuel consumption | 0x400100 | OBSERVED | 84.0, probably 8.4 L/100 km |
| Tire pressures | 0x500100–0x500400 | OBSERVED | 29.5–30.1 psi (the car's unit setting is PSI) |
| Tire temperatures | 0x500500–0x500800 | OBSERVED | 68–71. GUESS: raw − 50, so about 20 °C |
| Distance / time to service | 0x403000 / 0x403100 | OBSERVED | 3609 (km?) / 6744 (unit unknown) |
| Day / night, light sensor | 0x201000 / 0x200F00 | OBSERVED | Night / 0x200F02 after dark |
| Seatbelts, seat occupancy | 0x201200, 0x201300, 0x203300, 0x203400 | OBSERVED | Active; value meanings not checked |
| RPM | 0x100900 | NOT_AVAILABLE | Says active, but stays at packed 1 while driving |
| Odometer | 0x100700 | NOT_AVAILABLE | Says active, but stays 0 while driving |
| Steering angle (+ speed) | 0x101000 / 0x101100 | NOT_AVAILABLE | Says active, but stays 0 while turning and driving. The sensor groups don't move either |
| Engine state, acceleration | 0x201600 / 0x101700 | NOT_AVAILABLE | notavailable |
| Handbrake, coolant, pedals, start/stop | 0x200300, 0x100F00, 0x101300/0x101400, 0x201700 | NOT_AVAILABLE | notavailable in the 2026-11-10 scan |

## The 2026-11-10 full scan (Car Probe 0.7, driving in D at about 16 km/h, at night)

- **Sensors:** 91 active, 60 not available. Besides the table above, active ones include the ABS/ESC/brake warning lamps, all 4 parking-sensor warnings, PM2.5 and air quality, CO2 level, car mode (NORMAL), alarm state, the fuel/oil/coolant warning flags, and the whole tire system (pressures, temperatures, warnings, sensor batteries).
- **Car functions:** 347 active, 502 not available. Useful ones to read: drive mode `DM_FUNC_DRIVE_MODE_SELECT` 0x22010100 (read COMFORT), AC on/off 0x10010300, recirculation 0x10030100, door lock 0x21020200, dipped beam 0x21050100, fog lamps 0x21050400, units 0x250101xx (km/h, °C, L/100 km, PSI, 24 h), trip computer average speed 0x24800602 (28 km/h) and travel time 0x24800603 (1259, probably minutes), service interval 0x24500A00 (10000 km) and 0x24500B00 (12 months), ambient-light colours 0x2A070200/0x2A070300, seat count 0x2D410F00 (5), and the custom steering-wheel key 0x21110100 (set to driving mode).
- **Car info:** 21 active. 6 speakers, left-hand drive, top speed 235, fuel tank capacity reads 0.0 (so keep the 51 L constant).
- `SETTING_FUNC_LAMP_LR_TRAFFIC_LIGHT` 0x2B020100 read RIGHT. The button app treats it as an indicator, but it is more likely the headlight setting for right-hand traffic.
- The raw scan is not stored in the repo; this summary is.

**Ignition states (names from ECARX, values seen in the car):** 0x200100 + 1 UNDEFINED, +2 LOCK, +3 OFF, +4 ACC, +5 ON, +6 START, +7 DRIVING. Islam saw +3, +5 and +7, which fits off, on, and engine running. `CarDecode.ignition()` returns these names; `Ignition.isOn` is true for ON, START and DRIVING.

**Fuel (GUESS):** the button app turns 0x404500 into litres for the Cityray with `4.4 + percent × 0.466`, capped at the 51 L tank (`CarDecode.fuelLitresFromPercent`). Its other presets: Preface `4 + p × 0.45` (49 L), Atlas `4 + p × 0.5` (54 L).

**Indoor temperature:** the button app drops readings outside −40…70 °C. All other ids are in the catalog; Probe's **Scan everything** shows which answer.

**Steering angle:** not available on this car through ECARX (see table). No app we decompiled reads it either, by sensor or by the W4M group. So Car Probe's group lines and Tumbler's Android `PERF_STEERING_ANGLE` are the only tests we have. **Correction:** the W4M group is 0x800400 (`SENSOR_GROUP_TYPE_W4M` = 8389632). Probe 0.6 asked for 0x800500, which is the brake-pressure group, so its W4M line proves nothing either way. Probe 0.7 reads all six groups: gyro 0x800100, acceleration 0x800200, wheel pulses 0x800300, W4M 0x800400, brake pressure 0x800500, shaft torque 0x800600.

**Packed integers (OBSERVED):** RPM and fuel send an integer inside the float's bits, so they show up as tiny values like `1.4E-45`. `CarDecode.packedInt()` unpacks them.

Sensors push about 10 times a second even when nothing changes, so only act on real changes.

## Car functions (`CarFunction`, `ICarFunction`), all GUESS

These come from the button app (`com.aleksan.button`), which reads and sets them on Cityray cars. `carlib` only reads them, with `getFunctionValue(id, zone)`. Zones are 1 driver, 4 passenger, 16 rear left, 64 rear right, and 0x20000000 trunk. gbinder calls the zone-less form with zone `Integer.MIN_VALUE`.

Function ids encode the car module in the top byte: 0x10 HVAC, 0x20 settings, 0x21 body (BCM), 0x22 drive mode, 0x23 parking (PAS), 0x24 hybrid, 0x25 units, 0x26 wireless charger, 0x27 HUD, 0x28 ADAS, 0x29 day mode / brightness, 0x2A ambient light, 0x2B lamps, 0x2C safety, 0x2D seats, 0x2E audio, 0x2F scenes. Common values: 0 off, 1 on, 2 default, 253 error, 254 none, 255 unknown.

Climate power 0x10010100, auto 0x10010200, max cooling 0x10010400, manual fan 0x10020100, auto fan 0x10020200, recirculation 0x10030100, windshield defrost 0x10040200, glass heating 0x10040300, seat ventilation 0x10050100, seat heating 0x10050200, set temperature 0x10060100, unknown 0x10070100, steering wheel heating 0x10090100, front window heating 0x10090800 / 0x10142300 / 0x10141B00 (the button app picks one by car type: "cityray", "official", "parallel import"), fragrance 0x100B0300, auto hold 0x20060400 (name confirmed by the ECARX interface), wipers 0x200C0100, trunk 0x21020100, cabin light 0x21051300, driving mode 0x22010100.

## Car info (`CarInfo`, `ICarInfo`), all GUESS

These come from the ECARX interface: fuel types 0x100100, vehicle types 0x100200, fuel tank capacity 0x200100 (float), EV battery capacity 0x200200 (float). The catalog has about 50 more (driver side, drive modes, car colour, tire config, ...) and 16 `CONFIG_INFO_*` items (360 camera, DVR, sunroof, ...), read with `getCarInfoConfig`.

## Steering-wheel keys (`EcarxKeys`)

- Interception takes the key away from the head unit until `release()` is called. **OBSERVED**: the input API was found.
- Callback: `IKeyCallback.onKeyPressed(int code)` / `onKeyReleased(int code)` (and `onKeyCanceled`). Returning true consumes the key.
- **CONFIRMED 2026-11-08:** 200024 / 200025 volume up/down, 200164 mute, 200231 voice/phone, 200087 / 200088 next/previous, 200082 menu, 200400 mode. No other wheel button sent a code to the head unit.
- **Open:** with 300 codes requested (Probe 0.4) the keys still did their normal jobs. The button app requests only ~13 codes; Probe 0.5 tests whether a short list lets us consume them.
- Other named codes (ECARX `KeyCode` table, via gbinder): 200003 home, 200004 back, 200085 play/pause, knobs 110007–110010. All 342 are in the catalog; `EcarxKeys.name(code, catalog)` falls back to them. ECARX calls 200400 `KEYCODE_R_WECHAT`; on the Cityray it is the Mode button.
- The button app 2.7.4 requests exactly these 13 codes: 3, 10, 200003, 200024, 200025, 200087, 200088, 200085, 200164, 200231, 200082, 200400, 119.

## GlyCar (`GlyCar`), GUESS

Geely's own car API is `com.geely.os.car.GlyCar` (one build calls it `com.geely.p003os.car.GlyCar`). It has `create(Context)`, `getIntProperty(id)`, `addRateSingleCarEventCallback(callback, rate, int[])` (button app passes rate 3), the older `registerSingleCarEventCallback(callback, int[])`, `unregisterSingleCarEventCallback(callback)` and `disconnect()`. The callback is `onChangeEvent(id, ?, value)`: the button app reads the id from argument 0 and the value from argument 2, and ignores `Integer.MIN_VALUE`. It uses this only for ignition (0x200100, same values as the ECARX sensor). `GlyCar.listenValues()` does the same.

## Raw CAN signals (`EcarxSignals`), route CONFIRMED

Below the sensor API sits ECARX's `car_signal` service (`ecarx.car.hardware.signal.CarSignalManager`). It exposes the individual CAN signals by name. A sensor that is dead in the sensor API can still have a live raw signal here, and this is the layer the car's own apps (camera, climate) use.

- **How to reach it:** the object from `getSensorManager()` is an `AbsCarSignal`; `getECarXCar()` then `getCarManager("car_signal")` gives the manager. gbinder gets the same manager through `ECarXCarProxy`. `EcarxSignals.connect()` tries both.
- **Ids:** static fields named `SignalId_*` on the manager class, so the list comes from the car's own firmware. `getSignalValue(id)` reads one. The service only keeps values for subscribed signals, so `registerCallback(callback, SignalFilter)` first.
- **Names other open-source Geely apps use:** `VehSpdLgtA` speed, `EngNSafeEngN` RPM, `ExtrLtgStsTurnIndrLe` / `ExtrLtgStsTurnIndrRi` indicators, `ExtrLtgStsHiBeam` / `LoBeam`, `VehBattUSysU` 12 V battery, `SteerWhlBtnPsd` wheel buttons. Sources: DezzK/status-widget, 1456581280/jilicarplay, Salat39/GInputBridge (gbinder's own source) on GitHub. We use the API facts, not their code.
- **Why this matters for steering and indicators:** the car opens the side camera when an indicator is on at low speed, so the head unit does receive the indicator. The camera's guide lines suggest it receives steering angle too.
- **Result, 2026-11-11 (Probe 0.9, parked):** connected through the sensor manager, 2953 named signals, subscribe accepted. **Indicators found (OBSERVED):** `ExtrLtgStsTurnIndrLe` / `ExtrLtgStsTurnIndrRi` go 1/0 with each blink of the lamp, `IndcrSts` is the steady state (0 off, 1 left, 2 right; `CarDecode.turnState`), `ExtrLtgStsStopLi` is the brake light. Android's car service listed 2100 properties but nearly all were unreadable and none changed.
- **Steering, open:** `SteerWhlSnsrAg` and `SteerWhlSnsrAgSpd` exist, and the sensor's message arrives (`SteerWhlSnsrChks` 221, `SteerWhlSnsrCntr` 6, `SteerWhlSnsrQf` 3), but the angle read came back empty. Same for `VehSpdLgtA`, `WhlSpdCircuml*`, `EngNSafeEngN`, `AgDataRawSafeYawRate`. Likely cause: these are float signals and the manager has one typed getter per signal (`float getAmbTRawAmbTVal()` is one gbinder calls), so `getSignalValue(int)` is the wrong call for them. `EcarxSignals.read(name)` calls the typed getter. Probe 0.10's **Steering watch** tests exactly this on a short list and prints the error text of anything that fails.
- **Probe 0.10 result, 2026-11-12 (parked, wheel turned):** the reason is now CONFIRMED: `getSignalValue` throws "Invalid property type. Expected Integer, but was Float" for float signals. The manager also has `isFloatSignal(int)` and `getFloatSignalValue(int)` (`EcarxSignals.number(id)` uses them). The typed getters work but every float signal read 0.00, steering angle included, while the wheel turned. The steering and speed message counters (`SteerWhlSnsrCntr` 2, `VehSpdLgtCntr` 10) never changed across 29 reads while the indicator signals updated live, so the head unit was holding a stale copy of those messages, not a live feed. Open question: does that change with the engine running or while moving? Probe 0.11 tests `getFloatSignalValue` and watches for 60 s so it can run during a short drive.
- **Probe 0.11 result, 2026-11-12 (engine on, short drive, wheel turned):** **speed is live (OBSERVED):** `VehSpdLgtA` rose 0 to about 1.67 while creeping forward, read the same by the typed getter and `getFloatSignalValue`, and the callback fired 314 times. Probably m/s (1.6 m/s is about 6 km/h), GUESS. Its counter `VehSpdLgtCntr` stayed at 10 throughout, so the Cntr/Chks signals are not a freshness sign on this car; the "stale" idea above was wrong. Still 0.00 while moving and steering: `SteerWhlSnsrAg`, `SteerWhlSnsrAgSpd`, `SteerActReGroupAgAct`, all four `WhlSpdCircuml*`, `AgDataRawSafeYawRate`, `EngNSafeEngN`. So those signals are named in the firmware but the head unit is probably not being sent them. Some `SignalId_` fields are unknown to the service ("Invalid property Id", e.g. `VehSpdIndcd`), and one of those in a subscribe list makes it refuse the whole list; `EcarxSignals.usable()` filters them out.
- **Islam, 2026-11-12:** the reverse camera's guide lines DO bend with the wheel. So the steering angle reaches something that draws them: either the head unit (then some process on it reads the angle, and its APK shows how) or a separate camera module that mixes the lines into the video. Probe 0.13's **Find camera apps** lists the camera / parking / vehicle apps on the head unit and **Pack and share their APKs** zips them for static study (never run).
- Probe 0.12's raw hunt reads all known signals with the float-aware `number()` for 90 s and ranks the decimal signals that moved by range, to catch a steering angle stored under another name.
- Car Probe 0.9's **Raw signal hunt** watches every named signal, plus every property Android's car service lists, for 30 seconds and reports what changed.

## Turn signals

**Solved through raw signals, see above.** The routes below are the ones tried before; none of them moved on the Cityray.

The button app hunts for the indicators in several places, in this order, because no single one works on every Geely:

1. Android `TURN_SIGNAL_STATE` 0x11400408 and `HAZARD_LIGHTS_STATE` 0x11400E03 (AOSP: 0 none, 1 right, 2 left).
2. Vendor body-module switches 0x21401664 (left) and 0x21401665 (right), plus `BCM_FUNC_LIGHT_HAZARD_FLASHERS` 0x21050F00, read as a sensor event, then as a car function. Values: 0 off, other on, 255 unknown.
3. Autolink's car proxy service (`com.autolink.carproxyservice/.CarProxyService`, binder interface `com.autolink.adapterbinder.ICarProxyService`, transaction 5 = get int property, 3 = subscribe), with ids 0x21407951 / 0x21407952 (left / right) and 0x21407944 (flash).
4. Cluster chime requests 0x21401A19 / 0x21401A1A.

`VendorSignal`, `AutolinkCarProxy` and `CarDecode.turnSignalAosp` / `lampSwitch` cover 1 to 3. Car Probe 0.7 showed all of them, and none moved when Islam used the indicators (2026-11-10). Probe 0.8's **Find what changes** listens to every catalog sensor and function for 30 seconds and lists what moved, which is the next way to hunt for them. If nothing moves, the indicators probably don't reach the head unit through any app-readable route.

## Other routes the apps use

- **ECARX CarSignalManager** (`ecarx.car.hardware.signal.CarSignalManager`): raw CAN-style signals with getters such as `getAmbTEstimdAmbTEstimd()` and a callback for every signal change. gbinder registers a catch-all callback but only logs it. Not in `carlib` yet.
- **Geely media center** (`com.geely.mediacenterservice`, bound with action `android.intent.action.GEELY_MEDIACENTER_SERVICE`; interfaces `com.geely.lib.oneosapi.mediacenter.IMediaCenter` / `IMusicManager` / `IRadioManager`). The button app talks to it with raw binder calls for media control. A lead for a now-playing route that doesn't need notification access.

## Geely launcher app list (`GeelyAppStore`), GUESS

Geely's launcher reads the provider at `content://com.geely.appstore.AppstoreProvider/appInfo` (columns: `package_name`, `apk_name`, `apk_icon`, ...). It needs the `com.geely.appstore.APPSTORE_PROVIDER` permission. The gappstorage app seems to use it to show sideloaded apps in the launcher grid. `carlib` only reads it.

## Known but not in the code

- **Geely OneOS SDK** (`com.geely.lib.oneosapi`, including MediaCenter now-playing, KeyInputManager and AppStoreManager). gbinder bundles this whole client SDK (about 1,200 classes). It is not in the system image, so we can't call it without Geely's SDK. Car Ambient keeps using notification access for now-playing.
- **gbinder app intents** (`com.salat.gbinder.GET_INT_PROPERTY`, `LISTEN_PROPERTY_CHANGES`, ...). These only work when the gbinder app is installed. We don't need them, because we call the same API directly.
- **gappstorage** and **BackFree** have nothing car-specific beyond the launcher provider above (`appInfo` and `recentPlay`).

## Installing apps on the car

- Islam installs with Kamel's in-car installer (`com.example.kamelauto`). It turns on network ADB from the head unit itself and installs APKs.
- GitHub Actions builds every APK. Download them from the run's Artifacts section.

## Safety rules

- Our apps read car data. They don't change car settings unless Islam decides otherwise.
- Don't install the platform-signed Settings app from Kamel's bundle, because it can leave the head unit stuck.
- Kamel's installer was flagged by Google. Install only the parts you need, and skip the stealth app-hider.
- None of the apps we learned from are open source. We use the API names (interoperability) and never copy their code.
- A Geely OTA update can move any of this. Re-run Car Probe after updates.
