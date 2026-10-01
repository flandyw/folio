package com.folio.notes.mistakes

import org.json.JSONArray
import org.json.JSONObject

/** Structured data for the offline renderer. Source is always text, never executable HTML. */
internal object RichTextDocument {
    fun encode(blocks: List<RichBlock>): String = JSONArray().apply {
        blocks.forEach { block ->
            put(JSONObject().apply {
                when (block) {
                    is RichBlock.Para -> { put("type", "paragraph"); put("inlines", inlines(block.inlines)) }
                    is RichBlock.Heading -> { put("type", "heading"); put("level", block.level); put("inlines", inlines(block.inlines)) }
                    is RichBlock.Quote -> { put("type", "quote"); put("inlines", inlines(block.inlines)) }
                    is RichBlock.Bullets -> { put("type", "bullets"); put("items", JSONArray().apply { block.items.forEach { put(inlines(it)) } }) }
                    is RichBlock.Numbers -> { put("type", "numbers"); put("items", JSONArray().apply { block.items.forEach { put(inlines(it)) } }) }
                    is RichBlock.Code -> { put("type", "code"); put("text", block.code) }
                    is RichBlock.DisplayMath -> { put("type", "math"); put("latex", block.latex) }
                    RichBlock.Divider -> put("type", "divider")
                }
            })
        }
    }.toString()

    private fun inlines(items: List<RichInline>) = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                when (item) {
                    is RichInline.Run -> {
                        put("type", "text"); put("text", item.text)
                        put("bold", item.bold); put("italic", item.italic)
                        put("code", item.code); put("strike", item.strike)
                    }
                    is RichInline.Math -> { put("type", "math"); put("latex", item.latex); put("display", item.display) }
                    RichInline.Break -> put("type", "break")
                }
            })
        }
    }
}
