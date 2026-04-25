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

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _botLabel = MutableStateFlow("")
    val botLabel: StateFlow<String> = _botLabel.asStateFlow()

    private var currentAssistantMsg: ChatMessage? = null
    private var currentThinkingMsg: ChatMessage? = null

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

    private fun handleFrame(frame: DirectClient.StreamFrame) {
        when (frame.type) {
            "state_sync" -> {
                val bot = frame.data["bot"]?.toString() ?: ""
                if (bot.isNotBlank()) _botLabel.value = bot
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
                    val msgs = _messages.value.toMutableList()
                    val idx = msgs.indexOfLast { it.id == currentThinkingMsg?.id }
                    if (idx >= 0) {
                        val updated = currentThinkingMsg!!.copy(thinkingText = text)
                        currentThinkingMsg = updated
                        msgs[idx] = updated
                        _messages.value = msgs
                    }
                }
            }

            "tool_call" -> {
                val status = when (frame.data["status"]?.toString()) {
                    "running" -> "running"
                    "complete" -> "complete"
                    "error" -> "error"
                    else -> "running"
                }
                addMessage(
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        eventType = ChatMessage.EventType.TOOL_CALL,
                        toolName = frame.data["name"]?.toString() ?: "",
                        toolStatus = status,
                        text = frame.data["args"]?.toString() ?: "",
                    )
                )
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
                    if (thinking.isNotBlank()) {
                        val msgs = _messages.value.toMutableList()
                        msgs.removeAll { it.id == currentThinkingMsg?.id }
                        _messages.value = msgs
                    }
                    currentThinkingMsg = null
                    addMessage(currentAssistantMsg!!)
                } else {
                    // Subsequent deltas — update in-place
                    val msgs = _messages.value.toMutableList()
                    val idx = msgs.indexOfLast { it.id == currentAssistantMsg?.id }
                    if (idx >= 0) {
                        currentAssistantMsg = currentAssistantMsg!!.copy(text = text, isStreaming = more)
                        msgs[idx] = currentAssistantMsg!!
                        _messages.value = msgs
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

            "diff" -> {
                addMessage(
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        eventType = ChatMessage.EventType.DIFF,
                        diffContent = frame.data["diff"]?.toString() ?: "",
                        diffFile = frame.data["file"]?.toString() ?: "file",
                    )
                )
            }

            "turn_complete" -> {
                // Mark thinking as inactive (replace dots with "Thought")
                if (currentThinkingMsg != null) {
                    val msgs = _messages.value.toMutableList()
                    val idx = msgs.indexOfLast { it.id == currentThinkingMsg?.id }
                    if (idx >= 0) {
                        val stopped = currentThinkingMsg!!.copy(isStreaming = false)
                        currentThinkingMsg = null
                        msgs[idx] = stopped
                        _messages.value = msgs
                    }
                }
                currentAssistantMsg = null
                currentThinkingMsg = null
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

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        addMessage(ChatMessage(role = ChatMessage.Role.USER, text = text))
        _isProcessing.value = true
        client.sendMessage(text)
    }

    fun disconnect() {
        client.disconnect()
        _isConnected.value = false
    }

    fun reconnect() {
        if (bridgeUrl.isNotBlank()) connect()
    }

    private fun addMessage(msg: ChatMessage) {
        _messages.value = _messages.value + msg
    }

    override fun onCleared() {
        client.disconnect()
        super.onCleared()
    }
}
