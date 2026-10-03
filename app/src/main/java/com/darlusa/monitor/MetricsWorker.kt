package com.darlusa.monitor

import android.Manifest
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

class MetricsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        // DarLusa published API endpoint (public ingestion)
        const val INGEST_URL = "https://darlusafashion.lovable.app/api/public/device-metrics"
        // Simple shared key — set the same string server-side in device_metrics config
        const val SHARED_KEY = "darlusa-monitor-v1"

        val MONITORED_APPS = listOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.zhiliaoapp.musically.go" to "TikTok Lite",
            "com.tiktok.lite.go" to "TikTok Lite",
            "com.snapchat.android" to "Snapchat",
            "com.instagram.android" to "Instagram",
            "com.instagram.lite" to "Instagram Lite",
            "com.google.android.youtube" to "YouTube"
        )
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs.get(ctx)
        val label = prefs.getString("device_label", null) ?: return Result.success()

        val tz = TimeZone.getTimeZone("Africa/Dar_es_Salaam")
        val cal = Calendar.getInstance(tz)
        // Business date = Dar calendar day 00:00 -> 23:59
        val now = cal.timeInMillis
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val businessStart = Calendar.getInstance(tz).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val businessEnd = businessStart + 24L * 60 * 60 * 1000

        val businessDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
            .format(Date(businessStart))

        val payload = JSONObject().apply {
            put("device_label", label)
            put("business_date", businessDateStr)
            put("device_model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("reported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(now)))
        }

        // Per-app usage
        val apps = JSONArray()
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usm.queryAndAggregateUsageStats(businessStart, minOf(businessEnd, now))
            for ((pkg, name) in MONITORED_APPS) {
                val s = stats[pkg]
                val fgMs = s?.totalTimeInForeground ?: 0L
                val mb = queryAppMb(ctx, pkg, ConnectivityManager.TYPE_MOBILE, businessStart, minOf(businessEnd, now))
                val wifi = queryAppMb(ctx, pkg, ConnectivityManager.TYPE_WIFI, businessStart, minOf(businessEnd, now))
                if (fgMs > 0 || mb > 0 || wifi > 0) {
                    apps.put(JSONObject().apply {
                        put("package", pkg)
                        put("display_name", name)
                        put("foreground_seconds", fgMs / 1000)
                        put("mobile_mb", mb)
                        put("wifi_mb", wifi)
                    })
                }
            }
        } catch (_: Throwable) {}
        payload.put("apps", apps)

        // Hotspot/tethering total (UID_TETHERING = -5)
        payload.put("hotspot_mb", queryTetheringMb(ctx, businessStart, minOf(businessEnd, now)))

        // Kariakoo arrival detection
        val arrival = detectKariakooArrival(ctx)
        if (arrival != null) {
            payload.put("kariakoo_arrival_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(arrival.first)))
            payload.put("kariakoo_lat", arrival.second)
            payload.put("kariakoo_lng", arrival.third)
        }

        return if (post(payload)) Result.success() else Result.retry()
    }

    // Android 10+ forbids reading the SIM subscriberId; passing null returns all
    // traffic of that network type for the app, which is what we want.
    private fun subIdOrNull(ctx: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        return try {
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                @Suppress("MissingPermission", "HardwareIds", "DEPRECATION")
                tm.subscriberId
            } else null
        } catch (_: Throwable) { null }
    }

    private fun queryAppMb(ctx: Context, pkg: String, netType: Int, start: Long, end: Long): Double {
        return try {
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
            val subId = if (netType == ConnectivityManager.TYPE_MOBILE) subIdOrNull(ctx) else null
            val bucket = NetworkStats.Bucket()
            val stats = nsm.queryDetailsForUid(netType, subId, start, end, uid)
            var total = 0L
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                total += bucket.rxBytes + bucket.txBytes
            }
            stats.close()
            total / 1_000_000.0
        } catch (_: Throwable) { 0.0 }
    }

    private fun queryTetheringMb(ctx: Context, start: Long, end: Long): Double {
        return try {
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val subId = subIdOrNull(ctx)
            val bucket = NetworkStats.Bucket()
            // UID_TETHERING = -5
            val stats = nsm.queryDetailsForUid(ConnectivityManager.TYPE_MOBILE, subId, start, end, -5)
            var total = 0L
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                total += bucket.rxBytes + bucket.txBytes
            }
            stats.close()
            total / 1_000_000.0
        } catch (_: Throwable) { 0.0 }
    }

    // Kariakoo polygon — excludes Mnazi Mmoja (east of Uhuru St) entirely.
    // Pairs are (lat, lon). Points chosen to cover Kariakoo market core:
    // Msimbazi (N) → Likoma/Nkrumah (S), Lumumba (W) → Mkunguni/Sikukuu (E, stopping before Uhuru St).
    private val KARIAKOO = listOf(
        -6.8125 to 39.2680,
        -6.8128 to 39.2765,
        -6.8235 to 39.2770,
        -6.8238 to 39.2685
    )

    private suspend fun detectKariakooArrival(ctx: Context): Triple<Long, Double, Double>? {
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val loc = suspendCancellableCoroutine<android.location.Location?> { cont ->
            try {
                client.lastLocation.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
            } catch (_: Throwable) { cont.resume(null) }
        } ?: return null
        val inside = pointInPolygon(loc.latitude, loc.longitude, KARIAKOO)
        return if (inside) Triple(loc.time, loc.latitude, loc.longitude) else null
    }

    private fun pointInPolygon(lat: Double, lon: Double, poly: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (yi, xi) = poly[i]
            val (yj, xj) = poly[j]
            if (((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi)) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun post(payload: JSONObject): Boolean {
        return try {
            val url = URL(INGEST_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Device-Key", SHARED_KEY)
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Throwable) { false }
    }
}
