package com.pinostr.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinostr.app.model.ChatMessage

@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    when (message.eventType) {
        ChatMessage.EventType.THINKING -> {
            ThinkingBubble(
                text = message.thinkingText,
                active = message.isStreaming,
                modifier = modifier,
            )
        }

        ChatMessage.EventType.TOOL_CALL -> {
            ToolCallCard(
                toolName = message.toolName,
                status = message.toolStatus,
                argsText = message.text,
                modifier = modifier,
            )
        }

        ChatMessage.EventType.DIFF -> {
            DiffCard(
                fileName = message.diffFile,
                diffContent = message.diffContent,
                status = message.toolStatus.ifEmpty { "modified" },
                modifier = modifier,
            )
        }

        ChatMessage.EventType.STATUS -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A2E))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8888AA),
                    fontSize = 11.sp,
                )
            }
        }

        ChatMessage.EventType.TURN_COMPLETE -> {
            // Brief separator
            Box(modifier = modifier.padding(vertical = 2.dp))
        }

        ChatMessage.EventType.TEXT -> {
            // Regular text message — user or assistant
            val isUser = message.role == ChatMessage.Role.USER
            val bgColor = if (isUser) Color(0xFF3A3A6A) else Color(0xFF252542)
            val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
            val shape = if (isUser)
                RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
            else
                RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)

            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                contentAlignment = align,
            ) {
                Column(
                    modifier = Modifier
                        .let { if (isUser) it.widthIn(max = 320.dp) else it.fillMaxWidth(0.9f) }
                        .clip(shape)
                        .background(bgColor)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    // Role label
                    if (!isUser) {
                        Text(
                            text = "pi",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF7B68EE),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    // Collapsible thinking section (embedded in response)
                    if (!isUser && message.thinkingText.isNotBlank()) {
                        var showThinking by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .clickable { showThinking = !showThinking }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF555577)),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (showThinking) "Hide thinking" else "Show thinking",
                                color = Color(0xFF666688),
                                fontSize = 10.sp,
                            )
                        }
                        AnimatedVisibility(visible = showThinking) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1A1A2E))
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = message.thinkingText,
                                    color = Color(0xFF8888AA),
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    // Content (selectable for copy)
                    if (message.text.isNotBlank()) {
                        MarkdownText(
                            markdown = message.text + if (message.isStreaming) " ▍" else "",
                        )
                    }

                    // Timestamp (cached per message id to avoid date formatting work)
                    Spacer(Modifier.height(4.dp))
                    val tsLabel = remember(message.id) { formatTimestamp(message.timestamp) }
                    Text(
                        text = tsLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF666688),
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}
