package com.example.floatingclock

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object ShoppingBackup {
    private const val FORMAT_VERSION = 1

    suspend fun export(resolver: ContentResolver, destination: Uri, store: ShoppingStore) = withContext(Dispatchers.IO) {
        val backup = snapshot(store.state.value, store.bootCount)
        val output = resolver.openOutputStream(destination) ?: throw IOException("Backup destination unavailable")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(backup.toString(2)) }
    }

    private fun snapshot(state: ShoppingState, bootCount: Int): JSONObject {
        val exportedAt = Instant.now()
        val activeSession = state.session?.let { session ->
            val remaining = session.remaining(bootCount)
            val status = when {
                remaining == 0L -> "expired"
                session.pausedRemaining != null -> "paused"
                else -> "running"
            }
            JSONObject()
                .put("id", session.id)
                .put("itemId", session.itemId.ifBlank { null } ?: JSONObject.NULL)
                .put("name", session.name)
                .put("startedAt", session.startedAt)
                .put("status", status)
                .put("durationMillis", session.duration)
                .put("elapsedMillis", session.duration - remaining)
                .put("remainingMillis", remaining)
        }
        val items = JSONArray()
        state.items.forEach { item ->
            items.put(item.toShoppingJson().put("totalSeconds", item.records.sumOf { it.seconds }))
        }
        return JSONObject()
            .put("format", "toolbox-shopping")
            .put("version", FORMAT_VERSION)
            .put("exportedAt", exportedAt.toString())
            .put("items", items)
            .put("activeSession", activeSession ?: JSONObject.NULL)
    }
}
