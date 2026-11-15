@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.chatgpt

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.app.AppPrefs
import com.folio.notes.input.guardUiTouches
import com.folio.notes.ui.FolioIcons
import com.folio.notes.ui.FolioMenuItem
import com.folio.notes.ui.FolioMenuPopover

/**
 * Model and reasoning level for background classification, separate from Page review's choice, so a smaller,
 * faster model can sort the library while reviews keep a stronger one. Saved as it changes.
 */
@Composable
internal fun ClassifierModelControls(chatGpt: ChatGptManager, modifier: Modifier = Modifier) {
    val prefs = LocalContext.current.getSharedPreferences("preferences", 0)
    val state by chatGpt.state.collectAsState()
    LaunchedEffect(Unit) { chatGpt.loadModelsQuietly() }
    var modelPref by remember { mutableStateOf(prefs.getString(AppPrefs.AI_CLASSIFY_MODEL, null)) }
    var effortPref by remember { mutableStateOf(prefs.getString(AppPrefs.AI_CLASSIFY_EFFORT, null)) }
    val model = ChatGptInference.classifyModel(modelPref, state.model, state.models)
    fun effortText(pref: String?) = when (pref) {
        null -> "Lightest" + (ChatGptInference.classifyEffort(null, model?.efforts.orEmpty())?.let { " (${ChatGptInference.effortLabel(it)})" } ?: "")
        ChatGptInference.MODEL_DEFAULT -> "Model default"
        else -> ChatGptInference.effortLabel(pref)
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        var modelsOpen by remember { mutableStateOf(false) }
        var effortsOpen by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            OutlinedButton({ modelsOpen = true }, modifier = Modifier.fillMaxWidth(), enabled = state.models.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                Text(when {
                    state.models.isEmpty() -> "Loading models…"
                    modelPref == null -> "Same as Page review · ${model?.name.orEmpty()}"
                    else -> model?.name.orEmpty()
                }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(FolioIcons.ChevronDown, null)
            }
            FolioMenuPopover(modelsOpen, { modelsOpen = false }, Modifier.heightIn(max = 360.dp).guardUiTouches(), title = "Classification model") {
                FolioMenuItem({ Text("Same as Page review") }, {
                    modelPref = null; prefs.edit().remove(AppPrefs.AI_CLASSIFY_MODEL).apply(); modelsOpen = false
                }, selected = modelPref == null)
                ModelMenuItems(state.models, modelPref) { option ->
                    modelPref = option.slug; prefs.edit().putString(AppPrefs.AI_CLASSIFY_MODEL, option.slug).apply(); modelsOpen = false
                }
            }
        }
        Box {
            OutlinedButton({ effortsOpen = true }, enabled = model?.efforts?.isNotEmpty() == true, shapes = ButtonDefaults.shapes()) {
                Text(if (model?.efforts.isNullOrEmpty()) "Model default" else effortText(effortPref).substringBefore(" ("), maxLines = 1)
                Icon(FolioIcons.ChevronDown, null)
            }
            FolioMenuPopover(effortsOpen, { effortsOpen = false }, Modifier.guardUiTouches(), title = "Reasoning") {
                (listOf(null, ChatGptInference.MODEL_DEFAULT) + model?.efforts.orEmpty()).forEach { option ->
                    FolioMenuItem({ Text(effortText(option)) }, {
                        effortPref = option
                        prefs.edit().apply { if (option == null) remove(AppPrefs.AI_CLASSIFY_EFFORT) else putString(AppPrefs.AI_CLASSIFY_EFFORT, option) }.apply()
                        effortsOpen = false
                    }, selected = effortPref == option)
                }
            }
        }
    }
}
