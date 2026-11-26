/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.taskertext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TaskerVariableActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskerProtocol.ACTION_FIRE_SETTING) {
            return
        }
        val bundle = intent.getBundleExtra(TaskerProtocol.EXTRA_BUNDLE) ?: return
        val text = bundle.getString(TaskerVariablePlugin.TEXT_KEY) ?: return
        if (text.isEmpty()) {
            return
        }
        TaskerVariablePlugin.broadcastText(context, text)
    }
}
