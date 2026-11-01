package dev.carapps.car

/**
 * Sensor ids used by the head unit's ECARX car API (com.ecarx.xui.adaptapi.car.sensor.ISensor).
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
        note = "Event sensor. Read 0x200100 +7 the whole session; other states not mapped yet."),
    GEAR(0x200200, "Gear", confidence = Confidence.CONFIRMED,
        note = "Event sensor. +0x30 P, +0x40 R, +0x10 N, +0x20 D (corrected 2026-11-01)."),
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
        note = "Active, read 0.");

    companion object {
        fun byId(id: Int): CarSensor? = entries.firstOrNull { it.id == id }

        /** Sensors worth subscribing to on this car. */
        val usable: List<CarSensor> get() = entries.filter { it.confidence != Confidence.NOT_AVAILABLE }
    }
}
