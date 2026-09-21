package com.ray.flowmeter.floating

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
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
    private val packageManager: PackageManager = context.packageManager
    private val networkStatsManager: NetworkStatsManager? = context.getSystemService(NetworkStatsManager::class.java)
    private val connectivityManager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private val repository = UserPreferencesRepository(context)
    private var speedUnit: String = "BYTES"

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
    private var lastSyncTime: Long = 0L

    // Active app tracking
    private data class TrackedApp(
        val uid: Int,
        var rxSpeed: Long,
        var txSpeed: Long,
        var totalSpeed: Long,
        var rxShare: Double = 0.0,
        var txShare: Double = 0.0,
        var lastActiveTime: Long = 0L,
        var silentSyncCount: Int = 0
    )
    private val activeAppHistory = mutableMapOf<Int, TrackedApp>()

    fun start(scope: CoroutineScope) {
        if (sampleJob?.isActive == true) return

        // Collect speed unit preference from repository
        scope.launch(Dispatchers.IO) {
            repository.speedUnit.collect { unit ->
                speedUnit = unit
            }
        }

        sampleJob = scope.launch(Dispatchers.IO) {
            // Baseline initialization on IO thread
            val now = System.currentTimeMillis()
            lastTotalRxBytes = TrafficStats.getTotalRxBytes()
            lastTotalTxBytes = TrafficStats.getTotalTxBytes()
            lastSampleTime = now
            lastSyncTime = now
            lastSyncedUidStats.clear()
            activeAppHistory.clear()
            captureUidStats(lastSyncedUidStats)

            while (isActive) {
                delay(1000L) // Responsive 1-second interval
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
        activeAppHistory.clear()
        lastSyncedUidStats.clear()
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
        val now = System.currentTimeMillis()
        val dtSec = max((now - lastSampleTime) / 1000.0, 0.1)

        // 1. Total device speeds via TrafficStats (instantaneous & accurate)
        val currentTotalRx = TrafficStats.getTotalRxBytes()
        val currentTotalTx = TrafficStats.getTotalTxBytes()

        val rawRxDiff = (currentTotalRx - lastTotalRxBytes).coerceAtLeast(0L)
        val rawTxDiff = (currentTotalTx - lastTotalTxBytes).coerceAtLeast(0L)

        // If a VPN is connected, Android's /proc/net/dev counts packets on physical interface (wlan0/rmnet)
        // AND on virtual tunnel interface (tun0), causing TrafficStats to be doubled. Normalize if VPN active.
        val isVpn = isVpnActive()
        val vpnFactor = if (isVpn) 2.0 else 1.0

        val rxSpeed = ((rawRxDiff / dtSec) / vpnFactor).toLong()
        val txSpeed = ((rawTxDiff / dtSec) / vpnFactor).toLong()
        val totalSpeed = rxSpeed + txSpeed

        lastTotalRxBytes = currentTotalRx
        lastTotalTxBytes = currentTotalTx
        lastSampleTime = now

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
            val elapsedSyncSec = max((now - lastSyncTime) / 1000.0, 0.5)
            lastSyncedUidStats = currentUidStats
            lastSyncTime = now

            val activeUidsInSync = uidRxDeltas.keys + uidTxDeltas.keys

            for (uid in activeUidsInSync) {
                val rxDelta = uidRxDeltas[uid] ?: 0L
                val txDelta = uidTxDeltas[uid] ?: 0L

                val rxShare = if (totalNewRx > 0L) (rxDelta.toDouble() / totalNewRx).coerceIn(0.0, 1.0) else 0.0
                val txShare = if (totalNewTx > 0L) (txDelta.toDouble() / totalNewTx).coerceIn(0.0, 1.0) else 0.0

                val avgRxRate = (rxDelta / elapsedSyncSec).toLong()
                val avgTxRate = (txDelta / elapsedSyncSec).toLong()

                val appRxSpeed = if (rxSpeed > 0L && totalNewRx > 0L) {
                    minOf((rxSpeed * rxShare).toLong(), rxSpeed)
                } else {
                    minOf(avgRxRate, rxSpeed)
                }

                val appTxSpeed = if (txSpeed > 0L && totalNewTx > 0L) {
                    minOf((txSpeed * txShare).toLong(), txSpeed)
                } else {
                    minOf(avgTxRate, txSpeed)
                }

                val existing = activeAppHistory[uid]
                if (existing != null) {
                    existing.rxSpeed = appRxSpeed
                    existing.txSpeed = appTxSpeed
                    existing.totalSpeed = appRxSpeed + appTxSpeed
                    existing.rxShare = rxShare
                    existing.txShare = txShare
                    existing.lastActiveTime = now
                    existing.silentSyncCount = 0
                } else {
                    activeAppHistory[uid] = TrackedApp(
                        uid = uid,
                        rxSpeed = appRxSpeed,
                        txSpeed = appTxSpeed,
                        totalSpeed = appRxSpeed + appTxSpeed,
                        rxShare = rxShare,
                        txShare = txShare,
                        lastActiveTime = now,
                        silentSyncCount = 0
                    )
                }
            }

            for ((uid, tracked) in activeAppHistory) {
                if (uid !in activeUidsInSync) {
                    tracked.silentSyncCount++
                    tracked.rxShare = 0.0
                    tracked.txShare = 0.0
                    tracked.rxSpeed = (tracked.rxSpeed * 0.3).toLong()
                    tracked.txSpeed = (tracked.txSpeed * 0.3).toLong()
                    tracked.totalSpeed = tracked.rxSpeed + tracked.txSpeed
                }
            }
        } else {
            // Between syncs: if device traffic is active, maintain app speeds proportionally
            if (totalSpeed >= 1000L) {
                for (tracked in activeAppHistory.values) {
                    if (tracked.rxShare > 0.0 || tracked.txShare > 0.0) {
                        val appRx = if (rxSpeed > 0L && tracked.rxShare > 0.0) {
                            minOf((rxSpeed * tracked.rxShare).toLong(), rxSpeed)
                        } else 0L

                        val appTx = if (txSpeed > 0L && tracked.txShare > 0.0) {
                            minOf((txSpeed * tracked.txShare).toLong(), txSpeed)
                        } else 0L

                        tracked.rxSpeed = appRx
                        tracked.txSpeed = appTx
                        tracked.totalSpeed = appRx + appTx

                        if (tracked.totalSpeed >= 500L) {
                            tracked.lastActiveTime = now
                        }
                    }
                }
            } else {
                for (tracked in activeAppHistory.values) {
                    tracked.rxSpeed = 0L
                    tracked.txSpeed = 0L
                    tracked.totalSpeed = 0L
                }
            }
        }

        // Clean up dead/silent apps:
        // Either silent for multiple syncs or device has been idle for > 3.0s
        val iterator = activeAppHistory.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val tracked = entry.value
            val timeSinceActive = now - tracked.lastActiveTime

            if (tracked.silentSyncCount >= 2 || (timeSinceActive > 3000L && totalSpeed < 1000L)) {
                iterator.remove()
            }
        }

        // Sort active apps by total speed descending
        val sortedTracked = activeAppHistory.values
            .filter { it.totalSpeed > 0L || (now - it.lastActiveTime) < 2000L }
            .sortedWith(compareByDescending<TrackedApp> { it.totalSpeed }.thenByDescending { it.lastActiveTime })
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
                    val pkg = packages[0]
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
