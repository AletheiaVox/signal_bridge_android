package com.signalbridge.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalbridge.app.R
import com.signalbridge.app.auth.TokenManager
import com.signalbridge.app.data.*
import com.signalbridge.app.service.RelayService
import com.signalbridge.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    tokenManager: TokenManager,
    onNavigateToSettings: () -> Unit,
) {
    val context = LocalContext.current
    val relayState by RelayStateHolder.state.collectAsState()
    val devices by RelayStateHolder.devices.collectAsState()
    val governor by RelayStateHolder.governor.collectAsState()
    val health by RelayStateHolder.health.collectAsState()
    val error by RelayStateHolder.error.collectAsState()

    val isConnected = relayState != RelayState.DISCONNECTED && relayState != RelayState.ERROR
    val isConnecting = relayState == RelayState.CONNECTING

    val snackbarHostState = remember { SnackbarHostState() }

    // Notification permission launcher (Android 13+)
    // After permission is granted (or already was), proceed with connect
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Start regardless of grant — service works without notification,
        // but we want to show it. User can grant later via app settings.
        requestBatteryOptimizationExemption(context)
        RelayService.start(context)
    }

    // Show transient snackbar when error changes
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text("Signal Bridge", fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // ── Connection status cards ────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusCard(
                    label = "Server",
                    connected = health.serverConnected,
                    icon = Icons.Default.Cloud,
                    detail = if (health.serverConnected && health.lastHeartbeatAgo > 0) {
                        "${health.lastHeartbeatAgo / 1000}s ago"
                    } else null,
                    modifier = Modifier.weight(1f),
                )
                StatusCard(
                    label = "Intiface",
                    connected = health.intifaceConnected && health.intifaceHealthy,
                    icon = Icons.Default.Bluetooth,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── State indicator ────────────────────────────────────
            val stateText = when (relayState) {
                RelayState.DISCONNECTED -> stringResource(R.string.state_disconnected)
                RelayState.CONNECTING -> stringResource(R.string.state_connecting)
                RelayState.IDLE -> stringResource(R.string.state_idle)
                RelayState.ACTIVE -> stringResource(R.string.state_active)
                RelayState.COOLDOWN -> stringResource(R.string.state_cooldown)
                RelayState.ERROR -> stringResource(R.string.state_error)
            }
            val stateColor by animateColorAsState(
                targetValue = when (relayState) {
                    RelayState.DISCONNECTED -> SBGrayLight
                    RelayState.CONNECTING -> SBAmber
                    RelayState.IDLE -> SBGreen
                    RelayState.ACTIVE -> SBGreen
                    RelayState.COOLDOWN -> SBAmber
                    RelayState.ERROR -> SBRed
                },
                label = "stateColor",
            )

            Text(
                text = stateText,
                style = MaterialTheme.typography.titleMedium,
                color = stateColor,
                fontWeight = FontWeight.SemiBold,
            )

            // ── Error message ─────────────────────────────────────
            AnimatedVisibility(visible = error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = error ?: "",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Safety governor indicator ──────────────────────────
            AnimatedVisibility(visible = isConnected && governor.heatPct > 0) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Session intensity",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                        val heatText = if (governor.inCooldown) {
                            "COOLDOWN ${governor.cooldownRemaining}s"
                        } else if (governor.predictedSeconds != null && governor.heatPct > 50) {
                            "~${governor.predictedSeconds}s"
                        } else {
                            "${governor.heatPct.toInt()}%"
                        }
                        Text(
                            text = heatText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                governor.inCooldown -> SBAmber
                                governor.heatPct > 80 -> SBRed
                                governor.heatPct > 50 -> SBAmber
                                else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (governor.heatPct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = when {
                            governor.inCooldown -> SBAmber
                            governor.heatPct > 80 -> SBRed
                            governor.heatPct > 50 -> SBAmber
                            else -> SBGreen
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Device list ───────────────────────────────────────
            if (devices.isNotEmpty()) {
                Text(
                    text = "Devices (${devices.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(devices) { device ->
                        DeviceCard(device)
                    }
                }
            } else if (isConnected) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_devices),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── STOP ALL button (always visible when connected) ───
            AnimatedVisibility(visible = isConnected) {
                Button(
                    onClick = { RelayService.emergencyStop(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SBRed,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.stop_all_button),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Connect / Disconnect button ───────────────────────
            Button(
                onClick = {
                    if (isConnected || isConnecting) {
                        RelayService.stop(context)
                    } else {
                        // On Android 13+, request notification permission first
                        // (needed for the foreground service notification + STOP ALL button)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            requestBatteryOptimizationExemption(context)
                            RelayService.start(context)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = if (isConnected) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isConnected || isConnecting) {
                        stringResource(R.string.disconnect_button)
                    } else {
                        stringResource(R.string.connect_button)
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCard(
    label: String,
    connected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (connected) SBGreen else SBGrayLight,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (connected) "Connected" else "Disconnected",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (connected) SBGreen else SBGrayLight,
                    )
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceInfo) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (device.isActive) BorderStroke(1.dp, SBGreen) else null,
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = device.capabilities.keys.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (device.isActive) {
                Text(
                    text = "${(device.currentIntensity * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SBGreen,
                )
            }
        }
    }
}

/**
 * Request battery optimization exemption if not already granted.
 * Shows a one-tap system dialog — much less friction than navigating Settings manually.
 */
private fun requestBatteryOptimizationExemption(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Some OEMs block this intent — not critical, service still works
        }
    }
}
