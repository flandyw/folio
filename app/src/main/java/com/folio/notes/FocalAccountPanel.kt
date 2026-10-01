@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
fun FocalAccountPanel(onDismiss: () -> Unit, onMistakes: () -> Unit) {
    var showSessions by remember { mutableStateOf(false) }
    if (showSessions) {
        FocalStudyPanel(null, onDismiss = { showSessions = false })
    } else FolioPanel("Focal account", onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16),
        ) {
            com.folio.notes.mistakes.FocalAccountContent()
            HorizontalDivider()
            OutlinedButton({ showSessions = true }, shapes = ButtonDefaults.shapes()) { Text("Open study sessions") }
            OutlinedButton(onMistakes, shapes = ButtonDefaults.shapes()) { Text("Open mistake review") }
        }
    }
}
