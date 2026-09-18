package com.mgghost.assistant

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageButton
import android.graphics.Typeface

class GhostOverlayService : Service() {
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var stateView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 12, 18, 12)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 46f
                setStroke(2, Color.rgb(215, 227, 246))
            }
            elevation = 18f
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
        }
        val avatar = ImageView(this).apply { setImageResource(R.drawable.ic_ghost); layoutParams = LinearLayout.LayoutParams(46, 46) }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        text.addView(TextView(this).apply { this.text = "MG Ghost AI"; textSize = 15f; setTextColor(Color.rgb(21,33,59)); setTypeface(typeface, Typeface.BOLD) })
        stateView = TextView(this).apply { textSize = 13f; setTextColor(Color.rgb(90,108,138)); this.text = "Listening…" }
        text.addView(stateView)
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setColorFilter(Color.rgb(100,115,140)); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { stopSelf() }
            layoutParams = LinearLayout.LayoutParams(40, 40)
        }
        root!!.addView(avatar); root!!.addView(text); root!!.addView(close)
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 28 }
        try {
            wm!!.addView(root, params)
            root!!.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(AccelerateDecelerateInterpolator()).start()
        } catch (_: Exception) { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = intent?.getStringExtra("state") ?: return START_NOT_STICKY
        stateView?.text = when (state) {
            "listening" -> "● Listening…"
            "processing" -> "◌ Thinking…"
            "speaking" -> "◉ Speaking…"
            else -> "Ready"
        }
        if (state == "speaking") pulse()
        if (state == "done") handler.postDelayed({ stopSelf() }, 800)
        return START_NOT_STICKY
    }

    private fun pulse() {
        root?.animate()?.scaleX(1.02f)?.scaleY(1.02f)?.setDuration(450)?.withEndAction {
            root?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(450)?.start()
        }?.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { root?.let { wm?.removeView(it) } } catch (_: Exception) {}
        root = null; wm = null; stateView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
