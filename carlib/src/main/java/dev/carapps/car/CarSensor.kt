package dev.carapps.car

/**
 * Sensor ids used by the head unit's ECARX car API (com.ecarx.xui.adaptapi.car.sensor.ISensor).
 *
 * These are the values the system expects. They were read from the interface constants
 * that apps on this head unit compile against. [CarDecode] turns raw values into meaning
 * where we have measured it; Car Probe shows the raw values for the rest.
 */
enum class CarSensor(val id: Int, val label: String, val unit: String = "") {
    IGNITION(0x200100, "Ignition state"),
    GEAR(0x200200, "Gear"),
    ENGINE_STATE(0x201600, "Engine state"),
    SPEED(0x100100, "Speed", "km/h"),
    SPEED_FROM_CLUSTER(0x101A00, "Speed (cluster)", "km/h"),
    ACCELERATION(0x101700, "Acceleration"),
    RPM(0x100900, "RPM", "rpm"),
    FUEL_LEVEL(0x100600, "Fuel level"),
    ODOMETER(0x100700, "Odometer", "km"),
    STEERING_ANGLE(0x101000, "Steering angle", "deg"),
    STEERING_ANGLE_SPEED(0x101100, "Steering angle speed");

    companion object {
        fun byId(id: Int): CarSensor? = entries.firstOrNull { it.id == id }
    }
}
