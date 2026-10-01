package com.folio.notes.mistakes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FocalLoginCardTests {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(MistakesState())
    private val requests = mutableListOf<Triple<String, String, String>>()

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FocalLoginCard(
                        state.value,
                        { email, password -> requests.add(Triple("sign-in", email, password)) },
                        { email, password -> requests.add(Triple("create", email, password)) },
                        { email -> requests.add(Triple("reset", email, "")) },
                        { state.value = state.value.copy(error = null, authMessage = null) },
                    )
                }
            }
        }
    }

    @Test fun signInValidatesEmailAndPreservesPasswordOnFailure() {
        show()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.onNodeWithText("Email address").performTextInput("not-an-email")
        compose.onNodeWithText("Password").performTextInput("secret")
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.onNodeWithText("Email address").performTextReplacement(" student@example.com ")
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple("sign-in", "student@example.com", "secret")), requests)
            state.value = state.value.copy(error = "Could not sign in.")
        }
        compose.onNodeWithText("Could not sign in.").assertExists()
        compose.onNodeWithText("Sign in").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, requests.size) }
    }

    @Test fun createAccountHasItsOwnPasswordRuleAndClearsFeedbackWhenLeaving() {
        show()
        compose.onNodeWithText("New to Focal? Create an account").performScrollTo().performClick()
        compose.onNodeWithText("Email address").performTextInput("student@example.com")
        compose.onNodeWithText("Choose a password").performTextInput("short")
        compose.onNodeWithText("Create account").assertIsNotEnabled()
        compose.onNodeWithText("Choose a password").performTextReplacement("secret")
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple("create", "student@example.com", "secret")), requests)
            state.value = state.value.copy(authMessage = "Check your email.")
        }
        compose.onNodeWithText("Back to sign in").performScrollTo().performClick()
        compose.onNodeWithText("Check your email.").assertDoesNotExist()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.onNodeWithText("Email address").assertTextContains("student@example.com")
    }

    @Test fun resetRequiresOnlyEmailAndNeverSubmitsAPassword() {
        show()
        compose.onNodeWithText("Forgot password?").performScrollTo().performClick()
        compose.onNodeWithText("Password").assertDoesNotExist()
        compose.onNodeWithText("Send reset link").assertIsNotEnabled()
        compose.onNodeWithText("Email address").performTextInput("student@example.com")
        compose.onNodeWithText("Send reset link").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("reset", "student@example.com", "")), requests) }
    }

    @Test fun busyStatePreventsDuplicateRequestsAndNavigation() {
        show()
        compose.onNodeWithText("Email address").performTextInput("student@example.com")
        compose.onNodeWithText("Password").performTextInput("secret")
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithText("Signing in…").assertIsNotEnabled()
        compose.onNodeWithText("Forgot password?").assertIsNotEnabled()
        compose.onNodeWithText("New to Focal? Create an account").assertIsNotEnabled()
        compose.onNodeWithText("Email address").assertIsNotEnabled()
    }
}
