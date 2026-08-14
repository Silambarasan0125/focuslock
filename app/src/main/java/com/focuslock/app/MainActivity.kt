package com.focuslock.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_SAFETY_CONTROLS = "open_safety_controls"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_activity_fragment_container)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        PasswordManager.initialize(applicationContext)
        if (PasswordManager.isPasswordSet()) showMainScreenFragment() else showSetupFragment()
    }

    fun showSetupFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_activity_fragment_container, SetupFragment())
            .commit()
    }

    fun showMainScreenFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_activity_fragment_container, MainScreenFragment())
            .commit()
    }

    fun showPasswordGate(onPasswordCorrect: () -> Unit) {
        val gateView = LayoutInflater.from(this).inflate(R.layout.password_gate_dialog, null)
        val passwordInput = gateView.findViewById<EditText>(R.id.password_gate_edit_text)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.password_gate_title)
            .setMessage(R.string.password_gate_message)
            .setView(gateView)
            .setPositiveButton(R.string.password_gate_verify, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (PasswordManager.checkPassword(passwordInput.text.toString())) {
                    passwordInput.error = null
                    dialog.dismiss()
                    onPasswordCorrect()
                } else {
                    passwordInput.error = getString(R.string.error_invalid_password)
                }
            }
        }
        dialog.show()
    }
}

class SetupFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_setup, container, false)
        val passwordInput = view.findViewById<EditText>(R.id.setup_password_edit_text)
        val confirmInput = view.findViewById<EditText>(R.id.setup_confirm_password_edit_text)
        val feedback = view.findViewById<TextView>(R.id.setup_password_feedback)
        val saveButton = view.findViewById<Button>(R.id.setup_save_password_button)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val password = passwordInput.text.toString()
                val confirmation = confirmInput.text.toString()
                val longEnough = password.length >= 6
                val matches = confirmation.isNotEmpty() && password == confirmation

                saveButton.isEnabled = longEnough && matches
                feedback.text = when {
                    password.isEmpty() -> getString(R.string.setup_password_rule)
                    !longEnough -> getString(R.string.setup_password_too_short)
                    confirmation.isEmpty() -> getString(R.string.setup_confirm_prompt)
                    !matches -> getString(R.string.error_password_mismatch)
                    else -> getString(R.string.setup_password_ready)
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        }

        passwordInput.addTextChangedListener(watcher)
        confirmInput.addTextChangedListener(watcher)
        saveButton.isEnabled = false

        saveButton.setOnClickListener {
            PasswordManager.savePassword(passwordInput.text.toString())
            Toast.makeText(requireContext(), R.string.password_saved_toast, Toast.LENGTH_SHORT).show()
            (requireActivity() as MainActivity).showMainScreenFragment()
        }

        return view
    }
}

class MainScreenFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_main_screen, container, false)

        view.findViewById<Button>(R.id.btn_accessibility_settings).setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                mainActivity().showPasswordGate { openAccessibilitySettings() }
            } else {
                openAccessibilitySettings()
            }
        }

        view.findViewById<Button>(R.id.btn_overlay_permission).setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                mainActivity().showPasswordGate { openOverlayPermissionSettings() }
            } else {
                openOverlayPermissionSettings()
            }
        }

        view.findViewById<View>(R.id.card_battery_optimization).setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        view.findViewById<View>(R.id.card_auto_start).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.auto_start_title)
                .setMessage(R.string.auto_start_help)
                .setPositiveButton(R.string.got_it, null)
                .show()
        }

        view.findViewById<Button>(R.id.btn_pause_focus).setOnClickListener {
            if (FocusLockState.isPaused(requireContext())) {
                FocusLockState.resumeNow(requireContext())
                Toast.makeText(requireContext(), R.string.focus_resumed_toast, Toast.LENGTH_SHORT).show()
                updateDashboard(view)
            } else {
                mainActivity().showPasswordGate {
                    FocusLockState.pauseFor(requireContext())
                    requireContext().stopService(Intent(requireContext(), OverlayService::class.java))
                    Toast.makeText(requireContext(), R.string.focus_paused_toast, Toast.LENGTH_SHORT).show()
                    updateDashboard(view)
                }
            }
        }

        view.findViewById<Button>(R.id.btn_payment_compatibility).setOnClickListener {
            mainActivity().showPasswordGate { showPaymentCompatibilityHelp() }
        }

        updateDashboard(view)
        scrollToSafetyControlsIfRequested(view)
        return view
    }

    override fun onResume() {
        super.onResume()
        view?.let(::updateDashboard)
    }

    private fun updateDashboard(root: View) {
        val context = requireContext()
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(context)
        val pausedMillis = FocusLockState.getRemainingPauseMillis(context)
        val paused = pausedMillis > 0L
        val currentZone = TimeZoneHelper.getCurrentZone()

        root.findViewById<TextView>(R.id.tv_service_status).text = when {
            paused -> getString(R.string.status_paused)
            accessibilityEnabled && overlayEnabled -> getString(R.string.status_ready)
            else -> getString(R.string.status_needs_setup)
        }

        root.findViewById<TextView>(R.id.tv_zone_summary).text = when (currentZone) {
            TimeZoneHelper.BlockZone.HARD_BLOCK -> getString(R.string.zone_hard_summary)
            TimeZoneHelper.BlockZone.SOFT_BLOCK -> getString(R.string.zone_soft_summary)
            TimeZoneHelper.BlockZone.FREE -> getString(R.string.zone_free_summary)
        }

        root.findViewById<TextView>(R.id.tv_pause_status).text = if (paused) {
            val minutes = (pausedMillis + 59_999L) / 60_000L
            getString(R.string.pause_remaining, minutes)
        } else {
            getString(R.string.pause_inactive)
        }

        root.findViewById<TextView>(R.id.tv_accessibility_status).text =
            getString(if (accessibilityEnabled) R.string.permission_on else R.string.permission_off)
        root.findViewById<TextView>(R.id.tv_overlay_status).text =
            getString(if (overlayEnabled) R.string.permission_on else R.string.permission_off)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryReady = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        root.findViewById<TextView>(R.id.tv_battery_status).text =
            getString(if (batteryReady) R.string.permission_on else R.string.permission_recommended)

        root.findViewById<Button>(R.id.btn_accessibility_settings).text =
            getString(if (accessibilityEnabled) R.string.review_accessibility else R.string.enable_accessibility)
        root.findViewById<Button>(R.id.btn_overlay_permission).text =
            getString(if (overlayEnabled) R.string.review_overlay else R.string.enable_overlay)
        root.findViewById<Button>(R.id.btn_pause_focus).text =
            getString(if (paused) R.string.resume_focus_now else R.string.pause_focus_15)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = requireContext()
            .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo.serviceInfo
                serviceInfo.packageName == requireContext().packageName &&
                    serviceInfo.name == AppBlockerService::class.java.name
            }
    }

    private fun requestBatteryOptimizationExemption() {
        val context = requireContext()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            Toast.makeText(context, R.string.battery_already_ready, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showPaymentCompatibilityHelp() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.payment_compatibility_title)
            .setMessage(R.string.payment_compatibility_message)
            .setPositiveButton(R.string.disable_detection_temporarily) { _, _ ->
                FocusLockState.pauseFor(requireContext())
                requireContext().stopService(Intent(requireContext(), OverlayService::class.java))
                if (AppBlockerService.disableForPaymentCompatibility()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.payment_compatibility_enabled_toast,
                        Toast.LENGTH_LONG
                    ).show()
                }
                openOverlayPermissionSettings()
                view?.let(::updateDashboard)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlayPermissionSettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
        )
    }

    private fun scrollToSafetyControlsIfRequested(root: View) {
        val activity = requireActivity()
        if (!activity.intent.getBooleanExtra(MainActivity.EXTRA_OPEN_SAFETY_CONTROLS, false)) return

        activity.intent.removeExtra(MainActivity.EXTRA_OPEN_SAFETY_CONTROLS)
        val scrollView = root as? ScrollView ?: return
        val safetyCard = root.findViewById<View>(R.id.card_safety_controls)
        scrollView.post { scrollView.smoothScrollTo(0, safetyCard.top) }
    }

    private fun mainActivity(): MainActivity = requireActivity() as MainActivity
}
