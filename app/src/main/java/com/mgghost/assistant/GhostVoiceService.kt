package com.mgghost.assistant

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.*
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Optional wake listener. It is NEVER started automatically by MainActivity.
 * The user must switch Hey Ghost on while the activity is visible.
 * Android's public SpeechRecognizer is not a low-power hotword engine, so this
 * is an explicit opt-in compatibility mode rather than a claim of system-level
 * Google Assistant hotword behavior.
 */
class GhostVoiceService : Service() {
    companion object {
        const val ACTION = "com.mgghost.assistant.VOICE_COMMAND"
        const val ACTION_PAUSE = "com.mgghost.assistant.PAUSE_WAKE"
        const val ACTION_RESUME = "com.mgghost.assistant.RESUME_WAKE"
        const val ACTION_STOP = "com.mgghost.assistant.STOP_WAKE"
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var paused = false
    private var stopping = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("ghost", MODE_PRIVATE)
        if (!prefs.getBoolean("wake_enabled", false)) { stopSelf(); return }

        if (!hasAudioPermission() || !SpeechRecognizer.isRecognitionAvailable(this)) { stopSelf(); return }
        createForegroundNotification()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        startListening(350)
    }

    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun createForegroundNotification() {
        val channelId = "ghost_voice"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "MG Ghost Voice", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 100, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_ghost)
            .setContentTitle("MG Ghost — Hey Ghost is on")
            .setContentText("Wake listening is enabled. Tap to open MG Ghost.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(42, notification)
        } catch (_: SecurityException) { stopSelf() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { listening = true }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { listening = false }
        override fun onError(error: Int) {
            listening = false
            if (!stopping && !paused && getSharedPreferences("ghost", MODE_PRIVATE).getBoolean("wake_enabled", false)) {
                startListening(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 400 else 900)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            listening = false
            if (paused || stopping) return
            val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            val lower = phrase.lowercase(Locale.getDefault())
            val match = Regex("\\bhey[\\s,.-]+ghost\\b").find(lower)
            if (match == null) {
                startListening(250)
                return
            }

            paused = true
            try { recognizer?.cancel() } catch (_: Exception) {}
            showOverlay("listening")
            val command = phrase.substring(match.range.last + 1).trim().trimStart(',', ':', '-', '.')
            val payload = Intent(ACTION).setPackage(packageName)
                .putExtra("state", "processing")
                .putExtra("command", command.ifBlank { "__WAKE_ONLY__" })
            sendBroadcast(payload)

            // If the user removed MG Ghost from Recents, MainActivity's dynamic
            // receiver is gone. Bring the app back only when there is no existing
            // MG Ghost task; otherwise the broadcast above is enough. Android may
            // still block background Activity launches on some OEMs, so the overlay
            // and persistent notification remain the fallback.
            val hasTask = try {
                @Suppress("DEPRECATION")
                (getSystemService(ACTIVITY_SERVICE) as ActivityManager).appTasks.isNotEmpty()
            } catch (_: Exception) { true }
            if (!hasTask) {
                try {
                    startActivity(Intent(this@GhostVoiceService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("wake_command", command.ifBlank { "__WAKE_ONLY__" })
                    })
                } catch (_: Exception) {
                    // The persistent foreground notification still gives the user a
                    // direct way back into MG Ghost if the device blocks this launch.
                }
            }
        }
    }

    private fun startListening(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (stopping || paused || listening || recognizer == null || !hasAudioPermission()) return@postDelayed
            try {
                recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                })
            } catch (_: Exception) { startListening(1200) }
        }, delayMs)
    }

    private fun showOverlay(state: String) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
        try { startService(Intent(this, GhostOverlayService::class.java).putExtra("state", state)) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                paused = true; listening = false; handler.removeCallbacksAndMessages(null)
                try { recognizer?.cancel() } catch (_: Exception) {}
            }
            ACTION_RESUME -> {
                if (!stopping && getSharedPreferences("ghost", MODE_PRIVATE).getBoolean("wake_enabled", false)) {
                    paused = false; startListening(350)
                }
            }
            ACTION_STOP -> { getSharedPreferences("ghost", MODE_PRIVATE).edit().putBoolean("wake_enabled", false).apply(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not stop or cancel wake mode when the user swipes MG Ghost away from Recents.
        // The foreground service is intentionally independent of the Activity task.
        if (!stopping && getSharedPreferences("ghost", MODE_PRIVATE).getBoolean("wake_enabled", false)) {
            paused = false
            listening = false
            try { recognizer?.cancel() } catch (_: Exception) {}
            startListening(800)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
