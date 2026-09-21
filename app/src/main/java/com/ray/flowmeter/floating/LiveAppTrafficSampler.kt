package com.ray.flowmeter.floating

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.ray.flowmeter.R
import com.ray.flowmeter.data.UserPreferencesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Custom speed formatter strictly for the floating window.
 * Displays exact bytes (e.g. 252 B/s) without altering global notification/widget formatting.
 * Adapts to user's speed unit preference (BYTES vs BITS).
 */
fun formatFloatingSpeed(bytesPerSecond: Long, speedUnit: String = "BYTES"): String {
    val locale = Locale.getDefault()
    val bytes = bytesPerSecond.coerceAtLeast(0L)
    val isBits = speedUnit == "BITS"
    val value = if (isBits) bytes * 8.0 else bytes.toDouble()
    return when {
        value >= 1_000_000_000.0 -> {
            val unit = if (isBits) "Gbps" else "GB/s"
            String.format(locale, "%.1f %s", value / 1_000_000_000.0, unit)
        }
        value >= 1_000_000.0 -> {
            val unit = if (isBits) "Mbps" else "MB/s"
            String.format(locale, "%.1f %s", value / 1_000_000.0, unit)
        }
        value >= 1_000.0 -> {
            val unit = if (isBits) "kbps" else "KB/s"
            String.format(locale, "%.0f %s", value / 1_000.0, unit)
        }
        else -> {
            val unit = if (isBits) "bps" else "B/s"
            String.format(locale, "%d %s", value.toLong(), unit)
        }
    }
}

class LiveAppTrafficSampler(private val context: Context) {

    private val _state = MutableStateFlow(FloatingTrafficState())
    val state: StateFlow<FloatingTrafficState> = _state.asStateFlow()

    private var sampleJob: Job? = null
    private var samplerScope: CoroutineScope? = null
    private val isSampling = AtomicBoolean(false)
    private var idleTicks = 0
    private val packageManager: PackageManager = context.packageManager
    private val networkStatsManager: NetworkStatsManager? = context.getSystemService(NetworkStatsManager::class.java)
    private val connectivityManager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private val repository = UserPreferencesRepository(context)
    private var speedUnit: String = "BYTES"
    private val mainHandler = Handler(Looper.getMainLooper())

    // Low-threshold usage callbacks to force continuous kernel eBPF flushes while floating window is open
    private var usageCallbackWifi: NetworkStatsManager.UsageCallback? = null
    private var usageCallbackMobile: NetworkStatsManager.UsageCallback? = null

    // Cached app metadata to avoid repeated PackageManager queries
    private data class CachedAppInfo(
        val appName: String,
        val packageName: String,
        val icon: ImageBitmap?
    )
    private val appInfoCache = mutableMapOf<Int, CachedAppInfo>()

    // Snapshots for device total traffic calculation (TrafficStats)
    private var lastTotalRxBytes: Long = 0L
    private var lastTotalTxBytes: Long = 0L
    private var lastSampleTime: Long = 0L

    // Snapshots for per-UID delta calculation (NetworkStatsManager)
    private var lastSyncedUidStats = mutableMapOf<Int, Pair<Long, Long>>() // uid -> (rx, tx)

    // Active app tracking
    private data class TrackedApp(
        val uid: Int,
        var rxSpeed: Long = 0L,
        var txSpeed: Long = 0L,
        var totalSpeed: Long = 0L,
        var rxShare: Double = 0.0,
        var txShare: Double = 0.0
    )
    private val activeAppHistory = mutableMapOf<Int, TrackedApp>()

    fun start(scope: CoroutineScope) {
        if (sampleJob?.isActive == true) return
        samplerScope = scope

        // Collect speed unit preference from repository
        scope.launch(Dispatchers.IO) {
            repository.speedUnit.collect { unit ->
                speedUnit = unit
            }
        }

        sampleJob = scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            lastTotalRxBytes = TrafficStats.getTotalRxBytes()
            lastTotalTxBytes = TrafficStats.getTotalTxBytes()
            lastSampleTime = now
            lastSyncedUidStats.clear()
            activeAppHistory.clear()
            idleTicks = 0

            // Keep kernel eBPF counters synced while window is open
            registerPollAssist()

            // Baseline capture
            captureUidStats(lastSyncedUidStats)

            // Fast initial sample at 250ms for snappy responsiveness
            delay(250L)
            try {
                updateSample()
            } catch (e: Exception) {
                android.util.Log.e("LiveAppTrafficSampler", "Error updating initial sample", e)
            }

            while (isActive) {
                // If device traffic is flowing but no app is resolved yet, poll quickly in 200ms
                val nextDelay = if (_state.value.totalSpeed >= 100L && activeAppHistory.isEmpty()) 200L else 1000L
                delay(nextDelay)
                try {
                    updateSample()
                } catch (e: Exception) {
                    android.util.Log.e("LiveAppTrafficSampler", "Error updating sample", e)
                }
            }
        }
    }

    fun stop() {
        sampleJob?.cancel()
        sampleJob = null
        samplerScope = null
        unregisterPollAssist()
        activeAppHistory.clear()
        lastSyncedUidStats.clear()
    }

    private fun triggerImmediateSample() {
        val scope = samplerScope ?: return
        if (sampleJob?.isActive != true) return
        scope.launch(Dispatchers.IO) {
            try {
                updateSample()
            } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPollAssist() {
        val nsm = networkStatsManager ?: return
        val threshold = 10 * 1024L // 10 KB low threshold for instant triggering on small requests

        try {
            if (usageCallbackWifi == null) {
                usageCallbackWifi = object : NetworkStatsManager.UsageCallback() {
                    override fun onThresholdReached(networkType: Int, subscriberId: String?) {
                        mainHandler.post {
                            if (sampleJob?.isActive == true) {
                                try { nsm.unregisterUsageCallback(this) } catch (_: Exception) {}
                                try {
                                    nsm.registerUsageCallback(ConnectivityManager.TYPE_WIFI, null, threshold, this, mainHandler)
                                } catch (_: Exception) {}
                                triggerImmediateSample()
                            }
                        }
                    }
                }
                nsm.registerUsageCallback(ConnectivityManager.TYPE_WIFI, null, threshold, usageCallbackWifi!!, mainHandler)
            }
        } catch (e: Exception) {
            android.util.Log.w("LiveAppTrafficSampler", "Unable to register WiFi usage callback", e)
        }

        try {
            if (usageCallbackMobile == null) {
                usageCallbackMobile = object : NetworkStatsManager.UsageCallback() {
                    override fun onThresholdReached(networkType: Int, subscriberId: String?) {
                        mainHandler.post {
                            if (sampleJob?.isActive == true) {
                                try { nsm.unregisterUsageCallback(this) } catch (_: Exception) {}
                                try {
                                    nsm.registerUsageCallback(ConnectivityManager.TYPE_MOBILE, null, threshold, this, mainHandler)
                                } catch (_: Exception) {}
                                triggerImmediateSample()
                            }
                        }
                    }
                }
                nsm.registerUsageCallback(ConnectivityManager.TYPE_MOBILE, null, threshold, usageCallbackMobile!!, mainHandler)
            }
        } catch (e: Exception) {
            android.util.Log.w("LiveAppTrafficSampler", "Unable to register Mobile usage callback", e)
        }
    }

    private fun unregisterPollAssist() {
        val nsm = networkStatsManager ?: return
        usageCallbackWifi?.let {
            try { nsm.unregisterUsageCallback(it) } catch (_: Exception) {}
            usageCallbackWifi = null
        }
        usageCallbackMobile?.let {
            try { nsm.unregisterUsageCallback(it) } catch (_: Exception) {}
            usageCallbackMobile = null
        }
    }

    private fun isVpnActive(): Boolean {
        return try {
            val activeNet = connectivityManager?.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(activeNet) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    private fun updateSample() {
        if (!isSampling.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            val dtSec = max((now - lastSampleTime) / 1000.0, 0.1)

            // 1. Total device speeds via TrafficStats (instantaneous & accurate)
            val currentTotalRx = TrafficStats.getTotalRxBytes()
            val currentTotalTx = TrafficStats.getTotalTxBytes()

            val rawRxDiff = (currentTotalRx - lastTotalRxBytes).coerceAtLeast(0L)
            val rawTxDiff = (currentTotalTx - lastTotalTxBytes).coerceAtLeast(0L)

            // VPN normalization
            val isVpn = isVpnActive()
            val vpnFactor = if (isVpn) 2.0 else 1.0

            val rxSpeed = ((rawRxDiff / dtSec) / vpnFactor).toLong()
            val txSpeed = ((rawTxDiff / dtSec) / vpnFactor).toLong()
            val totalSpeed = rxSpeed + txSpeed

            lastTotalRxBytes = currentTotalRx
            lastTotalTxBytes = currentTotalTx
            lastSampleTime = now

            // If total device speed is idle (< 100 B/s), immediately clear the UI active apps.
            // Retain recent app attribution in activeAppHistory for up to 4 seconds of pause
            // so a new link click in the browser displays instantaneously without waiting for a new sync.
            if (totalSpeed < 100L) {
                idleTicks++
                for (tracked in activeAppHistory.values) {
                    tracked.rxSpeed = 0L
                    tracked.txSpeed = 0L
                    tracked.totalSpeed = 0L
                }
                if (idleTicks >= 4) {
                    activeAppHistory.clear()
                }
                _state.value = _state.value.copy(
                    rxSpeed = 0L,
                    txSpeed = 0L,
                    totalSpeed = 0L,
                    speedUnit = speedUnit,
                    activeApps = emptyList()
                )
                return
            }

            idleTicks = 0

        // 2. Per-UID Attribution via NetworkStatsManager
        val currentUidStats = mutableMapOf<Int, Pair<Long, Long>>()
        captureUidStats(currentUidStats)

        var totalNewRx = 0L
        var totalNewTx = 0L
        val uidRxDeltas = mutableMapOf<Int, Long>()
        val uidTxDeltas = mutableMapOf<Int, Long>()

        for ((uid, currentBytes) in currentUidStats) {
            val previousBytes = lastSyncedUidStats[uid] ?: currentBytes
            val rxDiff = (currentBytes.first - previousBytes.first).coerceAtLeast(0L)
            val txDiff = (currentBytes.second - previousBytes.second).coerceAtLeast(0L)

            if (rxDiff > 0L) {
                uidRxDeltas[uid] = rxDiff
                totalNewRx += rxDiff
            }
            if (txDiff > 0L) {
                uidTxDeltas[uid] = txDiff
                totalNewTx += txDiff
            }
        }

        val hasNewSync = totalNewRx > 0L || totalNewTx > 0L

        if (hasNewSync) {
            lastSyncedUidStats = currentUidStats

            val allActiveUids = uidRxDeltas.keys + uidTxDeltas.keys
            activeAppHistory.clear()

            for (uid in allActiveUids) {
                val rxDelta = uidRxDeltas[uid] ?: 0L
                val txDelta = uidTxDeltas[uid] ?: 0L

                val rxShare = if (totalNewRx > 0L) (rxDelta.toDouble() / totalNewRx).coerceIn(0.0, 1.0) else 0.0
                val txShare = if (totalNewTx > 0L) (txDelta.toDouble() / totalNewTx).coerceIn(0.0, 1.0) else 0.0

                val appRxSpeed = (rxSpeed * rxShare).toLong()
                val appTxSpeed = (txSpeed * txShare).toLong()
                val appTotalSpeed = appRxSpeed + appTxSpeed

                if (appTotalSpeed >= 100L) {
                    activeAppHistory[uid] = TrackedApp(
                        uid = uid,
                        rxSpeed = appRxSpeed,
                        txSpeed = appTxSpeed,
                        totalSpeed = appTotalSpeed,
                        rxShare = rxShare,
                        txShare = txShare
                    )
                }
            }
        } else {
            // Between sync flushes: scale existing active apps with live device speed
            val iterator = activeAppHistory.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val tracked = entry.value
                val appRxSpeed = (rxSpeed * tracked.rxShare).toLong()
                val appTxSpeed = (txSpeed * tracked.txShare).toLong()
                val appTotalSpeed = appRxSpeed + appTxSpeed

                if (appTotalSpeed >= 100L) {
                    tracked.rxSpeed = appRxSpeed
                    tracked.txSpeed = appTxSpeed
                    tracked.totalSpeed = appTotalSpeed
                } else {
                    iterator.remove()
                }
            }
        }

        // Sort active apps by total speed descending
        val sortedTracked = activeAppHistory.values
            .filter { it.totalSpeed >= 100L }
            .sortedByDescending { it.totalSpeed }
            .take(3)

        val referenceSpeed = maxOf(totalSpeed, sortedTracked.firstOrNull()?.totalSpeed ?: 1L, 1L)

        val formattedList = sortedTracked.map { tracked ->
            val info = resolveAppInfo(tracked.uid)
            val rxRatio = (tracked.rxSpeed.toFloat() / referenceSpeed).coerceIn(0f, 1f)
            val txRatio = (tracked.txSpeed.toFloat() / referenceSpeed).coerceIn(0f, 1f)

            ActiveAppTraffic(
                uid = tracked.uid,
                packageName = info.packageName,
                appName = info.appName,
                icon = info.icon,
                rxSpeed = tracked.rxSpeed,
                txSpeed = tracked.txSpeed,
                totalSpeed = tracked.totalSpeed,
                rxRatio = rxRatio,
                txRatio = txRatio
            )
        }

        _state.value = _state.value.copy(
            rxSpeed = rxSpeed,
            txSpeed = txSpeed,
            totalSpeed = totalSpeed,
            speedUnit = speedUnit,
            activeApps = formattedList
        )
    } finally {
        isSampling.set(false)
    }
}

    private fun captureUidStats(outStats: MutableMap<Int, Pair<Long, Long>>) {
        val nsm = networkStatsManager ?: return
        val endTime = System.currentTimeMillis()
        // 24-hour window ensures startTime is prior to current bucket start, preventing fractional interpolation
        val startTime = endTime - (24L * 60 * 60 * 1000)

        val transports = listOf(
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET
        )

        for (transport in transports) {
            try {
                val stats = nsm.querySummary(transport, null, startTime, endTime) ?: continue
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val uid = bucket.uid
                    val prev = outStats.getOrDefault(uid, 0L to 0L)
                    outStats[uid] = Pair(
                        prev.first + bucket.rxBytes,
                        prev.second + bucket.txBytes
                    )
                }
                stats.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveAppInfo(uid: Int): CachedAppInfo {
        appInfoCache[uid]?.let { return it }

        val systemIcon = loadSystemIcon()
        val resolved = when (uid) {
            -3, -5 -> CachedAppInfo(context.getString(R.string.label_tethering), "tethering", null)
            -2, -4 -> CachedAppInfo(context.getString(R.string.label_removed_apps), "removed", null)
            0 -> CachedAppInfo(context.getString(R.string.label_root), "root", systemIcon)
            3 -> CachedAppInfo("Sys Daemons", "sys", systemIcon)
            Process.SYSTEM_UID -> CachedAppInfo(context.getString(R.string.label_android_system), "android", systemIcon)
            Process.SHELL_UID -> CachedAppInfo("Shell", "com.android.shell", null)
            1051, 1052 -> CachedAppInfo(context.getString(R.string.label_dns_resolver), "android.dns", systemIcon)
            1020 -> CachedAppInfo(context.getString(R.string.label_mdns_responder), "android.mdns", systemIcon)
            1013 -> CachedAppInfo(context.getString(R.string.label_media_service), "android.media", systemIcon)
            1061, 2904 -> CachedAppInfo(context.getString(R.string.label_system_update), "android.ota", systemIcon)
            1073 -> CachedAppInfo("Network Stack", "android.networkstack", systemIcon)
            else -> {
                val packages = try {
                    packageManager.getPackagesForUid(uid)
                } catch (_: Exception) {
                    null
                }

                if (!packages.isNullOrEmpty()) {
                    val pkg = if (packages.size > 1) {
                        packages.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null } ?: packages[0]
                    } else {
                        packages[0]
                    }
                    try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        val name = packageManager.getApplicationLabel(appInfo).toString()
                        val icon = try {
                            packageManager.getApplicationIcon(appInfo).toBitmap(width = 48, height = 48).asImageBitmap()
                        } catch (_: Exception) {
                            null
                        }
                        CachedAppInfo(name, pkg, icon)
                    } catch (_: Exception) {
                        CachedAppInfo(pkg, pkg, null)
                    }
                } else {
                    val name = try {
                        packageManager.getNameForUid(uid)
                    } catch (_: Exception) {
                        null
                    } ?: if (uid in 0..9999) {
                        context.getString(R.string.label_system_processes) + " ($uid)"
                    } else {
                        "UID $uid"
                    }
                    val icon = if (uid in 0..9999) systemIcon else null
                    CachedAppInfo(name, "uid_$uid", icon)
                }
            }
        }

        appInfoCache[uid] = resolved
        return resolved
    }

    private var cachedSystemIcon: ImageBitmap? = null
    private fun loadSystemIcon(): ImageBitmap? {
        if (cachedSystemIcon != null) return cachedSystemIcon
        cachedSystemIcon = try {
            val appInfo = packageManager.getApplicationInfo("android", 0)
            packageManager.getApplicationIcon(appInfo).toBitmap(width = 48, height = 48).asImageBitmap()
        } catch (_: Exception) {
            null
        }
        return cachedSystemIcon
    }
}
