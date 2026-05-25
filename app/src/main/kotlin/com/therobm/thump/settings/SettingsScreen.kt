package com.therobm.thump.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.therobm.thump.subsonic.SubsonicAuthMode
import com.therobm.thump.subsonic.SubsonicClient
import com.therobm.thump.subsonic.SubsonicCredentials
import com.therobm.thump.subsonic.SubsonicPingResult
import com.therobm.thump.subsonic.SubsonicResult
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * The Settings screen.
 *
 * In its current form this is the same ping/probe form that lived in MainActivity during the
 * subsonic-client and pulse-detect-and-persist work. It will grow real settings rows (cache
 * size, scrobble toggle, normalize volume, clear cache) once those features land.
 *
 * On a successful ping the screen calls back to the host with the new credentials and the
 * detected Pulse status so the rest of the app can rebuild its SubsonicClient. The host owns
 * persistence — this screen does not touch SharedPreferences itself.
 */
@Composable
fun SettingsScreen(
    initialServerUrl: String,
    initialUsername: String,
    initialPassword: String,
    initialUseTokenAuth: Boolean,
    httpClient: OkHttpClient,
    jsonDecoder: Json,
    contentPadding: PaddingValues,
    onCredentialsUpdated: (serverUrl: String, username: String, password: String, useTokenAuth: Boolean, isPulseServer: Boolean) -> Unit,
    modifier: Modifier,
) {
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }
    var useTokenAuth by remember { mutableStateOf(initialUseTokenAuth) }
    var resultText by remember { mutableStateOf("(no call yet)") }
    var isPinging by remember { mutableStateOf(false) }

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
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Server", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { newValue: String -> serverUrl = newValue },
            label = { Text(text = "Server URL (e.g. https://music.example.com)") },
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
            enabled = !isPinging && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            onClick = {
                isPinging = true
                resultText = "pinging..."

                val trimmedServerUrl = serverUrl.trim()
                val trimmedUsername = username.trim()
                val credentials = SubsonicCredentials(
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
                val client = SubsonicClient(
                    okHttpClient = httpClient,
                    jsonDecoder = jsonDecoder,
                    credentials = credentials,
                    authMode = authMode,
                )

                coroutineScope.launch {
                    val pingResult = client.ping()
                    val pingDisplay = formatPingResult(pingResult)

                    if (pingResult is SubsonicResult.Ok) {
                        val probeResult = client.probePulseExtensions()
                        val probeDisplay = formatProbeResult(probeResult)
                        resultText = pingDisplay + "\n\n" + probeDisplay

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
                        resultText = pingDisplay
                    }

                    isPinging = false
                }
            },
        ) {
            Text(text = "Connect")
        }

        Text(text = resultText)
    }
}

private fun formatPingResult(result: SubsonicResult<SubsonicPingResult>): String {
    when (result) {
        is SubsonicResult.Ok -> {
            val value = result.value
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
                append("Ping OK\n")
                append("protocol: ").append(value.protocolVersion).append('\n')
                append("server type: ").append(serverTypeText).append('\n')
                append("server version: ").append(serverVersionText).append('\n')
                append("OpenSubsonic: ").append(value.isOpenSubsonicServer)
            }
        }
        is SubsonicResult.ServerError -> {
            return "Ping server error " + result.code + ": " + result.message
        }
        is SubsonicResult.TransportError -> {
            return "Ping transport error: " + result.cause.javaClass.simpleName + " - " + result.cause.message
        }
        is SubsonicResult.MalformedResponse -> {
            return "Ping malformed response: " + result.cause.javaClass.simpleName + " - " + result.cause.message
        }
    }
}

private fun formatProbeResult(result: SubsonicResult<Boolean>): String {
    when (result) {
        is SubsonicResult.Ok -> {
            if (result.value) {
                return "Pulse extensions: DETECTED"
            } else {
                return "Pulse extensions: not present (standard OpenSubsonic server)"
            }
        }
        is SubsonicResult.ServerError -> {
            return "Pulse probe server error " + result.code + ": " + result.message
        }
        is SubsonicResult.TransportError -> {
            return "Pulse probe transport error: " + result.cause.javaClass.simpleName + " - " + result.cause.message
        }
        is SubsonicResult.MalformedResponse -> {
            return "Pulse probe malformed response: " + result.cause.javaClass.simpleName + " - " + result.cause.message
        }
    }
}
