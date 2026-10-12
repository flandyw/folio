package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Shared toolbar overlay container. Every layout gets the same tool owner and text-draft controls. */
@Composable internal fun BoxScope.EditorToolbarHost(style: ToolbarStyle, music: Boolean,
    onHeight: (Int) -> Unit, tools: @Composable () -> Unit, text: @Composable () -> Unit) {
    Column(Modifier.align(Alignment.TopCenter).zIndex(11f).fillMaxWidth()
        .then(if (style == ToolbarStyle.GOODNOTES && !music) Modifier
            else Modifier.padding(top = 6.dp, start = 6.dp, end = 6.dp))
        .onSizeChanged { onHeight(it.height) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools()
        text()
    }
}
