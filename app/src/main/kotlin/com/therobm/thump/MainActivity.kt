package com.therobm.thump

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
                Text(
                    text = "Thump",
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(16.dp)
                )
            }
        }
    }
}
