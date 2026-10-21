package com.folio.notes.editor.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Virtualized paper column. The seam is presentation space and never scales stored ink. */
@Composable internal fun ContinuousDocument(
    state: LazyListState, modifier: Modifier, top: androidx.compose.ui.unit.Dp,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(state = state, modifier = modifier,
        contentPadding = PaddingValues(top = top + 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, content = content)
}
