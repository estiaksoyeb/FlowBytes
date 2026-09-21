package com.ray.flowmeter.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.ray.flowmeter.MainActivity
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.ray.flowmeter.ui.theme.FlowMeterTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class FloatingTrafficManager private constructor(private val context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: FloatingTrafficManager? = null

        fun getInstance(context: Context): FloatingTrafficManager {
            return instance ?: synchronized(this) {
                instance ?: FloatingTrafficManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var managerScope: CoroutineScope? = null
    private val sampler = LiveAppTrafficSampler(context)

    private var params: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var posX: Float = 100f
    private var posY: Float = 200f

    fun isWindowShowing(): Boolean = isShowing

    fun show() {
        if (isShowing) return
        if (!Settings.canDrawOverlays(context)) return

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        managerScope = scope

        posX = 100f
        posY = 200f

        val layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = posX.toInt()
            y = posY.toInt()
        }
        params = layoutParams

        val owner = OverlayLifecycleOwner().apply {
            onCreate()
            onStart()
        }
        lifecycleOwner = owner

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            owner.attachToView(this)

            setContent {
                FlowMeterTheme {
                    val state by sampler.state.collectAsState()

                    FloatingTrafficCard(
                        state = state,
                        onClose = { hide() },
                        onOpenApp = { openApp() },
                        modifier = Modifier.pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                updatePosition(dragAmount.x, dragAmount.y)
                            }
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(composeView, layoutParams)
            overlayView = composeView
            isShowing = true
            sampler.start(scope)
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
        }
    }

    fun openApp() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        if (!isShowing) return
        sampler.stop()

        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        cleanup()
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    private fun updatePosition(dx: Float, dy: Float) {
        val p = params ?: return
        val view = overlayView ?: return

        posX = (posX + dx).coerceAtLeast(0f)
        posY = (posY + dy).coerceAtLeast(0f)
        p.x = posX.toInt()
        p.y = posY.toInt()

        try {
            windowManager.updateViewLayout(view, p)
        } catch (_: Exception) {
        }
    }

    private fun cleanup() {
        lifecycleOwner?.apply {
            onPause()
            onStop()
            onDestroy()
        }
        lifecycleOwner = null
        overlayView = null
        params = null
        isShowing = false
        managerScope?.cancel()
        managerScope = null
    }
}
