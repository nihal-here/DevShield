package com.devshield.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.devshield.R
import com.devshield.core.NotificationController
import com.devshield.core.SettingsController
import com.devshield.core.StateRepository
import com.devshield.databinding.ActivityMainBinding
import com.devshield.model.SupportedSetting
import com.devshield.security.PermissionChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var stateRepository: StateRepository
    private lateinit var settingsController: SettingsController
    private lateinit var notificationController: NotificationController

    private var selectedAppPackage: String? = null
    private var selectedAppLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 15/16 (API 35/36) Edge-to-Edge WindowInsets architecture
        // Fixed Header receives top (status bar / camera cutout) and horizontal insets.
        // ScrollView receives bottom (navigation / gesture bar) and horizontal insets.
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.fixedHeaderContainer.setPadding(
                insets.left,
                insets.top,
                insets.right,
                0
            )
            binding.contentScrollView.setPadding(
                insets.left,
                0,
                insets.right,
                insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        stateRepository = StateRepository(this)
        settingsController = SettingsController(this, stateRepository)
        notificationController = NotificationController(this)

        // Load saved target app preference if present
        val (savedPkg, savedLabel) = stateRepository.getSavedTargetApp()
        if (savedPkg != null && isAppInstalled(savedPkg)) {
            selectedAppPackage = savedPkg
            selectedAppLabel = savedLabel
        }

        setupListeners()
        updateTargetAppUI()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateRecoveryBanner()
        refreshDiagnostics()
    }

    private fun setupListeners() {
        binding.btnCopyAdb.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("ADB Command", binding.tvAdbCommand.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "ADB command copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnChooseApp.setOnClickListener {
            showAppPickerDialog()
        }

        binding.btnChangeApp.setOnClickListener {
            showAppPickerDialog()
        }

        binding.btnClearApp.setOnClickListener {
            selectedAppPackage = null
            selectedAppLabel = null
            stateRepository.saveTargetAppPreference(null, null)
            updateTargetAppUI()
        }

        binding.switchSuppressWireless.isChecked = stateRepository.isSuppressWirelessDebuggingEnabled()
        binding.switchSuppressWireless.setOnCheckedChangeListener { _, isChecked ->
            stateRepository.setSuppressWirelessDebuggingEnabled(isChecked)
        }

        binding.btnEnterProtectionMode.setOnClickListener {
            enterProtectionMode()
        }

        // Single primary restore button in the active-state banner
        binding.btnRecoveryRestore.setOnClickListener {
            restoreSettings()
        }

        binding.btnRefreshDiag.setOnClickListener {
            refreshDiagnostics()
            Toast.makeText(this, "Diagnostics updated", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestWrite.setOnClickListener {
            runDiagnosticWriteTest()
        }
    }

    private fun updateTargetAppUI() {
        val pkg = selectedAppPackage
        if (pkg != null) {
            binding.layoutNoAppSelected.visibility = View.GONE
            binding.layoutAppSelected.visibility = View.VISIBLE

            binding.tvSelectedAppTitle.text = selectedAppLabel ?: pkg
            binding.tvSelectedAppPackage.text = pkg

            try {
                val icon = packageManager.getApplicationIcon(pkg)
                binding.imgSelectedAppIcon.setImageDrawable(icon)
            } catch (e: Exception) {
                binding.imgSelectedAppIcon.setImageResource(R.drawable.ic_shield)
            }

            val label = selectedAppLabel ?: "App"
            binding.btnEnterProtectionMode.text = "Shield & Launch $label"
        } else {
            binding.layoutNoAppSelected.visibility = View.VISIBLE
            binding.layoutAppSelected.visibility = View.GONE
            binding.btnEnterProtectionMode.text = getString(R.string.btn_enter_protection_mode)
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun updatePermissionStatus() {
        val hasPermission = PermissionChecker.hasWriteSecureSettings(this)
        if (hasPermission) {
            binding.tvPermissionStatus.text = getString(R.string.permission_granted)
            binding.tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.layoutAdbHelp.visibility = View.GONE
        } else {
            binding.tvPermissionStatus.text = getString(R.string.permission_denied)
            binding.tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.tvAdbCommand.text = PermissionChecker.getAdbGrantCommand(this)
            binding.layoutAdbHelp.visibility = View.VISIBLE
        }
    }

    private fun updateRecoveryBanner() {
        val snapshot = stateRepository.getSnapshot()
        if (snapshot != null && snapshot.hasModifications()) {
            binding.cardRecovery.visibility = View.VISIBLE
            binding.btnEnterProtectionMode.isEnabled = false
            binding.btnEnterProtectionMode.alpha = 0.5f
            binding.switchSuppressWireless.isEnabled = false
            val dateStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(snapshot.timestamp))
            val appInfo = snapshot.targetAppLabel ?: snapshot.targetAppPackage ?: "System"
            binding.tvRecoveryDetails.text =
                "Active since $dateStr for '$appInfo'. Developer and debugging settings are currently suppressed.\n\nSettings remain suppressed until manually restored."
        } else {
            binding.cardRecovery.visibility = View.GONE
            binding.btnEnterProtectionMode.isEnabled = true
            binding.btnEnterProtectionMode.alpha = 1.0f
            binding.switchSuppressWireless.isEnabled = true
        }
    }

    private fun enterProtectionMode() {
        val result = settingsController.enterBankingMode(
            targetAppPackage = selectedAppPackage,
            targetAppLabel = selectedAppLabel,
            suppressWirelessDebugging = binding.switchSuppressWireless.isChecked
        )

        result.fold(
            onSuccess = { snapshot ->
                notificationController.showBankingModeNotification(selectedAppLabel)
                updateRecoveryBanner()
                refreshDiagnostics()
                com.devshield.service.DevShieldTileService.requestTileUpdate(this)

                Toast.makeText(this, "Protection Mode Active: Settings suppressed and verified.", Toast.LENGTH_SHORT).show()

                // Launch target app if selected
                selectedAppPackage?.let { pkg ->
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    } else {
                        Toast.makeText(this, "Cannot launch app: no default launch activity found.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onFailure = { error ->
                AlertDialog.Builder(this)
                    .setTitle("Cannot Enter Protection Mode")
                    .setMessage(error.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        )
    }

    private fun restoreSettings() {
        val result = settingsController.restorePreviousState()
        result.fold(
            onSuccess = {
                notificationController.cancelBankingModeNotification()
                updateRecoveryBanner()
                refreshDiagnostics()
                com.devshield.service.DevShieldTileService.requestTileUpdate(this)
                Toast.makeText(this, "Settings restored to previous values successfully.", Toast.LENGTH_SHORT).show()
            },
            onFailure = { error ->
                AlertDialog.Builder(this)
                    .setTitle("Restoration Failed")
                    .setMessage(error.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        )
    }

    private fun refreshDiagnostics() {
        val diag = settingsController.getDiagnosticState()

        val devState = diag[SupportedSetting.DEVELOPMENT_OPTIONS] ?: -1
        binding.tvDiagDevOptions.text = formatDiagValue(devState)
        binding.tvDiagDevOptions.setTextColor(getDiagColor(devState))

        val usbState = diag[SupportedSetting.USB_DEBUGGING] ?: -1
        binding.tvDiagUsbDebugging.text = formatDiagValue(usbState)
        binding.tvDiagUsbDebugging.setTextColor(getDiagColor(usbState))

        val wifiState = diag[SupportedSetting.WIRELESS_DEBUGGING] ?: -1
        binding.tvDiagWirelessDebugging.text = formatDiagValue(wifiState)
        binding.tvDiagWirelessDebugging.setTextColor(getDiagColor(wifiState))
    }

    private fun formatDiagValue(value: Int): String {
        return when (value) {
            1 -> "ON (1)"
            0 -> "OFF (0)"
            else -> "UNKNOWN ($value)"
        }
    }

    private fun getDiagColor(value: Int): Int {
        return when (value) {
            1 -> ContextCompat.getColor(this, R.color.warning)
            0 -> ContextCompat.getColor(this, R.color.success)
            else -> ContextCompat.getColor(this, R.color.text_secondary)
        }
    }

    private fun runDiagnosticWriteTest() {
        val testResult = settingsController.runDiagnosticWriteTest()
        testResult.fold(
            onSuccess = { verifiedValue ->
                AlertDialog.Builder(this)
                    .setTitle("Write Verification Passed")
                    .setMessage("WRITE_SECURE_SETTINGS is active and operational.\nSuccessfully wrote and verified value '$verifiedValue' for '${SupportedSetting.DEVELOPMENT_OPTIONS.label}'.")
                    .setPositiveButton("OK", null)
                    .show()
            },
            onFailure = { error ->
                AlertDialog.Builder(this)
                    .setTitle("Write Verification Failed")
                    .setMessage(error.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        )
    }

    private fun showAppPickerDialog() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val appLabels = mutableListOf<String>()
        val appPackages = mutableListOf<String>()

        for (resolveInfo in resolveInfos) {
            val label = resolveInfo.loadLabel(pm).toString()
            val pkg = resolveInfo.activityInfo.packageName
            appLabels.add("$label ($pkg)")
            appPackages.add(pkg)
        }

        if (appPackages.isEmpty()) {
            Toast.makeText(this, "No launchable apps found.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.target_app_section_title))
            .setItems(appLabels.toTypedArray()) { _, which ->
                val selectedPkg = appPackages[which]
                val selectedLbl = resolveInfos[which].loadLabel(pm).toString()
                selectedAppPackage = selectedPkg
                selectedAppLabel = selectedLbl
                stateRepository.saveTargetAppPreference(selectedPkg, selectedLbl)
                updateTargetAppUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
