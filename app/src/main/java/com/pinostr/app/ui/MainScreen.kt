package com.pinostr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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

    val threads by viewModel.threads.collectAsState()
    val activeThreadId by viewModel.activeThreadId.collectAsState()
    var showSettings by remember { mutableStateOf(viewModel.getBridgeUrl().isBlank()) }
    var showDirPicker by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    // Auto-show directory picker on first launch (no threads yet, connected).
    val needsProject = threads.isEmpty() && isConnected && viewModel.getBridgeUrl().isNotBlank()
    if (needsProject && !showDirPicker) {
        showDirPicker = true
    }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll: immediate jump (no animation) to avoid layout thrash during streaming.
    // Animate only when a genuinely new message arrives (not streaming updates).
    // If user has scrolled up, don't fight them — let them read.
    var lastMessageCount by remember { mutableIntStateOf(0) }
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.let { last ->
                last.index + last.offset <= 0 && // scrolled to very end
                last.index >= info.totalItemsCount - 1
            } ?: true
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val newMsg = messages.size > lastMessageCount
        val prevCount = lastMessageCount
        lastMessageCount = messages.size
        if (!isAtBottom && !newMsg) return@LaunchedEffect // user scrolled away during streaming
        if (newMsg && prevCount > 0) {
            listState.animateScrollToItem(messages.size - 1)
        } else {
            listState.scrollToItem(messages.size - 1)
        }
    }

    // ── Chat screen (always rendered, settings/dir picker are overlays) ──
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
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, "History", tint = Color(0xFF7B68EE))
                    }
                    IconButton(onClick = { showDirPicker = true }) {
                        Icon(Icons.Default.Add, "New Agent", tint = Color(0xFF7B68EE))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings", tint = Color(0xFF8888AA))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF12121E)),
            )

            // Thread tabs — compact horizontal scroll bar, shows only non-closed threads
            val openThreads = threads.filter { !it.closed }
            if (openThreads.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A2E))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    openThreads.forEach { t ->
                        val isActive = t.id == activeThreadId
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isActive) Color(0xFF7B68EE) else Color(0xFF252542))
                                .clickable { viewModel.switchThread(t.id) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = t.name,
                                    color = if (isActive) Color.White else Color(0xFF8888AA),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                                if (t.isProcessing) {
                                    Spacer(Modifier.width(3.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(8.dp),
                                        color = if (isActive) Color.White else Color(0xFF7B68EE),
                                        strokeWidth = 1.dp,
                                    )
                                }
                                // Close button always present for multi-thread cleanup
                                IconButton(
                                    onClick = { viewModel.closeThread(t.id) },
                                    modifier = Modifier.size(16.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        "Close thread",
                                        tint = if (isActive) Color.White.copy(alpha = 0.5f) else Color(0xFF555577),
                                        modifier = Modifier.size(10.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }

            // Messages (or project selection prompt)
            if (messages.isEmpty() && threads.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, null, tint = Color(0xFF333355), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Select a project", color = Color(0xFF8888AA), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("Pick a folder so pi knows where to work", color = Color(0xFF555577), fontSize = 13.sp)
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { showDirPicker = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7B68EE),
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Browse folders", fontSize = 14.sp)
                        }
                    }
                }
            } else if (messages.isEmpty()) {
                // Thread exists but no messages yet
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Start chatting with pi", color = Color(0xFF555577), fontSize = 14.sp)
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
                        enabled = inputText.isNotBlank(),
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(
                            if (inputText.isNotBlank()) Color(0xFF7B68EE) else Color(0xFF333355)
                        ),
                    ) {
                        Icon(Icons.Default.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
            }
        }
    }

    // ── Directory picker ──
    if (showDirPicker) {
        DirectoryPicker(
            viewModel = viewModel,
            onSelect = { path ->
                viewModel.addThread(path)
                showDirPicker = false
            },
            onDismiss = { showDirPicker = false },
        )
    }

    // ── Settings dialog ──
    if (showSettings) {
        SettingsDialog(
            currentUrl = viewModel.getBridgeUrl(),
            isConnected = isConnected,
            activeThreadDir = threads.find { it.id == activeThreadId }?.dir ?: "",
            onSave = { url ->
                viewModel.setBridgeUrl(url)
                showSettings = false
            },
            onSavePairing = { json ->
                viewModel.setPairingData(json)
            },
            onDismiss = { showSettings = false },
        )
    }

    // ── History panel ──
    if (showHistory) {
        HistoryPanel(
            threads = threads,
            onResume = { thread ->
                if (thread.closed) {
                    viewModel.resumeThread(thread.id)
                } else {
                    viewModel.switchThread(thread.id)
                }
                showHistory = false
            },
            onClose = { thread ->
                viewModel.closeThread(thread.id)
            },
            onDismiss = { showHistory = false },
        )
    }
}

// ── Settings dialog ─────────────────────────────────────────────────

@Composable
private fun SettingsDialog(
    currentUrl: String,
    isConnected: Boolean,
    activeThreadDir: String,
    onSave: (String) -> Unit,
    onSavePairing: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(currentUrl) }
    var pairingJson by remember { mutableStateOf("") }
    var showPairingField by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF252542),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Color(0xFFE0E0F0), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("✕", color = Color(0xFF666688), fontSize = 16.sp) }
            }
        },
        text = {
            Column {
                // Bridge URL
                Text("WebSocket URL", color = Color(0xFF8888AA), fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("ws://100.x.x.x:3002", color = Color(0xFF555577)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE0E0F0),
                        unfocusedTextColor = Color(0xFFE0E0F0),
                        focusedContainerColor = Color(0xFF1A1A2E),
                        unfocusedContainerColor = Color(0xFF1A1A2E),
                        focusedBorderColor = Color(0xFF7B68EE),
                        unfocusedBorderColor = Color(0xFF333355),
                        cursorColor = Color(0xFF7B68EE),
                    ),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(12.dp))

                // Active thread directory
                if (activeThreadDir.isNotBlank()) {
                    Text("Project", color = Color(0xFF8888AA), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(activeThreadDir, color = Color(0xFFB0B0D0), fontSize = 13.sp, maxLines = 2)
                    Spacer(Modifier.height(12.dp))
                }

                // Status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (isConnected) Color(0xFF66BB6A) else Color(0xFF8888AA)))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isConnected) "Connected" else "Disconnected", color = Color(0xFF8888AA), fontSize = 13.sp)
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF333355), thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))

                // Nostr Pairing section
                TextButton(onClick = { showPairingField = !showPairingField }) {
                    Icon(
                        Icons.Default.VpnKey,
                        null,
                        tint = Color(0xFF7B68EE),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (showPairingField) "Hide Nostr pairing" else "Nostr pairing (P2P)",
                        color = Color(0xFF7B68EE),
                        fontSize = 12.sp,
                    )
                }

                if (showPairingField) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Paste the pairing JSON from http://bridge-ip:3003/pairing",
                        color = Color(0xFF666688),
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = pairingJson,
                        onValueChange = { pairingJson = it },
                        placeholder = { Text("{\"pubkey\":\"...\", \"relays\":[...]}", color = Color(0xFF555577), fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                        minLines = 3,
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE0E0F0),
                            unfocusedTextColor = Color(0xFFE0E0F0),
                            focusedContainerColor = Color(0xFF1A1A2E),
                            unfocusedContainerColor = Color(0xFF1A1A2E),
                            focusedBorderColor = Color(0xFF7B68EE),
                            unfocusedBorderColor = Color(0xFF333355),
                            cursorColor = Color(0xFF7B68EE),
                        ),
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            if (pairingJson.isNotBlank()) {
                                onSavePairing(pairingJson.trim())
                                pairingJson = ""
                                showPairingField = false
                            }
                        },
                        enabled = pairingJson.isNotBlank(),
                    ) {
                        Icon(
                            Icons.Default.Save,
                            null,
                            tint = Color(0xFF7B68EE),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Save Nostr pairing", color = Color(0xFF7B68EE), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onSave(url.trim()) },
                enabled = url.isNotBlank(),
            ) { Text("Save", color = Color(0xFF7B68EE)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF666688)) }
        },
    )
}

// ── Directory picker ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryPicker(
    viewModel: ChatViewModel,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val dirPath by viewModel.dirPath.collectAsState()
    val dirEntries by viewModel.dirEntries.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val savedDir = remember {
        ctx.getSharedPreferences("pi_nostr", android.content.Context.MODE_PRIVATE)
            .getString("last_dir", "/") ?: "/"
    }
    var currentPath by remember { mutableStateOf(savedDir) }
    var inputText by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    // Request initial listing
    LaunchedEffect(Unit) { viewModel.listDir(currentPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF252542),
        title = { Text("Select Project", color = Color(0xFFE0E0F0), fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                // Text input with autocomplete
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { text ->
                        inputText = text
                        // Auto-list directory as user types a path
                        if (text.endsWith("/") || text.endsWith("..")) {
                            val resolved = if (text.startsWith("/")) text else
                                "$currentPath/${text}".replace("//", "/")
                            currentPath = pathDir(resolved)
                            viewModel.listDir(currentPath)
                            inputText = pathLast(resolved)
                        }
                    },
                    placeholder = { Text("Type or browse to a path...", color = Color(0xFF555577), fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE0E0F0),
                        unfocusedTextColor = Color(0xFFE0E0F0),
                        focusedContainerColor = Color(0xFF1A1A2E),
                        unfocusedContainerColor = Color(0xFF1A1A2E),
                        focusedBorderColor = Color(0xFF7B68EE),
                        unfocusedBorderColor = Color(0xFF333355),
                        cursorColor = Color(0xFF7B68EE),
                    ),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(4.dp))

                // Current path
                Text(
                    text = currentPath,
                    color = Color(0xFF8888AA),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))

                if (dirEntries.isEmpty() && dirPath.isNotBlank()) {
                    val filteredEmpty = inputText.isNotBlank() && dirEntries.none { it.name.contains(inputText, ignoreCase = true) }
                    Text(
                        if (filteredEmpty) "No matches for \"$inputText\"" else "No subdirectories",
                        color = Color(0xFF555577),
                        fontSize = 13.sp,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        // Up button
                        if (currentPath != "/") {
                            item {
                                TextButton(onClick = {
                                    val parent = currentPath.substringBeforeLast("/").ifEmpty { "/" }
                                    currentPath = parent.ifEmpty { "/" }
                                    inputText = ""
                                    viewModel.listDir(currentPath)
                                }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ArrowUpward, null, tint = Color(0xFF7B68EE), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("..", color = Color(0xFF7B68EE), fontSize = 13.sp)
                                    }
                                }
                            }
                        }

                        val filtered = if (inputText.isBlank()) dirEntries else
                            dirEntries.filter { it.name.contains(inputText, ignoreCase = true) }

                        items(filtered, key = { it.path }) { entry ->
                            TextButton(
                                onClick = {
                                    currentPath = entry.path
                                    inputText = ""
                                    viewModel.listDir(entry.path)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Color(0xFF8888AA), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = entry.name,
                                    color = Color(0xFFE0E0F0),
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(currentPath) }) {
                Text("Select", color = Color(0xFF7B68EE))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF666688))
            }
        },
    )
}

private fun pathDir(p: String): String {
    val i = p.lastIndexOf('/')
    return if (i <= 0) "/" else p.substring(0, i)
}

private fun pathLast(p: String): String {
    val i = p.lastIndexOf('/')
    return if (i < 0) p else p.substring(i + 1)
}
