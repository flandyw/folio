@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing

private enum class LoginStep(val title: String, val action: String, val working: String) {
    SIGN_IN("Welcome to Focal", "Sign in", "Signing in…"),
    CREATE_ACCOUNT("Create your Focal account", "Create account", "Creating account…"),
    RESET_PASSWORD("Reset your password", "Send reset link", "Sending reset link…"),
}

/** Shared by every Focal entry point; passwords are never saved into instance state. */
@Composable
internal fun FocalLoginCard(
    state: MistakesState,
    onSignIn: (String, String) -> Unit,
    onCreateAccount: (String, String) -> Unit,
    onResetPassword: (String) -> Unit,
    onClearFeedback: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(LoginStep.SIGN_IN) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    var emailTouched by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val resetting = step == LoginStep.RESET_PASSWORD
    val creating = step == LoginStep.CREATE_ACCOUNT
    val validEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val canSubmit = !state.busy && validEmail &&
        (resetting || if (creating) password.length >= 6 else password.isNotEmpty())

    fun navigate(next: LoginStep) {
        if (state.busy) return
        step = next
        password = ""
        showPassword = false
        emailTouched = false
        onClearFeedback()
        focus.clearFocus()
    }
    fun submit() {
        if (!canSubmit) return
        focus.clearFocus()
        showPassword = false
        when (step) {
            LoginStep.SIGN_IN -> onSignIn(email.trim(), password)
            LoginStep.CREATE_ACCOUNT -> onCreateAccount(email.trim(), password)
            LoginStep.RESET_PASSWORD -> onResetPassword(email.trim())
        }
    }
    // Keep the password for a failed attempt, but discard it after success or a session change.
    LaunchedEffect(state.userId, state.authMessage) {
        if (state.userId != null || state.authMessage != null) {
            password = ""
            showPassword = false
        }
    }
    BackHandler(step != LoginStep.SIGN_IN) { navigate(LoginStep.SIGN_IN) }

    ElevatedCard(shape = FolioShapes.extraLarge) {
        Column(
            Modifier.fillMaxWidth().padding(FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16),
        ) {
            if (step != LoginStep.SIGN_IN) {
                TextButton({ navigate(LoginStep.SIGN_IN) }, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(FolioSpacing.dp8))
                    Text("Back to sign in")
                }
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    if (resetting) Icons.Rounded.Lock else Icons.Rounded.School, null,
                    Modifier.padding(FolioSpacing.dp16).size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text(step.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    when (step) {
                        LoginStep.SIGN_IN -> "Your study sessions and mistake review, together. Sign in with your Focal account."
                        LoginStep.CREATE_ACCOUNT -> "One account for study sessions and mistake review in Folio and Focal."
                        LoginStep.RESET_PASSWORD -> "Enter your Focal email. We’ll send you a link to choose a new password."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; emailTouched = true; onClearFeedback() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email address") },
                placeholder = { Text("you@example.com") },
                leadingIcon = { Icon(Icons.Rounded.AlternateEmail, null) },
                singleLine = true,
                isError = emailTouched && email.isNotBlank() && !validEmail,
                supportingText = if (emailTouched && email.isNotBlank() && !validEmail) {
                    { Text("Enter a valid email address.") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = if (resetting) ImeAction.Done else ImeAction.Next),
                keyboardActions = KeyboardActions(onDone = { submit() }, onNext = { passwordFocus.requestFocus() }),
                enabled = !state.busy,
            )
            if (!resetting) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; onClearFeedback() },
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                    label = { Text(if (creating) "Choose a password" else "Password") },
                    leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                    trailingIcon = {
                        IconButton({ showPassword = !showPassword }, enabled = !state.busy, shapes = IconButtonDefaults.shapes()) {
                            Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                if (showPassword) "Hide password" else "Show password")
                        }
                    },
                    singleLine = true,
                    supportingText = if (creating) { { Text("Use at least 6 characters.") } } else null,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    enabled = !state.busy,
                )
            }
            if (step == LoginStep.SIGN_IN) {
                TextButton({ navigate(LoginStep.RESET_PASSWORD) }, enabled = !state.busy,
                    modifier = Modifier.align(Alignment.End), shapes = ButtonDefaults.shapes()) {
                    Text("Forgot password?")
                }
            }
            val feedback = state.error ?: state.authMessage
            if (feedback != null) {
                val isError = state.error != null
                Surface(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    shape = FolioShapes.large,
                    color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        Icon(if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.MarkEmailRead, null)
                        Text(feedback, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Button(
                onClick = { submit() }, enabled = canSubmit, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (state.busy) {
                    LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(FolioSpacing.dp10))
                    Text(step.working)
                } else {
                    Text(step.action)
                    Spacer(Modifier.width(FolioSpacing.dp8))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                }
            }
            if (step == LoginStep.SIGN_IN) {
                OutlinedButton({ navigate(LoginStep.CREATE_ACCOUNT) }, enabled = !state.busy,
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text("New to Focal? Create an account")
                }
                Text("Your notebooks and handwriting stay in Folio. Signing in connects study sessions and mistake sync.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
