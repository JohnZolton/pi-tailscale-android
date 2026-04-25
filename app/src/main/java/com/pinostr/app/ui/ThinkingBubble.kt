package com.pinostr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Expandable thinking bubble with auto-scroll.
 * Collapsed by default — tap to expand and scroll through thinking text.
 * Dots animate while active, stop when `active` is false.
 */
@Composable
fun ThinkingBubble(
    text: String,
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var dotCount by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    // Animated dots only while active
    if (active) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(500)
                dotCount = (dotCount + 1) % 4
            }
        }
    }
    val dots = ".".repeat(dotCount)

    // Auto-scroll to bottom when text grows — immediate, no animation lag
    LaunchedEffect(text, expanded) {
        if (expanded && text.isNotBlank()) {
            // Small delay to let layout settle, then jump to bottom
            delay(16)
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A4A)),
    ) {
        // Header — always visible
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (active) Color(0xFF7B68EE) else Color(0xFF555577)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (active) "Thinking$dots" else "Thought$dots",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) Color(0xFF9E9EE0) else Color(0xFF666688),
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            if (text.isNotBlank()) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle thinking",
                    tint = Color(0xFF555577),
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Expandable thinking text
        if (expanded && text.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1A1A2E))
                    .verticalScroll(scrollState)
                    .padding(10.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8888AA),
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
