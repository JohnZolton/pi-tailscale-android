package com.pinostr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinostr.app.model.ChatMessage
import com.pinostr.app.viewmodel.ChatViewModel
import com.pinostr.app.viewmodel.ChatViewModel.ThreadData

/**
 * Side panel showing all saved thread histories grouped by directory.
 * Active (non-closed) threads are shown first with a colored indicator,
 * closed threads below grouped under their directory header.
 *
 * Tap a thread to resume it (bring to active tabs and switch to it).
 */
@Composable
fun HistoryPanel(
    threads: List<ThreadData>,
    onResume: (ThreadData) -> Unit,
    onClose: (ThreadData) -> Unit,
    onDismiss: () -> Unit,
) {
    // Group: active first, then closed grouped by dir
    val activeThreads = threads.filter { !it.closed }
    val closedByDir = threads.filter { it.closed }.groupBy { it.dir }.entries
        .sortedByDescending { (_, list) -> list.maxOf { it.lastActiveAt } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E36),
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, tint = Color(0xFF7B68EE), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Chat History", color = Color(0xFFE0E0F0), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = Color(0xFF8888AA), modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            if (threads.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No chat history yet", color = Color(0xFF555577), fontSize = 14.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    // Active sessions header
                    if (activeThreads.isNotEmpty()) {
                        item {
                            SectionHeader(
                                icon = Icons.Default.Chat,
                                label = "Active",
                                count = activeThreads.size,
                            )
                        }
                        items(activeThreads, key = { it.id }) { thread ->
                            ThreadRow(
                                thread = thread,
                                isActive = true,
                                onTap = { onResume(thread) },
                                onClose = { onClose(thread) },
                            )
                        }
                        if (closedByDir.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0xFF333355), thickness = 0.5.dp)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }

                    // Closed sessions grouped by directory
                    for ((dir, dirThreads) in closedByDir) {
                        item {
                            SectionHeader(
                                icon = Icons.Default.Folder,
                                label = dir.substringAfterLast("/").ifEmpty { dir },
                                count = dirThreads.size,
                                subtitle = dir,
                            )
                        }
                        items(dirThreads, key = { it.id }) { thread ->
                            ThreadRow(
                                thread = thread,
                                isActive = false,
                                onTap = { onResume(thread) },
                                onClose = null, // already closed
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color(0xFF7B68EE), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = Color(0xFFB0B0D0),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF333355))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text("$count", color = Color(0xFF8888AA), fontSize = 10.sp)
        }
        if (subtitle != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = subtitle,
                color = Color(0xFF555577),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ThreadRow(
    thread: ThreadData,
    isActive: Boolean,
    onTap: () -> Unit,
    onClose: (() -> Unit)?,
) {
    val firstUserMsg = thread.messages.firstOrNull { it.role == ChatMessage.Role.USER }?.text
    val snippet = firstUserMsg?.take(80)?.let { if (it.length < (firstUserMsg?.length ?: 0)) "$it…" else it }
        ?: thread.name

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Active indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isActive) Color(0xFF7B68EE) else Color(0xFF333355)),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = snippet,
                color = Color(0xFFD0D0E0),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.name,
                    color = Color(0xFF666688),
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatRelativeTime(thread.lastActiveAt),
                    color = Color(0xFF555577),
                    fontSize = 9.sp,
                )
                val msgCount = thread.messages.count { it.eventType == ChatMessage.EventType.TEXT }
                if (msgCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$msgCount msgs",
                        color = Color(0xFF444466),
                        fontSize = 9.sp,
                    )
                }
            }
        }
        if (onClose != null) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    "Close thread",
                    tint = Color(0xFF555577),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}
