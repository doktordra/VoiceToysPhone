package org.fossify.phone.dialogs

import android.content.Intent
import android.provider.Settings
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getVisibleContactSources
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.models.contacts.ContactSource
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.DialogRankAccountsBinding
import org.fossify.phone.databinding.ItemRankAccountBinding
import org.fossify.phone.extensions.config

// single place to enable/disable, reorder (rank) and add contact accounts.
// the ranking drives duplicate removal in the contacts list; the checkboxes hide/show whole sources.
class RankAccountsDialog(
    private val activity: SimpleActivity,
    private val callback: () -> Unit
) {
    private val binding = DialogRankAccountsBinding.inflate(activity.layoutInflater)
    private val orderedSources = ArrayList<ContactSource>()
    private val enabledSourceNames = HashSet<String>()

    init {
        ContactsHelper(activity).getContactSources { sources ->
            val distinct = sources.distinctBy { it.name }
            val savedOrder = activity.config.contactSourcesPriority.split(",").filter { it.isNotEmpty() }
            val sorted = distinct.sortedWith(
                compareBy {
                    val index = savedOrder.indexOf(it.name)
                    if (index == -1) Int.MAX_VALUE else index
                }
            )
            val visible = activity.getVisibleContactSources()
            activity.runOnUiThread {
                orderedSources.addAll(sorted)
                orderedSources.forEach { source ->
                    val isVisible = visible.contains(source.name) ||
                        (source.type == SMT_PRIVATE && visible.contains(SMT_PRIVATE))
                    if (isVisible) {
                        enabledSourceNames.add(source.name)
                    }
                }
                renderRows()
                setupAddAccount()
                setupHideDuplicates()
                showDialog()
            }
        }
    }

    private fun renderRows() {
        binding.rankAccountsHolder.removeAllViews()
        val textColor = activity.getProperTextColor()
        val primaryColor = activity.getProperPrimaryColor()
        val backgroundColor = activity.getProperBackgroundColor()
        orderedSources.forEachIndexed { index, source ->
            val rowBinding = ItemRankAccountBinding.inflate(activity.layoutInflater, binding.rankAccountsHolder, false)
            rowBinding.rankAccountCheckbox.text = source.publicName.ifEmpty { source.name }
            rowBinding.rankAccountCheckbox.setColors(textColor, primaryColor, backgroundColor)
            rowBinding.rankAccountCheckbox.isChecked = enabledSourceNames.contains(source.name)
            rowBinding.rankAccountCheckbox.setOnClickListener {
                if (rowBinding.rankAccountCheckbox.isChecked) {
                    enabledSourceNames.add(source.name)
                } else {
                    enabledSourceNames.remove(source.name)
                }
            }
            rowBinding.rankAccountMoveUp.setOnClickListener { moveSource(index, -1) }
            rowBinding.rankAccountMoveDown.setOnClickListener { moveSource(index, 1) }
            binding.rankAccountsHolder.addView(rowBinding.root)
        }
    }

    private fun setupAddAccount() {
        binding.rankAccountsAdd.setOnClickListener {
            try {
                activity.startActivity(Intent(Settings.ACTION_ADD_ACCOUNT))
            } catch (e: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
                } catch (ignored: Exception) {
                    activity.toast(org.fossify.commons.R.string.no_app_found)
                }
            }
        }
    }

    private fun moveSource(index: Int, direction: Int) {
        val target = index + direction
        if (target < 0 || target >= orderedSources.size) {
            return
        }

        val source = orderedSources.removeAt(index)
        orderedSources.add(target, source)
        renderRows()
    }

    private fun setupHideDuplicates() {
        binding.rankAccountsHideDuplicates.setColors(
            activity.getProperTextColor(),
            activity.getProperPrimaryColor(),
            activity.getProperBackgroundColor()
        )
        binding.rankAccountsHideDuplicates.isChecked = activity.config.hideDuplicateContacts
    }

    private fun showDialog() {
        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, org.fossify.phone.R.string.manage_accounts) { alertDialog ->
                    alertDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        saveChanges()
                        alertDialog.dismiss()
                        callback()
                    }
                }
            }
    }

    private fun saveChanges() {
        activity.config.contactSourcesPriority = orderedSources.joinToString(",") { it.name }
        activity.config.hideDuplicateContacts = binding.rankAccountsHideDuplicates.isChecked

        val ignored = orderedSources.filter { !enabledSourceNames.contains(it.name) }.map {
            if (it.type == SMT_PRIVATE) SMT_PRIVATE else it.getFullIdentifier()
        }.toHashSet()
        activity.config.ignoredContactSources = ignored
    }
}
