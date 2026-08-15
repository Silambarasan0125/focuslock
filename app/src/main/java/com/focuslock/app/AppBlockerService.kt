package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AppBlockerService : AccessibilityService() {

    private val tag = "AppBlockerService"

    private val transientPackages = setOf(
        "com.focuslock.app",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.coloros.systemui",
        "com.coloros.launcher",
        "com.oplus.systemui",
        "com.oppo.launcher",
        "com.google.android.inputmethod.latin",
        "com.coloros.inputmethod",
        "com.oplus.inputmethod",
        "com.heytap.inputmethod",
        "com.oplus.securitykeyboard",
        "android"
    )

    private val stopHandler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private var pauseExpiryRunnable: Runnable? = null
    private var overlayActive = false
    private var activeOverlayMode: OverlayService.OverlayMode? = null
    private var activeBlockedPackage: String? = null
    private var overlayRequestedAtMillis = 0L
    private var lastForegroundPackage: String? = null

    companion object {
        private const val EXIT_CONFIRMATION_MILLIS = 600L
        private const val OVERLAY_START_GRACE_MILLIS = 1_500L

        @Volatile
        private var activeInstance: AppBlockerService? = null

        /**
         * Accessibility permissions cannot be silently re-enabled by an app.
         * This one-tap escape is therefore explicit and must be reversed by the
         * user from Android's accessibility settings after making a payment.
         */
        fun disableForPaymentCompatibility(): Boolean {
            val service = activeInstance ?: return false
            service.stopOverlayService("Payment compatibility mode")
            service.disableSelf()
            activeInstance = null
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        Log.d(tag, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()
        Log.d(tag, "Window changed: $packageName / $className")
        lastForegroundPackage = packageName
        syncOverlayState()

        if (TimeZoneHelper.getCurrentZone() == TimeZoneHelper.BlockZone.FREE) {
            cancelPauseExpiryCheck()
            if (overlayActive || OverlayService.isRunning) {
                stopOverlayService("Free zone started")
            } else {
                cancelPendingStop()
            }
            return
        }

        if (FocusLockState.isPaused(this)) {
            stopOverlayService("FocusLock is paused")
            schedulePauseExpiryCheck()
            return
        }
        cancelPauseExpiryCheck()

        val selectedPackages = BlockedAppsStore.getSelectedPackages(this)

        when {
            packageName == applicationContext.packageName && overlayActive -> {
                Log.d(tag, "Ignoring FocusLock's own overlay window event")
            }

            packageName == applicationContext.packageName ->
                stopOverlayService("FocusLock controls opened")

            packageName in BlockedAppsStore.paymentSafePackages ->
                stopOverlayService("Payment-safe app opened: $packageName")

            packageName in selectedPackages ->
                handleBlockedPackage(packageName)

            packageName in transientPackages && overlayActive -> {
                Log.d(tag, "Ignoring transient package while overlay state settles: $packageName")
            }

            else -> scheduleConfirmedStop(packageName)
        }
    }

    override fun onInterrupt() {
        Log.d(tag, "Accessibility Service interrupted")
        stopOverlayService("Accessibility service interrupted")
    }

    override fun onDestroy() {
        cancelPendingStop()
        cancelPauseExpiryCheck()
        stopOverlayService("Accessibility service destroyed")
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    private fun handleBlockedPackage(packageName: String) {
        cancelPendingStop()
        val appLabel = BlockedAppsStore.getAppLabel(this, packageName)

        when (TimeZoneHelper.getCurrentZone()) {
            TimeZoneHelper.BlockZone.HARD_BLOCK -> {
                Log.d(tag, "HARD_BLOCK for $packageName")
                startOverlayService(OverlayService.OverlayMode.HARD, packageName, appLabel)
            }
            TimeZoneHelper.BlockZone.SOFT_BLOCK -> {
                Log.d(tag, "SOFT_BLOCK for $packageName")
                startOverlayService(OverlayService.OverlayMode.SOFT, packageName, appLabel)
            }
            TimeZoneHelper.BlockZone.FREE -> {
                stopOverlayService("Free zone for $packageName")
            }
        }
    }

    private fun startOverlayService(
        mode: OverlayService.OverlayMode,
        packageName: String,
        appLabel: String
    ) {
        if (!Settings.canDrawOverlays(this)) {
            Log.d(tag, "Overlay permission missing; cannot block $appLabel")
            stopOverlayService("Overlay permission missing")
            return
        }

        if (overlayActive && activeOverlayMode == mode && activeBlockedPackage == packageName) {
            Log.d(tag, "Overlay already requested for $packageName in $mode mode")
            return
        }

        overlayActive = true
        activeOverlayMode = mode
        activeBlockedPackage = packageName
        overlayRequestedAtMillis = SystemClock.elapsedRealtime()

        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_OVERLAY_MODE, mode.name)
            putExtra(OverlayService.EXTRA_BLOCKED_APP_LABEL, appLabel)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOverlayService(reason: String) {
        cancelPendingStop()
        if (!overlayActive && !OverlayService.isRunning) {
            activeOverlayMode = null
            activeBlockedPackage = null
            overlayRequestedAtMillis = 0L
            return
        }

        overlayActive = false
        activeOverlayMode = null
        activeBlockedPackage = null
        overlayRequestedAtMillis = 0L
        stopService(Intent(this, OverlayService::class.java))
        Log.d(tag, "Overlay stopped: $reason")
    }

    private fun scheduleConfirmedStop(packageName: String) {
        if (!overlayActive) {
            return
        }

        cancelPendingStop()
        stopRunnable = Runnable {
            Log.d(tag, "Stopping overlay after confirmed foreground package: $packageName")
            stopOverlayService("Allowed app opened: $packageName")
        }
        stopHandler.postDelayed(requireNotNull(stopRunnable), EXIT_CONFIRMATION_MILLIS)
        Log.d(tag, "Scheduled overlay stop for foreground package: $packageName")
    }

    private fun cancelPendingStop() {
        stopRunnable?.let { stopHandler.removeCallbacks(it) }
        stopRunnable = null
    }

    private fun syncOverlayState() {
        val requestStillStarting = overlayRequestedAtMillis > 0L &&
            SystemClock.elapsedRealtime() - overlayRequestedAtMillis < OVERLAY_START_GRACE_MILLIS

        if (overlayActive && !OverlayService.isRunning && !requestStillStarting) {
            overlayActive = false
            activeOverlayMode = null
            activeBlockedPackage = null
            overlayRequestedAtMillis = 0L
        }
    }

    private fun schedulePauseExpiryCheck() {
        cancelPauseExpiryCheck()
        val remaining = FocusLockState.getRemainingPauseMillis(this)
        if (remaining <= 0L) return

        pauseExpiryRunnable = Runnable {
            val foregroundPackage = lastForegroundPackage ?: return@Runnable
            if (!FocusLockState.isPaused(this) &&
                foregroundPackage in BlockedAppsStore.getSelectedPackages(this)
            ) {
                handleBlockedPackage(foregroundPackage)
            }
        }
        stopHandler.postDelayed(requireNotNull(pauseExpiryRunnable), remaining + 250L)
    }

    private fun cancelPauseExpiryCheck() {
        pauseExpiryRunnable?.let { stopHandler.removeCallbacks(it) }
        pauseExpiryRunnable = null
    }
}
