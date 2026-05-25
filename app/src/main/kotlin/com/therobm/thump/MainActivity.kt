package com.therobm.thump

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThumpApp()
        }
    }
}

private const val PREFS_NAME = "thump_settings"
private const val PREFS_KEY_SERVER_URL = "server_url"
private const val PREFS_KEY_USERNAME = "username"
// TODO: encrypt the stored password (Android Keystore / EncryptedSharedPreferences) when the real
// Settings screen lands. Plain SharedPreferences is fine for the test harness on a dev device.
private const val PREFS_KEY_PASSWORD = "password"
private const val PREFS_KEY_USE_TOKEN_AUTH = "use_token_auth"
private const val PREFS_KEY_PULSE_DETECTED_FOR_URL = "pulse_detected_for_url"
private const val PREFS_KEY_PULSE_DETECTED_VALUE = "pulse_detected_value"

@Composable
private fun ThumpApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold { innerPadding ->
                PingTestScreen(
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun PingTestScreen(modifier: Modifier) {
    val context = LocalContext.current
    val sharedPreferences: SharedPreferences = remember(context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var serverUrl by remember { mutableStateOf(readStringOrBlank(sharedPreferences, PREFS_KEY_SERVER_URL)) }
    var username by remember { mutableStateOf(readStringOrBlank(sharedPreferences, PREFS_KEY_USERNAME)) }
    var password by remember { mutableStateOf(readStringOrBlank(sharedPreferences, PREFS_KEY_PASSWORD)) }
    var useTokenAuth by remember {
        mutableStateOf(sharedPreferences.getBoolean(PREFS_KEY_USE_TOKEN_AUTH, true))
    }

    val initialCachedPulseLine: String
    val cachedPulseUrl = sharedPreferences.getString(PREFS_KEY_PULSE_DETECTED_FOR_URL, null)
    if (cachedPulseUrl != null && cachedPulseUrl == serverUrl.trim()) {
        val cachedPulseValue = sharedPreferences.getBoolean(PREFS_KEY_PULSE_DETECTED_VALUE, false)
        initialCachedPulseLine = "Cached Pulse detection for this URL: " + cachedPulseValue
    } else {
        initialCachedPulseLine = "No cached Pulse detection for this URL yet"
    }
    var resultText by remember { mutableStateOf(initialCachedPulseLine) }
    var isPinging by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Thump - ping test", style = MaterialTheme.typography.titleLarge)

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

                val editor = sharedPreferences.edit()
                editor.putString(PREFS_KEY_SERVER_URL, trimmedServerUrl)
                editor.putString(PREFS_KEY_USERNAME, trimmedUsername)
                editor.putString(PREFS_KEY_PASSWORD, password)
                editor.putBoolean(PREFS_KEY_USE_TOKEN_AUTH, useTokenAuth)
                editor.apply()

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
                    okHttpClient = buildPingTestHttpClient(),
                    jsonDecoder = buildPingTestJsonDecoder(),
                    credentials = credentials,
                    authMode = authMode,
                )
                coroutineScope.launch {
                    val pingResult = client.ping()
                    val pingDisplay = formatPingResult(pingResult)

                    if (pingResult is SubsonicResult.Ok) {
                        val probeResult = client.probePulseExtensions()
                        val probeDisplay = formatProbeResult(probeResult)

                        if (probeResult is SubsonicResult.Ok) {
                            val probeEditor = sharedPreferences.edit()
                            probeEditor.putString(PREFS_KEY_PULSE_DETECTED_FOR_URL, trimmedServerUrl)
                            probeEditor.putBoolean(PREFS_KEY_PULSE_DETECTED_VALUE, probeResult.value)
                            probeEditor.apply()
                        }

                        resultText = pingDisplay + "\n\n" + probeDisplay
                    } else {
                        resultText = pingDisplay
                    }

                    isPinging = false
                }
            },
        ) {
            Text(text = "Ping")
        }

        Text(text = resultText)
    }
}

private fun readStringOrBlank(sharedPreferences: SharedPreferences, key: String): String {
    val storedValue: String? = sharedPreferences.getString(key, null)
    if (storedValue == null) {
        return ""
    }
    return storedValue
}

private fun buildPingTestHttpClient(): OkHttpClient {
    val loggingInterceptor = HttpLoggingInterceptor()
    loggingInterceptor.level = HttpLoggingInterceptor.Level.BODY
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()
}

private fun buildPingTestJsonDecoder(): Json {
    return Json {
        ignoreUnknownKeys = true
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
