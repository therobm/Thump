package com.therobm.thump.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.therobm.thump.ThumpColors
import com.therobm.thump.data.InvalidationSpec
import com.therobm.thump.data.ThumpData
import com.therobm.thump.subsonic.SubsonicAuthMode
import com.therobm.thump.subsonic.SubsonicClient
import com.therobm.thump.subsonic.SubsonicCredentials
import com.therobm.thump.subsonic.SubsonicPingResult
import com.therobm.thump.subsonic.SubsonicResult
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * The real Settings tab. Sections, top to bottom:
 *
 * - **Server**: URL / username / password / auth mode + Connect button. Connect runs the same
 *   ping + Pulse probe the old test screen ran; on success it calls back into the host so
 *   credentials get persisted and the rest of the app rebuilds against the new server.
 * - **Playback**: scrobble toggle, prefetch-lookahead slider, volume-normalize chip row.
 * - **Cache**: cache-size slider, "Clear cache" action.
 *
 * Server credentials still live in the host's SharedPreferences (managed by MainActivity)
 * because the playback service reads them from the same store. Everything else (prefetch
 * limit, scrobble toggle, cache size, normalize mode) lives in the new ThumpSettings store.
 */
@Composable
fun SettingsScreen(
    initialServerUrl: String,
    initialUsername: String,
    initialPassword: String,
    initialUseTokenAuth: Boolean,
    httpClient: OkHttpClient,
    jsonDecoder: Json,
    thumpData: ThumpData,
    contentPadding: PaddingValues,
    onCredentialsUpdated: (serverUrl: String, username: String, password: String, useTokenAuth: Boolean, isPulseServer: Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val settings: ThumpSettings = remember(context) { ThumpSettings(context.applicationContext) }

    var serverUrl: String by remember { mutableStateOf(initialServerUrl) }
    var username: String by remember { mutableStateOf(initialUsername) }
    var password: String by remember { mutableStateOf(initialPassword) }
    var useTokenAuth: Boolean by remember { mutableStateOf(initialUseTokenAuth) }
    var connectResultText: String by remember { mutableStateOf("") }
    var isConnecting: Boolean by remember { mutableStateOf(false) }

    var prefetchLookahead: Int by remember { mutableStateOf(settings.getPrefetchLookahead()) }
    var audioCacheSizeBytes: Long by remember { mutableStateOf(settings.getAudioCacheSizeBytes()) }
    var scrobbleEnabled: Boolean by remember { mutableStateOf(settings.getScrobbleEnabled()) }
    var normalizeMode: String by remember { mutableStateOf(settings.getNormalizeMode()) }
    var clearCacheNoticeText: String by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(initialServerUrl, initialUsername, initialPassword, initialUseTokenAuth) {
        serverUrl = initialServerUrl
        username = initialUsername
        password = initialPassword
        useTokenAuth = initialUseTokenAuth
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(title = "Server")

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { newValue: String -> serverUrl = newValue },
            label = { Text(text = "Server URL (e.g. https://music.example.com:45678)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { newValue: String -> username = newValue },
            label = { Text(text = "Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { newValue: String -> password = newValue },
            label = { Text(text = "Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        FilterChip(
            selected = useTokenAuth,
            onClick = { useTokenAuth = !useTokenAuth },
            label = {
                if (useTokenAuth) {
                    Text(text = "Auth: token (recommended)")
                } else {
                    Text(text = "Auth: legacy password")
                }
            },
        )

        Button(
            enabled = !isConnecting && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            onClick = {
                isConnecting = true
                connectResultText = "Connecting..."
                val trimmedServerUrl: String = serverUrl.trim()
                val trimmedUsername: String = username.trim()
                val credentials: SubsonicCredentials = SubsonicCredentials(
                    serverUrl = trimmedServerUrl,
                    username = trimmedUsername,
                    password = password,
                )
                val authMode: SubsonicAuthMode
                if (useTokenAuth) {
                    authMode = SubsonicAuthMode.Token
                } else {
                    authMode = SubsonicAuthMode.Legacy
                }
                val client: SubsonicClient = SubsonicClient(
                    okHttpClient = httpClient,
                    jsonDecoder = jsonDecoder,
                    credentials = credentials,
                    authMode = authMode,
                )

                coroutineScope.launch {
                    val pingResult: SubsonicResult<SubsonicPingResult> = client.ping()
                    val pingDisplay: String = formatPingResult(pingResult)
                    if (pingResult is SubsonicResult.Ok) {
                        val probeResult: SubsonicResult<Boolean> = client.probePulseExtensions()
                        val probeDisplay: String = formatProbeResult(probeResult)
                        connectResultText = pingDisplay + "\n" + probeDisplay
                        val detectedIsPulse: Boolean
                        if (probeResult is SubsonicResult.Ok) {
                            detectedIsPulse = probeResult.value
                        } else {
                            detectedIsPulse = false
                        }
                        onCredentialsUpdated(
                            trimmedServerUrl,
                            trimmedUsername,
                            password,
                            useTokenAuth,
                            detectedIsPulse,
                        )
                    } else {
                        connectResultText = pingDisplay
                    }
                    isConnecting = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = ThumpColors.Accent),
        ) {
            Text(text = "Connect")
        }
        if (connectResultText.isNotEmpty()) {
            Text(
                text = connectResultText,
                style = MaterialTheme.typography.bodySmall,
                color = ThumpColors.TextSecondary,
            )
        }

        SectionDivider()
        SectionHeader(title = "Playback")

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Scrobble plays to the server",
                color = ThumpColors.OnBackground,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = scrobbleEnabled,
                onCheckedChange = { newValue: Boolean ->
                    scrobbleEnabled = newValue
                    settings.setScrobbleEnabled(newValue)
                },
            )
        }

        Text(
            text = "Prefetch upcoming tracks: " + prefetchLookahead,
            color = ThumpColors.OnBackground,
        )
        Slider(
            value = prefetchLookahead.toFloat(),
            onValueChange = { newValue: Float -> prefetchLookahead = newValue.toInt() },
            onValueChangeFinished = {
                settings.setPrefetchLookahead(prefetchLookahead)
            },
            valueRange = ThumpSettings.MIN_PREFETCH_LOOKAHEAD.toFloat()..ThumpSettings.MAX_PREFETCH_LOOKAHEAD.toFloat(),
            steps = ThumpSettings.MAX_PREFETCH_LOOKAHEAD - ThumpSettings.MIN_PREFETCH_LOOKAHEAD - 1,
        )
        Text(
            text = "Set to 0 to disable prefetch. Spec default is 10.",
            style = MaterialTheme.typography.bodySmall,
            color = ThumpColors.TextSecondary,
        )

        Text(
            text = "Normalize volume",
            color = ThumpColors.OnBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NormalizeChip(
                label = "Off",
                value = ThumpSettings.NORMALIZE_MODE_OFF,
                selected = normalizeMode,
                onSelected = { newValue: String ->
                    normalizeMode = newValue
                    settings.setNormalizeMode(newValue)
                },
            )
            NormalizeChip(
                label = "Per track",
                value = ThumpSettings.NORMALIZE_MODE_TRACK,
                selected = normalizeMode,
                onSelected = { newValue: String ->
                    normalizeMode = newValue
                    settings.setNormalizeMode(newValue)
                },
            )
            NormalizeChip(
                label = "Per album",
                value = ThumpSettings.NORMALIZE_MODE_ALBUM,
                selected = normalizeMode,
                onSelected = { newValue: String ->
                    normalizeMode = newValue
                    settings.setNormalizeMode(newValue)
                },
            )
        }
        Text(
            text = "ReplayGain integration is not wired up yet; the picker only stores your preference for now.",
            style = MaterialTheme.typography.bodySmall,
            color = ThumpColors.TextSecondary,
        )

        SectionDivider()
        SectionHeader(title = "Cache")

        Text(
            text = "Audio cache size: " + formatBytes(audioCacheSizeBytes),
            color = ThumpColors.OnBackground,
        )
        Slider(
            value = audioCacheSizeBytes.toFloat(),
            onValueChange = { newValue: Float -> audioCacheSizeBytes = newValue.toLong() },
            onValueChangeFinished = {
                settings.setAudioCacheSizeBytes(audioCacheSizeBytes)
            },
            valueRange = ThumpSettings.MIN_AUDIO_CACHE_SIZE_BYTES.toFloat()..ThumpSettings.MAX_AUDIO_CACHE_SIZE_BYTES.toFloat(),
        )
        Text(
            text = "Range 100 MB – 5 GB. Restart the app for size changes to take effect.",
            style = MaterialTheme.typography.bodySmall,
            color = ThumpColors.TextSecondary,
        )

        Button(
            onClick = {
                coroutineScope.launch {
                    thumpData.invalidate(InvalidationSpec.EverythingBlobs)
                    clearCacheNoticeText = "Cache cleared."
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = ThumpColors.Surface),
        ) {
            Text(text = "Clear cache", color = ThumpColors.OnBackground)
        }
        if (clearCacheNoticeText.isNotEmpty()) {
            Text(
                text = clearCacheNoticeText,
                style = MaterialTheme.typography.bodySmall,
                color = ThumpColors.TextSecondary,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = ThumpColors.OnBackground,
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = ThumpColors.Divider,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun NormalizeChip(
    label: String,
    value: String,
    selected: String,
    onSelected: (String) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelected(value) },
        label = { Text(text = label) },
    )
}

private fun formatBytes(bytes: Long): String {
    val mb: Long = bytes / (1024L * 1024L)
    if (mb >= 1024L) {
        val gb: Double = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val tenths: Long = (gb * 10.0).toLong()
        val whole: Long = tenths / 10L
        val fraction: Long = tenths % 10L
        return whole.toString() + "." + fraction.toString() + " GB"
    }
    return mb.toString() + " MB"
}

private fun formatPingResult(result: SubsonicResult<SubsonicPingResult>): String {
    when (result) {
        is SubsonicResult.Ok -> {
            val value: SubsonicPingResult = result.value
            val serverTypeText: String
            if (value.serverType == null) {
                serverTypeText = "(unknown)"
            } else {
                serverTypeText = value.serverType
            }
            val serverVersionText: String
            if (value.serverVersion == null) {
                serverVersionText = "(unknown)"
            } else {
                serverVersionText = value.serverVersion
            }
            return buildString {
                append("Ping OK — ")
                append(serverTypeText)
                append(' ')
                append(serverVersionText)
                append(", protocol ")
                append(value.protocolVersion)
            }
        }
        is SubsonicResult.ServerError -> {
            return "Server error " + result.code + ": " + result.message
        }
        is SubsonicResult.TransportError -> {
            return "Network error: " + result.cause.javaClass.simpleName
        }
        is SubsonicResult.MalformedResponse -> {
            return "Bad response: " + result.cause.javaClass.simpleName
        }
    }
}

private fun formatProbeResult(result: SubsonicResult<Boolean>): String {
    when (result) {
        is SubsonicResult.Ok -> {
            if (result.value) {
                return "Pulse extensions detected."
            } else {
                return "Standard OpenSubsonic server."
            }
        }
        is SubsonicResult.ServerError -> {
            return "Pulse probe server error " + result.code
        }
        is SubsonicResult.TransportError -> {
            return "Pulse probe network error"
        }
        is SubsonicResult.MalformedResponse -> {
            return "Pulse probe response unreadable"
        }
    }
}
