package dev.carapps.car

import android.content.Context

/**
 * Tells an app whether the car is in Park. On a device without the ECARX API (a phone)
 * it never reports anything, so apps behave as they always did.
 */
class ParkWatcher(context: Context, private val onChange: (CarDecode.Gear) -> Unit) {

    private val car = EcarxCar(context)
    var gear: CarDecode.Gear = CarDecode.Gear.UNKNOWN
        private set

    val isParked: Boolean
        get() = gear == CarDecode.Gear.P

    fun start() {
        val ok = car.start(object : EcarxCar.Listener {
            override fun onEventChanged(sensorId: Int, event: Int) {
                if (sensorId == CarSensor.GEAR.id) update(CarDecode.gear(event))
            }
        }, listOf(CarSensor.GEAR.id))
        if (ok) car.latestEvent(CarSensor.GEAR.id)?.let { update(CarDecode.gear(it)) }
    }

    fun stop() {
        car.stop()
        gear = CarDecode.Gear.UNKNOWN
    }

    private fun update(g: CarDecode.Gear) {
        if (g == gear) return
        gear = g
        onChange(g)
    }
}
