package com.pinostr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a git diff with syntax highlighting.
 * Shows added lines in green, removed lines in red.
 */
@Composable
fun DiffCard(
    fileName: String,
    diffContent: String,
    status: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Code,
                contentDescription = null,
                tint = Color(0xFF7B68EE),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFE0E0F0),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8888AA),
                fontSize = 10.sp,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle diff",
                    tint = Color(0xFF8888AA),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Diff content
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF12121E))
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                val diffLines = diffContent.split("\n")
                val annotated = buildAnnotatedString {
                    for ((idx, line) in diffLines.withIndex()) {
                        if (idx > 0) append("\n")
                        when {
                            line.startsWith("+") && !line.startsWith("+++") -> {
                                withStyle(SpanStyle(
                                    color = Color(0xFF66BB6A),
                                    background = Color(0x1A66BB6A),
                                )) { append(line) }
                            }
                            line.startsWith("-") && !line.startsWith("---") -> {
                                withStyle(SpanStyle(
                                    color = Color(0xFFEF5350),
                                    background = Color(0x1AEF5350),
                                )) { append(line) }
                            }
                            line.startsWith("@@") -> {
                                withStyle(SpanStyle(
                                    color = Color(0xFF7B68EE),
                                    fontWeight = FontWeight.Bold,
                                )) { append(line) }
                            }
                            else -> {
                                withStyle(SpanStyle(color = Color(0xFFB0B0D0))) { append(line) }
                            }
                        }
                    }
                }

                Text(
                    text = annotated,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
