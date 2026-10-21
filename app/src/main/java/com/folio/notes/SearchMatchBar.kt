package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The results bar under the toolbar after a search: steps through the matching pages with
 * [onJump], reopens the search it came from, or closes. Shows nothing without matches.
 */
@Composable internal fun SearchMatchBar(overlays: EditorOverlayState, onJump: (Int) -> Unit) {
    val matches = overlays.searchMatches.value
    if (matches.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        val match = overlays.searchMatch.intValue.coerceIn(0, matches.lastIndex)
        // One match has nowhere else to go, so the arrows rest instead of re-jumping to the same page.
        val cycles = matches.size > 1
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = FolioSpacing.dp4),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
            Icon(Icons.Rounded.Search, null, Modifier.padding(horizontal = FolioSpacing.dp8).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                TextButton({ if (overlays.searchFromPdf.value) overlays.pdfSearch.value = true else overlays.noteSearch.value = true }) {
                    Text("${overlays.searchCaption.value} · ${match + 1} of ${matches.size}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton({ val next = (match + matches.size - 1) % matches.size; overlays.searchMatch.intValue = next; onJump(matches[next]) }, enabled = cycles) {
                Icon(Icons.Rounded.KeyboardArrowUp, "Previous matching page")
            }
            IconButton({ val next = (match + 1) % matches.size; overlays.searchMatch.intValue = next; onJump(matches[next]) }, enabled = cycles) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Next matching page")
            }
            IconButton({ overlays.searchMatches.value = emptyList() }) { Icon(Icons.Rounded.Close, "Close search") }
        }
    }
}
