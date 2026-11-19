package dev.carapps.car

/**
 * Sensor ids used by the head unit's ECARX car API (com.ecarx.xui.adaptapi.car.sensor.ISensor).
 *
 * This is the short, curated list: ids we have tested or that apps rely on. For every id
 * ECARX names (about 150 sensors), see [EcarxCatalog].
 *
 * The ids come from the interface constants that apps on this head unit compile against.
 * [confidence] and [note] record what Car Probe showed in the car on 2026-10-31
 * (parked, engine probably off). [CarDecode] turns raw values into meaning.
 */
enum class CarSensor(
    val id: Int,
    val label: String,
    val unit: String = "",
    val confidence: Confidence,
    val note: String,
) {
    // Labels from Car Probe in the car: 2026-11-08 (parked) and the 0.7 full scan on
    // 2026-11-10 (driving in D at about 16 km/h, ignition DRIVING, at night).
    IGNITION(0x200100, "Ignition state", confidence = Confidence.OBSERVED,
        note = "Event sensor. Seen +3, +5, +7 = OFF, ON, DRIVING (ECARX names); +7 while driving. See CarDecode.ignition."),
    GEAR(0x200200, "Gear", confidence = Confidence.CONFIRMED,
        note = "Event sensor. +0x30 P, +0x40 R, +0x10 N, +0x20 D (confirmed 2026-11-08; +0x20 while driving)."),
    ENGINE_STATE(0x201600, "Engine state", confidence = Confidence.NOT_AVAILABLE,
        note = "Reported notavailable and registerListener refused it."),
    SPEED(0x100100, "Speed", "km/h", Confidence.OBSERVED,
        note = "Read 15.8 while driving, next to 16.7 from the cluster sensor. A plain float in km/h."),
    SPEED_FROM_CLUSTER(0x101A00, "Speed (cluster)", "km/h", Confidence.OBSERVED,
        note = "Read 16.7 while driving, a little above SPEED like a real speedometer. The button app uses this one."),
    ACCELERATION(0x101700, "Acceleration", confidence = Confidence.NOT_AVAILABLE,
        note = "Reported notavailable and registerListener refused it."),
    RPM(0x100900, "RPM", "rpm", Confidence.NOT_AVAILABLE,
        note = "Says active but stays at packed 1, even while driving (2026-11-10). No RPM on this car."),
    FUEL_LEVEL(0x100600, "Fuel level", "mL", Confidence.OBSERVED,
        note = "Packed integer. 16061 when the tank read 28% (= 17.4 L by the button app's formula), so millilitres fits. See CarDecode.fuelLitres."),
    ODOMETER(0x100700, "Odometer", "km", Confidence.NOT_AVAILABLE,
        note = "Says active but stays 0.0, even while driving (2026-11-10)."),
    STEERING_ANGLE(0x101000, "Steering angle", "deg", Confidence.NOT_AVAILABLE,
        note = "Says active but stays 0 while turning and driving (2026-11-08, 2026-11-10). The sensor groups didn't move either."),
    STEERING_ANGLE_SPEED(0x101100, "Steering angle speed", confidence = Confidence.NOT_AVAILABLE,
        note = "Says active but stays 0."),
    FUEL_LEVEL_ALT(0x404500, "Fuel level %", "%", Confidence.OBSERVED,
        note = "IVirtualSensor FUEL_PERCENTAGE. Read 28.0. See CarDecode.fuelLitresFromPercent."),
    INDOOR_TEMP(0x100C00, "Indoor temperature", "C", Confidence.OBSERVED, note = "Plain float. Read 18.7."),
    OUTDOOR_TEMP(0x100B00, "Outdoor temperature", "C", Confidence.OBSERVED, note = "Plain float. Read 17.5."),
    RANGE(0x100800, "Range (km left)", "km", Confidence.OBSERVED,
        note = "ECARX ENDURANCE_MILEAGE. Read 195.0, same as ENDURANCE_MILEAGE_FUEL 0x101800."),
    DAY_NIGHT(0x201000, "Day / night", confidence = Confidence.OBSERVED,
        note = "Event: 0x201001 day, 0x201002 night. Read night (scan was after dark)."),
    PASSENGER_SEAT_OCCUPIED(0x203400, "Passenger seat occupied", confidence = Confidence.OBSERVED,
        note = "Active, event 0x203301. Meaning of the values not checked."),
    DRIVER_SEATBELT(0x201200, "Driver seatbelt", confidence = Confidence.OBSERVED,
        note = "Active, event 0x201202 while driving (probably buckled). Passenger 0x201300 read 0x201201."),
    AMBIENT_LIGHT(0x200F00, "Light sensor", confidence = Confidence.OBSERVED, note = "Active, event 0x200F02 at night."),
    AVG_FUEL_CONSUMPTION(0x400100, "Average fuel consumption", "L/100km x10", Confidence.OBSERVED,
        note = "IVirtualSensor TYPE_AVG_FUEL_CONSUMPTION. Read 84.0, probably 8.4 L/100km. Check against the cluster."),
    TIRE_PRESSURE_FL(0x500100, "Tire pressure front left", "psi", Confidence.OBSERVED,
        note = "Read 29.9. The car's tire-pressure unit setting (0x25010400) is PSI."),
    TIRE_PRESSURE_FR(0x500200, "Tire pressure front right", "psi", Confidence.OBSERVED, note = "Read 29.7."),
    TIRE_PRESSURE_RL(0x500300, "Tire pressure rear left", "psi", Confidence.OBSERVED, note = "Read 30.1."),
    TIRE_PRESSURE_RR(0x500400, "Tire pressure rear right", "psi", Confidence.OBSERVED, note = "Read 29.5."),
    TIRE_TEMP_FL(0x500500, "Tire temperature front left", confidence = Confidence.OBSERVED,
        note = "Read 70 at 17.5 C outside. GUESS: raw value with a +50 offset, so 20 C. Check on the cluster."),
    TIRE_TEMP_FR(0x500600, "Tire temperature front right", confidence = Confidence.OBSERVED, note = "Read 71."),
    TIRE_TEMP_RL(0x500700, "Tire temperature rear left", confidence = Confidence.OBSERVED, note = "Read 69."),
    TIRE_TEMP_RR(0x500800, "Tire temperature rear right", confidence = Confidence.OBSERVED, note = "Read 68."),
    MAINTENANCE_DISTANCE(0x403000, "Distance to service", "km", Confidence.OBSERVED,
        note = "IVirtualSensor TYPE_MAINTENANCE_MILEAGE. Read 3609. GUESS: km until the next service."),
    MAINTENANCE_TIME(0x403100, "Time to service", confidence = Confidence.OBSERVED,
        note = "IVirtualSensor TYPE_MAINTENANCE_TIME. Read 6744; unit unknown."),

    // NOT_AVAILABLE in the 2026-11-10 scan (notavailable while driving).
    HANDBRAKE(0x200300, "Handbrake", confidence = Confidence.NOT_AVAILABLE, note = "notavailable in the scan."),
    COOLANT_TEMP(0x100F00, "Coolant temperature", "C", Confidence.NOT_AVAILABLE, note = "notavailable in the scan."),
    ACCELERATOR(0x101400, "Accelerator pedal", "%", Confidence.NOT_AVAILABLE, note = "notavailable in the scan."),
    BRAKE(0x101300, "Brake pedal", "%", Confidence.NOT_AVAILABLE, note = "notavailable in the scan."),
    START_STOP(0x201700, "Engine start/stop", confidence = Confidence.NOT_AVAILABLE, note = "notavailable in the scan.");

    companion object {
        fun byId(id: Int): CarSensor? = entries.firstOrNull { it.id == id }

        /** Sensors worth subscribing to on this car. */
        val usable: List<CarSensor> get() = entries.filter { it.confidence != Confidence.NOT_AVAILABLE }
    }
}
