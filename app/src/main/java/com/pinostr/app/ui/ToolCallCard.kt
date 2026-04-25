package com.pinostr.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ToolCallCard(
    toolName: String,
    status: String,
    argsText: String = "",
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val icon = when (status) {
        "running" -> Icons.Default.HourglassTop
        "complete" -> Icons.Default.CheckCircle
        "error" -> Icons.Default.Error
        else -> Icons.Default.Code
    }
    val iconColor = when (status) {
        "running" -> Color(0xFFFFA726)
        "complete" -> Color(0xFF66BB6A)
        "error" -> Color(0xFFEF5350)
        else -> Color(0xFF7B68EE)
    }
    val bgColor = when (status) {
        "running" -> Color(0xFF2A2A4A)
        "complete" -> Color(0xFF1B3A2D)
        "error" -> Color(0xFF3A1B1B)
        else -> Color(0xFF252542)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE0E0F0),
                    fontSize = 13.sp,
                )
                Text(
                    text = status.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = iconColor.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
            if (argsText.isNotBlank()) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle args",
                    tint = Color(0xFF8888AA),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded && argsText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1A1A2E))
                    .padding(8.dp),
            ) {
                Text(
                    text = argsText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0B0D0),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
