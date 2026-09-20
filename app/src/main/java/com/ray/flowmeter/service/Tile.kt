package com.ray.flowmeter.service

import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ray.flowmeter.R
import com.ray.flowmeter.data.UserPreferencesRepository
import com.ray.flowmeter.utils.LocaleHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

// Quick Settings Tile Service that allows the user to quickly toggle foreground network monitoring
// directly from the Android system notification tray.
class SpeedTileService : TileService() {

    override fun attachBaseContext(newBase: Context) {
        val repository = UserPreferencesRepository(newBase)
        val languageCode = runBlocking {
            try {
                repository.language.first()
            } catch (_: Exception) {
                ""
            }
        }
        val context = LocaleHelper.applyLocale(newBase, languageCode)
        super.attachBaseContext(context)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: UserPreferencesRepository

    override fun onCreate() {
        super.onCreate()
        repository = UserPreferencesRepository(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    // Toggles network monitoring status when the user clicks the quick settings tile.
    override fun onClick() {
        super.onClick()
        serviceScope.launch {
            val currentState = repository.monitoringEnabled.first()
            val newState = !currentState
            repository.setMonitoringEnabled(newState)

            val intent = Intent(this@SpeedTileService, NetworkMonitoringService::class.java)
            if (newState) {
                startForegroundService(intent)
            } else {
                stopService(intent)
            }

            updateTile()
        }
    }

    private fun updateTile() {
        serviceScope.launch {
            val isEnabled = repository.monitoringEnabled.first()
            val tile = qsTile ?: return@launch

            tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (isEnabled) getString(R.string.tile_monitoring_on) else getString(R.string.tile_monitoring_off)
            tile.subtitle = getString(R.string.app_name)
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

// Quick Settings Tile Service to toggle the live per-app traffic floating window overlay.
class FloatingTrafficTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val manager = com.ray.flowmeter.floating.FloatingTrafficManager.getInstance(applicationContext)
        if (android.provider.Settings.canDrawOverlays(applicationContext)) {
            manager.toggle()
            updateTile()
        } else {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isShowing = com.ray.flowmeter.floating.FloatingTrafficManager.getInstance(applicationContext).isWindowShowing()
        tile.state = if (isShowing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_floating_traffic)
        tile.subtitle = getString(R.string.app_name)
        tile.updateTile()
    }
}

