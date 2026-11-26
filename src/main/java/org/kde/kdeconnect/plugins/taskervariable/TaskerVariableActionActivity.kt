/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.taskertext

import android.content.Intent
import android.os.Bundle
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityTaskerVariableActionBinding

class TaskerVariableActionActivity : BaseActivity<ActivityTaskerVariableActionBinding>() {
    private val lazyBinding = lazy { ActivityTaskerVariableActionBinding.inflate(layoutInflater) }

    override val binding: ActivityTaskerVariableActionBinding
        get() = lazyBinding.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = getString(R.string.tasker_action_send_variable)

        val existingBundle = intent.getBundleExtra(TaskerProtocol.EXTRA_BUNDLE)
        binding.text.setText(existingBundle?.getString(TaskerVariablePlugin.TEXT_KEY).orEmpty())

        binding.save.setOnClickListener { saveAndFinish() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun saveAndFinish() {
        val text = binding.text.text?.toString().orEmpty()
        val bundle = Bundle().apply {
            putString(TaskerVariablePlugin.TEXT_KEY, text)
        }
        TaskerProtocol.setVariableReplaceKeys(bundle, arrayOf(TaskerVariablePlugin.TEXT_KEY))

        val result = Intent().apply {
            putExtra(TaskerProtocol.EXTRA_BUNDLE, bundle)
            putExtra(
                TaskerProtocol.EXTRA_STRING_BLURB,
                text.ifBlank { getString(R.string.tasker_action_send_variable) },
            )
        }
        TaskerProtocol.requestNoTimeout(result)

        setResult(RESULT_OK, result)
        finish()
    }
}
