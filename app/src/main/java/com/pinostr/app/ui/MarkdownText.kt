package com.pinostr.app.ui

import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.html.HtmlPlugin

/**
 * Markdown renderer using Markwon — tables, code, lists, everything.
 * Text is selectable for copy.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFFE0E0F0),
) {
    val ctx = LocalContext.current
    val markwon = remember {
        Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .build()
    }
    // Compose Color stores value as a packed ULong. Convert to Android ARGB int.
    val packed = textColor.value
    val colorInt = ((packed shr 32).toInt() shl 24) or ((packed shr 16).toInt() and 0xFF shl 16) or ((packed shr 8).toInt() and 0xFF shl 8) or (packed.toInt() and 0xFF)

    AndroidView(
        factory = { context ->
            TextView(context).apply {
                textSize = 14f
                setLineSpacing(4f, 1f)
                typeface = Typeface.DEFAULT
                setTextColor(colorInt)
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
            }
        },
        update = { tv ->
            tv.setTextColor(colorInt)
            markwon.setMarkdown(tv, markdown)
        },
        modifier = modifier,
    )
}
