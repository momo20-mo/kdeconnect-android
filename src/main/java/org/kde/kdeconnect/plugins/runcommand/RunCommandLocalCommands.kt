/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.runcommand

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.kde.kdeconnect.helpers.JsonHelper.json
import org.kde.kdeconnect_tp.R
import java.util.UUID

@Serializable
data class LocalCommand(val name: String, val command: String)

object RunCommandLocalCommands {
    private const val PREFERENCE_COMMANDS = "runcommand_local_commands"
    private const val TAG = "RunCommandLocalCommands"

    fun isEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(
            context.getString(R.string.runcommand_send_commands_enabled_key),
            false
        )
    }

    fun isRelevantPreference(context: Context, key: String?): Boolean {
        return key == PREFERENCE_COMMANDS ||
            key == context.getString(R.string.runcommand_send_commands_enabled_key)
    }

    fun getCommands(context: Context): Map<String, LocalCommand> {
        val raw = preferences(context).getString(PREFERENCE_COMMANDS, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, LocalCommand>>(raw)
        } catch (e: SerializationException) {
            Log.e(TAG, "Error parsing local commands", e)
            emptyMap()
        }
    }

    fun commandsJson(context: Context): String = json.encodeToString(getCommands(context))

    fun addCommand(context: Context, name: String, command: String): String? {
        if (name.isBlank() || command.isBlank()) return null
        val id = UUID.randomUUID().toString()
        saveCommands(context, getCommands(context) + (id to LocalCommand(name, command)))
        return id
    }

    fun updateCommand(context: Context, id: String, name: String, command: String): Boolean {
        if (name.isBlank() || command.isBlank()) return false
        val commands = getCommands(context)
        if (!commands.containsKey(id)) return false
        saveCommands(context, commands + (id to LocalCommand(name, command)))
        return true
    }

    fun deleteCommand(context: Context, id: String) {
        val commands = getCommands(context)
        if (!commands.containsKey(id)) return
        saveCommands(context, commands - id)
    }

    fun findCommand(context: Context, id: String): LocalCommand? = getCommands(context)[id]

    private fun saveCommands(context: Context, commands: Map<String, LocalCommand>) {
        preferences(context).edit {
            putString(PREFERENCE_COMMANDS, json.encodeToString(commands))
        }
    }

    private fun preferences(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)
}
