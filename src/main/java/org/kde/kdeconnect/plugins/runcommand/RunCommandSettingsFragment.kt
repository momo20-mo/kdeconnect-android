/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.runcommand

import android.content.DialogInterface
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R

class RunCommandSettingsFragment : PluginSettingsFragment() {
    private lateinit var commandsCategory: PreferenceCategory
    private lateinit var sendCommandsSwitch: SwitchPreference
    private lateinit var addCommandPreference: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        commandsCategory = requireNotNull(
            findPreference<PreferenceCategory>(getString(R.string.runcommand_local_commands_category_key))
        )
        sendCommandsSwitch = requireNotNull(
            findPreference<SwitchPreference>(getString(R.string.runcommand_send_commands_enabled_key))
        )
        addCommandPreference = requireNotNull(
            findPreference<Preference>(getString(R.string.runcommand_add_command_key))
        )

        sendCommandsSwitch.setOnPreferenceChangeListener { _, newValue ->
            refreshCommands(newValue as Boolean)
            true
        }

        addCommandPreference.setOnPreferenceClickListener {
            showCommandDialog(null)
            true
        }

        refreshCommands(sendCommandsSwitch.isChecked)
    }

    private fun refreshCommands(enabled: Boolean) {
        addCommandPreference.isVisible = enabled

        for (i in commandsCategory.getPreferenceCount() - 1 downTo 0) {
            val preference = commandsCategory.getPreference(i)
            if (preference.key?.startsWith(COMMAND_KEY_PREFIX) == true) {
                commandsCategory.removePreference(preference)
            }
        }

        if (!enabled) return

        for ((id, command) in RunCommandLocalCommands.getCommands(requireContext())) {
            val preference = Preference(requireContext()).apply {
                key = COMMAND_KEY_PREFIX + id
                title = command.name
                summary = command.command
                isPersistent = false
                setOnPreferenceClickListener {
                    showCommandDialog(id)
                    true
                }
            }
            commandsCategory.addPreference(preference)
        }
    }

    private fun showCommandDialog(commandId: String?) {
        val view = layoutInflater.inflate(R.layout.dialog_runcommand_command, null)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.runcommand_command_name_layout)
        val nameInput = view.findViewById<TextInputEditText>(R.id.runcommand_command_name)
        val commandLayout = view.findViewById<TextInputLayout>(R.id.runcommand_command_text_layout)
        val commandInput = view.findViewById<TextInputEditText>(R.id.runcommand_command_text)

        val existing = commandId?.let { RunCommandLocalCommands.findCommand(requireContext(), it) }
        nameInput.setText(existing?.name)
        commandInput.setText(existing?.command)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (existing == null) R.string.runcommand_add_command
                else R.string.runcommand_edit_command
            )
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                if (existing != null) {
                    setNeutralButton(R.string.runcommand_delete_command) { _, _ ->
                        RunCommandLocalCommands.deleteCommand(requireContext(), commandId!!)
                        refreshCommands(sendCommandsSwitch.isChecked)
                    }
                }
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString().orEmpty()
                val command = commandInput.text?.toString().orEmpty()

                var valid = true
                if (name.isBlank()) {
                    nameLayout.error = getString(R.string.runcommand_command_name_required)
                    valid = false
                } else {
                    nameLayout.error = null
                }
                if (command.isBlank()) {
                    commandLayout.error = getString(R.string.runcommand_command_text_required)
                    valid = false
                } else {
                    commandLayout.error = null
                }
                if (!valid) return@setOnClickListener

                if (commandId == null) {
                    RunCommandLocalCommands.addCommand(requireContext(), name, command)
                } else {
                    RunCommandLocalCommands.updateCommand(requireContext(), commandId, name, command)
                }
                refreshCommands(sendCommandsSwitch.isChecked)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    companion object {
        private const val COMMAND_KEY_PREFIX = "runcommand_command_"

        fun newInstance(pluginKey: String, deviceId: String): RunCommandSettingsFragment {
            val fragment = RunCommandSettingsFragment()
            fragment.setArguments(pluginKey, deviceId, R.xml.runcommand_preferences)
            return fragment
        }
    }
}
