package com.mgghost.assistant

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var speech: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var status: TextView
    private lateinit var conversationInput: EditText
    private lateinit var greeting: TextView
    private lateinit var recent: LinearLayout
    private lateinit var wake: Switch
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var manualListening = false
    private var pendingPermissionAction: (() -> Unit)? = null
    private var suppressPermissionReply = false

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            when (i.getStringExtra("state")) {
                "listening" -> showOverlay("listening")
                "processing" -> showOverlay("processing")
                "speaking" -> showOverlay("speaking")
            }
            val command = i.getStringExtra("command").orEmpty()
            if (command.isNotBlank()) {
                if (command == "__WAKE_ONLY__") {
                    addRecent("Wake word", "Hey Ghost detected")
                    say("Yes?")
                } else {
                    conversationInput.setText(command)
                    runCommand(command)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        conversationInput = findViewById(R.id.input)
        greeting = findViewById(R.id.greeting)
        recent = findViewById(R.id.recent)
        wake = findViewById(R.id.wake)

        greeting.text = greetingText()
        tts = TextToSpeech(this, this)
        setupSpeech()
        setupUi()

        // Restore the switch without firing its listener during Activity creation.
        // If wake mode was explicitly enabled before, the foreground service itself
        // owns the microphone lifecycle; reopening the UI must not create a second
        // recognizer session.
        val savedWake = getSharedPreferences("ghost", MODE_PRIVATE).getBoolean("wake_enabled", false)
        wake.setOnCheckedChangeListener(null)
        wake.isChecked = savedWake
        wake.setOnCheckedChangeListener { _, enabled ->
            getSharedPreferences("ghost", MODE_PRIVATE).edit().putBoolean("wake_enabled", enabled).apply()
            if (enabled) startWake() else stopWake(true)
        }

        ContextCompat.registerReceiver(
            this,
            voiceReceiver,
            IntentFilter(GhostVoiceService.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateOverlayPermissionButton()
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        val command = intent?.getStringExtra("wake_command")?.trim().orEmpty()
        if (command.isBlank()) return
        intent?.removeExtra("wake_command")
        if (command == "__WAKE_ONLY__") {
            addRecent("Wake word", "Hey Ghost detected")
            say("Yes?")
        } else {
            conversationInput.setText(command)
            runCommand(command)
        }
    }

    private fun setupUi() {
        findViewById<ImageButton>(R.id.talk).setOnClickListener { listen() }
        findViewById<ImageButton>(R.id.send).setOnClickListener {
            val text = conversationInput.text.toString().trim()
            if (text.isNotBlank()) {
                conversationInput.text.clear()
                runCommand(text)
            }
        }
        findViewById<Button>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.overlayPermission).setOnClickListener { requestOverlayPermission() }
        findViewById<ImageButton>(R.id.menu).setOnClickListener { showSettings() }

        findViewById<View>(R.id.quick_call).setOnClickListener { runCommand("call Mum") }
        findViewById<View>(R.id.quick_message).setOnClickListener { runCommand("text Mum saying hello") }
        findViewById<View>(R.id.quick_reminder).setOnClickListener { runCommand("set reminder") }
        findViewById<View>(R.id.quick_whatsapp).setOnClickListener { runCommand("open WhatsApp") }
        findViewById<View>(R.id.quick_weather).setOnClickListener { runCommand("what's the weather") }
        findViewById<View>(R.id.quick_more).setOnClickListener { openMore() }
        findViewById<TextView>(R.id.nav_more).setOnClickListener { openMore() }
        findViewById<TextView>(R.id.nav_chat).setOnClickListener { conversationInput.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(conversationInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
        findViewById<TextView>(R.id.nav_calls).setOnClickListener { runCommand("call Mum") }

        conversationInput.setOnEditorActionListener { _, _, _ ->
            val text = conversationInput.text.toString().trim()
            if (text.isNotBlank()) {
                conversationInput.text.clear()
                runCommand(text)
            }
            true
        }
        wake.setOnCheckedChangeListener { _, enabled ->
            getSharedPreferences("ghost", MODE_PRIVATE).edit().putBoolean("wake_enabled", enabled).apply()
            if (enabled) startWake() else stopWake(true)
        }
    }

    private fun greetingText(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Good morning! 👋"
            hour < 18 -> "Good afternoon! 👋"
            else -> "Good evening! 👋"
        }
    }

    private fun setupSpeech() {
        speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { manualListening = true; status.text = "Listening…"; showOverlay("listening") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { manualListening = false; status.text = "Processing…"; showOverlay("processing") }
            override fun onError(error: Int) {
                manualListening = false
                status.text = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "Ready" else "Voice error — ready"
                hideOverlay()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                manualListening = false
                val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (result.isBlank()) { status.text = "Ready"; hideOverlay(); return }
                conversationInput.setText(result)
                runCommand(result)
            }
        })
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun listen() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermission(Manifest.permission.RECORD_AUDIO, 101) { listen() }
            return
        }
        try {
            speech.cancel()
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        } catch (_: Exception) {
            say("I couldn't start voice recognition.")
        }
    }

    private fun startWake() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            wake.isChecked = false
            suppressPermissionReply = true
            requestPermission(Manifest.permission.RECORD_AUDIO, 102) {
                suppressPermissionReply = false
                wake.isChecked = true
                startWake()
            }
            return
        }
        try {
            // This is intentionally started only from this visible Activity.
            ContextCompat.startForegroundService(this, Intent(this, GhostVoiceService::class.java))
            status.text = "Hey Ghost is on"
            addRecent("Wake mode", "Listening for Hey Ghost")
            maybeRequestBatteryExemption()
        } catch (_: Exception) {
            wake.isChecked = false
            getSharedPreferences("ghost", MODE_PRIVATE).edit().putBoolean("wake_enabled", false).apply()
            say("Android did not allow wake mode to start. Keep the app open and try again.")
        }
    }

    private fun maybeRequestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("Keep Hey Ghost running")
            .setMessage("Android may stop background microphone apps when battery saving is active. Allow MG Ghost to use unrestricted battery so Hey Ghost can keep listening after you remove the app from Recents.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }.show()
    }

    private fun stopWake(speak: Boolean) {
        stopService(Intent(this, GhostVoiceService::class.java))
        hideOverlay()
        status.text = "Ready"
        if (speak) say("Wake mode is off.")
    }

    private fun runCommand(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        addRecent("You", clean)
        showOverlay("processing")
        val action = CommandRouter.route(clean)
        if (action.confirmation) confirm(action) else execute(action)
    }

    private fun confirm(action: GhostAction) {
        hideOverlay()
        val message = if (action.type == "call") "Call ${action.value}?" else "Send this message?\n\n${extractSmsPreview(action.value)}"
        AlertDialog.Builder(this)
            .setTitle("Confirm action")
            .setMessage(message)
            .setNegativeButton("Cancel") { _, _ -> say("Cancelled.") }
            .setPositiveButton("Continue") { _, _ -> execute(action) }
            .show()
    }

    private fun extractSmsPreview(raw: String): String =
        Regex("(?i)(?:text|message)\\s+(.+?)\\s+(?:saying|that says)\\s+(.+)").find(raw)?.groupValues?.get(2) ?: raw

    private fun execute(action: GhostAction) {
        when (action.type) {
            "flash" -> setFlash(action.value == "on")
            "camera" -> openCamera()
            "app_name" -> openAppByName(action.value)
            "settings" -> openSettings(Settings.ACTION_SETTINGS, "Settings")
            "wifi" -> openSettings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")
            "bluetooth" -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")
            "battery" -> openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Battery settings")
            "dnd" -> openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "Do Not Disturb access")
            "time" -> say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
            "date" -> say("Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()))
            "volume" -> changeVolume(action.value == "up")
            "alarm" -> setAlarm()
            "timer" -> setTimer(action.value)
            "reminder" -> setReminder(action.value)
            "weather" -> search("weather today")
            "maps" -> openMaps(action.value)
            "search" -> search(action.value)
            "call" -> call(action.value)
            "sms" -> sms(action.value)
            "help" -> showHelp()
            "ai" -> askAi(action.value)
        }
    }

    private fun openAppByName(request: String) {
        val wanted = request.lowercase(Locale.getDefault()).trim()
            .replace(Regex("\\s+"), " ")
        val aliases = mapOf(
            "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube", "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm", "google maps" to "com.google.android.apps.maps", "maps" to "com.google.android.apps.maps",
            "facebook" to "com.facebook.katana", "instagram" to "com.instagram.android", "telegram" to "org.telegram.messenger",
            "spotify" to "com.spotify.music", "tiktok" to "com.zhiliaoapp.musically"
        )
        val direct = aliases[wanted]?.let { packageManager.getLaunchIntentForPackage(it) }
        val intent = direct ?: packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL
        ).firstOrNull { info ->
            val label = packageManager.getApplicationLabel(info.activityInfo.applicationInfo).toString().lowercase(Locale.getDefault())
            label == wanted || label.contains(wanted) || wanted.contains(label)
        }?.let { info ->
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(info.activityInfo.packageName)
        }
        if (intent == null) {
            say("I couldn't find an app called $request.")
            return
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            addRecent("Opened app", request)
            say("Opening $request.")
        } catch (_: Exception) { say("I found $request, but Android could not open it.") }
    }

    private fun openCamera() {
        try {
            startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
            addRecent("Camera", "Opened camera")
            say("Opening camera.")
        } catch (_: Exception) { say("No camera app is available.") }
    }

    private fun setFlash(on: Boolean) {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            requestPermission(Manifest.permission.CAMERA, 103) { setFlash(on) }
            return
        }
        try {
            val manager = getSystemService(CameraManager::class.java)
            val cameraId = manager.cameraIdList.firstOrNull { id -> manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (cameraId == null) { say("No flashlight is available on this phone."); return }
            manager.setTorchMode(cameraId, on)
            addRecent("Flashlight", if (on) "Turned on" else "Turned off")
            say(if (on) "Flashlight on." else "Flashlight off.")
        } catch (_: Exception) { say("Android blocked flashlight control.") }
    }

    private fun openSettings(action: String, label: String) {
        try { startActivity(Intent(action)); addRecent("Settings", label); say("Opening $label.") } catch (_: Exception) { say("I can't open $label.") }
    }

    private fun changeVolume(up: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        say(if (up) "Volume up." else "Volume down.")
    }

    private fun setAlarm() {
        try {
            startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_MESSAGE, "MG GHOST alarm"))
            say("Opening the alarm screen.")
        } catch (_: Exception) { say("I couldn't open alarms.") }
    }

    private fun setTimer(raw: String) {
        val number = Regex("(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val seconds = if (raw.lowercase().contains("hour")) number * 3600 else if (raw.lowercase().contains("second")) number else number * 60
        try {
            startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "MG GHOST timer")
            })
            say("Opening a timer for $number ${if (seconds >= 3600) "hour" else if (seconds < 60) "seconds" else "minutes"}.")
        } catch (_: Exception) { say("I couldn't open the timer.") }
    }

    private fun setReminder(raw: String) {
        val lower = raw.lowercase(Locale.getDefault())
        val minuteMatch = Regex("in\\s+(\\d+)\\s*(minute|minutes|min|mins)").find(lower)
        val title = Regex("(?i)(?:to|that)\\s+(.+)$").find(raw)?.groupValues?.get(1)?.trim()
            ?: "MG GHOST reminder"
        if (minuteMatch != null) {
            val mins = minuteMatch.groupValues[1].toLong()
            scheduleReminder(title, System.currentTimeMillis() + mins * 60_000L)
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 6, 28, 4) }
        val message = EditText(this).apply { hint = "What should I remind you about?"; setSingleLine(false) }
        val time = TimePicker(this).apply { setIs24HourView(false) }
        box.addView(message)
        box.addView(time)
        AlertDialog.Builder(this).setTitle("Set a reminder").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Set") { _, _ ->
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, time.hour); set(Calendar.MINUTE, time.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    if (time.hour < Calendar.getInstance().get(Calendar.HOUR_OF_DAY) || (time.hour == Calendar.getInstance().get(Calendar.HOUR_OF_DAY) && time.minute <= Calendar.getInstance().get(Calendar.MINUTE))) add(Calendar.DAY_OF_YEAR, 1)
                }
                scheduleReminder(message.text.toString().ifBlank { "MG GHOST reminder" }, cal.timeInMillis)
            }.show()
    }

    private fun scheduleReminder(title: String, triggerAt: Long) {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermission(Manifest.permission.POST_NOTIFICATIONS, 108) { scheduleReminder(title, triggerAt) }
            return
        }
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java).putExtra("title", title)
        val requestCode = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val pi = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        addRecent("Reminder", title)
        say("Reminder set.")
    }

    private fun openMaps(raw: String) {
        val q = raw.replace(Regex("(?i)navigate|directions|take me to"), "").trim()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q.ifBlank { "my location" }))))
            say("Opening maps.")
        } catch (_: Exception) { say("I couldn't open maps.") }
    }

    private fun search(raw: String) {
        val q = raw.replace(Regex("(?i)search for|search|look up"), "").trim()
        if (q.isBlank()) { say("What should I search for?"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            say("Searching for $q.")
        } catch (_: Exception) { say("I couldn't open search.") }
    }

    private fun findNumber(name: String): String? {
        val digits = name.replace(Regex("[^+0-9]"), "")
        if (digits.length >= 5) return digits
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun call(target: String) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) { requestPermission(Manifest.permission.READ_CONTACTS, 106) { call(target) }; return }
        val number = findNumber(target)
        if (number == null) { say("I couldn't find $target in your contacts."); return }
        if (!hasPermission(Manifest.permission.CALL_PHONE)) { requestPermission(Manifest.permission.CALL_PHONE, 104) { call(target) }; return }
        try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))); addRecent("Called", target); say("Calling $target.") }
        catch (_: Exception) { say("I couldn't place the call.") }
    }

    private fun sms(raw: String) {
        val match = Regex("(?i)(?:text|message)(?:\\s+to)?\\s+(.+?)\\s+(?:saying|that says)\\s+(.+)").find(raw)
        if (match == null) { say("Say: text Mum saying hello"); return }
        val target = match.groupValues[1].trim(); val body = match.groupValues[2].trim()
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) { requestPermission(Manifest.permission.READ_CONTACTS, 107) { sms(raw) }; return }
        val number = findNumber(target) ?: run { say("I couldn't find $target in your contacts."); return }
        if (!hasPermission(Manifest.permission.SEND_SMS)) { requestPermission(Manifest.permission.SEND_SMS, 105) { sms(raw) }; return }
        try { SmsManager.getDefault().sendTextMessage(number, null, body, null, null); addRecent("Sent a message", target); say("Message sent.") }
        catch (_: Exception) { say("I couldn't send the message.") }
    }

    private fun askAi(prompt: String) {
        status.text = "Thinking…"
        showOverlay("processing")
        GhostApi.ask(this, prompt) { reply, error -> runOnUiThread { say(reply ?: error ?: "AI request failed.") } }
    }

    private fun openMore() {
        startActivity(Intent(this, MoreActivity::class.java))
    }

    private fun showHelp() {
        val examples = "Try commands like:\n\n• Open WhatsApp\n• Open YouTube\n• Open Chrome\n• Turn on flashlight\n• Call Mum\n• Text Mum saying hello\n• Set a timer for 10 minutes\n• Set a reminder in 10 minutes to call Mum\n• What time is it?\n• Navigate to Ikeja\n• Search for something"
        AlertDialog.Builder(this).setTitle("What GHOST can do").setMessage(examples).setPositiveButton("Got it", null).show()
    }

    private fun say(text: String) {
        addRecent("GHOST", text)
        status.text = "Speaking…"
        showOverlay("speaking")
        if (!ttsReady) { pendingSpeech = text; return }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ghost_${System.nanoTime()}") == TextToSpeech.ERROR) finishSpeaking()
    }

    private fun finishSpeaking() {
        status.text = "Ready"
        hideOverlay()
        if (wake.isChecked) {
            try { startService(Intent(this, GhostVoiceService::class.java).setAction(GhostVoiceService.ACTION_RESUME)) } catch (_: Exception) {}
        }
    }

    override fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts.language = Locale.getDefault()
        tts.setSpeechRate(0.96f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { runOnUiThread { status.text = "Speaking…" } }
            override fun onDone(utteranceId: String?) { runOnUiThread { finishSpeaking() } }
            override fun onError(utteranceId: String?) { runOnUiThread { finishSpeaking() } }
        })
        pendingSpeech?.let { pendingSpeech = null; say(it) }
    }

    private fun requestPermission(permission: String, code: Int, after: () -> Unit) {
        pendingPermissionAction = after
        requestPermissions(arrayOf(permission), code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (granted) action?.invoke() else if (!suppressPermissionReply) say("That permission is needed for this action.")
        suppressPermissionReply = false
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    private fun updateOverlayPermissionButton() {
        findViewById<View>(R.id.overlayPermission).visibility = if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
    }

    private fun showOverlay(state: String) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
        try { startService(Intent(this, GhostOverlayService::class.java).putExtra("state", state)) } catch (_: Exception) {}
    }

    private fun hideOverlay() { try { stopService(Intent(this, GhostOverlayService::class.java)) } catch (_: Exception) {} }

    private fun addRecent(label: String, detail: String) {
        val empty = findViewById<TextView>(R.id.recent_empty)
        empty?.visibility = View.GONE
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(8, 8, 8, 8) }
        val icon = TextView(this).apply { text = if (label == "GHOST") "👻" else "•"; textSize = 20f; gravity = android.view.Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(42, 42) }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        text.addView(TextView(this).apply { this.text = label; textSize = 13f; setTextColor(Color.rgb(21,33,59)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        text.addView(TextView(this).apply { this.text = detail; textSize = 12f; setTextColor(Color.rgb(113,128,154)); maxLines = 2 })
        row.addView(icon); row.addView(text)
        recent.addView(row, 0)
        while (recent.childCount > 6) recent.removeViewAt(recent.childCount - 1)
    }

    private fun showSettings() {
        val message = "MG GHOST connects automatically.\n\nYou do not need to enter an OpenAI key, database URL, or backend token. The OpenAI secret stays on the MG GHOST server.\n\nBackend: " + BuildConfig.API_BASE_URL
        AlertDialog.Builder(this)
            .setTitle("MG GHOST settings")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayPermissionButton()
        val enabled = getSharedPreferences("ghost", MODE_PRIVATE).getBoolean("wake_enabled", false)
        if (enabled && !isWakeServiceRunning()) {
            wake.setOnCheckedChangeListener(null)
            wake.isChecked = true
            wake.setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("ghost", MODE_PRIVATE).edit().putBoolean("wake_enabled", checked).apply()
                if (checked) startWake() else stopWake(true)
            }
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) startWake()
        }
    }

    private fun isWakeServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE).any { it.service.className == GhostVoiceService::class.java.name }
    }

    override fun onDestroy() {
        try { speech.cancel(); speech.destroy() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        try { unregisterReceiver(voiceReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
