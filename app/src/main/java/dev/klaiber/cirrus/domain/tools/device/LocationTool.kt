package dev.klaiber.cirrus.domain.tools.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.klaiber.cirrus.data.repository.SettingsRepository
import dev.klaiber.cirrus.domain.tools.CirrusTool
import dev.klaiber.cirrus.domain.tools.github.errorJson
import dev.klaiber.cirrus.domain.tools.github.functionSchema
import dev.klaiber.cirrus.domain.tools.shell.shellTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the phone is.
 *
 * Built on the platform's own [LocationManager] rather than on Play Services' fused provider. The
 * fused one is better at this — it blends sensors and is what Google would tell you to use — but it
 * would be the app's first dependency on Play Services, for one tool, on a device that may not have
 * them. The platform API is enough for the question actually being asked, which is "roughly where
 * am I", not "navigate me".
 *
 * Coarse accuracy is requested, and that is a deliberate ceiling rather than a fallback. Every use
 * this is for — the weather, what is nearby, which timezone, how far to somewhere — is answered
 * just as well by the neighbourhood as by the doorstep, and the difference between those two is the
 * difference between a useful tool and a tracking device.
 *
 * The reply is a *place*, not just numbers. A model handed 52.52, 13.40 will usually say "Berlin"
 * and occasionally say something confidently wrong, so the reverse geocode happens here where it
 * can be checked, and the coordinates come along for anything that genuinely needs them.
 */
@Singleton
class LocationTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : CirrusTool {

    override val name: String = "get_location"

    override val definition: JsonElement = functionSchema(
        name = name,
        description = "Where the user's phone is now: a place name where one can be resolved, plus " +
            "coordinates. Use it when the answer depends on where they are — the weather, what is " +
            "nearby, travel times, which timezone — and say that you are checking. It is " +
            "approximate by design, at roughly neighbourhood accuracy, so do not use it for " +
            "anything needing a precise position. If it says the permission is missing, tell the " +
            "user where to grant it rather than trying again.",
    ) {}

    override suspend fun execute(arguments: JsonObject): String = shellTool {
        if (!hasPermission()) {
            settings.setLocationPermissionGranted(false)
            return@shellTool errorJson(
                "Android has not granted Cirrus location access. The user can grant it by " +
                    "turning Location off and on again at Settings → Tools → Location, which " +
                    "asks; if they refused before, Android will only ask from the phone's own " +
                    "Settings → Apps → Cirrus → Permissions.",
            )
        }
        settings.setLocationPermissionGranted(true)

        val manager = context.getSystemService<LocationManager>()
            ?: return@shellTool errorJson("This device has no location service.")

        val location = currentLocation(manager)
            ?: return@shellTool errorJson(
                "Could not get a fix. Location may be switched off for the whole phone, or the " +
                    "device may be somewhere without signal. Ask the user to check that Location " +
                    "is on in the phone's own quick settings.",
            )

        buildJsonObject {
            place(location)?.let { put("place", it) }
            put("latitude", round(location.latitude))
            put("longitude", round(location.longitude))
            put("accuracy_metres", location.accuracy.toInt())
            put("age_seconds", ((System.currentTimeMillis() - location.time) / 1000).coerceAtLeast(0))
            put(
                "note",
                "Approximate, to roughly neighbourhood accuracy. Do not present it as an exact " +
                    "address.",
            )
        }.toString()
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * A fix, preferring a fresh one but settling for a recent one.
     *
     * `getCurrentLocation` is the right call and exists from API 30; below that the only options
     * are a cached fix or registering for updates, and registering for updates to answer one
     * question means a listener that outlives the turn. The cached fix is what that API would
     * mostly return anyway.
     */
    // Checked in execute, and the call is wrapped in runCatching besides — a permission revoked
    // between the check and the call surfaces as SecurityException, which is handled as "no fix".
    @Suppress("MissingPermission")
    private suspend fun currentLocation(manager: LocationManager): Location? {
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return lastKnown(manager)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return lastKnown(manager)

        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    manager.getCurrentLocation(
                        provider,
                        signal,
                        context.mainExecutor,
                    ) { location -> if (continuation.isActive) continuation.resume(location) }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        } ?: lastKnown(manager)
    }

    @Suppress("MissingPermission") // Checked in execute; this is only reached once it has passed.
    private fun lastKnown(manager: LocationManager): Location? = manager.allProviders
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }

    /**
     * The place name, or null if this device cannot resolve one.
     *
     * The blocking [Geocoder] call is deprecated on API 33+ in favour of a callback, but it is the
     * only form that exists below it, and it is already on an IO dispatcher inside a timeout. A
     * device with no geocoder backend — plenty ship without one — simply returns nothing, and the
     * coordinates still answer the question.
     */
    private suspend fun place(location: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            @Suppress("DEPRECATION")
            runCatching {
                Geocoder(context, Locale.getDefault())
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
            }.getOrNull()?.let { address ->
                listOfNotNull(
                    address.locality ?: address.subAdminArea,
                    address.adminArea.takeIf { it != address.locality },
                    address.countryName,
                ).distinct().joinToString(", ").takeIf { it.isNotBlank() }
            }
        }
    }

    /** Three decimals is about 100 metres, which is the accuracy actually being claimed. */
    private fun round(value: Double): Double = Math.round(value * 1000) / 1000.0

    private companion object {
        const val FIX_TIMEOUT_MS = 8_000L
        const val GEOCODE_TIMEOUT_MS = 5_000L
    }
}
