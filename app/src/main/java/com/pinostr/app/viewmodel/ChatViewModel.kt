package com.pinostr.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.pinostr.app.model.ChatMessage
import com.pinostr.app.model.PersistenceManager
import com.pinostr.app.nostr.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // ── Persistence ──
    private val persistence = PersistenceManager(application)

    // ── Threads ──
    data class ThreadData(
        val id: String,
        val name: String,
        val dir: String,
        val messages: List<ChatMessage> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val lastActiveAt: Long = System.currentTimeMillis(),
        val closed: Boolean = false,
        // Runtime-only (not meaningful across restarts):
        val isProcessing: Boolean = false,
        val unread: Boolean = false,
    )
    private val _threads = MutableStateFlow<List<ThreadData>>(emptyList())
    val threads: StateFlow<List<ThreadData>> = _threads.asStateFlow()
    private val _activeThreadId = MutableStateFlow("")
    val activeThreadId: StateFlow<String> = _activeThreadId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _botLabel = MutableStateFlow("")
    val botLabel: StateFlow<String> = _botLabel.asStateFlow()

    // Directory browser state
    data class DirEntry(val name: String, val path: String)
    private val _dirPath = MutableStateFlow("")
    val dirPath: StateFlow<String> = _dirPath.asStateFlow()
    private val _dirEntries = MutableStateFlow<List<DirEntry>>(emptyList())
    val dirEntries: StateFlow<List<DirEntry>> = _dirEntries.asStateFlow()
    private val _currentDir = MutableStateFlow("")
    val currentDir: StateFlow<String> = _currentDir.asStateFlow()

    private var currentAssistantMsg: ChatMessage? = null
    private var currentThinkingMsg: ChatMessage? = null
    private var currentToolCallMsgs = mutableMapOf<String, ChatMessage>() // toolCallId → msg

    private var bridgeUrl: String = ""
    private var pairingData: String = ""
    private val client = DirectClient()
    private var connectionJob: kotlinx.coroutines.Job? = null

    private val gson = Gson()
    private var nostrSignaler: NostrSignaler? = null
    private var webrtcTransport: WebRtcTransport? = null

    init {
        // Load saved threads from disk
        val saved = persistence.loadThreads()
        if (saved.isNotEmpty()) {
            _threads.value = saved.map { it.copy(isProcessing = false, unread = false) }
            // Restore most recent non-closed thread, or the most recent overall
            val resume = saved.firstOrNull { !it.closed } ?: saved.first()
            switchThread(resume.id)
        }

        // Load saved bridge URL + pairing data from prefs
        val prefs = application.getSharedPreferences("pi_nostr", android.content.Context.MODE_PRIVATE)
        bridgeUrl = prefs.getString("bridge_url", "") ?: ""
        pairingData = prefs.getString("pairing_data", "") ?: ""
        if (bridgeUrl.isNotBlank()) {
            connect(preserveMessages = saved.isNotEmpty())
        } else if (pairingData.isNotBlank()) {
            initNostrPairing(pairingData)
        }
    }

    fun setBridgeUrl(url: String) {
        bridgeUrl = url
        getApplication<android.app.Application>()
            .getSharedPreferences("pi_nostr", android.content.Context.MODE_PRIVATE)
            .edit().putString("bridge_url", url).apply()
        if (url.isNotBlank()) connect()
    }

    fun getBridgeUrl(): String = bridgeUrl

    /** Clear the WebSocket bridge URL — disables Tailscale path. */
    fun clearBridgeUrl() {
        bridgeUrl = ""
        getApplication<android.app.Application>()
            .getSharedPreferences("pi_nostr", android.content.Context.MODE_PRIVATE)
            .edit().remove("bridge_url").apply()
        client.disconnect()
        addStatusMessage("WebSocket disconnected. Use Nostr pairing for P2P.")
    }

    /** Save Nostr pairing data and initialize the P2P flow. */
    fun setPairingData(json: String) {
        pairingData = json
        val prefs = getApplication<android.app.Application>()
            .getSharedPreferences("pi_nostr", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("pairing_data", json).apply()
        initNostrPairing(json)
    }

    private fun initNostrPairing(json: String) {
        try {
            val pairing = gson.fromJson(json, BridgePairing::class.java)
            if (pairing.pubkey.isBlank() || pairing.relays.isEmpty()) {
                addStatusMessage("Invalid pairing data: missing pubkey or relays")
                return
            }

            val ctx = getApplication<android.app.Application>()

            // 1. Create app identity (load existing or generate new)
            val identity = NostrIdentity.loadOrCreate(ctx)
            println("[pairing] App pubkey: ${identity.pubkey.take(12)}...")

            // 2. Create Nostr signaller
            val signaler = NostrSignaler()
            this.nostrSignaler = signaler

            // Wire incoming message handlers
            signaler.onPairingRequest = { msg, fromPubkey ->
                println("[pairing] Bridge acknowledged pairing!")
                addStatusMessage("✅ Pairing confirmed with bridge!")
            }

            // 3. Start Nostr signaller (connects to relays, subscribes)
            signaler.start(identity, pairing.relays, pairing.pubkey, viewModelScope)
            println("[pairing] Nostr signaller started")

            // 4. Send pairing-request to bridge
            signaler.sendMessage(pairing.pubkey, NostrSignaler.SignalingMessage(
                type = "pairing-request",
                appPubkey = identity.pubkey,
                pairingCode = pairing.pairingCode,
            ))
            addStatusMessage("Pairing sent over Nostr...")

        } catch (e: Exception) {
            println("[pairing] Error: ${e.message}")
            addStatusMessage("Pairing failed: ${e.message}")
        }
    }

    private fun addStatusMessage(text: String) {
        val msg = ChatMessage(
            role = ChatMessage.Role.SYSTEM,
            eventType = ChatMessage.EventType.STATUS,
            text = text,
        )
        addMessage(msg)
    }

    /**
     * Called when the app returns to foreground after being backgrounded
     * or the screen was locked. Android may have silently killed the
     * WebSocket socket, so we force a fresh connection.
     */
    fun onAppForegrounded() {
        if (bridgeUrl.isNotBlank()) {
            // Always reconnect on resume — don't trust stale isConnected state
            connect(preserveMessages = true)
        }
    }

    private fun connect(preserveMessages: Boolean = false) {
        client.disconnect()
        // Cancel any previous listener coroutines (from prior connect calls)
        connectionJob?.cancel()
        // Clear streaming state pointers (any in-progress turn is lost)
        currentAssistantMsg = null
        currentThinkingMsg = null
        currentToolCallMsgs.clear()
        if (!preserveMessages) {
            _messages.value = emptyList()
        }

        connectionJob = viewModelScope.launch {
            // Listen for connection state
            launch {
                for (state in client.connectionState) {
                    _isConnected.value = state == DirectClient.ConnectionState.CONNECTED
                }
            }

            // Listen for stream frames
            launch {
                for (frame in client.frames) {
                    handleFrame(frame)
                }
            }

            // Initiate connection
            client.connect(bridgeUrl, viewModelScope)
        }
    }

    fun listDir(path: String) {
        client.listDir(path)
    }

    private fun handleFrame(frame: DirectClient.StreamFrame) {
        // Route frame to the correct thread
        val frameThread = frame.data["thread"]?.toString() ?: ""
        if (frameThread.isNotBlank() && frameThread != _activeThreadId.value && frameThread != "default") {
            // Background thread — store text deltas and turn_complete
            _threads.value = _threads.value.map {
                if (it.id == frameThread) {
                    var t = it
                    if (frame.type == "text_delta") {
                        val text = frame.data["text"]?.toString() ?: ""
                        val more = (frame.data["more"] as? Boolean) ?: true
                        val msgs = t.messages.toMutableList()
                        val lastAsst = msgs.indexOfLast { m -> m.role == ChatMessage.Role.ASSISTANT && m.eventType == ChatMessage.EventType.TEXT }
                        if (lastAsst >= 0) {
                            msgs[lastAsst] = msgs[lastAsst].copy(text = text, isStreaming = more)
                        } else if (text.isNotBlank()) {
                            msgs.add(ChatMessage(role = ChatMessage.Role.ASSISTANT, eventType = ChatMessage.EventType.TEXT, text = text, isStreaming = more))
                        }
                        t = t.copy(messages = msgs)
                    }
                    if (frame.type == "tool_call") {
                        val callId = frame.data["id"]?.toString() ?: ""
                        val name = frame.data["name"]?.toString() ?: ""
                        val status = frame.data["status"]?.toString() ?: "running"
                        val args = frame.data["args"]?.toString() ?: ""
                        val msgs = t.messages.toMutableList()
                        val existingIdx = msgs.indexOfLast { m -> m.toolCallId == callId }
                        if (existingIdx >= 0) {
                            msgs[existingIdx] = msgs[existingIdx].copy(toolStatus = status, text = args.ifEmpty { msgs[existingIdx].text })
                        } else {
                            msgs.add(ChatMessage(role = ChatMessage.Role.ASSISTANT, eventType = ChatMessage.EventType.TOOL_CALL, toolCallId = callId, toolName = name, toolStatus = status, text = args, isStreaming = true))
                        }
                        t = t.copy(messages = msgs)
                    }
                    if (frame.type == "turn_complete") t = t.copy(isProcessing = false, unread = true)
                    t
                } else it
            }
            return
        }

        when (frame.type) {
            "dir_list" -> {
                val path = frame.data["path"]?.toString() ?: ""
                val error = frame.data["error"]?.toString()
                _dirPath.value = path
                if (error != null) {
                    _dirEntries.value = listOf(DirEntry("❌ $error", ""))
                } else {
                    val rawList = frame.data["entries"] as? List<*>
                    val entries = rawList?.mapNotNull { item ->
                        val map = when (item) {
                            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (item as Map<String, String>)
                            is com.google.gson.JsonObject -> {
                                mapOf(
                                    "name" to (item.get("name")?.asString ?: "?"),
                                    "path" to (item.get("path")?.asString ?: ""),
                                )
                            }
                            else -> null
                        } ?: return@mapNotNull null
                        DirEntry(map["name"] ?: "?", map["path"] ?: "")
                    } ?: emptyList()
                    _dirEntries.value = entries
                }
            }

            "state_sync" -> {
                val bot = frame.data["bot"]?.toString() ?: ""
                if (bot.isNotBlank()) _botLabel.value = bot
                val cwd = frame.data["cwd"]?.toString() ?: ""
                if (cwd.isNotBlank()) {
                    _currentDir.value = cwd
                    // Save last directory
                    val ctx = getApplication<android.app.Application>()
                    ctx.getSharedPreferences("pi_nostr", android.content.Context.MODE_PRIVATE)
                        .edit().putString("last_dir", cwd).apply()
                }
                _isProcessing.value = false
            }

            "thinking" -> {
                val text = frame.data["text"]?.toString() ?: ""
                if (currentThinkingMsg == null) {
                    currentThinkingMsg = ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        eventType = ChatMessage.EventType.THINKING,
                        thinkingText = text,
                        isStreaming = true,  // thinking is active
                    )
                    addMessage(currentThinkingMsg!!)
                } else {
                    // Update in-place
                    if (currentThinkingMsg != null) {
                        val updated = currentThinkingMsg!!.copy(thinkingText = text)
                        currentThinkingMsg = updated
                        updateMessage(currentThinkingMsg!!.id) { updated }
                    }
                }
            }

            "tool_call" -> {
                val id = frame.data["id"]?.toString() ?: ""
                val name = frame.data["name"]?.toString() ?: ""
                val status = when (frame.data["status"]?.toString()) {
                    "running" -> "running"
                    "complete" -> "complete"
                    "error" -> "error"
                    else -> "running"
                }
                val args = frame.data["args"]?.toString() ?: ""

                val existing = currentToolCallMsgs[id]
                if (existing != null) {
                    // Update existing tool call card in-place
                    val updated = existing.copy(toolStatus = status, text = args.ifEmpty { existing.text })
                    currentToolCallMsgs[id] = updated
                    updateMessage(existing.id) { updated }
                } else {
                    // New tool call card
                    val msg = ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        eventType = ChatMessage.EventType.TOOL_CALL,
                        toolCallId = id,
                        toolName = name,
                        toolStatus = status,
                        text = args,
                    )
                    currentToolCallMsgs[id] = msg
                    addMessage(msg)
                }
            }

            "text_delta" -> {
                val text = frame.data["text"]?.toString() ?: ""
                val more = (frame.data["more"] as? Boolean) ?: true

                // If no active streaming message, or the previous one was finalized
                // (isStreaming == false from a prior more:false), start a new text bubble.
                if (currentAssistantMsg == null || !currentAssistantMsg!!.isStreaming) {
                    // Include any pending thinking text at top of response bubble
                    val thinking = currentThinkingMsg?.thinkingText ?: ""
                    currentAssistantMsg = ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        eventType = ChatMessage.EventType.TEXT,
                        text = text,
                        thinkingText = thinking,
                        isStreaming = more,
                    )
                    // Remove the separate thinking message (it's now embedded)
                    if (thinking.isNotBlank() && currentThinkingMsg != null) {
                        removeMessage(currentThinkingMsg!!.id)
                    }
                    currentThinkingMsg = null
                    addMessage(currentAssistantMsg!!)
                } else {
                    // Subsequent deltas — update in-place
                    currentAssistantMsg = currentAssistantMsg!!.copy(text = text, isStreaming = more)
                    updateMessage(currentAssistantMsg!!.id) { currentAssistantMsg!! }
                }
            }

            "turn_complete" -> {
                if (currentThinkingMsg != null) {
                    val stopped = currentThinkingMsg!!.copy(isStreaming = false)
                    updateMessage(currentThinkingMsg!!.id) { stopped }
                    currentThinkingMsg = null
                }
                currentAssistantMsg = null
                currentThinkingMsg = null
                currentToolCallMsgs.clear()
                _isProcessing.value = false
                // Persist processing state to thread
                val tid = _activeThreadId.value
                if (tid.isNotBlank()) {
                    _threads.value = _threads.value.map {
                        if (it.id == tid) it.copy(isProcessing = false) else it
                    }
                }
            }

            "error" -> {
                addMessage(
                    ChatMessage(
                        role = ChatMessage.Role.SYSTEM,
                        eventType = ChatMessage.EventType.STATUS,
                        text = "❌ ${frame.data["message"]?.toString() ?: "Error"}",
                    )
                )
            }
        }
    }

    // ── Thread management ──

    /** Create a new thread in a directory, switch to it */
    fun addThread(dir: String) {
        val name = dir.substringAfterLast("/").take(20)
        val id = "thread_${System.currentTimeMillis()}"
        val thread = ThreadData(id = id, name = name.ifEmpty { "root" }, dir = dir, messages = emptyList())
        _threads.value = _threads.value + thread
        switchThread(id)
        _isProcessing.value = false
        persist()
        client.sendMessage("/dir $dir", id)
    }

    /** Close a thread (remove from active tabs, keep in history). */
    fun closeThread(id: String) {
        _threads.value = _threads.value.map {
            if (it.id == id) it.copy(closed = true) else it
        }
        // If closing the active thread, switch to another open one or none
        if (id == _activeThreadId.value) {
            val next = _threads.value.firstOrNull { !it.closed }
            if (next != null) switchThread(next.id) else {
                _activeThreadId.value = ""
                _messages.value = emptyList()
                currentAssistantMsg = null
                currentThinkingMsg = null
                currentToolCallMsgs.clear()
            }
        }
        persist()
    }

    /** Resume a closed thread (bring it back to active tabs). */
    fun resumeThread(id: String) {
        _threads.value = _threads.value.map {
            if (it.id == id) it.copy(closed = false) else it
        }
        switchThread(id)
        persist()
    }

    /** Switch to a thread by ID */
    fun switchThread(id: String) {
        val thread = _threads.value.find { it.id == id } ?: return
        _activeThreadId.value = id
        _messages.value = thread.messages
        _isProcessing.value = thread.isProcessing
        // Clear unread when viewing
        _threads.value = _threads.value.map {
            if (it.id == id) it.copy(unread = false) else it
        }
        currentAssistantMsg = null
        currentThinkingMsg = null
        currentToolCallMsgs.clear()
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val tid = _activeThreadId.value.ifEmpty { "default" }

        // Don't capture commands as chat messages
        if (!text.startsWith("/") && !text.startsWith("!")) {
            addMessage(ChatMessage(role = ChatMessage.Role.USER, text = text))
            // Set this thread as processing
            _threads.value = _threads.value.map {
                if (it.id == tid) it.copy(isProcessing = true) else it
            }
        }
        _isProcessing.value = true
        persist()
        client.sendMessage(text, tid)
    }

    fun disconnect() {
        client.disconnect()
        _isConnected.value = false
    }

    fun reconnect() {
        if (bridgeUrl.isNotBlank()) connect(preserveMessages = true)
    }

    /** Persist all threads to disk (off main thread). */
    private fun persist() {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.saveThreads(_threads.value)
        }
    }

    /** Sync current `_messages` to the active thread's stored list */
    private fun syncThreadMessages() {
        val tid = _activeThreadId.value
        if (tid.isNotBlank()) {
            _threads.value = _threads.value.map {
                if (it.id == tid) it.copy(messages = _messages.value, lastActiveAt = System.currentTimeMillis()) else it
            }
        }
    }

    /** Set _messages and sync to thread, persist. */
    private fun setMessages(msgs: List<ChatMessage>) {
        _messages.value = msgs
        syncThreadMessages()
        persist()
    }

    private fun addMessage(msg: ChatMessage) {
        setMessages(_messages.value + msg)
    }

    /** Remove a message by id from both _messages and thread */
    private fun removeMessage(id: String) {
        setMessages(_messages.value.filter { it.id != id })
    }

    /** Update a message in-place in both _messages and the thread's list */
    private fun updateMessage(id: String, update: (ChatMessage) -> ChatMessage) {
        val msgs = _messages.value.toMutableList()
        val idx = msgs.indexOfLast { it.id == id }
        if (idx >= 0) {
            msgs[idx] = update(msgs[idx])
            setMessages(msgs)
        }
    }

    override fun onCleared() {
        persist()
        client.disconnect()
        super.onCleared()
    }
}
