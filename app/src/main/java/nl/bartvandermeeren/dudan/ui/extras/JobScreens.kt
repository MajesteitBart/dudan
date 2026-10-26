package nl.bartvandermeeren.dudan.ui.extras

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.chat.userMessage
import nl.bartvandermeeren.dudan.data.DELIVER_APP
import nl.bartvandermeeren.dudan.data.DELIVER_NOWHERE
import nl.bartvandermeeren.dudan.data.DeliveryTarget
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.IntervalUnit
import nl.bartvandermeeren.dudan.data.JobChanges
import nl.bartvandermeeren.dudan.data.JobDraft
import nl.bartvandermeeren.dudan.data.JobInfo
import nl.bartvandermeeren.dudan.data.JobList
import nl.bartvandermeeren.dudan.data.JobSchedule
import nl.bartvandermeeren.dudan.data.ScheduleText
import nl.bartvandermeeren.dudan.data.newTaskDelivery
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.components.CtaButton
import nl.bartvandermeeren.dudan.ui.components.GlassDialog
import nl.bartvandermeeren.dudan.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.dudan.ui.components.Toggle
import nl.bartvandermeeren.dudan.ui.components.glass
import nl.bartvandermeeren.dudan.ui.components.outlined
import nl.bartvandermeeren.dudan.ui.components.pane
import nl.bartvandermeeren.dudan.ui.theme.GoogleSansCode
import nl.bartvandermeeren.dudan.ui.theme.LocalAccent
import nl.bartvandermeeren.dudan.ui.theme.Palette

/** What `editing` holds while a new task is being written; task ids never start with a plus. */
private const val NEW_JOB = "+new"

private val DraftSaver = listSaver<ScheduleDraft, Any>(save = { it.encode() }, restore = { ScheduleDraft.decode(it) })

/**
 * Scheduled tasks: run, pause and resume each one and, where the server allows it, add, change and delete them.
 * The editor is state inside this screen, the way SkillsScreen opens a skill, so Back returns to the list where it was.
 * A task whose results go to a chat in dudan opens that chat with Results, once the chat exists.
 */
@Composable
fun JobsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<JobList?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyJob by remember { mutableStateOf<String?>(null) }
    // Null until the server says. When it can't say, the controls show and any error speaks for itself.
    var manages by remember { mutableStateOf<Boolean?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    suspend fun load() {
        try {
            list = vm.api.jobs()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    LaunchedEffect(Unit) {
        launch {
            manages = try {
                vm.api.capabilities().managesJobs
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
        }
        load()
    }
    // Clark makes a task's chat when the first result comes in; the drawer's list says which exist.
    LaunchedEffect(Unit) { vm.refreshSessions() }
    fun act(job: JobInfo, action: String) {
        busyJob = job.id
        scope.launch {
            runCatching { vm.api.jobAction(job.id, action) }.onFailure { e ->
                if (e is CancellationException) throw e
                error = e.userMessage()
            }
            load()
            busyJob = null
        }
    }
    val done: (Boolean) -> Unit = { changed ->
        editing = null
        if (changed) scope.launch { load() }
    }

    editing?.let { target ->
        val loaded = list
        val job = loaded?.jobs?.firstOrNull { it.id == target }
        when {
            target == NEW_JOB -> JobEditor(vm, null, loaded?.deliveryTargets.orEmpty(), done)
            job != null -> JobEditor(vm, job, loaded?.deliveryTargets.orEmpty(), done)
            // Back from the background the list comes first; a task deleted meanwhile closes the editor.
            loaded == null -> ScreenFrame(null, onBack = { editing = null }) { LoadingOrError(loading = true, error = error) {} }
            else -> LaunchedEffect(target) { editing = null }
        }
        if (target == NEW_JOB || job != null || loaded == null) return
    }

    val zone = remember { ZoneId.systemDefault() }
    val locale = LocalConfiguration.current.locales[0]
    val text = remember(locale) { ScheduleText(locale) }
    val today = LocalDate.now(zone)
    val canAdd = manages == true && list != null
    ScreenFrame(stringResource(R.string.scheduled_tasks), onBack) {
        Box(Modifier.fillMaxSize()) {
            LoadingOrError(list == null && error == null, if (list == null) error else null) {
                val jobs = list?.jobs.orEmpty()
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    // Room under the last card for New task.
                    contentPadding = PaddingValues(top = 4.dp, bottom = if (canAdd) 104.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (jobs.isEmpty()) {
                        item { Text(stringResource(if (canAdd) R.string.no_jobs_add else R.string.no_jobs), color = Palette.TextTertiary, modifier = Modifier.padding(24.dp)) }
                    }
                    error?.let { item { Text(it, color = Palette.Danger, modifier = Modifier.padding(horizontal = 24.dp)) } }
                    items(jobs, key = { it.id }) { job ->
                        val chat = job.sessionId?.takeIf { id -> sessions.items.any { it.id == id } }
                        JobCard(
                            job = job,
                            schedule = text.summary(job, zone, today),
                            nextRun = text.serverTime(job.nextRunAt, zone, today),
                            readOnlyNote = if (manages == true && !job.editable) job.editNote ?: stringResource(R.string.job_read_only) else null,
                            busy = busyJob == job.id,
                            onEdit = if (manages == true && job.editable) ({ editing = job.id }) else null,
                            onResults = chat?.let { id -> { vm.openSession(id) } },
                            onAction = { act(job, it) },
                        )
                    }
                }
            }
            // At the bottom, in thumb reach, like the drawer's tools.
            if (canAdd) {
                CtaButton(
                    stringResource(R.string.job_new),
                    onClick = { editing = NEW_JOB },
                    icon = Icons.Rounded.Add,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobCard(
    job: JobInfo,
    schedule: String?,
    nextRun: String?,
    readOnlyNote: String?,
    busy: Boolean,
    onEdit: (() -> Unit)?,
    onResults: (() -> Unit)?,
    onAction: (String) -> Unit,
) {
    val paused = job.paused || !job.enabled
    Column(
        Modifier
            .listCard(RoundedCornerShape(24.dp))
            .then(if (onEdit != null) Modifier.clickable(onClickLabel = stringResource(R.string.job_edit), onClick = onEdit) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(job.name, style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(
            schedule,
            nextRun?.takeIf { !paused }?.let { stringResource(R.string.job_next, it) },
            if (paused) stringResource(R.string.job_paused) else null,
        ).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        job.prompt?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        readOnlyNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary) }
        if (job.lastShadow) Text(stringResource(R.string.job_shadow), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.TextSecondary, modifier = Modifier.padding(8.dp).size(18.dp))
            } else {
                PillButton(Icons.Rounded.Bolt, stringResource(R.string.job_run_now)) { onAction("run") }
                if (onResults != null) PillButton(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.job_results), onClick = onResults)
                if (paused) {
                    PillButton(Icons.Outlined.PlayArrow, stringResource(R.string.job_resume)) { onAction("resume") }
                } else {
                    PillButton(Icons.Outlined.Pause, stringResource(R.string.job_pause)) { onAction("pause") }
                }
                if (onEdit != null) PillButton(Icons.Outlined.Edit, stringResource(R.string.job_edit), onClick = onEdit)
            }
        }
    }
}

/**
 * A new task ([job] null) or an existing one. Sends only what changed, so a schedule the picker can't show (Hermes'
 * `every 90m`) or doesn't send (Clark's `at …`) stays as it is when only the instruction changes.
 */
@Composable
private fun JobEditor(vm: MainViewModel, job: JobInfo?, targets: List<DeliveryTarget>, onDone: (changed: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val locale = LocalConfiguration.current.locales[0]
    val text = remember(locale) { ScheduleText(locale) }
    val opened = remember { LocalDateTime.now(zone) }
    val original = remember(job?.id) { job?.let { JobSchedule.parse(it, zone) } }
    val initialDeliver = remember(job?.id) {
        if (job == null) newTaskDelivery(targets) else job.deliver?.takeIf { it.isNotBlank() } ?: DELIVER_NOWHERE
    }

    var name by rememberSaveable { mutableStateOf(job?.name.orEmpty()) }
    var prompt by rememberSaveable { mutableStateOf(job?.prompt.orEmpty()) }
    // What the server has, to tell whether the prompt changed. The list may cut it short; GET /api/jobs/{id} doesn't.
    var serverPrompt by rememberSaveable { mutableStateOf(job?.prompt.orEmpty()) }
    var promptLoaded by rememberSaveable { mutableStateOf(job == null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var deliver by rememberSaveable { mutableStateOf(initialDeliver) }
    // Saved, since `opened`, and a new task's defaults with it, is another moment after the app is recreated.
    val openingDraft = rememberSaveable(saver = DraftSaver) { ScheduleDraft.opening(job, original, opened) }
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(openingDraft) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // Clark's list cuts a prompt at 600 characters, so saving an edit of the list's prompt would cut it for good. Only a
    // server without the single-task endpoint falls back to it; any other failure keeps Save off and offers Retry.
    LaunchedEffect(job?.id, loadAttempt) {
        if (job == null || promptLoaded) return@LaunchedEffect
        loadError = null
        try {
            vm.api.job(job.id).prompt?.let { full ->
                if (prompt == serverPrompt) prompt = full
                serverPrompt = full
            }
            promptLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (HermesApi.lacksJobEndpoint(e)) promptLoaded = true else loadError = e.jobMessage()
        }
    }

    val edit = ScheduleEdit(job, original, openingDraft, draft, zone, Instant.now())
    val nameChanged = job != null && job.nameEditable && name.trim() != job.name
    val promptChanged = prompt.trim() != serverPrompt.trim()
    val deliverChanged = targets.isNotEmpty() && deliver != initialDeliver
    val dirty = if (job == null) name.isNotBlank() || prompt.isNotBlank() || edit.touched else nameChanged || promptChanged || deliverChanged || edit.changed
    // Hermes runs skill-only tasks without a prompt; one of those keeps its empty prompt unless it gets one.
    val promptOk = prompt.isNotBlank() || (job != null && !promptChanged)
    val canSave = name.isNotBlank() && promptOk && promptLoaded && edit.ok && (job == null || dirty) && !busy

    fun save() {
        busy = true
        error = null
        scope.launch {
            try {
                if (job == null) {
                    vm.api.createJob(JobDraft(name.trim(), edit.wire, prompt.trim(), zone.id, deliver.takeIf { targets.isNotEmpty() }))
                } else {
                    val changes = JobChanges(
                        name = name.trim().takeIf { nameChanged },
                        schedule = edit.wire.takeIf { edit.changed },
                        prompt = prompt.trim().takeIf { promptChanged },
                        timezone = zone.id,
                        deliver = deliver.takeIf { deliverChanged },
                    )
                    if (!changes.isEmpty) vm.api.updateJob(job.id, changes)
                }
                onDone(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.jobMessage()
                busy = false
            }
        }
    }

    fun delete() {
        val id = job?.id ?: return
        busy = true
        error = null
        scope.launch {
            try {
                vm.api.deleteJob(id)
                onDone(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.jobMessage()
                busy = false
            }
        }
    }

    fun back() {
        when {
            // The request runs in this editor's scope, so leaving would cancel it. Its answer closes the editor.
            busy -> Unit
            dirty -> confirmDiscard = true
            else -> onDone(false)
        }
    }

    ScreenFrame(
        title = null,
        onBack = ::back,
        header = {
            Text(
                stringResource(if (job == null) R.string.job_new else R.string.job_edit_title),
                style = MaterialTheme.typography.titleLarge,
                color = Palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                EditorCard(null) {
                    val fixedName = job != null && !job.nameEditable
                    EditorField(name, { name = it; error = null }, stringResource(R.string.job_name), stringResource(R.string.job_name_hint), readOnly = fixedName)
                    if (fixedName) Text(stringResource(R.string.job_name_fixed), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                    EditorField(
                        prompt, { prompt = it; error = null }, stringResource(R.string.job_prompt), stringResource(R.string.job_prompt_hint),
                        readOnly = !promptLoaded, singleLine = false,
                        trailing = if (!promptLoaded && loadError == null) ({ CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.TextSecondary, modifier = Modifier.size(18.dp)) }) else null,
                    )
                }
                EditorCard(stringResource(R.string.job_when)) {
                    // An unreadable schedule that's kept isn't drawn as an error under "this stays as it is".
                    ScheduleSection(draft, text, zone, cronError = !edit.keeps && edit.problem != null && draft.cron.isNotBlank()) { draft = it; error = null }
                    HorizontalDivider(color = Palette.Hairline)
                    SchedulePreview(job, edit, text, zone)
                }
                if (targets.isNotEmpty()) {
                    EditorCard(stringResource(R.string.job_deliver)) {
                        DeliveryChoices(targets, initialDeliver, deliver) { deliver = it; error = null }
                    }
                }
            }
            // Between the form and the buttons, so a refusal shows where Save was tapped, above the keyboard too.
            loadError?.let { message ->
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EditorError(message, Modifier.weight(1f))
                    TextButton(onClick = { loadAttempt++ }) { Text(stringResource(R.string.action_retry)) }
                }
            }
            error?.let { EditorError(it, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp)) }
            // Delete and Save along the bottom edge, where a thumb reaches them on a big screen too.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (job != null) PillButton(Icons.Outlined.Delete, stringResource(R.string.delete), color = Palette.Danger) { if (!busy) confirmDelete = true }
                Spacer(Modifier.weight(1f))
                CtaButton(
                    stringResource(R.string.save),
                    onClick = ::save,
                    enabled = canSave,
                    busy = if (busy) ({ CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(16.dp)) }) else null,
                )
            }
        }
    }

    if (confirmDelete && job != null) {
        GlassDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.job_delete_title, job.name)) },
            text = { Text(stringResource(R.string.job_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; delete() }) { Text(stringResource(R.string.delete), color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDiscard) {
        GlassDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.job_discard_title)) },
            text = { Text(stringResource(R.string.job_discard_body)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDone(false) }) { Text(stringResource(R.string.job_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.job_keep_editing)) } },
        )
    }
}

/** A request that failed, read out when it appears. */
@Composable
private fun EditorError(message: String, modifier: Modifier) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = Palette.Danger, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Servers refuse a task in a sentence (a bad cron, a file written by hand); show that sentence as it is. */
private fun Throwable.jobMessage(): String {
    val e = this as? HermesApi.HermesException
    return if (e != null && e.status in 400..499 && e.status != 401 && e.status != 403 && e.message.isNotBlank()) e.message else userMessage()
}

/** A glass card holding one part of the editor, its title inside, like Settings' sections. */
@Composable
private fun EditorCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary)
        content()
    }
}

@Composable
private fun EditorField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    code: Boolean = false,
    isError: Boolean = false,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Palette.TextTertiary) } },
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 4,
        maxLines = if (singleLine) 1 else 12,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        trailingIcon = trailing,
        textStyle = if (code) MaterialTheme.typography.bodyLarge.copy(fontFamily = GoogleSansCode) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = if (code) KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii) else KeyboardOptions.Default,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LocalAccent.current.color,
            unfocusedBorderColor = Palette.Outline,
            focusedLabelColor = Palette.TextPrimary,
            unfocusedLabelColor = Palette.TextSecondary,
            focusedContainerColor = Palette.Surface,
            unfocusedContainerColor = Palette.Surface,
            cursorColor = LocalAccent.current.soft,
            focusedTextColor = if (readOnly) Palette.TextSecondary else Palette.TextPrimary,
            unfocusedTextColor = if (readOnly) Palette.TextSecondary else Palette.TextPrimary,
            unfocusedSupportingTextColor = Palette.TextTertiary,
            focusedSupportingTextColor = Palette.TextSecondary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSection(draft: ScheduleDraft, text: ScheduleText, zone: ZoneId, cronError: Boolean, onChange: (ScheduleDraft) -> Unit) {
    // Once · Repeat · Daily · Weekly · Monthly · Custom: one row on a big screen, two on a narrow one.
    FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScheduleMode.entries.forEach { mode ->
            Choice(stringResource(modeLabel(mode)), selected = draft.mode == mode) { onChange(draft.withMode(mode)) }
        }
    }
    when (draft.mode) {
        ScheduleMode.Once -> OncePicker(draft, text, zone, onChange)
        ScheduleMode.Repeat -> RepeatPicker(draft, text, onChange)
        ScheduleMode.Daily -> {
            Label(stringResource(R.string.schedule_time))
            TimeField(draft.time, text) { onChange(draft.copy(time = it)) }
        }
        ScheduleMode.Weekly -> {
            Label(stringResource(R.string.schedule_days))
            DayPicker(draft.weekDays, text) { onChange(draft.copy(weekDays = it)) }
            Label(stringResource(R.string.schedule_time))
            TimeField(draft.time, text) { onChange(draft.copy(time = it)) }
        }
        ScheduleMode.Monthly -> {
            Label(stringResource(R.string.schedule_day_of_month))
            MonthDayGrid(draft.monthDay) { onChange(draft.copy(monthDay = it)) }
            if (draft.monthDay > 28) {
                Text(stringResource(R.string.schedule_short_months, draft.monthDay), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
            Label(stringResource(R.string.schedule_time))
            TimeField(draft.time, text) { onChange(draft.copy(time = it)) }
        }
        ScheduleMode.Custom -> EditorField(
            draft.cron, { onChange(draft.copy(cron = it)) }, stringResource(R.string.schedule_cron), "30 7 * * 1-5",
            code = true,
            isError = cronError,
            supporting = stringResource(R.string.schedule_cron_hint),
        )
    }
}

private fun modeLabel(mode: ScheduleMode): Int = when (mode) {
    ScheduleMode.Once -> R.string.schedule_once
    ScheduleMode.Repeat -> R.string.schedule_repeat
    ScheduleMode.Daily -> R.string.schedule_daily
    ScheduleMode.Weekly -> R.string.schedule_weekly
    ScheduleMode.Monthly -> R.string.schedule_monthly
    ScheduleMode.Custom -> R.string.schedule_custom
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun OncePicker(draft: ScheduleDraft, text: ScheduleText, zone: ZoneId, onChange: (ScheduleDraft) -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    val now = LocalDateTime.now(zone)
    val today = now.toLocalDate()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PickerField(Icons.Outlined.CalendarToday, text.date(draft.once.toLocalDate(), today), stringResource(R.string.schedule_date), Modifier.weight(1f)) { pickDate = true }
        TimeField(draft.once.toLocalTime(), text, Modifier.weight(1f)) { onChange(draft.copy(once = draft.once.toLocalDate().atTime(it))) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(stringResource(R.string.schedule_in_an_hour), selected = false, action = true) {
            onChange(draft.copy(once = ScheduleDraft.inAnHour(LocalDateTime.now(zone))))
        }
        ScheduleDraft.tonight(now)?.let { tonight ->
            Choice(stringResource(R.string.schedule_tonight), selected = draft.once == tonight) { onChange(draft.copy(once = tonight)) }
        }
        val tomorrow = ScheduleDraft.tomorrowMorning(now)
        Choice(stringResource(R.string.schedule_tomorrow), selected = draft.once == tomorrow) { onChange(draft.copy(once = tomorrow)) }
    }
    if (pickDate) {
        // The date picker works in UTC midnights; only today and later can be picked.
        fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = draft.once.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = remember(today) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = !day(utcTimeMillis).isBefore(today)
                    override fun isSelectableYear(year: Int) = year >= today.year
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(draft.copy(once = day(it).atTime(draft.once.toLocalTime()))) }
                    pickDate = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatPicker(draft: ScheduleDraft, text: ScheduleText, onChange: (ScheduleDraft) -> Unit) {
    Label(stringResource(R.string.schedule_every))
    // A server's */2 isn't offered, but stays selectable when the task already uses it.
    val offered = JobSchedule.MINUTE_STEPS.map { it to IntervalUnit.Minutes } + JobSchedule.HOUR_STEPS.map { it to IntervalUnit.Hours }
    val steps = if ((draft.every to draft.unit) in offered) offered else (offered + (draft.every to draft.unit)).sortedBy { (n, unit) -> if (unit == IntervalUnit.Minutes) n else n * 60 }
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { (n, unit) ->
            val label = stringResource(if (unit == IntervalUnit.Minutes) R.string.schedule_minutes_short else R.string.schedule_hours_short, n)
            Choice(label, selected = draft.every == n && draft.unit == unit) { onChange(draft.copy(every = n, unit = unit)) }
        }
    }
    Toggle(stringResource(R.string.schedule_window), stringResource(R.string.schedule_window_detail), draft.windowOn) { onChange(draft.copy(windowOn = it)) }
    if (draft.windowOn) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(stringResource(R.string.schedule_from))
                HourField(draft.windowStart, minute = 0, text) { onChange(draft.copy(windowStart = it)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(stringResource(R.string.schedule_until))
                HourField(draft.windowEnd, minute = 59, text) { onChange(draft.copy(windowEnd = it)) }
            }
        }
    }
    Toggle(stringResource(R.string.schedule_days_toggle), stringResource(R.string.schedule_days_detail), draft.daysOn) { onChange(draft.copy(daysOn = it)) }
    if (draft.daysOn) DayPicker(draft.repeatDays, text) { onChange(draft.copy(repeatDays = it)) }
}

/** Seven toggles, Monday first, with quick picks for the usual sets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPicker(days: Set<DayOfWeek>, text: ScheduleText, onChange: (Set<DayOfWeek>) -> Unit) {
    val accent = LocalAccent.current
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        DayOfWeek.entries.forEach { day ->
            val on = day in days
            val name = day.getDisplayName(TextStyle.FULL, locale)
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .then(if (on) Modifier.pane(CircleShape, accent.color) else Modifier.outlined(CircleShape))
                    .toggleable(on, role = Role.Checkbox) { onChange(if (on) days - day else days + day) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Text(text.shortDay(day), style = MaterialTheme.typography.labelLarge, color = if (on) accent.on else Palette.TextPrimary, maxLines = 1)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(stringResource(R.string.schedule_weekdays), selected = days == JobSchedule.WEEKDAYS) { onChange(JobSchedule.WEEKDAYS) }
        Choice(stringResource(R.string.schedule_weekend), selected = days == JobSchedule.WEEKEND) { onChange(JobSchedule.WEEKEND) }
        Choice(stringResource(R.string.schedule_every_day), selected = days.size == 7) { onChange(DayOfWeek.entries.toSet()) }
    }
}

/** Days 1 to 31 as a calendar-like grid, seven to a row. */
@Composable
private fun MonthDayGrid(selected: Int, onPick: (Int) -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..31).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    val on = day == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .then(if (on) Modifier.pane(shape, accent.color) else Modifier.outlined(shape))
                            .selectable(on, role = Role.RadioButton) { onPick(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$day", style = MaterialTheme.typography.labelLarge, color = if (on) accent.on else Palette.TextPrimary)
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * What the picked schedule means and when it runs next, on the phone's clock. A cron runs on the edit's clock: the
 * task's own zone while its schedule is unchanged, which the sentence names when it isn't the phone's [zone]. Once
 * the schedule has changed, notes say what saving it does to the task's clock.
 */
@Composable
private fun SchedulePreview(job: JobInfo?, edit: ScheduleEdit, text: ScheduleText, zone: ZoneId) {
    val schedule = edit.schedule
    val problem = edit.problem
    val today = LocalDate.now(zone)
    val runs = remember(job, schedule, edit.clock, edit.changed) { edit.nextRuns(3) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (edit.keeps) {
            // Hermes' `every 90m` still says when it runs next.
            Text(stringResource(R.string.job_schedule_kept, job?.schedule ?: job?.scheduleExpr.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
            NextRuns(runs, text, today)
        } else {
            // A sentence about a schedule that can't run would only contradict the error under it.
            if (problem !is ScheduleProblem.Syntax && problem != ScheduleProblem.Never && problem != ScheduleProblem.NoDays) {
                Text(text.describe(schedule, today, edit.clock, zone), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            }
            if (problem != null) {
                Text(problemText(problem), style = MaterialTheme.typography.bodyMedium, color = Palette.Danger)
            } else {
                NextRuns(runs, text, today)
            }
            if (job != null && edit.changed && schedule !is JobSchedule.Once) {
                if (job.scheduleKind == "interval" && (schedule is JobSchedule.Interval || schedule is JobSchedule.Custom)) {
                    Text(stringResource(R.string.schedule_interval_note), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                }
                val jobZone = job.zoneId
                if (jobZone != null && jobZone != zone) {
                    Text(stringResource(R.string.schedule_zone_note, zone.id, jobZone.id), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun NextRuns(runs: List<LocalDateTime>, text: ScheduleText, today: LocalDate) {
    if (runs.isEmpty()) return
    Text(stringResource(R.string.schedule_next, text.runs(runs, today)), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
}

@Composable
private fun problemText(problem: ScheduleProblem): String = when (problem) {
    ScheduleProblem.Past -> stringResource(R.string.schedule_past)
    ScheduleProblem.Skipped -> stringResource(R.string.schedule_skipped)
    ScheduleProblem.NoDays -> stringResource(R.string.schedule_no_days)
    ScheduleProblem.BadWindow -> stringResource(R.string.schedule_bad_window)
    ScheduleProblem.Never -> stringResource(R.string.cron_error_never)
    is ScheduleProblem.Syntax -> {
        val fields = stringArrayResource(R.array.cron_fields)
        problem.field?.let { stringResource(R.string.cron_error_field, fields[it], problem.token.orEmpty()) } ?: stringResource(R.string.cron_error_fields)
    }
}

/**
 * The server's places for results, the one the task already uses even when it's no longer listed, and nowhere. Clark's
 * `@dudan` reads as this app, in the app's language.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeliveryChoices(targets: List<DeliveryTarget>, initial: String, selected: String, onSelect: (String) -> Unit) {
    val app = stringResource(R.string.job_deliver_app)
    fun label(id: String, server: String) = if (id == DELIVER_APP) app else server
    val options = targets.map { it.id to label(it.id, it.label) } +
        listOfNotNull((initial to label(initial, initial)).takeIf { initial != DELIVER_NOWHERE && targets.none { it.id == initial } }) +
        (DELIVER_NOWHERE to stringResource(R.string.job_deliver_none))
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) -> Choice(label, selected = selected == id) { onSelect(id) } }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
}

/**
 * A pill to pick from: filled with the accent when chosen, outlined otherwise, 44 dp tall for a thumb. [action]
 * pills do something (In 1 hour) instead of standing for a choice.
 */
@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, action: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val base = modifier
        .heightIn(min = 44.dp)
        .then(if (selected) Modifier.pane(CircleShape, accent.color) else Modifier.outlined(CircleShape))
    Box(
        (if (action) base.clickable(role = Role.Button, onClick = onClick) else base.selectable(selected, role = Role.RadioButton, onClick = onClick))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) accent.on else Palette.TextPrimary, maxLines = 1)
    }
}

/** An outlined field showing a picked value, such as a date, with its icon. */
@Composable
private fun PickerField(icon: ImageVector?, value: String, description: String, modifier: Modifier = Modifier, trailing: ImageVector? = null, onClick: () -> Unit) {
    val shape: Shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .heightIn(min = 52.dp)
            .outlined(shape, Palette.Surface)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$description, $value" }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Icon(trailing, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

/** A time, picked on Material's clock in 24 or 12 hours as the phone is set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(time: LocalTime, text: ScheduleText, modifier: Modifier = Modifier, onPick: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerField(Icons.Outlined.Schedule, text.time(time), stringResource(R.string.schedule_time), modifier) { open = true }
    if (!open) return
    val context = LocalContext.current
    val state = rememberTimePickerState(time.hour, time.minute, is24Hour = DateFormat.is24HourFormat(context))
    TimePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                open = false
                onPick(LocalTime.of(state.hour, state.minute))
            }) { Text(stringResource(android.R.string.ok)) }
        },
        title = { Text(stringResource(R.string.schedule_time)) },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    ) { TimePicker(state) }
}

/** A whole hour from a menu, shown as HH:00 for a window's start or HH:59 for its end. */
@Composable
private fun HourField(hour: Int, minute: Int, text: ScheduleText, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PickerField(null, text.time(LocalTime.of(hour, minute)), stringResource(R.string.schedule_time), Modifier.fillMaxWidth(), trailing = Icons.Rounded.KeyboardArrowDown) { open = true }
        GlassDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            (0..23).forEach { h ->
                DropdownMenuItem(
                    text = { Text(text.time(LocalTime.of(h, minute)), color = if (h == hour) LocalAccent.current.soft else Palette.TextPrimary) },
                    onClick = {
                        open = false
                        onPick(h)
                    },
                )
            }
        }
    }
}
