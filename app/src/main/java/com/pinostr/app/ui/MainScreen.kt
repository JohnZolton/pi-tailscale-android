package com.pinostr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.pinostr.app.model.ChatMessage
import com.pinostr.app.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val botLabel by viewModel.botLabel.collectAsState()

    var showSettings by remember { mutableStateOf(viewModel.getBridgeUrl().isBlank()) }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    if (showSettings) {
        // ── Bridge URL config screen ──
        SettingsScreen(
            currentUrl = viewModel.getBridgeUrl(),
            isConnected = isConnected,
            onSave = { url ->
                viewModel.setBridgeUrl(url)
                showSettings = false
            },
        )
    } else {
        // ── Chat screen ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A2E))
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // Top bar
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) Color(0xFF66BB6A) else Color(0xFFEF5350)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = botLabel.ifEmpty { "pi" },
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE0E0F0),
                        )
                    }
                },
                actions = {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFF7B68EE),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings", tint = Color(0xFF8888AA))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF12121E)),
            )

            // Messages
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Forum, null, tint = Color(0xFF333355), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Connected to pi", color = Color(0xFF555577), fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Type a message below", color = Color(0xFF444466), fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatBubble(message = msg)
                    }
                }
            }

            // Input bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF12121E),
                shadowElevation = 8.dp,
            ) {
                val sendMsg = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText.trim())
                        inputText = ""
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Message pi...", color = Color(0xFF555577), fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = { sendMsg() },
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Send,
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE0E0F0),
                            unfocusedTextColor = Color(0xFFE0E0F0),
                            focusedContainerColor = Color(0xFF252542),
                            unfocusedContainerColor = Color(0xFF252542),
                            focusedBorderColor = Color(0xFF7B68EE),
                            unfocusedBorderColor = Color(0xFF333355),
                            cursorColor = Color(0xFF7B68EE),
                        ),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { sendMsg() },
                        enabled = inputText.isNotBlank() && !isProcessing,
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(
                            if (inputText.isNotBlank() && !isProcessing) Color(0xFF7B68EE) else Color(0xFF333355)
                        ),
                    ) {
                        Icon(Icons.Default.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// ── Settings screen ─────────────────────────────────────────────────

@Composable
private fun SettingsScreen(
    currentUrl: String,
    isConnected: Boolean,
    onSave: (String) -> Unit,
) {
    var url by remember { mutableStateOf(currentUrl) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A2E)).padding(24.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Text("pi-nostr", fontWeight = FontWeight.Bold, color = Color(0xFFE0E0F0), fontSize = 28.sp)
        Spacer(Modifier.height(8.dp))
        Text("Bridge WebSocket URL", color = Color(0xFF8888AA), fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = { Text("ws://100.x.x.x:3002", color = Color(0xFF555577)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFE0E0F0),
                unfocusedTextColor = Color(0xFFE0E0F0),
                focusedContainerColor = Color(0xFF252542),
                unfocusedContainerColor = Color(0xFF252542),
                focusedBorderColor = Color(0xFF7B68EE),
                unfocusedBorderColor = Color(0xFF333355),
                cursorColor = Color(0xFF7B68EE),
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (url.isNotBlank()) onSave(url.trim()) },
            enabled = url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B68EE)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Connect", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(8.dp).clip(CircleShape).background(
                    if (isConnected) Color(0xFF66BB6A) else Color(0xFF8888AA)
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isConnected) "Connected" else "Disconnected",
                color = Color(0xFF8888AA),
                fontSize = 13.sp,
            )
        }
        if (isConnected) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "✅ Ready! Type a message to start.",
                color = Color(0xFF66BB6A),
                fontSize = 13.sp,
            )
        }
    }
}
