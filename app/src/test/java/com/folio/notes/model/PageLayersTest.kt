package com.folio.notes.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PageLayersTest {
    @Test fun projectionsMatchStableLayerOrderingAndPreserveItemIdentity() {
        val random = Random(314159)
        repeat(100) {
            val layers = List(random.nextInt(1, PageLayers.MAX_LAYERS + 1)) { index ->
                PageLayer(index * 3, "Layer $index", visible = random.nextBoolean(), locked = random.nextBoolean())
            }.shuffled(random)
            val strokes = List(200) { Stroke(Tool.PEN, 0, 2f, emptyList(), layer = random.nextInt(40)) }
            val texts = List(20) { TextBox(x = 0f, y = 0f, text = "$it", layer = random.nextInt(40)) }
            val images = List(20) { PageImage(id = "$it", x = 0f, y = 0f, width = 10f, height = 10f, layer = random.nextInt(40)) }
            fun <T> expected(items: List<T>, layer: (T) -> Int): List<T> = items
                .filter { item -> layers.first { it.id == PageLayers.resolve(layers, layer(item)) }.visible }
                .sortedBy { item -> layers.indexOfFirst { it.id == PageLayers.resolve(layers, layer(item)) } }
            val source = NotePage(strokes = strokes, texts = texts, images = images, layers = layers)
            val view = PageLayers.view(source)
            val expectedStrokes = expected(strokes) { it.layer }
            val expectedTexts = expected(texts) { it.layer }
            val expectedImages = expected(images) { it.layer }
            assertEquals(expectedStrokes, view.strokes)
            assertEquals(expectedTexts, view.texts)
            assertEquals(expectedImages, view.images)
            expectedStrokes.forEachIndexed { index, stroke -> assertSame(stroke, view.strokes[index]) }
            assertEquals(expectedStrokes, PageLayers.viewStrokes(strokes, layers))
            assertSame(view, PageLayers.view(view))
        }
    }

    @Test fun ordinaryPagesKeepTheirOriginalCollectionsAndLockedLayersRemainVisible() {
        val strokes = listOf(Stroke(Tool.PEN, 0, 2f, emptyList()))
        val page = NotePage(strokes = strokes)
        assertSame(page, PageLayers.view(page))
        assertSame(strokes, PageLayers.viewStrokes(strokes, emptyList()))
        val locked = listOf(PageLayer(0, "Locked", locked = true), PageLayer(1, "Hidden", visible = false))
        assertSame(strokes.single(), PageLayers.viewStrokes(strokes, locked).single())
        assertFalse(PageLayers.editable(locked, 0))
    }
}
