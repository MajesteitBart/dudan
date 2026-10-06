package nl.bartvandermeeren.dudan.ui.extras

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.chat.userMessage
import nl.bartvandermeeren.dudan.data.JobInfo
import nl.bartvandermeeren.dudan.data.SkillDetail
import nl.bartvandermeeren.dudan.data.SkillInfo
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.components.CtaButton
import nl.bartvandermeeren.dudan.ui.components.GlassDefaults
import nl.bartvandermeeren.dudan.ui.components.Markdown
import nl.bartvandermeeren.dudan.ui.components.PlainIconButton
import nl.bartvandermeeren.dudan.ui.components.glass
import nl.bartvandermeeren.dudan.ui.components.outlined
import nl.bartvandermeeren.dudan.ui.openui.openExternalUrl
import nl.bartvandermeeren.dudan.ui.theme.GoogleSansCode
import nl.bartvandermeeren.dudan.ui.theme.LocalAccent
import nl.bartvandermeeren.dudan.ui.theme.Palette

@Composable
private fun ScreenFrame(title: String?, onBack: () -> Unit, header: @Composable (() -> Unit)? = null, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 880.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlainIconButton(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    stringResource(R.string.action_back),
                    onClick = onBack,
                    modifier = Modifier.outlined(CircleShape),
                    size = 44.dp,
                    iconSize = 24.dp,
                )
                if (header != null) {
                    Box(Modifier.weight(1f)) { header() }
                } else if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary)
                }
            }
            content()
        }
    }
}

/** A glass card on the sky, inset from the screen edges. */
private fun Modifier.listCard(shape: Shape = RoundedCornerShape(22.dp)) = fillMaxWidth()
    .padding(horizontal = 16.dp)
    .glass(shape)

@Composable
fun SearchScreen(vm: MainViewModel, onBack: () -> Unit) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = remember(query, sessions.items) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) sessions.items
        else sessions.items.filter { (it.title.orEmpty() + " " + it.preview.orEmpty()).lowercase().contains(q) }
    }
    ScreenFrame(
        title = null,
        onBack = onBack,
        header = {
            Box(Modifier.fillMaxWidth().glass(CircleShape)) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(12.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.TextPrimary),
                        cursorBrush = SolidColor(LocalAccent.current.soft),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary)
                                inner()
                            }
                        },
                    )
                }
            }
        },
    ) {
        // All results on one glass card, rows split by hairlines, like the inbox panel in Superhuman's hero.
        LazyColumn(
            Modifier
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
                .fillMaxSize()
                .glass(GlassDefaults.CardShape),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            if (results.isEmpty()) {
                item {
                    Text(
                        stringResource(if (query.isBlank()) R.string.no_chats else R.string.no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextTertiary,
                        modifier = Modifier.padding(22.dp),
                    )
                }
            }
            itemsIndexed(results, key = { _, it -> it.id }) { index, session ->
                if (index > 0) HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(horizontal = 20.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.openSession(session.id) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Text(
                        session.displayTitle.ifBlank { stringResource(R.string.untitled_chat) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    session.preview?.takeIf { it.isNotBlank() && it != session.displayTitle }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Where the docs explain stock Hermes' 500 for the skill list and its one-line fix. */
private const val SKILLS_FIX_URL = "https://github.com/MajesteitBart/dudan/blob/main/docs/setup.md#skills"

@Composable
fun SkillsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val catalog by vm.skillCatalog.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.skillCatalog.refresh() }
    var openSkill by rememberSaveable { mutableStateOf<String?>(null) }
    // Kept out here so the list is where it was when you come back from a skill.
    val listState = rememberLazyListState()
    openSkill?.let { name ->
        val skill = catalog.skills?.firstOrNull { it.name == name } ?: SkillInfo(name, null, null)
        SkillDetailScreen(vm, skill, readable = catalog.readsSkills, onBack = { openSkill = null })
        return
    }
    val skills = catalog.skills
    ScreenFrame(stringResource(R.string.skills), onBack) {
        if (skills == null && catalog.errorStatus == 500) HermesSkillsBug()
        else LoadingOrError(skills == null, if (skills == null) catalog.error else null) {
            val grouped = skills.orEmpty().groupBy { it.category?.takeIf { c -> c.isNotBlank() } ?: "general" }.toSortedMap()
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Text(
                        stringResource(R.string.skills_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextSecondary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                // One pane per category, its skills separated by hairlines.
                grouped.forEach { (category, list) ->
                    item(key = "c_$category") {
                        Text(
                            categoryLabel(category),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalAccent.current.soft,
                            modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 8.dp),
                        )
                    }
                    item(key = "g_$category") {
                        Column(Modifier.listCard(RoundedCornerShape(24.dp)).padding(vertical = 4.dp)) {
                            list.forEachIndexed { index, skill ->
                                if (index > 0) HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(horizontal = 18.dp))
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { openSkill = skill.name }
                                        .padding(horizontal = 18.dp, vertical = 12.dp),
                                ) {
                                    Text(skill.name, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                                    skill.description?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun categoryLabel(category: String) = category.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }

/** Stock Hermes answers 500 for the skill list until upstream merges a one-line fix; docs/setup.md has it. */
@Composable
private fun HermesSkillsBug() {
    val context = LocalContext.current
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.skills_hermes_bug), style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary)
        PillButton(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.skills_hermes_bug_fix)) { openExternalUrl(context, SKILLS_FIX_URL) }
    }
}

/**
 * One skill: what it's for, its category and Use in chat. When the server can send SKILL.md (a patched
 * Hermes, see tools/hermes-patches), it also shows the instructions, where the file lives and its extra files.
 */
@Composable
private fun SkillDetailScreen(vm: MainViewModel, skill: SkillInfo, readable: Boolean, onBack: () -> Unit) {
    var detail by remember(skill.name) { mutableStateOf<SkillDetail?>(null) }
    var error by remember(skill.name) { mutableStateOf<String?>(null) }
    LaunchedEffect(skill.name, readable) {
        if (!readable) return@LaunchedEffect
        try {
            detail = vm.api.skill(skill.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    val accent = LocalAccent.current
    ScreenFrame(
        title = null,
        onBack = onBack,
        header = {
            Text(skill.name, style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "about") {
                Column(Modifier.listCard(RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    (detail?.description ?: skill.description)?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                    }
                    (detail?.category ?: skill.category)?.takeIf { it.isNotBlank() }?.let {
                        Text(categoryLabel(it), style = MaterialTheme.typography.labelMedium, color = accent.soft)
                    }
                    detail?.path?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = GoogleSansCode), color = Palette.TextSecondary)
                    }
                    CtaButton(stringResource(R.string.skill_use), onClick = { vm.useSkill(skill) }, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (readable) {
                val loaded = detail
                when {
                    error != null -> item(key = "error") {
                        Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = 24.dp))
                    }
                    loaded == null -> item(key = "loading") { LoadingOrError(loading = true, error = null) {} }
                    else -> {
                        if (loaded.body.isNotBlank()) {
                            item(key = "body") {
                                Box(Modifier.listCard(RoundedCornerShape(24.dp)).padding(18.dp)) {
                                    SelectionContainer { Markdown(loaded.body.trim(), style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                        }
                        if (loaded.files.isNotEmpty()) {
                            item(key = "files") {
                                Column(Modifier.listCard(RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.skill_files), style = MaterialTheme.typography.labelMedium, color = accent.soft)
                                    loaded.files.forEach { file ->
                                        Text(file, style = MaterialTheme.typography.bodySmall.copy(fontFamily = GoogleSansCode), color = Palette.TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JobsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<JobInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyJob by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        try {
            jobs = vm.api.jobs()
            error = null
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    LaunchedEffect(Unit) { load() }
    fun act(job: JobInfo, action: String) {
        busyJob = job.id
        scope.launch {
            runCatching { vm.api.jobAction(job.id, action) }.onFailure { error = it.userMessage() }
            load()
            busyJob = null
        }
    }
    ScreenFrame(stringResource(R.string.scheduled_tasks), onBack) {
        LoadingOrError(jobs == null && error == null, if (jobs == null) error else null) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (jobs.orEmpty().isEmpty()) {
                    item { Text(stringResource(R.string.no_jobs), color = Palette.TextTertiary, modifier = Modifier.padding(24.dp)) }
                }
                error?.let { item { Text(it, color = Palette.Danger, modifier = Modifier.padding(horizontal = 24.dp)) } }
                items(jobs.orEmpty(), key = { it.id }) { job ->
                    Box(Modifier.listCard(RoundedCornerShape(24.dp))) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(job.name, style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(
                                job.schedule,
                                job.nextRunAt?.let { stringResource(R.string.job_next, it.take(16).replace('T', ' ')) },
                                if (job.paused || !job.enabled) stringResource(R.string.job_paused) else null,
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                            job.prompt?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (busyJob == job.id) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.TextSecondary, modifier = Modifier.size(18.dp))
                                } else {
                                    PillButton(Icons.Rounded.Bolt, stringResource(R.string.job_run_now)) { act(job, "run") }
                                    if (job.paused || !job.enabled) {
                                        PillButton(Icons.Outlined.PlayArrow, stringResource(R.string.job_resume)) { act(job, "resume") }
                                    } else {
                                        PillButton(Icons.Outlined.Pause, stringResource(R.string.job_pause)) { act(job, "pause") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .outlined(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Palette.Icon, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = Palette.TextPrimary)
    }
}

@Composable
private fun LoadingOrError(loading: Boolean, error: String?, content: @Composable () -> Unit) {
    when {
        error != null -> Text(error, color = Palette.TextSecondary, modifier = Modifier.padding(24.dp))
        loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.TextSecondary, modifier = Modifier.size(28.dp))
        }
        else -> content()
    }
}
