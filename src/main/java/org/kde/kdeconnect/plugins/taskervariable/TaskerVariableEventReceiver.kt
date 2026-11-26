/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.taskertext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

class TaskerVariableEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskerProtocol.ACTION_QUERY_CONDITION) {
            return
        }

        val messageId = TaskerProtocol.Event.retrievePassThroughMessageID(intent)
        if (messageId == -1) {
            resultCode = TaskerProtocol.RESULT_CONDITION_UNSATISFIED
            return
        }

        val text = TaskerVariableEventDispatcher.getText(messageId)
        if (text == null) {
            resultCode = TaskerProtocol.RESULT_CONDITION_UNSATISFIED
            return
        }

        resultCode = TaskerProtocol.RESULT_CONDITION_SATISFIED
        val variables = Bundle().apply {
            putString(TaskerVariableEventActivity.EVENT_VARIABLE, text)
        }
        val resultExtras = getResultExtras(true) ?: return
        TaskerProtocol.addVariableBundle(resultExtras, variables)
    }
}
