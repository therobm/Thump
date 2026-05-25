package com.therobm.thump

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
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThumpApp()
        }
    }
}

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
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var useTokenAuth by remember { mutableStateOf(true) }
    var resultText by remember { mutableStateOf("(no call yet)") }
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
                val credentials = SubsonicCredentials(
                    serverUrl = serverUrl.trim(),
                    username = username.trim(),
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
                    resultText = formatPingResult(pingResult)
                    isPinging = false
                }
            },
        ) {
            Text(text = "Ping")
        }

        Text(text = resultText)
    }
}

private fun buildPingTestHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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
                append("OK\n")
                append("protocol: ").append(value.protocolVersion).append('\n')
                append("server type: ").append(serverTypeText).append('\n')
                append("server version: ").append(serverVersionText).append('\n')
                append("OpenSubsonic: ").append(value.isOpenSubsonicServer)
            }
        }
        is SubsonicResult.ServerError -> {
            return "Server error " + result.code + ": " + result.message
        }
        is SubsonicResult.TransportError -> {
            return "Transport error: " + result.cause.javaClass.simpleName + " - " + result.cause.message
        }
        is SubsonicResult.MalformedResponse -> {
            return "Malformed response: " + result.cause.javaClass.simpleName + " - " + result.cause.message
        }
    }
}
