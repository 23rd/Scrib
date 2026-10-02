package org.scrib.transcriber

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private suspend fun fetchModels(settings: EndpointSettings): Result<List<String>> = withContext(Dispatchers.IO) {
    if (!settings.isComplete) {
        return@withContext Result.failure(EndpointException("Fill in the address and the key first"))
    }
    var connection: HttpURLConnection? = null
    try {
        connection = URL("${settings.normalizedBaseUrl}/models").openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        val status = connection.responseCode
        if (status !in 200..299) {
            val body = runCatching {
                connection.errorStream?.use { String(it.readBytes()) } ?: ""
            }.getOrDefault("")
            val detail = runCatching {
                JSONObject(body).optString("error").ifEmpty { JSONObject(body).optString("detail") }
            }.getOrDefault("")
            val message = when (status) {
                401, 403 -> "The server rejected the API key (HTTP $status). $detail"
                404 -> "No /v1/models at ${settings.normalizedBaseUrl} (HTTP 404)."
                else -> "The server returned HTTP $status. $detail"
            }
            return@withContext Result.failure(EndpointException(message))
        }
        val body = BufferedInputStream(connection.inputStream).use { it.readBytes() }
        val array = JSONObject(String(body)).optJSONArray("data")
        val ids = ArrayList<String>()
        for (i in 0 until (array?.length() ?: 0)) {
            array!!.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        }
        if (ids.isEmpty()) {
            Result.failure(EndpointException("The server listed no models"))
        } else {
            Result.success(ids)
        }
    } catch (e: EndpointException) {
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(EndpointException("Cannot reach ${settings.host}: ${e.message ?: "connection failed"}"))
    } finally {
        connection?.disconnect()
    }
}

private suspend fun testEndpoint(context: Context, settings: EndpointSettings): Result<String> =
    fetchModels(settings).map { models ->
        if (models.contains(settings.model)) {
            context.getString(R.string.endpoint_test_ok, models.size)
        } else {
            context.getString(R.string.endpoint_test_ok_other, models.size, settings.model)
        }
    }

class EndpointPluginImpl : EndpointPlugin {

    @Volatile
    private var engine: EndpointTranscriptionEngine? = null

    override fun isActive(context: Context): Boolean = EndpointStore.isActive(context.applicationContext)

    override fun isConfigured(context: Context): Boolean = EndpointStore.isConfigured(context.applicationContext)

    override fun host(context: Context): String = EndpointStore.settings(context.applicationContext).host

    override fun displayName(context: Context): String =
        EndpointStore.settings(context.applicationContext).display

    override fun modelName(context: Context): String = EndpointStore.settings(context.applicationContext).model

    override fun setActive(context: Context, active: Boolean) {
        EndpointStore.setActive(context.applicationContext, active)
        if (active) {
            TranscriptionEngine.releaseAll(context.applicationContext)
        }
    }

    override fun engine(context: Context): TranscriptionEngine {
        val app = context.applicationContext
        val settings = EndpointStore.settings(app)
        if (!settings.isComplete) {
            throw ModelNotAvailableException("No transcription endpoint is set up")
        }
        val current = engine
        if (current != null && current.matches(settings)) {
            return current
        }
        return EndpointTranscriptionEngine(app, settings).also { engine = it }
    }

    override suspend fun fetchModels(context: Context): Result<List<String>> =
        fetchModels(EndpointStore.settings(context.applicationContext))

    override suspend fun testConnection(context: Context): Result<String> =
        testEndpoint(context.applicationContext, EndpointStore.settings(context.applicationContext))

    @Composable
    override fun SettingsBlocks(onChanged: () -> Unit) {
        val context = LocalContext.current
        val cs = MaterialTheme.colorScheme
        var expanded by rememberSaveable { mutableStateOf(true) }
        var showDialog by rememberSaveable { mutableStateOf(false) }

        var configured by remember { mutableStateOf(isConfigured(context)) }
        var active by remember { mutableStateOf(isActive(context)) }
        var stored by remember { mutableStateOf(EndpointStore.settings(context)) }

        fun reload() {
            configured = isConfigured(context)
            active = isActive(context)
            stored = EndpointStore.settings(context)
            onChanged()
        }

        SectionHeader(
            title = stringResource(R.string.endpoint_header),
            expanded = expanded,
            onToggle = { expanded = !expanded },
            top = 8.dp,
        )
        if (!expanded) return

        if (!configured) {
            Text(
                stringResource(R.string.endpoint_not_configured),
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        Surface(
            color = if (active) cs.primaryContainer else cs.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (active) cs.primary else cs.outline),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable { showDialog = true }
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (configured) stored.display else stringResource(R.string.endpoint_setup),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) cs.onPrimaryContainer else cs.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (active) {
                        Text(
                            stringResource(R.string.endpoint_active),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.onPrimaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (configured) {
                    Text(
                        stringResource(R.string.endpoint_model_line, stored.model),
                        fontSize = 12.5.sp,
                        color = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        if (configured) {
            Text(
                stringResource(R.string.endpoint_privacy),
                fontSize = 12.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EndpointButton(
                    label = stringResource(if (active) R.string.endpoint_stop_using else R.string.endpoint_use),
                    primary = !active,
                    onClick = {
                        setActive(context, !active)
                        reload()
                    }
                )
                EndpointButton(
                    label = stringResource(R.string.endpoint_edit),
                    onClick = { showDialog = true }
                )
            }
        }

        if (showDialog) {
            EndpointDialog(
                initial = stored,
                onDismiss = { showDialog = false },
                onSaved = { reload() },
                onForget = {
                    EndpointStore.forget(context).apply()
                    showDialog = false
                    reload()
                }
            )
        }
    }
}

@Composable
private fun EndpointButton(label: String, onClick: () -> Unit, primary: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (primary) cs.primary else cs.surface,
        shape = RoundedCornerShape(14.dp),
        border = if (primary) null else BorderStroke(1.dp, cs.outline),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            label,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) cs.onPrimary else cs.onSurface,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointDialog(
    initial: EndpointSettings,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onForget: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var key by rememberSaveable { mutableStateOf(initial.apiKey) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var modelOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun run(action: suspend () -> Result<String>) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            status = action().fold({ text -> text to false }, { e -> (e.message ?: "Failed") to true })
            busy = false
        }
    }

    fun save(): EndpointSettings? = try {
        val settings = EndpointSettings.parse(url, key, model, name)
        EndpointStore.save(context, settings)
        settings
    } catch (e: IllegalArgumentException) {
        status = (e.message ?: "Check the fields") to true
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.endpoint_dialog_title), fontSize = 17.sp) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.endpoint_dialog_hint),
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.endpoint_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.endpoint_name)) },
                    supportingText = { Text(stringResource(R.string.endpoint_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.endpoint_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = !menuOpen }
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(stringResource(R.string.endpoint_model)) },
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    model = option
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            val settings = save() ?: return@TextButton
                            busy = true
                            status = null
                            scope.launch {
                                fetchModels(settings).fold(
                                    { ids ->
                                        modelOptions = ids
                                        if (model.isBlank() || model !in ids) {
                                            model = ids.first()
                                        }
                                        status = context.getString(R.string.endpoint_models_found, ids.size) to false
                                    },
                                    { status = (it.message ?: "Failed") to true }
                                )
                                busy = false
                            }
                        }
                    ) { Text(stringResource(R.string.endpoint_fetch), fontSize = 13.sp) }
                }
                status?.let { (message, error) ->
                    Text(
                        message,
                        fontSize = 12.5.sp,
                        color = if (error) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.heightIn(max = 18.dp, min = 18.dp),
                        strokeWidth = 2.dp
                    )
                }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val settings = save()
                        if (settings != null) {
                            run { testEndpoint(context, settings) }
                        }
                    }
                ) { Text(stringResource(R.string.endpoint_test), fontSize = 13.sp) }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        if (save() != null) {
                            onSaved()
                            onDismiss()
                        }
                    }
                ) { Text(stringResource(R.string.endpoint_save), fontSize = 13.sp) }
            }
        },
        dismissButton = {
            Row {
                if (initial.isComplete) {
                    TextButton(enabled = !busy, onClick = onForget) {
                        Text(stringResource(R.string.endpoint_forget), fontSize = 13.sp)
                    }
                }
                TextButton(enabled = !busy, onClick = onDismiss) {
                    Text(stringResource(R.string.endpoint_cancel), fontSize = 13.sp)
                }
            }
        }
    )
}
