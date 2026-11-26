/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.taskertext

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect_tp.R

/**
 * Tasker opens this activity when the user configures the "Variable Received" event.
 * The event has no settings, so it immediately returns an empty configuration.
 */
class TaskerVariableEventActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val result = Intent().apply {
            putExtra(TaskerProtocol.EXTRA_BUNDLE, Bundle())
            putExtra(TaskerProtocol.EXTRA_STRING_BLURB, getString(R.string.tasker_variable_event_blurb))
        }
        TaskerProtocol.addRelevantVariableList(result, arrayOf(EVENT_VARIABLE))

        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        const val EVENT_VARIABLE = "kt_text"
    }
}
