package com.ray.flowmeter.floating

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.max

/**
 * Custom speed formatter strictly for the floating window.
 * Displays exact bytes (e.g. 252 B/s) without altering global notification/widget formatting.
 */
fun formatFloatingSpeed(bytesPerSecond: Long): String {
    val locale = Locale.getDefault()
    val bytes = bytesPerSecond.coerceAtLeast(0L)
    return when {
        bytes >= 1_000_000_000L -> String.format(locale, "%.1f GB/s", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(locale, "%.1f MB/s", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(locale, "%.0f KB/s", bytes / 1_000.0)
        else -> String.format(locale, "%d B/s", bytes)
    }
}

class LiveAppTrafficSampler(private val context: Context) {

    private val _state = MutableStateFlow(FloatingTrafficState())
    val state: StateFlow<FloatingTrafficState> = _state.asStateFlow()

    private var sampleJob: Job? = null
    private val packageManager: PackageManager = context.packageManager
    private val networkStatsManager: NetworkStatsManager? = context.getSystemService(NetworkStatsManager::class.java)

    // Cached app metadata to avoid repeated PackageManager queries
    private data class CachedAppInfo(
        val appName: String,
        val packageName: String,
        val icon: ImageBitmap?
    )
    private val appInfoCache = mutableMapOf<Int, CachedAppInfo>()

    // Snapshots for delta calculations
    private var lastTotalRxBytes: Long = 0L
    private var lastTotalTxBytes: Long = 0L
    private var lastSampleTime: Long = 0L
    private var lastUidStats = mutableMapOf<Int, Pair<Long, Long>>() // uid -> (rx, tx)

    // Active app tracking with decay/grace period (prevents rapid flickering)
    private data class TrackedApp(
        val uid: Int,
        var rxSpeed: Long,
        var txSpeed: Long,
        var totalSpeed: Long,
        var lastActiveTime: Long
    )
    private val activeAppHistory = mutableMapOf<Int, TrackedApp>()

    fun start(scope: CoroutineScope) {
        if (sampleJob?.isActive == true) return

        // Initialize baselines
        lastTotalRxBytes = TrafficStats.getTotalRxBytes()
        lastTotalTxBytes = TrafficStats.getTotalTxBytes()
        lastSampleTime = System.currentTimeMillis()
        lastUidStats.clear()
        activeAppHistory.clear()
        captureUidStats(lastUidStats)

        sampleJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500L) // Stable 1.5s interval
                updateSample()
            }
        }
    }

    fun stop() {
        sampleJob?.cancel()
        sampleJob = null
        activeAppHistory.clear()
    }

    private fun updateSample() {
        val now = System.currentTimeMillis()
        val dtSec = max((now - lastSampleTime) / 1000.0, 0.5)

        // 1. Total Speeds via TrafficStats (instantaneous & accurate)
        val currentTotalRx = TrafficStats.getTotalRxBytes()
        val currentTotalTx = TrafficStats.getTotalTxBytes()

        val totalRxDiff = (currentTotalRx - lastTotalRxBytes).coerceAtLeast(0L)
        val totalTxDiff = (currentTotalTx - lastTotalTxBytes).coerceAtLeast(0L)

        val rxSpeed = (totalRxDiff / dtSec).toLong()
        val txSpeed = (totalTxDiff / dtSec).toLong()
        val totalSpeed = rxSpeed + txSpeed

        lastTotalRxBytes = currentTotalRx
        lastTotalTxBytes = currentTotalTx
        lastSampleTime = now

        // 2. Per-UID Speeds via NetworkStatsManager delta
        val currentUidStats = mutableMapOf<Int, Pair<Long, Long>>()
        captureUidStats(currentUidStats)

        // Identify current traffic deltas
        val activeUidsThisCycle = mutableSetOf<Int>()

        for ((uid, currentBytes) in currentUidStats) {
            val previousBytes = lastUidStats[uid] ?: currentBytes
            val rxDiff = (currentBytes.first - previousBytes.first).coerceAtLeast(0L)
            val txDiff = (currentBytes.second - previousBytes.second).coerceAtLeast(0L)
            val appTotalDiff = rxDiff + txDiff

            if (appTotalDiff > 0) {
                val appRxSpeed = (rxDiff / dtSec).toLong()
                val appTxSpeed = (txDiff / dtSec).toLong()
                val appTotalSpeed = appRxSpeed + appTxSpeed

                activeAppHistory[uid] = TrackedApp(
                    uid = uid,
                    rxSpeed = appRxSpeed,
                    txSpeed = appTxSpeed,
                    totalSpeed = appTotalSpeed,
                    lastActiveTime = now
                )
                activeUidsThisCycle.add(uid)
            }
        }

        // Update previous snapshot
        lastUidStats = currentUidStats

        // For apps that had traffic recently but were silent in this cycle:
        // Keep them for a 3.5s grace period with decaying speed so the UI doesn't jump
        val iterator = activeAppHistory.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val uid = entry.key
            val tracked = entry.value

            if (uid !in activeUidsThisCycle) {
                val elapsedSinceActive = now - tracked.lastActiveTime
                if (elapsedSinceActive > 3500L) {
                    iterator.remove()
                } else {
                    // Decay speed smoothly to 0
                    tracked.rxSpeed = (tracked.rxSpeed * 0.25).toLong()
                    tracked.txSpeed = (tracked.txSpeed * 0.25).toLong()
                    tracked.totalSpeed = tracked.rxSpeed + tracked.txSpeed
                }
            }
        }

        // Sort active apps by total speed (or freshness)
        val sortedTracked = activeAppHistory.values
            .sortedWith(compareByDescending<TrackedApp> { it.totalSpeed }.thenByDescending { it.lastActiveTime })
            .take(3)

        val maxActiveSpeed = sortedTracked.firstOrNull()?.totalSpeed?.coerceAtLeast(1L) ?: 1L

        val formattedList = sortedTracked.map { tracked ->
            val info = resolveAppInfo(tracked.uid)
            val rxRatio = (tracked.rxSpeed.toFloat() / maxActiveSpeed).coerceIn(0f, 1f)
            val txRatio = (tracked.txSpeed.toFloat() / maxActiveSpeed).coerceIn(0f, 1f)

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
            activeApps = formattedList
        )
    }

    private fun captureUidStats(outStats: MutableMap<Int, Pair<Long, Long>>) {
        val nsm = networkStatsManager ?: return
        val endTime = System.currentTimeMillis()
        // Query recent 5 minutes only for extreme speed and low CPU usage
        val startTime = endTime - (5L * 60 * 1000)

        val transports = listOf(
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_WIFI
        )

        for (transport in transports) {
            try {
                val stats = nsm.querySummary(transport, null, startTime, endTime)
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

        val resolved = when (uid) {
            Process.SYSTEM_UID -> CachedAppInfo("Android System", "android", loadSystemIcon())
            Process.SHELL_UID -> CachedAppInfo("Shell", "com.android.shell", null)
            1073 -> CachedAppInfo("Tethering & Hotspot", "tethering", null)
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
                    CachedAppInfo("UID $uid", "uid_$uid", null)
                }
            }
        }

        appInfoCache[uid] = resolved
        return resolved
    }

    private fun loadSystemIcon(): ImageBitmap? {
        return try {
            val appInfo = packageManager.getApplicationInfo("android", 0)
            packageManager.getApplicationIcon(appInfo).toBitmap(width = 48, height = 48).asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}
