package app.cuentapepas.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import app.cuentapepas.core.session.GpsPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the GPS is doing, for the status bar and the session summary ("GPS: sin permiso"). */
sealed interface GpsState {
    /** [LocationRecorder.start] was not called yet. */
    data object Idle : GpsState

    /** Fine-location permission not granted: the session goes on without GPS. */
    data object NoPermission : GpsState

    /** The GPS provider is switched off in the system settings. */
    data object ProviderOff : GpsState

    /** Waiting for the first fix. */
    data object Searching : GpsState

    /** Last fix, with its horizontal accuracy in metres. */
    data class Fix(val accuracyM: Float) : GpsState

    /** The system refused or lacks the provider; [reason] is technical. */
    data class Unavailable(val reason: String) : GpsState
}

/**
 * Records GPS points of a live session: `LocationManager.GPS_PROVIDER` every 1000 ms with no distance filter. Points
 * (only fixes that report an accuracy) are kept in memory ([points]) and handed to [sink] in batches every
 * [flushEveryMs]; a batch whose [sink] call throws stays pending and is retried with the next flush, and the failure
 * is exposed in [sinkError]. Without permission the recorder stays idle in [GpsState.NoPermission].
 *
 * Main-thread API ([start], [stop]); [points], [lastAccuracyM] and [state] can be read from anywhere.
 */
class LocationRecorder(
    context: Context,
    private val scope: CoroutineScope,
    private val sink: suspend (List<GpsPoint>) -> Unit,
    private val flushEveryMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val stateFlow = MutableStateFlow<GpsState>(GpsState.Idle)

    val state: StateFlow<GpsState> = stateFlow.asStateFlow()

    private val all = ArrayList<GpsPoint>()
    private val pending = ArrayList<GpsPoint>()
    private val lock = Any()
    private val flushMutex = Mutex()
    private var flushJob: Job? = null
    private var listening = false

    @Volatile var lastAccuracyM: Float? = null
        private set

    @Volatile var sinkError: String? = null
        private set

    /** Whether the fine-location permission is currently granted. */
    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Every recorded point so far (copy). */
    val points: List<GpsPoint> get() = synchronized(lock) { all.toList() }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = record(location)

        override fun onProviderEnabled(provider: String) {
            if (stateFlow.value is GpsState.ProviderOff) stateFlow.value = GpsState.Searching
        }

        override fun onProviderDisabled(provider: String) {
            lastAccuracyM = null
            stateFlow.value = GpsState.ProviderOff
        }
    }

    private fun record(location: Location) {
        if (!location.hasAccuracy()) return // an accuracy-less fix is useless for the repeat-pass check; never invent one
        val acc = location.accuracy
        if (!acc.isFinite() || acc < 0f) return
        val point = GpsPoint(clock(), location.latitude, location.longitude, acc)
        synchronized(lock) {
            all += point
            pending += point
        }
        lastAccuracyM = acc
        stateFlow.value = GpsState.Fix(acc)
    }

    /** Starts listening. Returns false (state [GpsState.NoPermission] / [GpsState.Unavailable]) when it cannot. */
    fun start(): Boolean {
        if (listening) return true
        if (!hasPermission) {
            stateFlow.value = GpsState.NoPermission
            return false
        }
        return try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                stateFlow.value = GpsState.ProviderOff
            } else {
                stateFlow.value = GpsState.Searching
            }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            listening = true
            flushJob = scope.launch {
                while (true) {
                    delay(flushEveryMs)
                    flush()
                }
            }
            true
        } catch (e: SecurityException) {
            stateFlow.value = GpsState.NoPermission
            false
        } catch (e: IllegalArgumentException) {
            stateFlow.value = GpsState.Unavailable(e.message ?: "no GPS provider")
            false
        }
    }

    /** Hands the pending points to the sink now. Safe to call at any time (also from the periodic job). */
    suspend fun flush() = flushMutex.withLock {
        val batch = synchronized(lock) { pending.toList() }
        if (batch.isEmpty()) return@withLock
        try {
            sink(batch)
            sinkError = null
            synchronized(lock) { pending.subList(0, batch.size).clear() } // pending is append-only between flushes
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            sinkError = "${e::class.java.simpleName}: ${e.message}" // kept pending, retried on the next flush
        }
    }

    /** Stops listening (keeps the points). Call [flush] afterwards to persist what is pending. */
    fun stop() {
        flushJob?.cancel()
        flushJob = null
        if (listening) {
            try {
                manager.removeUpdates(listener)
            } catch (e: SecurityException) {
                // permission revoked while running: nothing is registered any more
            }
            listening = false
        }
    }

    private companion object {
        const val INTERVAL_MS = 1000L
    }
}
