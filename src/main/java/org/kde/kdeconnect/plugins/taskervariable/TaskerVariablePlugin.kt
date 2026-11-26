/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.taskertext

import android.content.Context
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class TaskerVariablePlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_tasker_variable)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_tasker_variable_desc)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE) {
            return false
        }
        val text = np.getStringOrNull(TEXT_KEY) ?: return false
        TaskerVariableEventDispatcher.dispatch(context, text)
        return true
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE)

    companion object {
        const val PACKET_TYPE = "kdeconnect.taskertext"
        const val TEXT_KEY = "text"

        fun broadcastText(context: Context, text: String) {
            val localDeviceId = runCatching { DeviceHelper.getDeviceId(context) }.getOrNull()

            KdeConnect.getInstance().devices.values
                .asSequence()
                .filter { it.isPaired }
                .filter { localDeviceId == null || it.deviceId != localDeviceId }
                .forEach { device ->
                    val packet = NetworkPacket(PACKET_TYPE)
                    packet[TEXT_KEY] = text
                    device.sendPacket(packet)
                }
        }
    }
}
