package com.example.waves

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Перетаскивание окна по экранным (raw) координатам. Так окно не «дёргается»:
 * локальные координаты сдвигаются вместе с окном и дают обратную связь, raw — нет.
 */
class DragLayout(ctx: Context, private val onMove: (Float, Float) -> Unit) : FrameLayout(ctx) {
    var zone: Rect? = null          // откуда можно тянуть; null = всё окно
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var armed = false; private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; lastX = downX; lastY = downY; dragging = false
                armed = zone?.contains(ev.x.toInt(), ev.y.toInt()) ?: true
            }
            MotionEvent.ACTION_MOVE -> if (armed && !dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                dragging = true; lastX = ev.rawX; lastY = ev.rawY
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                onMove(ev.rawX - lastX, ev.rawY - lastY); lastX = ev.rawX; lastY = ev.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }
}

class OverlayService : LifecycleService(), SavedStateRegistryOwner {
    private val ssc = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    private lateinit var wm: WindowManager
    private var root: DragLayout? = null
    private var px = 40f; private var py = 300f

    override fun onCreate() {
        super.onCreate()
        ssc.performAttach(); ssc.performRestore(null)
        Style.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("overlay", "Оверлей", NotificationManager.IMPORTANCE_MIN))
        startForeground(2, NotificationCompat.Builder(this, "overlay")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Оверлей плеера включён").build())
        wm = getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = px.roundToInt(); y = py.roundToInt() }
        val layout = DragLayout(this) { dx, dy ->
            px += dx; py += dy; lp.x = px.roundToInt(); lp.y = py.roundToInt()
            root?.let { wm.updateViewLayout(it, lp) }
        }
        // владельцы нужны корневому view окна
        layout.setViewTreeLifecycleOwner(this)
        layout.setViewTreeSavedStateRegistryOwner(this)
        val cv = ComposeView(this).apply {
            setContent {
                LaunchedEffect(Style.blurBehind) {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        if (Style.blurBehind) { lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND; lp.blurBehindRadius = 40 }
                        else { lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv(); lp.blurBehindRadius = 0 }
                        root?.let { wm.updateViewLayout(it, lp) }
                    }
                }
                WavesTheme { OverlayPlayer(onZone = { layout.zone = it }, onClose = { stopSelf() }) }
            }
        }
        layout.addView(cv)
        root = layout
        wm.addView(layout, lp)
    }

    override fun onDestroy() {
        root?.let { wm.removeView(it) }; root = null
        super.onDestroy()
    }
}
