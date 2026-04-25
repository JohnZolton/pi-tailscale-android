package com.pinostr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import com.pinostr.app.ui.MainScreen
import com.pinostr.app.viewmodel.ChatViewModel

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7B68EE),
    secondary = Color(0xFF5A4BD1),
    surface = Color(0xFF1A1A2E),
    background = Color(0xFF1A1A2E),
    onPrimary = Color.White,
    onSurface = Color(0xFFE0E0F0),
    onBackground = Color(0xFFE0E0F0),
)

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Obtain ViewModel early so lifecycle callbacks can use it
        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]

        setContent {
            MaterialTheme(colorScheme = DarkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PiNostrApp(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // When the user returns to the app, Android may have silently killed
        // the WebSocket socket (Tailscale or not). Force a reconnect so the
        // connection is fresh and streaming works again.
        if (::viewModel.isInitialized) {
            viewModel.onAppForegrounded()
        }
    }
}

@Composable
fun PiNostrApp(viewModel: ChatViewModel) {
    MainScreen(viewModel = viewModel)
}
