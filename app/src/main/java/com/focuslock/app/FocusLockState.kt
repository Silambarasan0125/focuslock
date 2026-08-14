package com.focuslock.app

import android.content.Context

/**
 * Stores small pieces of user-controlled FocusLock state.
 *
 * Pause state is deliberately separate from the password store: it is not a
 * secret, and both the activity and accessibility service need to read it.
 */
object FocusLockState {

    const val DEFAULT_PAUSE_MINUTES = 15L

    private const val PREFS_NAME = "focuslock_runtime_state"
    private const val KEY_PAUSED_UNTIL = "paused_until"

    fun pauseFor(context: Context, minutes: Long = DEFAULT_PAUSE_MINUTES) {
        val pausedUntil = System.currentTimeMillis() + minutes * 60_000L
        preferences(context).edit().putLong(KEY_PAUSED_UNTIL, pausedUntil).apply()
    }

    fun resumeNow(context: Context) {
        preferences(context).edit().remove(KEY_PAUSED_UNTIL).apply()
    }

    fun isPaused(context: Context): Boolean = getRemainingPauseMillis(context) > 0L

    fun getRemainingPauseMillis(context: Context): Long {
        val prefs = preferences(context)
        val pausedUntil = prefs.getLong(KEY_PAUSED_UNTIL, 0L)
        val remaining = pausedUntil - System.currentTimeMillis()

        if (pausedUntil > 0L && remaining <= 0L) {
            prefs.edit().remove(KEY_PAUSED_UNTIL).apply()
        }

        return remaining.coerceAtLeast(0L)
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
