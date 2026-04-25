package com.pinostr.app.model

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.pinostr.app.viewmodel.ChatViewModel
import java.io.File

/**
 * Manages saving and loading thread histories to JSON files.
 * Each thread is stored as a separate .json file under
 * `context.filesDir/threads/` keyed by thread ID.
 *
 * Runtime-only fields (isProcessing, unread) are excluded from persistence.
 */
class PersistenceManager(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .setLenient()
        .create()

    private val threadsDir: File
        get() = File(context.filesDir, "threads").also { it.mkdirs() }

    /** Data shape for persistence — strips runtime-only fields. */
    private data class PersistedThread(
        val id: String,
        val name: String,
        val dir: String,
        val messages: List<ChatMessage>,
        val createdAt: Long,
        val lastActiveAt: Long,
        val closed: Boolean = false,
    )

    /** Save all threads. Runtime fields are reset on reload. */
    fun saveThreads(threads: List<ChatViewModel.ThreadData>) {
        threadsDir.listFiles()?.forEach { it.delete() } // clear old files
        for (t in threads) {
            val pt = PersistedThread(
                id = t.id,
                name = t.name,
                dir = t.dir,
                messages = t.messages,
                createdAt = t.createdAt,
                lastActiveAt = t.lastActiveAt,
                closed = t.closed,
            )
            val file = File(threadsDir, "${t.id}.json")
            file.writeText(gson.toJson(pt))
        }
    }

    /** Load all saved threads. Runtime fields default to false. */
    fun loadThreads(): List<ChatViewModel.ThreadData> {
        val files = threadsDir.listFiles() ?: return emptyList()
        return files.filter { it.extension == "json" }.mapNotNull { file ->
            try {
                val pt = gson.fromJson(file.readText(), PersistedThread::class.java)
                ChatViewModel.ThreadData(
                    id = pt.id,
                    name = pt.name,
                    dir = pt.dir,
                    messages = pt.messages,
                    createdAt = pt.createdAt,
                    lastActiveAt = pt.lastActiveAt,
                    closed = pt.closed,
                )
            } catch (e: Exception) {
                file.delete() // corrupted file
                null
            }
        }.sortedByDescending { it.lastActiveAt }
    }

    /** Delete a single thread file. */
    fun deleteThread(id: String) {
        File(threadsDir, "$id.json").delete()
    }
}
