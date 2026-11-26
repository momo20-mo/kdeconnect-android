/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.taskertext

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the text of recently received variables around until Tasker asks for it.
 *
 * Tasker associates a QUERY_CONDITION response with the REQUEST_QUERY that caused it
 * through the message ID in the pass-through bundle. We store the text under that ID
 * and remove entries that Tasker never queried after a short timeout.
 */
object TaskerVariableEventDispatcher {
    private const val MAX_EVENT_AGE_MS = 60_000L

    private data class PendingEvent(val text: String, val receivedAt: Long)

    private val pendingEvents = ConcurrentHashMap<Int, PendingEvent>()

    fun dispatch(context: Context, text: String) {
        val now = SystemClock.elapsedRealtime()
        purgeExpired(now)

        val intent = Intent(TaskerProtocol.ACTION_REQUEST_QUERY).apply {
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(TaskerProtocol.EXTRA_ACTIVITY, TaskerVariableEventActivity::class.java.name)
        }

        var messageId: Int
        do {
            messageId = TaskerProtocol.Event.addPassThroughMessageID(intent)
        } while (pendingEvents.containsKey(messageId))

        pendingEvents[messageId] = PendingEvent(text, now)
        TaskerProtocol.sendRequestQuery(context, intent)
    }

    fun getText(messageId: Int): String? {
        purgeExpired(SystemClock.elapsedRealtime())
        return pendingEvents[messageId]?.text
    }

    private fun purgeExpired(now: Long) {
        pendingEvents.entries.removeAll { now - it.value.receivedAt > MAX_EVENT_AGE_MS }
    }
}
