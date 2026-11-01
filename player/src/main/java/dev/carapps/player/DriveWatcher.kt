package dev.carapps.player

import android.content.Context
import dev.carapps.car.CarDecode
import dev.carapps.car.CarSensor
import dev.carapps.car.EcarxCar

/**
 * Reports whether the car is out of Park, from the head unit's gear sensor.
 * On a phone, or if the gear can't be read, it reports "parked" so the full layout stays.
 */
class DriveWatcher(context: Context, private val onChange: (driving: Boolean) -> Unit) {

    private val car = EcarxCar(context)
    private var driving = false

    fun start() {
        if (!car.isAvailable) return
        car.start(object : EcarxCar.Listener {
            override fun onEventChanged(sensorId: Int, event: Int) {
                if (sensorId == CarSensor.GEAR.id) update(event)
            }
        }, listOf(CarSensor.GEAR.id))
        car.latestEvent(CarSensor.GEAR.id)?.let { update(it) }
    }

    fun stop() {
        car.stop()
    }

    private fun update(event: Int) {
        val gear = CarDecode.gear(event)
        // Only a gear we recognise switches modes, so an unmapped value never hides controls by mistake.
        val now = gear == CarDecode.Gear.R || gear == CarDecode.Gear.N || gear == CarDecode.Gear.D
        if (gear == CarDecode.Gear.UNKNOWN || now == driving) return
        driving = now
        onChange(now)
    }
}
