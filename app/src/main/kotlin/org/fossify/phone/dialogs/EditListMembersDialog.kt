package org.fossify.phone.dialogs

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.addContactsToGroup
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.removeContactsFromGroup
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.databinding.DialogEditListMembersBinding
import org.fossify.phone.databinding.ItemListMemberCheckboxBinding

// lets the user add or remove contacts from a specific contact list (group) without leaving the app
class EditListMembersDialog(
    val activity: BaseSimpleActivity,
    private val allContacts: ArrayList<Contact>,
    private val groupId: Long,
    private val groupTitle: String,
    val callback: () -> Unit
) {
    private val binding = DialogEditListMembersBinding.inflate(activity.layoutInflater)
    private val selectedIds = HashSet<Int>()
    private val initialIds = HashSet<Int>()

    init {
        allContacts.forEach { contact ->
            if (contact.groups.any { it.id == groupId }) {
                selectedIds.add(contact.id)
                initialIds.add(contact.id)
            }
        }

        binding.membersSearch.onTextChangeListener { renderContacts(it) }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> dialogConfirmed() }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                val title = String.format(activity.getString(R.string.edit_list_members_title), groupTitle)
                activity.setupDialogStuff(binding.root, this, titleText = title)
            }

        renderContacts("")
    }

    private fun renderContacts(query: String) {
        binding.membersHolder.removeAllViews()
        val normalizedQuery = query.trim()
        val filtered = if (normalizedQuery.isEmpty()) {
            allContacts
        } else {
            allContacts.filter { it.getNameToDisplay().contains(normalizedQuery, true) }
        }

        val textColor = activity.getProperTextColor()
        val primaryColor = activity.getProperPrimaryColor()
        val backgroundColor = activity.getProperBackgroundColor()

        filtered.forEach { contact ->
            val itemBinding = ItemListMemberCheckboxBinding.inflate(activity.layoutInflater, binding.membersHolder, false)
            itemBinding.memberCheckbox.apply {
                text = contact.getNameToDisplay()
                setColors(textColor, primaryColor, backgroundColor)
                isChecked = selectedIds.contains(contact.id)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selectedIds.add(contact.id)
                    } else {
                        selectedIds.remove(contact.id)
                    }
                }
            }
            itemBinding.memberCheckboxHolder.setOnClickListener { itemBinding.memberCheckbox.toggle() }
            binding.membersHolder.addView(itemBinding.root)
        }
    }

    private fun dialogConfirmed() {
        val added = allContacts.filter { selectedIds.contains(it.id) && !initialIds.contains(it.id) } as ArrayList<Contact>
        val removed = allContacts.filter { !selectedIds.contains(it.id) && initialIds.contains(it.id) } as ArrayList<Contact>
        if (added.isEmpty() && removed.isEmpty()) {
            return
        }

        ensureBackgroundThread {
            if (added.isNotEmpty()) {
                activity.addContactsToGroup(added, groupId)
            }
            if (removed.isNotEmpty()) {
                activity.removeContactsFromGroup(removed, groupId)
            }
            activity.runOnUiThread {
                callback()
            }
        }
    }
}
