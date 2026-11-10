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
    IGNITION(0x200100, "Ignition state", confidence = Confidence.OBSERVED,
        note = "Event sensor. Seen +3, +5, +7 = OFF, ON, DRIVING (ECARX names). See CarDecode.ignition."),
    GEAR(0x200200, "Gear", confidence = Confidence.CONFIRMED,
        note = "Event sensor. +0x30 P, +0x40 R, +0x10 N, +0x20 D (confirmed 2026-11-08)."),
    ENGINE_STATE(0x201600, "Engine state", confidence = Confidence.NOT_AVAILABLE,
        note = "Reported notavailable and registerListener refused it."),
    SPEED(0x100100, "Speed", "km/h", Confidence.OBSERVED,
        note = "Active, read 0 while parked. Unit is a guess until we see it move."),
    SPEED_FROM_CLUSTER(0x101A00, "Speed (cluster)", "km/h", Confidence.OBSERVED,
        note = "Active, read 0 while parked. Probably the speed shown on the instrument cluster."),
    ACCELERATION(0x101700, "Acceleration", confidence = Confidence.NOT_AVAILABLE,
        note = "Reported notavailable and registerListener refused it."),
    RPM(0x100900, "RPM", "rpm", Confidence.OBSERVED,
        note = "Sends an integer packed in the float (read 1, likely engine off). See CarDecode.packedInt."),
    FUEL_LEVEL(0x100600, "Fuel level", confidence = Confidence.OBSERVED,
        note = "Packed integer, about 25020 and slowly falling. Guess: millilitres. See CarDecode.fuelLitres."),
    ODOMETER(0x100700, "Odometer", "km", Confidence.OBSERVED,
        note = "Active but read 0. Maybe needs the engine on, or it's packed too. Recheck."),
    STEERING_ANGLE(0x101000, "Steering angle", "deg", Confidence.OBSERVED,
        note = "Active, read 0 with the wheel straight. Recheck while turning."),
    STEERING_ANGLE_SPEED(0x101100, "Steering angle speed", confidence = Confidence.OBSERVED,
        note = "Active, read 0."),

    // GUESS: ids from ECARX's ISensor table and the button app, not tried in the car yet.
    FUEL_LEVEL_ALT(0x404500, "Fuel level %", "%", Confidence.GUESS,
        note = "IVirtualSensor FUEL_PERCENTAGE. The button app reads fuel here. See CarDecode.fuelLitresFromPercent."),
    INDOOR_TEMP(0x100C00, "Indoor temperature", "C", Confidence.GUESS, note = "Used by the button app."),
    OUTDOOR_TEMP(0x100B00, "Outdoor temperature", "C", Confidence.GUESS, note = "Used by the button app."),
    HANDBRAKE(0x200300, "Handbrake", confidence = Confidence.GUESS, note = "Event sensor."),
    RANGE(0x100800, "Range (km left)", "km", Confidence.GUESS, note = "ECARX ENDURANCE_MILEAGE."),
    COOLANT_TEMP(0x100F00, "Coolant temperature", "C", Confidence.GUESS, note = "ECARX ENGINE_COOLANT_TEMPERATURE."),
    ACCELERATOR(0x101400, "Accelerator pedal", "%", Confidence.GUESS, note = "ECARX ACCELERATOR_DEPTH."),
    BRAKE(0x101300, "Brake pedal", "%", Confidence.GUESS, note = "ECARX BRAKE_DEPTH."),
    DAY_NIGHT(0x201000, "Day / night", confidence = Confidence.GUESS, note = "Event sensor."),
    START_STOP(0x201700, "Engine start/stop", confidence = Confidence.GUESS, note = "ECARX ENGINE_START_STOP_STATE."),
    PASSENGER_SEAT_OCCUPIED(0x203400, "Passenger seat occupied", confidence = Confidence.GUESS,
        note = "ECARX SEAT_OCCUPATION_STATUS_PASSENGER. The button app watches it."),
    DRIVER_SEATBELT(0x201200, "Driver seatbelt", confidence = Confidence.GUESS, note = "ECARX SAFE_BELT_DRIVER."),
    AMBIENT_LIGHT(0x200F00, "Light sensor", confidence = Confidence.GUESS, note = "ECARX SENSOR_TYPE_LIGHT."),
    AVG_FUEL_CONSUMPTION(0x400100, "Average fuel consumption", "L/100km", Confidence.GUESS,
        note = "IVirtualSensor TYPE_AVG_FUEL_CONSUMPTION."),
    TIRE_PRESSURE_FL(0x500100, "Tire pressure front left", "kPa", Confidence.GUESS, note = "ITireSensor. Unit is a guess."),
    TIRE_PRESSURE_FR(0x500200, "Tire pressure front right", "kPa", Confidence.GUESS, note = "ITireSensor. Unit is a guess."),
    TIRE_PRESSURE_RL(0x500300, "Tire pressure rear left", "kPa", Confidence.GUESS, note = "ITireSensor. Unit is a guess."),
    TIRE_PRESSURE_RR(0x500400, "Tire pressure rear right", "kPa", Confidence.GUESS, note = "ITireSensor. Unit is a guess.");

    companion object {
        fun byId(id: Int): CarSensor? = entries.firstOrNull { it.id == id }

        /** Sensors worth subscribing to on this car. */
        val usable: List<CarSensor> get() = entries.filter { it.confidence != Confidence.NOT_AVAILABLE }
    }
}
