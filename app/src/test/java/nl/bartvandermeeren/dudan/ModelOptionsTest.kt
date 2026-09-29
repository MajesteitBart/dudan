package nl.bartvandermeeren.dudan

import kotlinx.serialization.json.JsonObject
import nl.bartvandermeeren.dudan.data.HermesJson
import nl.bartvandermeeren.dudan.data.ModelCatalogParser
import nl.bartvandermeeren.dudan.data.ModelChoice
import nl.bartvandermeeren.dudan.data.ReasoningEffort
import nl.bartvandermeeren.dudan.data.storedEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelOptionsTest {
    // The shape clarkbox's Hermes 0.21.5 returns from /api/model/options, trimmed.
    private val catalog = ModelCatalogParser.parse(
        HermesJson.parseToJsonElement(
            """
            {"model": "gpt-6-astra", "provider": "openai-codex", "providers": [
              {"slug": "openai-codex", "name": "ChatGPT or Codex Subscription", "authenticated": true,
               "models": ["gpt-6-astra", "gpt-5.3-codex-spark"],
               "capabilities": {"gpt-6-astra": {"fast": true, "reasoning": true}, "gpt-5.3-codex-spark": {"fast": false, "reasoning": true}}},
              {"slug": "openai-api", "name": "OpenAI API", "authenticated": true,
               "models": ["gpt-4o"], "capabilities": {"gpt-4o": {"fast": true, "reasoning": false}}},
              {"slug": "nous", "name": "Nous Portal", "authenticated": true, "models": [{"id": "hermes-4-405b"}]}
            ]}
            """,
        ) as JsonObject,
    )

    @Test
    fun readsWhatEachModelTakes() {
        val astra = catalog.options.single { it.model == "gpt-6-astra" }
        assertEquals(true to true, astra.reasoning to astra.fast)
        val spark = catalog.options.single { it.model == "gpt-5.3-codex-spark" }
        assertEquals(true to false, spark.reasoning to spark.fast)
        val gpt4o = catalog.options.single { it.model == "gpt-4o" }
        assertEquals(false to true, gpt4o.reasoning to gpt4o.fast)
        // No capabilities at all: unknown, so the picker still shows the thinking level.
        val hermes = catalog.options.single { it.model == "hermes-4-405b" }
        assertEquals(null to null, hermes.reasoning to hermes.fast)
    }

    @Test
    fun theServerDefaultRunsOnTheCurrentModel() {
        assertEquals("gpt-6-astra", catalog.optionFor(ModelChoice())?.model)
        assertEquals("gpt-4o", catalog.optionFor(ModelChoice("openai-api", "gpt-4o"))?.model)
        assertNull(catalog.optionFor(ModelChoice("openai-api", "gpt-6-astra")))
    }

    @Test
    fun dropsWhatTheModelDoesNotTake() {
        val wants = ModelChoice(effort = ReasoningEffort.High, fast = true)
        // gpt-4o has no thinking level; Codex Spark has no fast mode.
        assertEquals(wants.copy("openai-api", "gpt-4o", effort = null), catalog.supported(wants.copy("openai-api", "gpt-4o")))
        assertEquals(wants.copy("openai-codex", "gpt-5.3-codex-spark", fast = false), catalog.supported(wants.copy("openai-codex", "gpt-5.3-codex-spark")))
        // The server default (gpt-6-astra) takes both.
        assertEquals(wants, catalog.supported(wants))
        // Hermes doesn't say for hermes-4-405b: keep the level, but only offer fast mode where it's reported.
        assertEquals(wants.copy("nous", "hermes-4-405b", fast = false), catalog.supported(wants.copy("nous", "hermes-4-405b")))
        // A model the catalog doesn't list stays as it is.
        assertEquals(wants.copy("other", "x"), catalog.supported(wants.copy("other", "x")))
    }

    @Test
    fun theOldThinkingSettingCarriesOver() {
        // Before 0.6.3 "Fast" only ever sent reasoning_effort low, and "Extended" high.
        assertEquals(ReasoningEffort.Low, storedEffort(saved = null, legacy = "Fast", default = null))
        assertEquals(ReasoningEffort.High, storedEffort(saved = null, legacy = "Extended", default = ReasoningEffort.Low))
        assertNull(storedEffort(saved = null, legacy = "Default", default = ReasoningEffort.Low))
        // Nothing saved: the profile's default. A new value wins over the old one.
        assertEquals(ReasoningEffort.Low, storedEffort(saved = null, legacy = null, default = ReasoningEffort.Low))
        assertEquals(ReasoningEffort.Max, storedEffort(saved = "max", legacy = "Fast", default = null))
        assertNull(storedEffort(saved = "default", legacy = "Extended", default = ReasoningEffort.Low))
    }
}
