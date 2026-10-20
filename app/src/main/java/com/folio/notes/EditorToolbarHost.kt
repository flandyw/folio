package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Shared toolbar overlay container. Every layout gets the same tool owner and text-draft controls. */
@Composable internal fun BoxScope.EditorToolbarHost(music: Boolean,
    onHeight: (Int) -> Unit, tools: @Composable () -> Unit, text: @Composable () -> Unit) {
    Column(Modifier.align(Alignment.TopCenter).zIndex(11f).fillMaxWidth()
        .then(if (!music) Modifier
            else Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
        .onSizeChanged { onHeight(it.height) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tools()
        text()
    }
}
