package com.focuslock.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Manages the user's password securely using EncryptedSharedPreferences.
 * This is a singleton object for easy access throughout the application.
 */
object PasswordManager {

    private const val PREFS_FILE_NAME = "focuslock_prefs"
    private const val KEY_PASSWORD = "user_password"

    private lateinit var sharedPreferences: SharedPreferences

    /**
     * Initializes the PasswordManager with the application context.
     * This method must be called once, for example in your Application class or MainActivity.
     * @param context The application context.
     */
    fun initialize(context: Context) {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        sharedPreferences = EncryptedSharedPreferences.create(
            PREFS_FILE_NAME,
            masterKeyAlias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Saves the provided password to EncryptedSharedPreferences.
     * @param password The password string to be saved.
     */
    fun savePassword(password: String) {
        if (!this::sharedPreferences.isInitialized) {
            throw IllegalStateException("PasswordManager not initialized. Call initialize() first.")
        }
        sharedPreferences.edit().putString(KEY_PASSWORD, password).apply()
    }

    /**
     * Checks if the provided input matches the stored password.
     * @param input The input string to check against the stored password.
     * @return True if the input matches the stored password, false otherwise.
     */
    fun checkPassword(input: String): Boolean {
        if (!this::sharedPreferences.isInitialized) {
            throw IllegalStateException("PasswordManager not initialized. Call initialize() first.")
        }
        val storedPassword = sharedPreferences.getString(KEY_PASSWORD, null)
        return storedPassword != null && storedPassword == input
    }

    /**
     * Checks if a password has been set by the user.
     * @return True if a password exists, false otherwise.
     */
    fun isPasswordSet(): Boolean {
        if (!this::sharedPreferences.isInitialized) {
            throw IllegalStateException("PasswordManager not initialized. Call initialize() first.")
        }
        return sharedPreferences.contains(KEY_PASSWORD)
    }

    /**
     * Clears the stored password (for testing or reset purposes).
     */
    fun clearPassword() {
        if (!this::sharedPreferences.isInitialized) {
            throw IllegalStateException("PasswordManager not initialized. Call initialize() first.")
        }
        sharedPreferences.edit().remove(KEY_PASSWORD).apply()
    }
}
