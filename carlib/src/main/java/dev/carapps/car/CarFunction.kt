package dev.carapps.car

/**
 * Function ids for the ECARX car-function API (ICarFunction.getFunctionValue(id, zone)).
 * Functions are the car's switchable features: climate, seat heating, wipers and so on.
 *
 * This library only READS them. Writing a function would change a real car setting.
 *
 * All ids here are GUESS: they come from the button app (com.aleksan.button), which reads
 * and sets them through the gbinder helper on Cityray cars. Names come from that app's
 * labels. Values and zones have not been checked in our car yet. Car Probe shows them.
 */
enum class CarFunction(
    val id: Int,
    val label: String,
    val zones: IntArray = intArrayOf(Zone.NONE),
    val confidence: Confidence = Confidence.GUESS,
    val note: String = "",
) {
    CLIMATE_POWER(0x10010100, "Climate power"),
    CLIMATE_AUTO(0x10010200, "Climate auto mode", intArrayOf(Zone.DRIVER)),
    CLIMATE_MAX_COOLING(0x10010400, "Max cooling"),
    FAN_MANUAL(0x10020100, "Fan level (manual)", intArrayOf(Zone.DRIVER)),
    FAN_AUTO(0x10020200, "Fan level (auto)", intArrayOf(Zone.DRIVER)),
    RECIRCULATION(0x10030100, "Recirculation"),
    WINDSHIELD_DEFROST(0x10040200, "Windshield defrost"),
    REAR_GLASS_HEATING(0x10040300, "Glass heating"),
    SEAT_HEATING(0x10050200, "Seat heating", intArrayOf(Zone.DRIVER, Zone.PASSENGER, Zone.REAR_LEFT, Zone.REAR_RIGHT)),
    SEAT_VENTILATION(0x10050100, "Seat ventilation", intArrayOf(Zone.DRIVER, Zone.PASSENGER)),
    CLIMATE_TEMPERATURE(0x10060100, "Climate set temperature",
        note = "The button app adjusts this; may be a float (customize value)."),
    UNKNOWN_10070100(0x10070100, "Unknown climate 0x10070100",
        note = "Subscribed by the button app; purpose not found yet."),
    STEERING_WHEEL_HEATING(0x10090100, "Steering wheel heating"),
    FRONT_WINDOW_HEATING_A(0x10090800, "Front window heating (variant A)",
        note = "The button app picks one of A/B/C by car type (cityray, official, parallel import)."),
    FRONT_WINDOW_HEATING_B(0x10142300, "Front window heating (variant B)"),
    FRONT_WINDOW_HEATING_C(0x10141B00, "Front window heating (variant C)"),
    FRAGRANCE(0x100B0300, "Fragrance (aromatization)"),
    AUTO_HOLD(0x20060400, "Auto hold",
        note = "Name confirmed by the ECARX interface (IVehicle.SETTING_FUNC_AUTO_HOLD)."),
    WIPERS(0x200C0100, "Wipers", intArrayOf(Zone.DRIVER)),
    TRUNK(0x21020100, "Trunk", intArrayOf(Zone.TRUNK)),
    CABIN_LIGHT(0x21051300, "Cabin light", intArrayOf(Zone.DRIVER, Zone.PASSENGER, Zone.REAR_LEFT, Zone.REAR_RIGHT)),
    DRIVING_MODE(0x22010100, "Driving mode");

    companion object {
        fun byId(id: Int): CarFunction? = entries.firstOrNull { it.id == id }
    }
}

/** Zone values used with [CarFunction]. GUESS, from the button app's labels. */
object Zone {
    const val NONE = 0
    const val DRIVER = 1
    const val PASSENGER = 4
    const val REAR_LEFT = 16
    const val REAR_RIGHT = 64
    const val TRUNK = 0x20000000

    fun label(zone: Int): String = when (zone) {
        NONE -> ""
        DRIVER -> "driver"
        PASSENGER -> "passenger"
        REAR_LEFT -> "rear left"
        REAR_RIGHT -> "rear right"
        TRUNK -> "trunk"
        else -> "zone $zone"
    }
}

/**
 * Fixed facts about the car from ICarInfo (getCarInfoInt / getCarInfoFloat).
 * GUESS: names and ids come from the ECARX interface; values not read in our car yet.
 */
enum class CarInfo(val id: Int, val label: String, val isFloat: Boolean, val confidence: Confidence = Confidence.GUESS) {
    FUEL_TYPES(0x100100, "Fuel types", isFloat = false),
    VEHICLE_TYPES(0x100200, "Vehicle types", isFloat = false),
    FUEL_CAPACITY(0x200100, "Fuel tank capacity", isFloat = true),
    EV_BATTERY_CAPACITY(0x200200, "EV battery capacity", isFloat = true),
}
