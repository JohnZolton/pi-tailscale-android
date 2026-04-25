package com.pinostr.app.ui

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
) {
    val ctx = LocalContext.current
    val markwon = remember {
        Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .build()
    }

    val textColorArgb = android.graphics.Color.rgb(224, 224, 240)

    AndroidView(
        factory = { context ->
            TextView(context).apply {
                textSize = 14f
                setLineSpacing(4f, 1f)
                setTextColor(textColorArgb)
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
            }
        },
        update = { tv ->
            tv.setTextColor(textColorArgb)
            markwon.setMarkdown(tv, markdown)
        },
        modifier = modifier,
    )
}
