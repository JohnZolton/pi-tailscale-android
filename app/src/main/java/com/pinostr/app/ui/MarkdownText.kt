package com.pinostr.app.ui

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin

/**
 * Markdown renderer using Markwon — tables, code, lists, everything.
 * Text is selectable for copy.
 *
 * Skips `markwon.setMarkdown()` when text hasn't changed to avoid
 * re-parsing during recompositions caused by scrolling or parent state changes.
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
            .usePlugin(TablePlugin.create(ctx))
            .build()
    }

    val textColorArgb = android.graphics.Color.rgb(224, 224, 240)
    val lastText = remember { mutableStateOf("") }

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
            // Skip re-parsing if text hasn't actually changed.
            // This avoids markwon rendering on every recomposition during
            // scrolling or when parent state changes.
            if (lastText.value != markdown) {
                lastText.value = markdown
                tv.setTextColor(textColorArgb)
                markwon.setMarkdown(tv, markdown)
            }
        },
        modifier = modifier,
    )
}
