package com.example.waves

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class OverlayService : LifecycleService(), SavedStateRegistryOwner {
    private val ssc = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    private lateinit var wm: WindowManager
    private var view: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        Style.init(this)
        ssc.performAttach(); ssc.performRestore(null)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("overlay", "Оверлей", NotificationManager.IMPORTANCE_MIN))
        startForeground(2, NotificationCompat.Builder(this, "overlay")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Оверлей плеера включён").build())
        wm = getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 300 }
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                LaunchedEffect(Style.blurBehind) {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        if (Style.blurBehind) { lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND; lp.blurBehindRadius = 40 }
                        else { lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv(); lp.blurBehindRadius = 0 }
                        view?.let { wm.updateViewLayout(it, lp) }
                    }
                }
                WavesTheme {
                    OverlayPlayer(
                        onDrag = { dx, dy -> lp.x += dx.toInt(); lp.y += dy.toInt(); view?.let { wm.updateViewLayout(it, lp) } },
                        onClose = { stopSelf() })
                }
            }
        }
        view = v
        wm.addView(v, lp)
    }

    override fun onDestroy() {
        view?.let { wm.removeView(it) }; view = null
        super.onDestroy()
    }
}
