package app.cuentapepas.ui.live

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridge from Activity key events to the live screen: MainActivity forwards volume-key presses here while the
 * live screen is active ([liveScreenActive]); the live screen collects [nextPlant] and advances to the next plant.
 */
object HardwareKeys {
    @Volatile
    var liveScreenActive: Boolean = false

    private val _nextPlant = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val nextPlant: SharedFlow<Unit> = _nextPlant.asSharedFlow()

    /** Returns true when the key press was consumed (only while the live screen is active). */
    fun onVolumeKey(): Boolean {
        if (!liveScreenActive) return false
        _nextPlant.tryEmit(Unit)
        return true
    }
}
