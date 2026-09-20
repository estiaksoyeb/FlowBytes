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
import kotlin.math.max

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

    fun start(scope: CoroutineScope) {
        if (sampleJob?.isActive == true) return

        // Initialize baselines
        lastTotalRxBytes = TrafficStats.getTotalRxBytes()
        lastTotalTxBytes = TrafficStats.getTotalTxBytes()
        lastSampleTime = System.currentTimeMillis()
        lastUidStats.clear()
        captureUidStats(lastUidStats)

        sampleJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1200L)
                updateSample()
            }
        }
    }

    fun stop() {
        sampleJob?.cancel()
        sampleJob = null
    }

    fun toggleExpanded() {
        _state.value = _state.value.copy(isExpanded = !_state.value.isExpanded)
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

        // 2. Per-UID Speeds via NetworkStatsManager snapshot delta
        val currentUidStats = mutableMapOf<Int, Pair<Long, Long>>()
        captureUidStats(currentUidStats)

        val activeList = mutableListOf<ActiveAppTraffic>()

        for ((uid, currentBytes) in currentUidStats) {
            val previousBytes = lastUidStats[uid] ?: currentBytes
            val rxDiff = (currentBytes.first - previousBytes.first).coerceAtLeast(0L)
            val txDiff = (currentBytes.second - previousBytes.second).coerceAtLeast(0L)
            val appTotalDiff = rxDiff + txDiff

            if (appTotalDiff > 0) {
                val appRxSpeed = (rxDiff / dtSec).toLong()
                val appTxSpeed = (txDiff / dtSec).toLong()
                val appTotalSpeed = appRxSpeed + appTxSpeed

                val info = resolveAppInfo(uid)
                activeList.add(
                    ActiveAppTraffic(
                        uid = uid,
                        packageName = info.packageName,
                        appName = info.appName,
                        icon = info.icon,
                        rxSpeed = appRxSpeed,
                        txSpeed = appTxSpeed,
                        totalSpeed = appTotalSpeed
                    )
                )
            }
        }

        // Update previous snapshot
        lastUidStats = currentUidStats

        // Sort by speed descending
        activeList.sortByDescending { it.totalSpeed }

        // Compute proportions for UI progress bars (relative to max active app speed)
        val maxActiveSpeed = activeList.firstOrNull()?.totalSpeed?.coerceAtLeast(1L) ?: 1L
        val formattedList = activeList.take(3).map { app ->
            val rxRatio = (app.rxSpeed.toFloat() / maxActiveSpeed).coerceIn(0f, 1f)
            val txRatio = (app.txSpeed.toFloat() / maxActiveSpeed).coerceIn(0f, 1f)
            app.copy(rxRatio = rxRatio, txRatio = txRatio)
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
        val startTime = endTime - (24L * 60 * 60 * 1000)

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
                // Permission or system failure, ignore gracefully
            }
        }
    }

    private fun resolveAppInfo(uid: Int): CachedAppInfo {
        appInfoCache[uid]?.let { return it }

        // Special system UIDs
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
                            packageManager.getApplicationIcon(appInfo).toBitmap(width = 64, height = 64).asImageBitmap()
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
            packageManager.getApplicationIcon(appInfo).toBitmap(width = 64, height = 64).asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}
