package com.focuslock.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.concurrent.TimeUnit

class OverlayService : Service() {

    private val tag = "OverlayService"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayMode: OverlayMode = OverlayMode.HARD
    private var blockedAppLabel: String = "this app"
    private val handler = Handler(Looper.getMainLooper())

    private val freeZoneGuardRunnable = object : Runnable {
        override fun run() {
            if (TimeZoneHelper.getCurrentZone() == TimeZoneHelper.BlockZone.FREE) {
                Log.d(tag, "Free zone began; removing overlay cleanly")
                stopSelf()
                return
            }
            handler.postDelayed(this, FREE_ZONE_CHECK_MILLIS)
        }
    }

    private val hardBlockCountdownRunnable = object : Runnable {
        override fun run() {
            updateHardBlockCountdown()
            handler.postDelayed(this, TimeUnit.MINUTES.toMillis(1))
        }
    }

    private lateinit var motivationalPrompts: Array<String>
    private var currentPromptIndex = 0

    private val softBlockPromptRunnable = object : Runnable {
        override fun run() {
            updateSoftBlockPrompt()
            handler.postDelayed(this, TimeUnit.SECONDS.toMillis(8))
        }
    }

    companion object {
        const val EXTRA_OVERLAY_MODE = "extra_overlay_mode"
        const val EXTRA_BLOCKED_APP_LABEL = "extra_blocked_app_label"

        private const val NOTIFICATION_CHANNEL_ID = "overlay_service_channel"
        private const val NOTIFICATION_ID = 2
        private const val SESSION_UNLOCK_PREFS = "session_unlock_prefs"
        private const val KEY_LAST_UNLOCK_TIMESTAMP = "last_unlock_timestamp"
        private const val SESSION_COOLDOWN_MILLIS = 30 * 60 * 1000L
        private const val FREE_ZONE_CHECK_MILLIS = 10_000L

        @Volatile
        var isRunning = false
            private set
    }

    enum class OverlayMode {
        HARD,
        SOFT
    }

    override fun onCreate() {
        super.onCreate()
        PasswordManager.initialize(applicationContext)
        motivationalPrompts = resources.getStringArray(R.array.motivational_prompts)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (FocusLockState.isPaused(this)) {
            Log.d(tag, "FocusLock is paused; overlay request ignored")
            stopSelf()
            return START_NOT_STICKY
        }

        if (TimeZoneHelper.getCurrentZone() == TimeZoneHelper.BlockZone.FREE) {
            Log.d(tag, "Free zone is active; overlay request ignored")
            stopSelf()
            return START_NOT_STICKY
        }

        val requestedMode = intent?.getStringExtra(EXTRA_OVERLAY_MODE)
            ?.let { modeName -> runCatching { OverlayMode.valueOf(modeName) }.getOrNull() }
            ?: run {
                Log.w(tag, "Overlay request had no valid mode; stopping safely")
                stopSelf()
                return START_NOT_STICKY
            }

        val requestedAppLabel = intent?.getStringExtra(EXTRA_BLOCKED_APP_LABEL)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.generic_blocked_app_name)

        if (isRunning && overlayView != null &&
            overlayMode == requestedMode && blockedAppLabel == requestedAppLabel
        ) {
            Log.d(tag, "Existing overlay already matches this request")
            return START_NOT_STICKY
        }

        overlayMode = requestedMode
        blockedAppLabel = requestedAppLabel

        if (!Settings.canDrawOverlays(this)) {
            Log.w(tag, "Overlay permission missing; request ignored")
            stopSelf()
            return START_NOT_STICKY
        }

        if (overlayMode == OverlayMode.SOFT && isRecentlyUnlocked()) {
            Log.d(tag, "Soft block cooldown is active; overlay request ignored")
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        showOverlay(overlayMode)
        handler.removeCallbacks(freeZoneGuardRunnable)
        handler.postDelayed(freeZoneGuardRunnable, FREE_ZONE_CHECK_MILLIS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(hardBlockCountdownRunnable)
        handler.removeCallbacks(softBlockPromptRunnable)
        handler.removeCallbacks(freeZoneGuardRunnable)
        removeOverlay()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val safetyIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_SAFETY_CONTROLS, true)
        }
        val safetyPendingIntent = PendingIntent.getActivity(
            this,
            0,
            safetyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_body))
            .setSmallIcon(R.drawable.ic_lock_notification)
            .setContentIntent(safetyPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showOverlay(mode: OverlayMode) {
        removeOverlay()
        handler.removeCallbacks(hardBlockCountdownRunnable)
        handler.removeCallbacks(softBlockPromptRunnable)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
        }

        overlayView = when (mode) {
            OverlayMode.HARD -> inflater.inflate(R.layout.overlay_hard_block, null).also { view ->
                layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                setupHardBlockListeners(view)
                handler.post(hardBlockCountdownRunnable)
            }

            OverlayMode.SOFT -> inflater.inflate(R.layout.overlay_soft_block, null).also { view ->
                layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                setupSoftBlockListeners(view)
                handler.post(softBlockPromptRunnable)
            }
        }

        try {
            windowManager?.addView(overlayView, layoutParams)
            isRunning = true
            Log.d(tag, "Overlay shown in $mode mode for $blockedAppLabel")
        } catch (error: RuntimeException) {
            Log.e(tag, "Could not attach overlay", error)
            overlayView = null
            isRunning = false
            stopSelf()
        }
    }

    private fun setupHardBlockListeners(view: View) {
        view.findViewById<TextView>(R.id.blocked_app_text).text =
            getString(R.string.hard_block_app_locked_message, blockedAppLabel)

        view.findViewById<Button>(R.id.go_home_button).setOnClickListener {
            goHome()
            stopSelf()
        }

        view.findViewById<Button>(R.id.safety_controls_button).setOnClickListener {
            openSafetyControls()
        }

        updateHardBlockCountdown()
    }

    private fun setupSoftBlockListeners(view: View) {
        val passwordEditText = view.findViewById<EditText>(R.id.password_edit_text)
        val journalEditText = view.findViewById<EditText>(R.id.journal_edit_text)
        val lineCounterTextView = view.findViewById<TextView>(R.id.line_counter_text)
        val unlockButton = view.findViewById<Button>(R.id.unlock_button)
        val promptTextView = view.findViewById<TextView>(R.id.motivational_prompt_text)

        view.findViewById<TextView>(R.id.soft_blocked_app_text).text =
            getString(R.string.soft_blocked_app_message, blockedAppLabel)
        promptTextView.text = motivationalPrompts[currentPromptIndex]

        view.findViewById<Button>(R.id.go_home_button).setOnClickListener {
            goHome()
            stopSelf()
        }
        view.findViewById<Button>(R.id.safety_controls_button).setOnClickListener {
            openSafetyControls()
        }

        journalEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val lines = countNonBlankLines(s)
                lineCounterTextView.text = getString(R.string.soft_block_line_count, lines)
                checkUnlockConditions(passwordEditText.text.toString(), lines, unlockButton)
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        passwordEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                checkUnlockConditions(
                    s.toString(),
                    countNonBlankLines(journalEditText.text),
                    unlockButton
                )
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        unlockButton.setOnClickListener {
            val password = passwordEditText.text.toString()
            val journalLines = countNonBlankLines(journalEditText.text)

            if (PasswordManager.checkPassword(password) && journalLines >= 6) {
                saveLastUnlockTimestamp()
                Toast.makeText(this, R.string.soft_block_unlocked_toast, Toast.LENGTH_SHORT).show()
                stopSelf()
            } else {
                Toast.makeText(this, R.string.soft_block_unlock_error, Toast.LENGTH_SHORT).show()
            }
        }

        lineCounterTextView.text = getString(R.string.soft_block_line_count, 0)
        checkUnlockConditions("", 0, unlockButton)
    }

    private fun updateHardBlockCountdown() {
        val minutes = TimeZoneHelper.getMinutesUntilUnlock()
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        overlayView?.findViewById<TextView>(R.id.countdown_text)?.text =
            getString(R.string.hard_block_countdown, hours, remainingMinutes)
    }

    private fun updateSoftBlockPrompt() {
        if (motivationalPrompts.isEmpty()) return
        overlayView?.findViewById<TextView>(R.id.motivational_prompt_text)?.text =
            motivationalPrompts[currentPromptIndex]
        currentPromptIndex = (currentPromptIndex + 1) % motivationalPrompts.size
    }

    private fun checkUnlockConditions(password: String, journalLines: Int, unlockButton: Button) {
        unlockButton.isEnabled = PasswordManager.checkPassword(password) && journalLines >= 6
    }

    private fun countNonBlankLines(text: CharSequence?): Int =
        text?.lines()?.count { it.isNotBlank() } ?: 0

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    private fun openSafetyControls() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_SAFETY_CONTROLS, true)
            }
        )
        stopSelf()
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (error: IllegalArgumentException) {
            Log.w(tag, "Overlay was already detached", error)
        } finally {
            overlayView = null
        }
    }

    private fun isRecentlyUnlocked(): Boolean {
        val lastUnlock = sessionPreferences().getLong(KEY_LAST_UNLOCK_TIMESTAMP, 0L)
        return System.currentTimeMillis() - lastUnlock < SESSION_COOLDOWN_MILLIS
    }

    private fun saveLastUnlockTimestamp() {
        sessionPreferences().edit()
            .putLong(KEY_LAST_UNLOCK_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    private fun sessionPreferences() = EncryptedSharedPreferences.create(
        SESSION_UNLOCK_PREFS,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
