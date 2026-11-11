package com.example.floatingclock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val SECOND_MS = 1_000L
internal const val MINUTE_MS = 60 * SECOND_MS

internal data class ShoppingRecord(val id: String, val date: Long, val seconds: Long)

internal data class ShoppingItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val note: String = "",
    val budget: String = "",
    val priceStage: Boolean = false,
    val done: Boolean = false,
    val records: List<ShoppingRecord> = emptyList(),
)

internal data class ShoppingSession(
    val id: String,
    val itemId: String,
    val name: String,
    val startedAt: Long,
    val duration: Long,
    val deadline: Long,
    val deadlineWall: Long,
    val bootCount: Int,
    val pausedRemaining: Long? = null,
) {
    fun remaining(currentBoot: Int): Long {
        val timeLeft = pausedRemaining ?: if (bootCount == currentBoot) {
            deadline - SystemClock.elapsedRealtime()
        } else {
            deadlineWall - System.currentTimeMillis()
        }
        return timeLeft.coerceIn(0, duration)
    }
}

internal data class ShoppingState(
    val items: List<ShoppingItem> = emptyList(),
    val session: ShoppingSession? = null,
) {
    fun updateItem(id: String, update: (ShoppingItem) -> ShoppingItem): ShoppingState =
        copy(items = items.map { if (it.id == id) update(it) else it })

    fun settle(seconds: Long): ShoppingState {
        val active = session ?: return this
        val record = ShoppingRecord(active.id, active.startedAt, seconds)
        return updateItem(active.itemId) { it.copy(records = listOf(record) + it.records) }.copy(session = null)
    }
}

internal class ShoppingStore private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("shopping", Context.MODE_PRIVATE)
    val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val mutableState = MutableStateFlow(decode(preferences.getString(STATE_KEY, null)))
    private val writes = Mutex()
    val state = mutableState.asStateFlow()

    suspend fun change(transform: (ShoppingState) -> ShoppingState): ShoppingState = writes.withLock {
        withContext(Dispatchers.IO) {
            val previous = mutableState.value
            val updated = transform(previous)
            if (previous != updated) {
                if (!preferences.edit().putString(STATE_KEY, encode(updated)).commit()) {
                    throw IOException("Could not save shopping data")
                }
                mutableState.value = updated
            }
            updated
        }
    }

    suspend fun expire(sessionId: String): Boolean {
        var expired = false
        change { state ->
            val active = state.session
            if (active?.id != sessionId || active.remaining(bootCount) > 0) return@change state
            expired = true
            state.settle(active.duration / SECOND_MS)
        }
        return expired
    }

    companion object {
        private const val STATE_KEY = "state"
        private var instance: ShoppingStore? = null

        @Synchronized
        fun get(context: Context): ShoppingStore =
            instance ?: ShoppingStore(context.applicationContext).also { instance = it }
    }
}

private fun encode(state: ShoppingState): String {
    val items = JSONArray()
    state.items.forEach { items.put(it.toShoppingJson()) }
    val result = JSONObject().put("items", items)
    state.session?.let { session ->
        result.put("session", JSONObject().put("id", session.id).put("itemId", session.itemId)
            .put("name", session.name).put("startedAt", session.startedAt).put("duration", session.duration)
            .put("deadline", session.deadline).put("deadlineWall", session.deadlineWall)
            .put("bootCount", session.bootCount).put("pausedRemaining", session.pausedRemaining))
    }
    return result.toString()
}

internal fun ShoppingItem.toShoppingJson(): JSONObject {
    val entries = JSONArray()
    records.forEach { record ->
        entries.put(JSONObject().put("id", record.id).put("date", record.date).put("seconds", record.seconds))
    }
    return JSONObject()
        .put("id", id)
        .put("name", name)
        .put("note", note)
        .put("budget", budget)
        .put("priceStage", priceStage)
        .put("done", done)
        .put("records", entries)
}

private fun decode(source: String?): ShoppingState {
    if (source == null) return ShoppingState()
    val state = JSONObject(source)
    val items = state.getJSONArray("items").objects().map { item ->
        ShoppingItem(
            id = item.getString("id"), name = item.getString("name"), note = item.optString("note"),
            budget = item.optString("budget"), priceStage = item.optBoolean("priceStage"),
            done = item.optBoolean("done"),
            records = item.getJSONArray("records").objects().map {
                ShoppingRecord(it.getString("id"), it.getLong("date"), it.getLong("seconds"))
            },
        )
    }
    val session = state.optJSONObject("session")?.let {
        ShoppingSession(it.getString("id"), it.getString("itemId"), it.getString("name"),
            it.getLong("startedAt"), it.getLong("duration"), it.getLong("deadline"), it.getLong("deadlineWall"),
            it.optInt("bootCount", -1), it.optLong("pausedRemaining", -1).takeIf { remaining -> remaining >= 0 })
    }
    return ShoppingState(items, session)
}

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
