package com.pinostr.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Simple Markdown renderer for chat messages.
 * Supports: **bold**, *italic*, `code`, ```code blocks```, - lists, # headings, > quotes
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    codeColor: Color = Color(0xFF7B68EE),
    linkColor: Color = Color(0xFF64B5F6),
) {
    val annotated = buildAnnotatedString {
        var i = 0
        while (i < markdown.length) {
            when {
                // Code block ```...```
                markdown.startsWith("```", i) -> {
                    val end = markdown.indexOf("```", i + 3)
                    if (end != -1) {
                        val codeBlock = markdown.substring(i + 3, end).trimStart('\n')
                        val codeLang = codeBlock.substringBefore('\n').take(20)
                        val code = codeBlock.substringAfter('\n').ifEmpty { codeBlock }
                        withStyle(SpanStyle(
                            color = codeColor,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 13.sp,
                        )) {
                            append(if (codeLang.isNotBlank()) "┌─ $codeLang\n" else "┌─\n")
                            append(code)
                            append("\n└─")
                        }
                        i = end + 3
                    } else {
                        append(markdown[i])
                        i++
                    }
                }

                // Inline code `...`
                markdown[i] == '`' -> {
                    val end = markdown.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(
                            color = codeColor,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 13.sp,
                            background = Color(0x337B68EE),
                        )) {
                            append(markdown.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(markdown[i]); i++ }
                }

                // Bold **...**
                markdown.startsWith("**", i) -> {
                    val end = markdown.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(markdown.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { append(markdown[i]); i++ }
                }

                // Italic *...*
                markdown[i] == '*' && !markdown.startsWith("**", i) -> {
                    val end = markdown.indexOf('*', i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(markdown.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(markdown[i]); i++ }
                }

                // Strikethrough ~~...~~
                markdown.startsWith("~~", i) -> {
                    val end = markdown.indexOf("~~", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                            append(markdown.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { append(markdown[i]); i++ }
                }

                // Links [text](url)
                markdown[i] == '[' -> {
                    val closeBracket = markdown.indexOf(']', i)
                    val openParen = if (closeBracket != -1 && closeBracket + 1 < markdown.length && markdown[closeBracket + 1] == '(') closeBracket + 1 else -1
                    val closeParen = if (openParen != -1) markdown.indexOf(')', openParen) else -1
                    if (closeBracket != -1 && closeParen != -1) {
                        val linkText = markdown.substring(i + 1, closeBracket)
                        val linkUrl = markdown.substring(openParen + 1, closeParen)
                        withStyle(SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                            append(linkText)
                        }
                        append(" [$linkUrl]")
                        i = closeParen + 1
                    } else { append(markdown[i]); i++ }
                }

                // Newline
                markdown[i] == '\n' -> {
                    append('\n')
                    i++
                }

                else -> { append(markdown[i]); i++ }
            }
        }
    }

    Text(
        text = annotated,
        style = style,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    )
}
