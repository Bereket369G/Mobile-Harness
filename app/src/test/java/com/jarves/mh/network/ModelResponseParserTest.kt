package com.jarves.mh.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelResponseParserTest {
    @Test
    fun parsesOpenAiStyleDataList() {
        val models = ModelResponseParser.parse(
            """{"data":[{"id":"model-b"},{"id":"model-a","display_name":"Model A"}]}""",
        )

        assertEquals(listOf("model-a", "model-b"), models.map { it.id })
        assertEquals("Model A", models.first().displayName)
    }

    @Test
    fun parsesModelsAndStringArrays() {
        assertEquals(
            listOf("alpha"),
            ModelResponseParser.parse("""{"models":[{"name":"alpha"}]}""").map { it.id },
        )
        assertEquals(
            listOf("alpha", "beta"),
            ModelResponseParser.parse("""["beta","alpha"]""").map { it.id },
        )
    }

    @Test
    fun malformedResponseReturnsEmptyList() {
        assertEquals(emptyList<DiscoveredModel>(), ModelResponseParser.parse("not json"))
    }

    /**
     * The OpenCode Zen catalog carries no pricing block at all — the free tier is encoded
     * purely in the model id. Without recognising the trailing "-free" suffix every one of
     * the $0 models would be presented as a paid model.
     */
    @Test
    fun zenCatalogMarksTrailingFreeSuffixAsFree() {
        val zenCatalog = """
            {"object":"list","data":[
              {"id":"claude-fable-5","object":"model","created":1790678741,"owned_by":"opencode"},
              {"id":"space-bunny-free","object":"model","created":1790678741,"owned_by":"opencode"},
              {"id":"mimo-v2.6-flash-free","object":"model","created":1790678741,"owned_by":"opencode"}
            ]}
        """.trimIndent()

        val models = ModelResponseParser.parse(zenCatalog)

        assertEquals(3, models.size)
        assertEquals("claude-fable-5", models.first { it.id == "claude-fable-5" }.displayName)
        assertEquals(false, models.first { it.id == "claude-fable-5" }.isFree)
        assertEquals(true, models.first { it.id == "space-bunny-free" }.isFree)
        assertEquals(true, models.first { it.id == "mimo-v2.6-flash-free" }.isFree)
    }

    /** Free entries must lead the list, so the $0 models are reachable without scrolling. */
    @Test
    fun freeModelsSortAheadOfPaidOnes() {
        val catalog = """{"data":[{"id":"alpha-paid"},{"id":"zeta-free"}]}"""

        assertEquals(listOf("zeta-free", "alpha-paid"), ModelResponseParser.parse(catalog).map { it.id })
    }

    /**
     * The suffix is anchored deliberately: a paid model whose name merely contains the word
     * must not be advertised as free.
     */
    @Test
    fun freeSubstringInsideAnIdIsNotTreatedAsFree() {
        val models = ModelResponseParser.parse("""{"data":[{"id":"waffle-freeform"},{"id":"freehand-v2"}]}""")

        assertEquals(listOf(false, false), models.map { it.isFree })
    }
}
