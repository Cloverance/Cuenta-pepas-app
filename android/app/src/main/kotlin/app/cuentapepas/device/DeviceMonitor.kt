package app.cuentapepas.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thermal and battery readings of the device.
 *
 * [thermalStatus] follows `PowerManager.THERMAL_STATUS_*` (0 none ... 6 shutdown) on API 29+, and is
 * [NO_DATA] (-1) on older versions, which the UI shows as "sin dato" (never as "none"). Battery readings
 * are `null` when the system does not report them, for the same reason.
 *
 * Call [close] when done so the thermal listener is unregistered.
 */
class DeviceMonitor(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val thermalState = MutableStateFlow(NO_DATA)
    private var thermalHandle: ThermalHandle? = null

    @Volatile var maxThermalSeen: Int = NO_DATA
        private set

    /** Current thermal status, or [NO_DATA]. */
    val thermalStatus: Int get() = thermalState.value

    /** Same value as [thermalStatus], observable. */
    val thermalStatusFlow: StateFlow<Int> = thermalState.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val handle = ThermalHandle(appContext, ::onThermal)
            handle.register()
            thermalHandle = handle
        }
    }

    private fun onThermal(status: Int) {
        thermalState.value = status
        if (status > maxThermalSeen) maxThermalSeen = status
    }

    /** Battery level 0-100, or null when unavailable. */
    fun batteryPct(): Int? {
        val i = batteryIntent() ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100f / scale).toInt().coerceIn(0, 100)
    }

    /** Battery temperature in degrees Celsius, or null when unavailable. */
    fun batteryTempC(): Float? {
        val i = batteryIntent() ?: return null
        if (!i.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) return null
        return i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f // reported in tenths of a degree
    }

    private fun batteryIntent(): Intent? =
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) // sticky broadcast

    override fun close() {
        // thermalHandle is only ever created on API 29+ (see init); the explicit check keeps that invariant visible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalHandle?.unregister()
        thermalHandle = null
    }

    /** Isolated so that no API 29 class is touched on older devices. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private class ThermalHandle(context: Context, private val onStatus: (Int) -> Unit) {
        private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        private val executor = ContextCompat.getMainExecutor(context)
        private val listener = PowerManager.OnThermalStatusChangedListener { status -> onStatus(status) }

        fun register() {
            pm.addThermalStatusListener(executor, listener)
            onStatus(pm.currentThermalStatus)
        }

        fun unregister() = pm.removeThermalStatusListener(listener)
    }

    companion object {
        const val NO_DATA = -1

        /** `PowerManager.THERMAL_STATUS_SEVERE`, duplicated so callers need no API 29 reference. */
        const val THERMAL_STATUS_SEVERE = 3
    }
}
