package nl.bartvandermeeren.dudan.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.bartvandermeeren.dudan.chat.userMessage

/** Hermes' skills, loaded once per server and shared by the `$` list, the Skills screen and the chat engine. */
class SkillCatalog(private val api: HermesApi, private val scope: CoroutineScope) {
    /**
     * [skills] is null until the first list arrives; a failed reload keeps the last one. [error] and
     * [errorStatus] say why the last load failed. [readsSkills] is true when the server can send one
     * skill's SKILL.md (see [ServerCapabilities.readsSkills]).
     */
    data class State(
        val skills: List<SkillInfo>? = null,
        val error: String? = null,
        val errorStatus: Int? = null,
        val readsSkills: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val loading = Mutex()

    /** Goes up with every [reset], so a load for the previous server doesn't land after it. */
    @Volatile private var generation = 0

    /**
     * Loads the list again. The Skills screen does this each time it opens, so new skills show up, and
     * it is the only way a server that answered with its known 500 gets asked again.
     */
    fun refresh() {
        scope.launch { loading.withLock { load() } }
    }

    /** Loads the list unless it's already here, for surfaces that can open without the app, like the overlay. */
    fun ensureLoaded() {
        if (_state.value.skills == null && !broken) scope.launch { skills() }
    }

    /** The skills, loading them first if needed. Empty when Hermes can't list them. */
    suspend fun skills(): List<SkillInfo> = loading.withLock { _state.value.skills ?: if (broken) emptyList() else load() ?: emptyList() }

    /**
     * Stock Hermes answers the list with a 500 every time (see docs/setup.md#skills), so after one, a `$`
     * in a message doesn't wait for another try. Other failures, such as a server out of reach, do retry.
     */
    private val broken: Boolean get() = _state.value.let { it.skills == null && it.errorStatus == 500 }

    /** Forgets the list; call when the server changes. */
    fun reset() {
        generation++
        _state.value = State()
    }

    private suspend fun load(): List<SkillInfo>? {
        val started = generation
        return try {
            val skills = api.skills()
            // Without the capability the Skills screen shows what the list has, and no SKILL.md.
            val readsSkills = try {
                api.capabilities().readsSkills
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (started == generation) _state.value = State(skills, readsSkills = readsSkills)
            skills
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = (e as? HermesApi.HermesException)?.status
            if (started == generation) _state.update { it.copy(error = e.userMessage(), errorStatus = status) }
            null
        }
    }
}
