package com.signalbridge.app.ui.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.signalbridge.app.BuildConfig
import com.signalbridge.app.R
import com.signalbridge.app.auth.AuthRepository
import com.signalbridge.app.auth.SafetyConfig
import com.signalbridge.app.auth.TokenManager
import com.signalbridge.app.ui.theme.SBAmber
import com.signalbridge.app.ui.theme.SBGreen
import com.signalbridge.app.ui.theme.SBRed
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    tokenManager: TokenManager,
    authRepository: AuthRepository,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(tokenManager.serverUrl) }
    var intifaceUrl by remember { mutableStateOf(tokenManager.intifaceUrl) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    // Safety config state — loaded from server
    var safetyConfig by remember { mutableStateOf<SafetyConfig?>(null) }
    var isLoadingConfig by remember { mutableStateOf(true) }
    var isSavingConfig by remember { mutableStateOf(false) }
    var configError by remember { mutableStateOf<String?>(null) }

    // Local editable copies of safety settings
    var governorEnabled by remember { mutableStateOf(true) }
    var heatRate by remember { mutableFloatStateOf(3.0f) }
    var coolRate by remember { mutableFloatStateOf(2.0f) }
    var cooldownThreshold by remember { mutableFloatStateOf(90f) }
    var cooldownDuration by remember { mutableFloatStateOf(30f) }

    val scope = rememberCoroutineScope()

    // Load config from server on first render
    LaunchedEffect(Unit) {
        isLoadingConfig = true
        val config = authRepository.getSafetyConfig()
        if (config != null) {
            safetyConfig = config
            governorEnabled = config.governor_enabled
            heatRate = config.heat_rate.toFloat()
            coolRate = config.cool_rate.toFloat()
            cooldownThreshold = config.cooldown_threshold.toFloat()
            cooldownDuration = config.cooldown_duration.toFloat()
        } else {
            configError = "Could not load safety settings from server"
        }
        isLoadingConfig = false
    }

    // Track if safety settings have been modified
    val safetyChanged = safetyConfig?.let { orig ->
        governorEnabled != orig.governor_enabled ||
            heatRate != orig.heat_rate.toFloat() ||
            coolRate != orig.cool_rate.toFloat() ||
            cooldownThreshold != orig.cooldown_threshold.toFloat() ||
            cooldownDuration != orig.cooldown_duration.toFloat()
    } ?: false

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Account ───────────────────────────────────────────
            SectionHeader("Account")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    LabelValue("Username", tokenManager.username ?: "Not signed in")
                    tokenManager.tokenExpiryDisplay?.let { expiry ->
                        Spacer(modifier = Modifier.height(4.dp))
                        LabelValue("Token", expiry)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Connection ────────────────────────────────────────
            SectionHeader(stringResource(R.string.connection_settings))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text(stringResource(R.string.server_url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = intifaceUrl,
                onValueChange = { intifaceUrl = it },
                label = { Text("Intiface URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Save button (only if changed)
            val urlsChanged = serverUrl != tokenManager.serverUrl || intifaceUrl != tokenManager.intifaceUrl
            AnimatedVisibility(visible = urlsChanged) {
                Button(
                    onClick = {
                        tokenManager.serverUrl = serverUrl
                        tokenManager.intifaceUrl = intifaceUrl
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Connection Settings")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Safety Governor ──────────────────────────────────
            SectionHeader(stringResource(R.string.safety_settings))

            if (isLoadingConfig) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Loading safety settings...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (configError != null && safetyConfig == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = configError ?: "",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                // Governor enabled toggle
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Safety Governor",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Automatic cooldown when session intensity is sustained",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                            Switch(
                                checked = governorEnabled,
                                onCheckedChange = { governorEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = SBGreen,
                                    checkedTrackColor = SBGreen.copy(alpha = 0.3f),
                                ),
                            )
                        }

                        AnimatedVisibility(visible = governorEnabled) {
                            Column {
                                Spacer(modifier = Modifier.height(16.dp))

                                // Cooldown threshold
                                SliderSetting(
                                    label = "Cooldown triggers at",
                                    value = cooldownThreshold,
                                    onValueChange = { cooldownThreshold = it },
                                    valueRange = 50f..100f,
                                    steps = 9,
                                    valueLabel = "${cooldownThreshold.roundToInt()}% heat",
                                    description = estimateCooldownTime(heatRate, coolRate, cooldownThreshold),
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Cooldown duration
                                SliderSetting(
                                    label = "Minimum cooldown",
                                    value = cooldownDuration,
                                    onValueChange = { cooldownDuration = it },
                                    valueRange = 10f..120f,
                                    steps = 10,
                                    valueLabel = "${cooldownDuration.roundToInt()}s",
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Heat rate
                                SliderSetting(
                                    label = "Heat sensitivity",
                                    value = heatRate,
                                    onValueChange = { heatRate = it },
                                    valueRange = 1f..10f,
                                    steps = 8,
                                    valueLabel = "%.1f".format(heatRate),
                                    description = "Higher = reaches cooldown faster",
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Cool rate
                                SliderSetting(
                                    label = "Recovery speed",
                                    value = coolRate,
                                    onValueChange = { coolRate = it },
                                    valueRange = 0.5f..5f,
                                    steps = 8,
                                    valueLabel = "%.1f".format(coolRate),
                                    description = "Higher = cools down faster when idle",
                                )
                            }
                        }
                    }
                }

                // Save safety settings button
                AnimatedVisibility(visible = safetyChanged) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    isSavingConfig = true
                                    configError = null
                                    val updates = SafetyConfig(
                                        governor_enabled = governorEnabled,
                                        heat_rate = heatRate.toDouble(),
                                        cool_rate = coolRate.toDouble(),
                                        cooldown_threshold = cooldownThreshold.toDouble(),
                                        cooldown_exit = safetyConfig?.cooldown_exit ?: 30.0,
                                        cooldown_duration = cooldownDuration.toDouble(),
                                    )
                                    val result = authRepository.setSafetyConfig(updates)
                                    if (result != null) {
                                        // Sync editable state from the server's effective
                                        // config so the UI shows what was actually stored,
                                        // not just what we asked for.
                                        safetyConfig = result
                                        governorEnabled = result.governor_enabled
                                        heatRate = result.heat_rate.toFloat()
                                        coolRate = result.cool_rate.toFloat()
                                        cooldownThreshold = result.cooldown_threshold.toFloat()
                                        cooldownDuration = result.cooldown_duration.toFloat()
                                        configError = null
                                    } else {
                                        configError = "Failed to save settings"
                                    }
                                    isSavingConfig = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSavingConfig,
                        ) {
                            if (isSavingConfig) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Save Safety Settings")
                        }

                        // Show error if save failed
                        AnimatedVisibility(visible = configError != null) {
                            Text(
                                text = configError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = SBRed,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Emergency Stop ────────────────────────────────────
            SectionHeader("Emergency Stop")

            val context = LocalContext.current

            // Re-check accessibility status when returning from Settings
            var accessibilityEnabled by remember {
                mutableStateOf(isAccessibilityServiceEnabled(context))
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        accessibilityEnabled = isAccessibilityServiceEnabled(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    var volumeStopEnabled by remember {
                        mutableStateOf(tokenManager.volumeKeyStopEnabled)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Volume key emergency stop",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Triple-press or hold volume-down to stop all devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        Switch(
                            checked = volumeStopEnabled,
                            onCheckedChange = {
                                volumeStopEnabled = it
                                tokenManager.volumeKeyStopEnabled = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SBGreen,
                                checkedTrackColor = SBGreen.copy(alpha = 0.3f),
                            ),
                        )
                    }

                    // Accessibility service setup
                    AnimatedVisibility(visible = volumeStopEnabled) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))

                            if (accessibilityEnabled) {
                                Text(
                                    text = "Accessibility service is enabled.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SBGreen,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            } else {
                                Text(
                                    text = "Volume key interception requires the Signal Bridge accessibility service to be enabled. " +
                                        "This only intercepts volume key presses — no screen reading or other permissions.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SBAmber,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Open Accessibility Settings")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "The notification STOP ALL button and in-app button are always active regardless of this setting.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── About ─────────────────────────────────────────────
            SectionHeader(stringResource(R.string.about_title))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    LabelValue("Version", BuildConfig.VERSION_NAME)
                    Spacer(modifier = Modifier.height(4.dp))
                    LabelValue("Server", tokenManager.serverUrl)

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Open-source licenses (auto-generated by OSS Licenses plugin)
                    Text(
                        text = "Open Source Licenses",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(
                                    Intent(context, com.google.android.gms.oss.licenses.OssLicensesMenuActivity::class.java)
                                )
                            }
                            .padding(vertical = 4.dp),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Privacy policy
                    Text(
                        text = "Privacy Policy",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://aletheiavox.github.io/signal_bridge_android/PRIVACY_POLICY"))
                                )
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Sign Out ──────────────────────────────────────────
            OutlinedButton(
                onClick = { showSignOutConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SBRed),
            ) {
                Text(stringResource(R.string.sign_out))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Sign out confirmation dialog
    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text("Sign Out") },
            text = { Text("This will disconnect the relay and clear your credentials.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutConfirm = false
                        onSignOut()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = SBRed),
                ) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Reusable components ──────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueLabel: String,
    description: String? = null,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = SBAmber,
                activeTrackColor = SBAmber,
            ),
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

/**
 * Estimate how long full intensity takes to trigger cooldown.
 */
private fun estimateCooldownTime(heatRate: Float, coolRate: Float, threshold: Float): String {
    val netRate = heatRate - coolRate
    return if (netRate > 0) {
        val seconds = (threshold / netRate).roundToInt()
        "~${seconds}s at full intensity to trigger"
    } else {
        "Full intensity won't trigger cooldown at these settings"
    }
}

/**
 * Check if the Signal Bridge AccessibilityService is currently enabled.
 */
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name.contains("VolumeKeyAccessibilityService")
    }
}
