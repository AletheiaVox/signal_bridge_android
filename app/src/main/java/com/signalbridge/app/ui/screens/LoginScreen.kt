package com.signalbridge.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalbridge.app.R
import com.signalbridge.app.auth.AuthRepository
import com.signalbridge.app.auth.AuthResult
import com.signalbridge.app.auth.TokenManager
import com.signalbridge.app.ui.theme.SBRed
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    tokenManager: TokenManager,
    authRepository: AuthRepository,
    onLoginSuccess: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    var isRegisterMode by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(tokenManager.serverUrl) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    fun submit() {
        // Validation
        if (username.isBlank()) {
            errorMessage = "Username is required"
            return
        }
        if (password.isBlank()) {
            errorMessage = "Password is required"
            return
        }
        if (isRegisterMode && password != confirmPassword) {
            errorMessage = "Passwords don't match"
            return
        }
        if (isRegisterMode && password.length < 8) {
            errorMessage = "Password must be at least 8 characters"
            return
        }

        // Save server URL if changed
        if (serverUrl != tokenManager.serverUrl) {
            tokenManager.serverUrl = serverUrl
        }

        errorMessage = null
        isLoading = true

        scope.launch {
            val result = if (isRegisterMode) {
                authRepository.register(username.trim(), password)
            } else {
                authRepository.login(username.trim(), password)
            }

            isLoading = false

            when (result) {
                is AuthResult.Success -> onLoginSuccess()
                is AuthResult.Error -> errorMessage = result.message
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // App title
            Text(
                text = "Signal Bridge",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = SBRed,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isRegisterMode) "Create your account" else "Sign in to continue",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Username field
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; errorMessage = null },
                label = { Text(stringResource(R.string.username_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                enabled = !isLoading,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Password field
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text(stringResource(R.string.password_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (isRegisterMode) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = { submit() },
                ),
                enabled = !isLoading,
            )

            // Confirm password (register mode only)
            AnimatedVisibility(visible = isRegisterMode) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; errorMessage = null },
                        label = { Text(stringResource(R.string.confirm_password_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { submit() },
                        ),
                        enabled = !isLoading,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Error message
            AnimatedVisibility(visible = errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = errorMessage ?: "",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Submit button
            Button(
                onClick = { submit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = SBRed),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = if (isRegisterMode) {
                            stringResource(R.string.register_button)
                        } else {
                            stringResource(R.string.login_button)
                        },
                        fontSize = 16.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Toggle login/register
            TextButton(
                onClick = {
                    isRegisterMode = !isRegisterMode
                    errorMessage = null
                    confirmPassword = ""
                },
                enabled = !isLoading,
            ) {
                Text(
                    text = if (isRegisterMode) {
                        stringResource(R.string.switch_to_login)
                    } else {
                        stringResource(R.string.switch_to_register)
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Advanced: server URL
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
            ) {
                Text(
                    text = stringResource(R.string.advanced_settings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }

            AnimatedVisibility(visible = showAdvanced) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(stringResource(R.string.server_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    enabled = !isLoading,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
