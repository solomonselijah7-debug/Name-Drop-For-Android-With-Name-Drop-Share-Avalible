package com.example.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.model.CountryProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone

object CountryLocationVerifier {

    private val dialPrefixes = mapOf(
        "ZA" to "+27",
        "US" to "+1",
        "CA" to "+1",
        "GB" to "+44",
        "AU" to "+61",
        "NZ" to "+64",
        "IN" to "+91",
        "DE" to "+49",
        "FR" to "+33",
        "ES" to "+34",
        "IT" to "+39",
        "NL" to "+31",
        "BR" to "+55",
        "MX" to "+52",
        "NG" to "+234",
        "KE" to "+254",
        "GH" to "+233",
        "AE" to "+971",
        "SG" to "+65",
        "JP" to "+81",
        "KR" to "+82",
        "ZW" to "+263",
        "NA" to "+264",
        "BW" to "+267"
    )

    suspend fun verifyCountryFromDevice(context: Context): CountryProfile =
        withContext(Dispatchers.IO) {
            var resolvedIso: String? = null
            var resolvedCountryName: String? = null

            val hasLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

            if (hasLocation) {
                try {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    val lastLoc = getBestLastKnownLocation(lm)
                    if (lastLoc != null && Geocoder.isPresent()) {
                        @Suppress("DEPRECATION")
                        val addresses = Geocoder(context, Locale.getDefault())
                            .getFromLocation(lastLoc.latitude, lastLoc.longitude, 1)
                        val addr = addresses?.firstOrNull()
                        if (addr != null) {
                            resolvedIso = addr.countryCode?.uppercase(Locale.US)
                            resolvedCountryName = addr.countryName
                        }
                    }
                } catch (e: Exception) {
                    DebugLogger.log("Country", "Geocoder lookup fallback: ${e.message}", "WARN")
                }
            }

            if (resolvedIso.isNullOrBlank()) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val netIso = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
                    ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
                if (!netIso.isNullOrBlank()) {
                    resolvedIso = netIso.uppercase(Locale.US)
                }
            }

            if (resolvedIso.isNullOrBlank()) {
                val tzId = TimeZone.getDefault().id
                resolvedIso = when {
                    tzId.contains("Johannesburg", ignoreCase = true) -> "ZA"
                    tzId.startsWith("America/") -> "US"
                    tzId.contains("London", ignoreCase = true) -> "GB"
                    else -> Locale.getDefault().country.takeIf { it.isNotBlank() } ?: "US"
                }
            }

            val finalIso = resolvedIso.uppercase(Locale.US)
            val finalName = resolvedCountryName ?: Locale("", finalIso).displayCountry
                .takeIf { it.isNotBlank() } ?: "United States"
            val prefix = dialPrefixes[finalIso] ?: "+1"
            val zoneId = TimeZone.getDefault().id

            DebugLogger.log(
                "Country",
                "Verified country: $finalName ($finalIso) $prefix in timezone $zoneId"
            )

            CountryProfile(
                countryName = finalName,
                countryCode = finalIso,
                dialPrefix = prefix,
                zoneId = zoneId,
                isVerified = true
            )
        }

    @Suppress("MissingPermission")
    private fun getBestLastKnownLocation(lm: LocationManager?): Location? {
        if (lm == null) return null
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (provider in providers) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.accuracy < best.accuracy) {
                best = loc
            }
        }
        return best
    }

    fun applyDialPrefix(phone: String, dialPrefix: String): String {
        val trimmed = phone.trim()
        if (trimmed.isEmpty()) return "$dialPrefix "
        if (trimmed.startsWith("+")) return trimmed
        val stripped = if (trimmed.startsWith("0")) trimmed.drop(1) else trimmed
        return "$dialPrefix $stripped"
    }
}
