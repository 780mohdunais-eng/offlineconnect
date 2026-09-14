package com.unais.offlineconnect.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unais.offlineconnect.bluetooth.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "offline_connect_prefs")

/**
 * All-local storage (spec section 20/15): no cloud database, everything lives in
 * DataStore on-device. Chat history is scoped per remote device address.
 */
class LocalStore(private val context: Context) {

    private object Keys {
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        fun chatHistory(address: String) = stringPreferencesKey("chat_$address")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val displayName: Flow<String?> = context.dataStore.data.map { it[Keys.DISPLAY_NAME] }

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { it[Keys.DISPLAY_NAME] = name }
    }

    suspend fun loadHistory(address: String): List<ChatMessage> {
        val raw = context.dataStore.data.first()[Keys.chatHistory(address)] ?: return emptyList()
        return try {
            json.decodeFromString<List<ChatMessage>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveHistory(address: String, messages: List<ChatMessage>) {
        // Keep the persisted history bounded so DataStore doesn't grow unbounded.
        val trimmed = if (messages.size > 500) messages.takeLast(500) else messages
        context.dataStore.edit { it[Keys.chatHistory(address)] = json.encodeToString(trimmed) }
    }
}

// ChatMessage needs to be @Serializable for the above to compile - see BluetoothModels.kt
