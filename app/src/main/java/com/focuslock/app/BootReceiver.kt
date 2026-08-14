package com.focuslock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && context != null) {
            PasswordManager.initialize(context)
            FocusLockState.getRemainingPauseMillis(context)

            // Never show a full-screen window merely because the phone booted.
            // Android will reconnect an enabled accessibility service, and the
            // overlay starts only when a blocked app actually reaches foreground.
            Log.d(TAG, "Boot completed; FocusLock state initialized")
        }
    }
}
