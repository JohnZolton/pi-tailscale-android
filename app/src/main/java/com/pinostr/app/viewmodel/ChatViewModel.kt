package com.pinostr.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pinostr.app.model.ChatMessage
import com.pinostr.app.nostr.DirectClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // ── Threads ──
    data class ThreadData(val id: String, val name: String, val dir: String, val messages: List<ChatMessage>)
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
    private val client = DirectClient()

    init {
        // Load saved bridge URL from prefs
        val prefs = application.getSharedPreferences("pi_nostr", android.content.Context.MODE_PRIVATE)
        bridgeUrl = prefs.getString("bridge_url", "") ?: ""
        if (bridgeUrl.isNotBlank()) {
            connect()
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

    private fun connect() {
        client.disconnect()
        currentAssistantMsg = null
        _messages.value = emptyList()

        viewModelScope.launch {
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

                if (currentAssistantMsg == null) {
                    // First delta — include thinking text at top of response bubble
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
                    if (currentAssistantMsg != null) {
                        currentAssistantMsg = currentAssistantMsg!!.copy(text = text, isStreaming = more)
                        updateMessage(currentAssistantMsg!!.id) { currentAssistantMsg!! }
                    } else {
                        // Fallback: create new if we lost track
                        currentAssistantMsg = ChatMessage(
                            role = ChatMessage.Role.ASSISTANT,
                            eventType = ChatMessage.EventType.TEXT,
                            text = text,
                            isStreaming = more,
                        )
                        addMessage(currentAssistantMsg!!)
                    }
                }
            }

            "turn_complete" -> {
                // Mark thinking as inactive (replace dots with "Thought")
                if (currentThinkingMsg != null) {
                    val stopped = currentThinkingMsg!!.copy(isStreaming = false)
                    updateMessage(currentThinkingMsg!!.id) { stopped }
                    currentThinkingMsg = null
                }
                currentAssistantMsg = null
                currentThinkingMsg = null
                currentToolCallMsgs.clear()
                _isProcessing.value = false
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
        // Send /dir to bridge to set this thread's working directory
        _isProcessing.value = false
        client.sendMessage("/dir $dir", id)
    }

    /** Switch to a thread by ID */
    fun switchThread(id: String) {
        val thread = _threads.value.find { it.id == id } ?: return
        _activeThreadId.value = id
        _messages.value = thread.messages
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
        }
        _isProcessing.value = true
        client.sendMessage(text, tid)
    }

    fun disconnect() {
        client.disconnect()
        _isConnected.value = false
    }

    fun reconnect() {
        if (bridgeUrl.isNotBlank()) connect()
    }

    /** Sync current `_messages` to the active thread's stored list */
    private fun syncThreadMessages() {
        val tid = _activeThreadId.value
        if (tid.isNotBlank()) {
            _threads.value = _threads.value.map {
                if (it.id == tid) it.copy(messages = _messages.value) else it
            }
        }
    }

    /** Set _messages and sync to thread */
    private fun setMessages(msgs: List<ChatMessage>) {
        _messages.value = msgs
        syncThreadMessages()
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
        client.disconnect()
        super.onCleared()
    }
}
