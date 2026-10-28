package nl.bartvandermeeren.dudan.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.bartvandermeeren.dudan.chat.userMessage
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.components.CtaButton
import nl.bartvandermeeren.dudan.ui.components.DudanMark
import nl.bartvandermeeren.dudan.ui.components.PlainIconButton
import nl.bartvandermeeren.dudan.ui.components.glass
import nl.bartvandermeeren.dudan.ui.openui.openExternalUrl
import nl.bartvandermeeren.dudan.ui.theme.LocalAccent
import nl.bartvandermeeren.dudan.ui.theme.Palette
import org.jetbrains.compose.resources.stringResource

internal sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Ok(val model: String?) : TestState
    data class Failed(val message: String) : TestState
}

/** The connection's fields and their test, which Setup and Settings both show. */
@Stable
internal class ConnectionForm(private val vm: MainViewModel, private val scope: CoroutineScope, settings: AppSettings) {
    var url by mutableStateOf(settings.serverUrl)
    var key by mutableStateOf(settings.apiKey)
    var name by mutableStateOf(settings.userName)
    var assistant by mutableStateOf(settings.assistantName)
    var test by mutableStateOf<TestState>(TestState.Idle)
        private set

    val canConnect: Boolean get() = url.isNotBlank() && key.isNotBlank() && test != TestState.Testing

    /** Counts tests; one whose address or key changed while the server answered is no longer current. */
    private var attempt = 0

    /**
     * Asks the server for its model with these credentials, and saves them once it answers: the values as
     * they were when the test began, so what's saved is what was tested.
     */
    fun connect() {
        if (!canConnect) return
        val mine = ++attempt
        val tested = listOf(url, key, name, assistant)
        test = TestState.Testing
        scope.launch {
            try {
                val (url, key, name, assistant) = tested
                val model = vm.apiFor(HermesApi.ServerConfig(HermesApi.normalizeBaseUrl(url), key.trim())).verify()
                if (mine != attempt) return@launch
                vm.store.saveSetup(url, key, name, assistant)
                test = TestState.Ok(model)
                vm.refreshSessions()
                vm.loadModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == attempt) test = TestState.Failed(e.userMessage())
            }
        }
    }

    /** A changed address or key hasn't been tested yet, and a test still running is for the old one. */
    fun edited() {
        attempt++
        test = TestState.Idle
    }
}

@Composable
internal fun rememberConnectionForm(vm: MainViewModel, settings: AppSettings): ConnectionForm {
    val scope = rememberCoroutineScope()
    return remember { ConnectionForm(vm, scope, settings) }
}

/** The address and key, then the two names side by side. Enter in any of them connects. */
@Composable
internal fun ConnectionFields(form: ConnectionForm) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FormField(
            form.url, { form.url = it; form.edited() }, stringResource(Res.string.server_url),
            placeholder = "http://my-server:8642", keyboard = KeyboardType.Uri, onSubmit = form::connect,
        )
        if (isInsecureRemote(form.url)) Notice(stringResource(Res.string.insecure_url_warning), Palette.SparkAmber)
        FormField(form.key, { form.key = it; form.edited() }, stringResource(Res.string.api_key), password = true, onSubmit = form::connect)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField(form.name, { form.name = it }, stringResource(Res.string.your_name), onSubmit = form::connect, modifier = Modifier.weight(1f))
            FormField(form.assistant, { form.assistant = it }, stringResource(Res.string.assistant_name), onSubmit = form::connect, modifier = Modifier.weight(1f))
        }
    }
}

/** The connect button with the test's outcome beside it. */
@Composable
internal fun ConnectionActions(form: ConnectionForm, label: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val t = form.test) {
            is TestState.Ok -> Notice(stringResource(Res.string.connection_ok, t.model ?: "Hermes"), Palette.Success, ok = true)
            is TestState.Failed -> Notice(stringResource(Res.string.connection_failed, t.message), Palette.Danger, error = true)
            else -> Unit
        }
        CtaButton(
            label,
            onClick = form::connect,
            enabled = form.canConnect,
            modifier = Modifier.align(Alignment.End),
            busy = if (form.test == TestState.Testing) {
                { CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
        )
    }
}

/**
 * A text field with its label above it, the way desktop forms read, instead of Material's label in the
 * border. [password] hides the text behind an eye that shows it. Screen readers get the label as the
 * field's name.
 */
@Composable
internal fun FormField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(14.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) Palette.TextPrimary else Palette.TextSecondary,
            modifier = Modifier.padding(start = 2.dp).clearAndSetSemantics { },
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.TextPrimary),
            cursorBrush = SolidColor(accent.soft),
            visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .onPreviewKeyEvent { event ->
                    if (onSubmit != null && event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { field ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clip(shape)
                        .background(Palette.Surface)
                        .border(1.dp, if (focused) accent.color.copy(alpha = 0.8f) else Palette.Outline, shape)
                        .padding(start = 14.dp, end = if (password) 4.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Palette.TextTertiary, maxLines = 1)
                        }
                        field()
                    }
                    if (password) {
                        PlainIconButton(
                            if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            stringResource(if (revealed) Res.string.desktop_hide_key else Res.string.desktop_show_key),
                            onClick = { revealed = !revealed },
                            size = 38.dp,
                            iconSize = 20.dp,
                            tint = Palette.TextSecondary,
                        )
                    }
                }
            },
        )
    }
}

/**
 * The first start: dudan's mark and a short welcome over one card with the connection, in the middle of
 * the window. Connecting saves it, and the chats take over.
 */
@Composable
fun SetupScreen(vm: MainViewModel, settings: AppSettings) {
    val form = rememberConnectionForm(vm, settings)
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DudanMark(size = 72.dp, halo = true)
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(Res.string.setup_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Palette.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                // A word joiner after "://" keeps the example address on one line.
                stringResource(Res.string.setup_intro).replace("://", "://\u2060"),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(28.dp))
            Column(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                ConnectionFields(form)
                ConnectionActions(form, stringResource(Res.string.connect))
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = Palette.TextTertiary, modifier = Modifier.size(14.dp))
                Text(stringResource(Res.string.desktop_setup_key_note), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { openExternalUrl(HERMES_SETUP_DOCS) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(Res.string.desktop_setup_help), style = MaterialTheme.typography.labelMedium, color = LocalAccent.current.soft)
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = LocalAccent.current.soft, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private const val HERMES_SETUP_DOCS = "https://github.com/MajesteitBart/dudan/blob/main/docs/setup.md#hermes-side"
