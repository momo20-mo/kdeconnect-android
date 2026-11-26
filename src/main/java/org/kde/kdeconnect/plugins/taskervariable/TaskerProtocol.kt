/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.taskertext

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.security.SecureRandom

/**
 * Constants and helpers for the Tasker/Locale plugin protocol.
 *
 * See https://tasker.joaoapps.com/plugins.html
 */
object TaskerProtocol {
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
    const val ACTION_REQUEST_QUERY = "com.twofortyfouram.locale.intent.action.REQUEST_QUERY"
    const val ACTION_EDIT_EVENT = "net.dinglisch.android.tasker.ACTION_EDIT_EVENT"

    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_STRING_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val EXTRA_ACTIVITY = "com.twofortyfouram.locale.intent.extra.ACTIVITY"

    const val EXTRA_VARIABLES_BUNDLE = "net.dinglisch.android.tasker.extras.VARIABLES"
    const val EXTRA_VARIABLE_REPLACE_KEYS = "net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS"
    const val EXTRA_REQUESTED_TIMEOUT = "net.dinglisch.android.tasker.extras.REQUESTED_TIMEOUT"
    const val EXTRA_RELEVANT_VARIABLES = "net.dinglisch.android.tasker.RELEVANT_VARIABLES"

    const val EXTRA_REQUEST_QUERY_PASS_THROUGH_DATA = "net.dinglisch.android.tasker.extras.PASS_THROUGH_DATA"
    const val PASS_THROUGH_BUNDLE_MESSAGE_ID_KEY = "net.dinglisch.android.tasker.MESSAGE_ID"

    const val RESULT_CONDITION_SATISFIED = 16
    const val RESULT_CONDITION_UNSATISFIED = 17
    const val RESULT_CONDITION_UNKNOWN = 18

    const val REQUESTED_TIMEOUT_MS_NONE = 0

    fun setVariableReplaceKeys(bundle: Bundle, keys: Array<String>) {
        bundle.putString(EXTRA_VARIABLE_REPLACE_KEYS, keys.joinToString(" "))
    }

    fun requestNoTimeout(intent: Intent) {
        intent.putExtra(EXTRA_REQUESTED_TIMEOUT, REQUESTED_TIMEOUT_MS_NONE)
    }

    fun addRelevantVariableList(intent: Intent, variables: Array<String>) {
        intent.putExtra(EXTRA_RELEVANT_VARIABLES, variables)
    }

    fun addVariableBundle(resultExtras: Bundle, variables: Bundle) {
        resultExtras.putBundle(EXTRA_VARIABLES_BUNDLE, variables)
    }

    /**
     * Ask the host to query our event plugin. The host may expose either a service or a
     * broadcast receiver for [ACTION_REQUEST_QUERY], so try both.
     */
    fun sendRequestQuery(context: Context, intent: Intent) {
        try {
            val packageManager = context.packageManager
            val handledPackages = mutableSetOf<String>()

            packageManager.queryIntentServices(Intent(ACTION_REQUEST_QUERY), 0).forEach { resolveInfo ->
                val serviceInfo = resolveInfo.serviceInfo ?: return@forEach
                val explicitIntent = Intent(intent)
                    .setComponent(ComponentName(serviceInfo.packageName, serviceInfo.name))
                try {
                    context.startService(explicitIntent)
                    handledPackages.add(serviceInfo.packageName)
                } catch (_: Exception) {
                    // Fall back to the broadcast below.
                }
            }

            val receivers = packageManager.queryBroadcastReceivers(Intent(ACTION_REQUEST_QUERY), 0)
            if (receivers.isEmpty()) {
                context.sendBroadcast(intent)
                return
            }
            receivers.forEach { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@forEach
                if (activityInfo.packageName in handledPackages) {
                    return@forEach
                }
                val explicitIntent = Intent(intent)
                    .setComponent(ComponentName(activityInfo.packageName, activityInfo.name))
                context.sendBroadcast(explicitIntent)
            }
        } catch (_: Exception) {
            context.sendBroadcast(intent)
        }
    }

    object Event {
        private val random = SecureRandom()

        fun addPassThroughMessageID(intent: Intent): Int {
            val data = intent.getBundleExtra(EXTRA_REQUEST_QUERY_PASS_THROUGH_DATA) ?: Bundle().also {
                intent.putExtra(EXTRA_REQUEST_QUERY_PASS_THROUGH_DATA, it)
            }
            val messageId = random.nextInt(Int.MAX_VALUE)
            data.putInt(PASS_THROUGH_BUNDLE_MESSAGE_ID_KEY, messageId)
            return messageId
        }

        fun retrievePassThroughMessageID(intent: Intent): Int {
            val data = intent.getBundleExtra(EXTRA_REQUEST_QUERY_PASS_THROUGH_DATA) ?: return -1
            return data.getInt(PASS_THROUGH_BUNDLE_MESSAGE_ID_KEY, -1)
        }
    }
}
