package org.fossify.phone.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.CallLog
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.GridLayout
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.dialogs.ColorPickerDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.addContactsToGroup
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.getColorStateList
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.extensions.removeContactsFromGroup
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.getProperText
import org.fossify.commons.helpers.letterBackgroundColors
import org.fossify.commons.models.contacts.Contact
import org.fossify.commons.models.contacts.Group
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MyLinearLayoutManager
import org.fossify.commons.views.MyTextView
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.ContactsAdapter
import org.fossify.phone.databinding.FragmentContactsBinding
import org.fossify.phone.databinding.FragmentLettersLayoutBinding
import org.fossify.phone.databinding.ItemCombinedContactBinding
import org.fossify.phone.databinding.DialogListNameBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.launchCreateNewContactIntent
import org.fossify.phone.extensions.setupWithContacts
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.ALL_CONTACTS_GROUP_ID
import org.fossify.phone.helpers.AvatarHelper
import org.fossify.phone.helpers.FAVORITES_GROUP_ID
import org.fossify.phone.helpers.VIEW_TYPE_COMBINED
import org.fossify.phone.interfaces.RefreshItemsListener
import kotlin.math.abs

class ContactsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LettersInnerBinding>(context, attributeSet),
    RefreshItemsListener {
    private lateinit var binding: FragmentLettersLayoutBinding
    private var allContacts = ArrayList<Contact>()
    private var groups = ArrayList<Group>()
    private var recencyMap: HashMap<String, Long>? = null

    // when editing a list's members, the list turns into a checkable picker over all contacts
    private var isEditingListMembers = false
    private var editingListGroup: Group? = null
    private val editingCheckedKeys = HashSet<Int>()

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentLettersLayoutBinding.bind(FragmentContactsBinding.bind(this).contactsFragment)
        innerBinding = LettersInnerBinding(binding)
    }

    override fun setupFragment() {
        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.no_contacts_found
        } else {
            R.string.could_not_access_contacts
        }

        binding.fragmentPlaceholder.text = context.getString(placeholderResId)

        val placeholderActionResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.create_new_contact
        } else {
            R.string.request_access
        }

        binding.fragmentPlaceholder2.apply {
            text = context.getString(placeholderActionResId)
            underlineText()
            setOnClickListener {
                if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
                    activity?.launchCreateNewContactIntent()
                } else {
                    requestReadContactsPermission()
                }
            }
        }

        setupContactsHeader()
        setupFrequencyIndicator()
    }

    // called by MainActivity when the user swipes horizontally anywhere on the Contacts page;
    // the ViewPager's own paging swipe is off, so this moves between contact lists instead
    fun onHorizontalSwipe(forward: Boolean) {
        switchToAdjacentList(forward)
    }

    // cycles through All contacts -> Favorites -> each list, wrapping around at the ends
    private fun switchToAdjacentList(forward: Boolean) {
        val ids = buildList {
            add(ALL_CONTACTS_GROUP_ID)
            if (!context.config.favoritesChipHidden) {
                add(FAVORITES_GROUP_ID)
            }
            orderedGroups().forEach { group -> group.id?.let { add(it) } }
        }
        if (ids.size < 2) {
            return
        }

        if (isEditingListMembers) {
            exitEditListMembersMode()
        }

        val currentIndex = ids.indexOf(context.config.selectedContactGroupId).coerceAtLeast(0)
        val nextIndex = if (forward) {
            (currentIndex + 1) % ids.size
        } else {
            (currentIndex - 1 + ids.size) % ids.size
        }

        context.config.selectedContactGroupId = ids[nextIndex]
        setupFilterChips()
        gotContacts(getFilteredContacts())
    }

    // in Frequent mode the A-Z scroller is meaningless, so the tapering wedge doubles as a
    // proportional scrollbar: dragging it scrolls the list from most to least contacted
    @SuppressLint("ClickableViewAccessibility")
    private fun setupFrequencyIndicator() {
        binding.frequencyIndicator.setOnTouchListener { view, event ->
            val itemCount = binding.fragmentList.adapter?.itemCount ?: 0
            if (itemCount == 0) {
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val usableHeight = (view.height - view.paddingTop - view.paddingBottom).coerceAtLeast(1)
                    val fraction = ((event.y - view.paddingTop) / usableHeight).coerceIn(0f, 1f)
                    val target = (fraction * (itemCount - 1)).toInt()
                    (binding.fragmentList.layoutManager as? MyLinearLayoutManager)?.scrollToPositionWithOffset(target, 0)
                    true
                }

                else -> false
            }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        binding.apply {
            (fragmentList.adapter as? MyRecyclerViewAdapter)?.updateTextColor(textColor)
            fragmentPlaceholder.setTextColor(textColor)
            fragmentPlaceholder2.setTextColor(properPrimaryColor)

            letterFastscroller.textColor = textColor.getColorStateList()
            letterFastscroller.pressedTextColor = properPrimaryColor
            letterFastscrollerThumb.setupWithFastScroller(letterFastscroller)
            letterFastscrollerThumb.textColor = properPrimaryColor.getContrastColor()
            letterFastscrollerThumb.thumbColor = properPrimaryColor.getColorStateList()
        }

        binding.addToListIcon.applyColorFilter(textColor)
        binding.addToListLabel.setTextColor(textColor)
        binding.contactsHeaderFrame.background?.mutate()?.applyColorFilter(textColor.adjustAlpha(0.2f))
        binding.frequencyIndicator.applyColorFilter(properPrimaryColor.adjustAlpha(0.5f))
        updateOrderToggle()
        updateViewTypeToggle()
        setupFilterChips()
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        val privateCursor = context?.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            allContacts = contacts

            if (SMT_PRIVATE !in context.baseConfig.ignoredContactSources) {
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    allContacts.addAll(privateContacts)
                    allContacts.sort()
                }
            }

            ContactsHelper(context).getStoredGroups { storedGroups ->
                groups = storedGroups
                activity?.runOnUiThread {
                    setupFilterChips()
                    if (context.config.contactsSortedByRecents && recencyMap == null) {
                        loadRecencyMap {
                            gotContacts(getFilteredContacts())
                            callback?.invoke()
                        }
                    } else {
                        gotContacts(getFilteredContacts())
                        callback?.invoke()
                    }
                }
            }
        }
    }

    // returns the contacts to display after applying the selected favorites/list filter and sorting
    private fun getFilteredContacts(): ArrayList<Contact> {
        // while picking members, offer the full contact list (still honouring the current sort order)
        if (isEditingListMembers) {
            val all = dedupBySourcePriority(ArrayList(allContacts))
            return if (context.config.contactsSortedByRecents && recencyMap != null) {
                sortByRecents(all)
            } else {
                all
            }
        }

        val groupId = context.config.selectedContactGroupId
        val filtered = when (groupId) {
            ALL_CONTACTS_GROUP_ID -> ArrayList(allContacts)
            FAVORITES_GROUP_ID -> allContacts.filter { it.starred == 1 } as ArrayList<Contact>
            else -> allContacts.filter { contact -> contact.groups.any { it.id == groupId } } as ArrayList<Contact>
        }

        val deduped = dedupBySourcePriority(filtered)
        return if (context.config.contactsSortedByRecents && recencyMap != null) {
            sortByRecents(deduped)
        } else {
            deduped
        }
    }

    // when the same person exists in several accounts, keep only the copy from the highest-ranked source
    // (see the manual account ranking). contacts are considered the same when they share a phone number.
    private fun dedupBySourcePriority(contacts: ArrayList<Contact>): ArrayList<Contact> {
        if (!context.config.hideDuplicateContacts) {
            return contacts
        }

        val order = context.config.contactSourcesPriority.split(",").filter { it.isNotEmpty() }
        if (contacts.size < 2) {
            return contacts
        }

        fun priorityOf(contact: Contact): Int {
            val index = order.indexOf(contact.source)
            return if (index == -1) Int.MAX_VALUE else index
        }

        // walk contacts best-source-first, dropping any lower-ranked copy whose numbers were already claimed
        val byPriority = contacts.sortedWith(compareBy({ priorityOf(it) }))
        val claimedNumbers = HashSet<String>()
        val removedRawIds = HashSet<Int>()
        byPriority.forEach { contact ->
            val numbers = contact.phoneNumbers
                .map { comparableNumber(it.normalizedNumber.ifEmpty { it.value }) }
                .filter { it.isNotEmpty() }
            if (numbers.isNotEmpty() && numbers.all { claimedNumbers.contains(it) }) {
                removedRawIds.add(contact.rawId)
            } else {
                claimedNumbers.addAll(numbers)
            }
        }

        if (removedRawIds.isEmpty()) {
            return contacts
        }

        // preserve the caller's ordering, just drop the superseded duplicates
        return ArrayList(contacts.filterNot { removedRawIds.contains(it.rawId) })
    }

    // re-applies the filter / view type / sorting to the already loaded contacts without re-querying the provider
    fun refreshDisplayedContacts() {
        activity?.runOnUiThread {
            if (context.config.contactsSortedByRecents && recencyMap == null) {
                loadRecencyMap { gotContacts(getFilteredContacts()) }
            } else {
                gotContacts(getFilteredContacts())
            }
        }
    }

    private fun gotContacts(contacts: ArrayList<Contact>) {
        setupLetterFastScroller(contacts)
        if (contacts.isEmpty()) {
            binding.apply {
                fragmentPlaceholder.beVisible()
                fragmentPlaceholder2.beVisible()
                fragmentList.beGone()
            }
        } else {
            binding.apply {
                fragmentPlaceholder.beGone()
                fragmentPlaceholder2.beGone()
                fragmentList.beVisible()
            }

            updateListAdapter(contacts)
        }

        updateCombinedContacts(contacts)
        // recompute after layout so canScrollVertically reflects the freshly populated list
        binding.fragmentList.post { updateAddToListButton() }
    }

    private fun updateListAdapter(contacts: ArrayList<Contact>) {
        val viewType = effectiveViewType()
        setViewType(viewType)

        val currAdapter = binding.fragmentList.adapter as ContactsAdapter?
        if (currAdapter == null) {
            val editing = isEditingListMembers
            val detailsClick: ((Contact) -> Unit)? =
                if (editing) null else { contact -> activity?.startContactDetailsIntent(contact) }
            val profileClick: ((Any) -> Unit)? =
                if (editing) null else { contact -> activity?.startContactDetailsIntent(contact as Contact) }

            ContactsAdapter(
                activity = activity as SimpleActivity,
                contacts = contacts,
                recyclerView = binding.fragmentList,
                viewType = adapterViewType(viewType),
                refreshItemsListener = this,
                itemClick = {
                    if (isEditingListMembers) {
                        toggleListMember(it as Contact)
                    } else {
                        (activity as? SimpleActivity)?.startCallWithConfirmationCheck(it as Contact)
                    }
                },
                itemLongClick = detailsClick,
                profileIconClick = profileClick,
                checkableKeys = if (editing) editingCheckedKeys else null
            ).apply {
                binding.fragmentList.adapter = this
                onSpanCountListener = { newSpanCount ->
                    context.config.contactsGridColumnCount = newSpanCount
                }
            }

            if (context.areSystemAnimationsEnabled) {
                binding.fragmentList.scheduleLayoutAnimation()
            }
        } else {
            currAdapter.viewType = adapterViewType(viewType)
            currAdapter.updateItems(contacts)
        }
    }

    fun columnCountChanged() {
        (binding.fragmentList.layoutManager as? MyGridLayoutManager)?.spanCount = context!!.config.contactsGridColumnCount
        binding.fragmentList.adapter?.apply {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    private fun setViewType(viewType: Int) {
        val spanCount = context.config.contactsGridColumnCount
        val layoutManager = if (viewType == VIEW_TYPE_GRID) {
            MyGridLayoutManager(context, spanCount)
        } else {
            MyLinearLayoutManager(context)
        }
        binding.fragmentList.layoutManager = layoutManager
        updateFastScrollerVisibility()
    }

    // while editing a list's members we always render a plain list (no grid, no combined window)
    private fun effectiveViewType(): Int {
        return if (isEditingListMembers) VIEW_TYPE_LIST else context.config.contactsViewType
    }

    // the A-Z fastscroller only makes sense for an alphabetical list; when sorting by frequency we
    // instead show a discreet tapering wedge (wide = most contacted, narrow = least) as a hint
    private fun updateFastScrollerVisibility() {
        val isGrid = effectiveViewType() == VIEW_TYPE_GRID
        val isFrequent = context.config.contactsSortedByRecents
        val showLetters = !isGrid && !isFrequent
        binding.letterFastscroller.beVisibleIf(showLetters)
        binding.letterFastscrollerThumb.beVisibleIf(showLetters)
        binding.frequencyIndicator.beVisibleIf(!isGrid && isFrequent)
    }

    // adapter only understands GRID or LIST, so COMBINED is rendered as a plain LIST
    private fun adapterViewType(viewType: Int) = if (viewType == VIEW_TYPE_GRID) VIEW_TYPE_GRID else VIEW_TYPE_LIST

    // in COMBINED mode show a grid of the 8 most contacted people at the bottom of the screen
    private fun updateCombinedContacts(contacts: ArrayList<Contact>) {
        val holder = binding.combinedContactsHolder
        if (isEditingListMembers || context.config.contactsViewType != VIEW_TYPE_COMBINED || contacts.isEmpty()) {
            holder.beGone()
            return
        }

        if (recencyMap == null) {
            loadRecencyMap { updateCombinedContacts(contacts) }
            return
        }

        val topContacts = sortByRecents(contacts).take(8)
        holder.removeAllViews()
        holder.background?.mutate()?.applyColorFilter(listColor(context.config.selectedContactGroupId))
        holder.columnCount = 4
        topContacts.forEach { contact ->
            val itemBinding = ItemCombinedContactBinding.inflate(LayoutInflater.from(context), holder, false)
            val displayName = contact.getNameToDisplay()
            val hasPhoto = contact.photoUri.isNotEmpty()
            // same rule as the main grid: no photo -> first name on the tile, surname on the label below;
            // with a photo -> show the photo and keep the full name below
            val tileText = if (hasPhoto) null else AvatarHelper.firstNamePart(displayName)
            val label = if (hasPhoto) displayName else AvatarHelper.surnamePart(displayName).ifEmpty { displayName }
            itemBinding.combinedContactName.text = label
            itemBinding.combinedContactName.setTextColor(context.getProperTextColor())
            AvatarHelper(context).loadContactAvatar(contact.photoUri, itemBinding.combinedContactImage, displayName, tileText, tileWrapThreshold = 6)
            itemBinding.root.setOnClickListener {
                (activity as? SimpleActivity)?.startCallWithConfirmationCheck(contact)
            }

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            holder.addView(itemBinding.root, params)
        }
        holder.beVisible()
    }

    // shows the in-fragment header (order/view-type toggles + list filter chips) and wires their actions
    private fun setupContactsHeader() {
        binding.contactsHeader.beVisible()
        binding.filterChipsScroll.beVisible()

        binding.contactsOrderToggle.setOnClickListener {
            val enabling = !context.config.contactsSortedByRecents
            if (enabling && !context.hasPermission(PERMISSION_READ_CALL_LOG)) {
                activity?.handlePermission(PERMISSION_READ_CALL_LOG) { toggleContactsOrder() }
            } else {
                toggleContactsOrder()
            }
        }

        binding.contactsViewTypeToggle.setOnClickListener {
            context.config.contactsViewType = when (context.config.contactsViewType) {
                VIEW_TYPE_LIST -> VIEW_TYPE_GRID
                VIEW_TYPE_GRID -> VIEW_TYPE_COMBINED
                else -> VIEW_TYPE_LIST
            }
            updateViewTypeToggle()
            gotContacts(getFilteredContacts())
        }

        binding.addToListButton.setOnClickListener {
            editSelectedListMembers()
        }

        // reveal/hide the add-to-list button as the user scrolls so it only appears at the bottom of the list
        binding.fragmentList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateAddToListButton()
            }
        })

        updateOrderToggle()
        updateViewTypeToggle()
    }

    // flips between alphabetical and "most frequently called" order; always rebuilds the call
    // frequency map when enabling so freshly granted permission or new calls are reflected
    private fun toggleContactsOrder() {
        val newValue = !context.config.contactsSortedByRecents
        context.config.contactsSortedByRecents = newValue
        updateOrderToggle()
        if (newValue) {
            loadRecencyMap { gotContacts(getFilteredContacts()) }
        } else {
            gotContacts(getFilteredContacts())
        }
    }

    private fun updateOrderToggle() {
        val active = context.config.contactsSortedByRecents
        binding.contactsOrderIcon.setImageResource(if (active) R.drawable.ic_clock_filled_vector else R.drawable.ic_clock_vector)
        binding.contactsOrderLabel.text = context.getString(if (active) R.string.sort_by_recent_communication else R.string.sort_alphabetically)
        val color = if (active) context.getProperPrimaryColor() else context.getProperTextColor()
        binding.contactsOrderIcon.applyColorFilter(color)
        binding.contactsOrderLabel.setTextColor(color)
    }

    private fun updateViewTypeToggle() {
        val viewType = context.config.contactsViewType
        val iconRes: Int
        val labelRes: Int
        when (viewType) {
            VIEW_TYPE_GRID -> {
                iconRes = R.drawable.ic_view_grid_vector
                labelRes = R.string.grid_view
            }
            VIEW_TYPE_COMBINED -> {
                iconRes = R.drawable.ic_view_grid_vector
                labelRes = R.string.combined_view
            }
            else -> {
                iconRes = R.drawable.ic_view_list_vector
                labelRes = org.fossify.commons.R.string.list_view
            }
        }
        binding.contactsViewTypeIcon.setImageResource(iconRes)
        binding.contactsViewTypeLabel.text = context.getString(labelRes)
        val color = context.getProperTextColor()
        binding.contactsViewTypeIcon.applyColorFilter(color)
        binding.contactsViewTypeLabel.setTextColor(color)
    }

    // rebuilds the horizontal filter chip row: Favorites, then one chip per contact list.
    // no chip selected (ALL_CONTACTS_GROUP_ID) means all contacts are shown
    private fun setupFilterChips() {
        binding.filterChipsHolder.removeAllViews()
        val selectedId = context.config.selectedContactGroupId
        if (!context.config.favoritesChipHidden) {
            val favLabel = context.config.favoritesChipLabel.ifEmpty { context.getString(org.fossify.commons.R.string.favorites) }
            addFilterChip(favLabel, FAVORITES_GROUP_ID, selectedId, favorites = true)
        }
        orderedGroups().forEach { group ->
            val id = group.id ?: return@forEach
            addFilterChip(group.title, id, selectedId, group)
        }
        addNewListChip()
        updateAddToListButton()
    }

    // lets the activity refresh the chip row after a list icon is picked
    fun refreshFilterChips() {
        activity?.runOnUiThread { setupFilterChips() }
    }

    // applies the user's custom left-to-right list order (stored in config) on top of the loaded groups
    private fun orderedGroups(): List<Group> {
        val order = context.config.contactListOrder
            .split(",")
            .mapNotNull { it.toLongOrNull() }
        if (order.isEmpty()) {
            return groups
        }

        return groups.sortedBy { group ->
            val index = order.indexOf(group.id)
            if (index >= 0) index else order.size + groups.indexOf(group)
        }
    }

    // moves a list one position left (-1) or right (+1) among the filter chips and persists the order
    private fun moveList(group: Group, direction: Int) {
        val current = orderedGroups().mapNotNull { it.id }.toMutableList()
        val from = current.indexOf(group.id)
        if (from < 0) {
            return
        }

        val to = from + direction
        if (to < 0 || to >= current.size) {
            return
        }

        val moved = current.removeAt(from)
        current.add(to, moved)
        context.config.contactListOrder = current.joinToString(",")
        setupFilterChips()
    }

    private fun addFilterChip(label: String, id: Long, selectedId: Long, group: Group? = null, favorites: Boolean = false) {
        val chip = LayoutInflater.from(context).inflate(R.layout.item_filter_chip, binding.filterChipsHolder, false) as MyTextView
        chip.text = label
        styleChip(chip, id == selectedId, listColor(id))
        applyChipIcon(chip, id)
        chip.setOnClickListener {
            // switching lists cancels an in-progress member edit
            if (isEditingListMembers) {
                exitEditListMembersMode()
            }
            // tapping the active chip clears the filter and shows all contacts
            val newId = if (context.config.selectedContactGroupId == id) ALL_CONTACTS_GROUP_ID else id
            context.config.selectedContactGroupId = newId
            setupFilterChips()
            gotContacts(getFilteredContacts())
        }

        // long-pressing a user list chip offers renaming / deleting it; the Favorites chip gets its own menu
        when {
            group != null -> chip.setOnLongClickListener {
                showListOptions(chip, group)
                true
            }

            favorites -> chip.setOnLongClickListener {
                showFavoritesOptions(chip)
                true
            }
        }
        binding.filterChipsHolder.addView(chip)
    }

    // draws the custom per-list image (icon-only) on the chip, or clears it back to plain text
    private fun applyChipIcon(chip: MyTextView, id: Long) {
        val iconUri = context.config.getListIcon(id)
        if (iconUri.isEmpty()) {
            chip.setCompoundDrawables(null, null, null, null)
            return
        }

        // icon-only chips become a centered square so the logo sits dead-center instead of hugging the edge
        val density = chip.resources.displayMetrics.density
        val sizePx = (density * 38).toInt()
        val padPx = (density * 6).toInt()
        val squarePx = sizePx + padPx * 2
        chip.text = ""
        chip.gravity = Gravity.CENTER
        chip.compoundDrawablePadding = 0
        chip.setPadding(padPx, padPx, padPx, padPx)
        chip.minWidth = 0
        chip.minHeight = 0
        chip.layoutParams = chip.layoutParams.apply {
            width = squarePx
            height = squarePx
        }
        Glide.with(context)
            .asBitmap()
            .load(Uri.parse(iconUri))
            .circleCrop()
            .override(sizePx, sizePx)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val drawable: Drawable = BitmapDrawable(chip.resources, resource)
                    drawable.setBounds(0, 0, sizePx, sizePx)
                    // use the top drawable so it centers horizontally regardless of the (empty) text
                    chip.setCompoundDrawables(null, drawable, null, null)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    chip.setCompoundDrawables(null, null, null, null)
                }
            })
    }

    // trailing "+" chip that creates a new contact list; long-press brings a hidden Favorites chip back
    private fun addNewListChip() {
        val chip = LayoutInflater.from(context).inflate(R.layout.item_filter_chip, binding.filterChipsHolder, false) as MyTextView
        chip.text = "+"
        styleChip(chip, false)
        chip.setOnClickListener { showListNameDialog(null) }
        if (context.config.favoritesChipHidden) {
            chip.setOnLongClickListener {
                PopupMenu(chip.context, chip).apply {
                    menu.add(0, 1, 0, R.string.show_favorites)
                    setOnMenuItemClickListener {
                        context.config.favoritesChipHidden = false
                        setupFilterChips()
                        true
                    }
                    show()
                }
                true
            }
        }
        binding.filterChipsHolder.addView(chip)
    }

    private fun showFavoritesOptions(anchor: MyTextView) {
        PopupMenu(anchor.context, anchor).apply {
            val hasIcon = context.config.getListIcon(FAVORITES_GROUP_ID).isNotEmpty()
            val hasColor = context.config.getListColor(FAVORITES_GROUP_ID) != null
            menu.add(0, 1, 0, R.string.rename_list)
            menu.add(0, 2, 1, R.string.set_list_icon)
            if (hasIcon) {
                menu.add(0, 3, 2, R.string.remove_list_icon)
            }
            menu.add(0, 8, 3, R.string.set_list_color)
            if (hasColor) {
                menu.add(0, 9, 4, R.string.remove_list_color)
            }
            menu.add(0, 4, 5, R.string.hide_favorites)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showFavoritesRenameDialog()
                    2 -> (activity as? MainActivity)?.pickListIcon(FAVORITES_GROUP_ID)
                    3 -> {
                        context.config.setListIcon(FAVORITES_GROUP_ID, null)
                        setupFilterChips()
                    }

                    8 -> pickListColor(FAVORITES_GROUP_ID)
                    9 -> {
                        context.config.setListColor(FAVORITES_GROUP_ID, null)
                        setupFilterChips()
                        gotContacts(getFilteredContacts())
                    }

                    4 -> {
                        context.config.favoritesChipHidden = true
                        if (context.config.selectedContactGroupId == FAVORITES_GROUP_ID) {
                            context.config.selectedContactGroupId = ALL_CONTACTS_GROUP_ID
                        }
                        setupFilterChips()
                        gotContacts(getFilteredContacts())
                    }
                }
                true
            }
            show()
        }
    }

    // renames the Favorites chip locally (e.g. to an emoji); an empty value restores the default name
    private fun showFavoritesRenameDialog() {
        val activity = activity as? SimpleActivity ?: return
        val dialogBinding = DialogListNameBinding.inflate(activity.layoutInflater)
        dialogBinding.listNameValue.setText(context.config.favoritesChipLabel)

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(dialogBinding.root, this, R.string.rename_list) { alertDialog ->
                    alertDialog.showKeyboard(dialogBinding.listNameValue)
                    alertDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        context.config.favoritesChipLabel = dialogBinding.listNameValue.value
                        setupFilterChips()
                        alertDialog.dismiss()
                    }
                }
            }
    }

    private fun showListOptions(anchor: MyTextView, group: Group) {
        val groupId = group.id ?: return
        PopupMenu(anchor.context, anchor).apply {
            val hasIcon = context.config.getListIcon(groupId).isNotEmpty()
            val hasColor = context.config.getListColor(groupId) != null
            menu.add(0, 1, 0, R.string.edit_list_members)
            menu.add(0, 2, 1, R.string.rename_list)
            menu.add(0, 3, 2, R.string.set_list_icon)
            if (hasIcon) {
                menu.add(0, 4, 3, R.string.remove_list_icon)
            }
            menu.add(0, 8, 4, R.string.set_list_color)
            if (hasColor) {
                menu.add(0, 9, 5, R.string.remove_list_color)
            }
            menu.add(0, 5, 6, R.string.delete_list)
            menu.add(0, 6, 7, R.string.move_list_left)
            menu.add(0, 7, 8, R.string.move_list_right)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> withWritePermission { enterEditListMembersMode(group) }
                    2 -> showListNameDialog(group)
                    3 -> (activity as? MainActivity)?.pickListIcon(groupId)
                    4 -> {
                        context.config.setListIcon(groupId, null)
                        setupFilterChips()
                    }

                    8 -> pickListColor(groupId)
                    9 -> {
                        context.config.setListColor(groupId, null)
                        setupFilterChips()
                        gotContacts(getFilteredContacts())
                    }

                    5 -> confirmDeleteList(group)
                    6 -> moveList(group, -1)
                    7 -> moveList(group, 1)
                }
                true
            }
            show()
        }
    }

    // lets the user pick a custom accent colour for a section; refreshes the chips and combined frame
    private fun pickListColor(id: Long) {
        val activity = activity as? SimpleActivity ?: return
        ColorPickerDialog(activity, listColor(id)) { wasPositivePressed, color ->
            if (wasPositivePressed) {
                context.config.setListColor(id, color)
                setupFilterChips()
                gotContacts(getFilteredContacts())
            }
        }
    }

    // shows the create/rename dialog; when group is null a new list is created
    private fun showListNameDialog(group: Group?) {
        val activity = activity as? SimpleActivity ?: return
        val dialogBinding = DialogListNameBinding.inflate(activity.layoutInflater)
        dialogBinding.listNameValue.setText(group?.title ?: "")
        val titleRes = if (group == null) R.string.new_list else R.string.rename_list

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(dialogBinding.root, this, titleRes) { alertDialog ->
                    alertDialog.showKeyboard(dialogBinding.listNameValue)
                    alertDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newName = dialogBinding.listNameValue.value
                        if (newName.isEmpty()) {
                            activity.toast(org.fossify.commons.R.string.empty_name)
                            return@setOnClickListener
                        }

                        if (group == null) {
                            createList(newName)
                        } else {
                            renameList(group, newName)
                        }
                        alertDialog.dismiss()
                    }
                }
            }
    }

    private fun createList(name: String) {
        withWritePermission {
            ensureBackgroundThread {
                ContactsHelper(context).createNewGroup(name, "", "")
                reloadGroups()
            }
        }
    }

    private fun renameList(group: Group, newName: String) {
        withWritePermission {
            ensureBackgroundThread {
                group.title = newName
                group.contactsCount = 0
                ContactsHelper(context).renameGroup(group)
                reloadGroups()
            }
        }
    }

    private fun confirmDeleteList(group: Group) {
        val activity = activity as? SimpleActivity ?: return
        val message = String.format(context.getString(R.string.delete_list_confirmation), group.title)
        ConfirmationDialog(activity, message) {
            withWritePermission {
                ensureBackgroundThread {
                    val id = group.id
                    if (id != null) {
                        ContactsHelper(context).deleteGroup(id)
                        if (context.config.selectedContactGroupId == id) {
                            context.config.selectedContactGroupId = ALL_CONTACTS_GROUP_ID
                        }
                    }
                    reloadGroups()
                }
            }
        }
    }

    // turns the contact list into a checkable picker for the currently selected list's members
    private fun editSelectedListMembers() {
        val groupId = context.config.selectedContactGroupId
        if (groupId == ALL_CONTACTS_GROUP_ID || groupId == FAVORITES_GROUP_ID) {
            return
        }

        val group = groups.firstOrNull { it.id == groupId } ?: return
        withWritePermission {
            enterEditListMembersMode(group)
        }
    }

    private fun enterEditListMembersMode(group: Group) {
        isEditingListMembers = true
        editingListGroup = group
        editingCheckedKeys.clear()
        // a leftover search query would filter the pickable contacts, so clear it as we start editing
        (activity as? MainActivity)?.closeSearchBar()
        allContacts.forEach { contact ->
            if (contact.groups.any { it.id == group.id }) {
                editingCheckedKeys.add(contact.rawId)
            }
        }

        // repurpose the add-to-list button as a "Done" confirm button
        binding.addToListButton.beVisible()
        binding.addToListLabel.setText(org.fossify.commons.R.string.ok)
        binding.addToListIcon.setImageResource(org.fossify.commons.R.drawable.ic_check_vector)
        binding.addToListIcon.applyColorFilter(context.getProperTextColor())
        binding.addToListButton.setOnClickListener { saveEditedListMembers() }

        binding.fragmentList.adapter = null
        gotContacts(getFilteredContacts())
        activity?.toast(R.string.tap_contacts_to_toggle_membership)
    }

    private fun toggleListMember(contact: Contact) {
        if (editingCheckedKeys.contains(contact.rawId)) {
            editingCheckedKeys.remove(contact.rawId)
        } else {
            editingCheckedKeys.add(contact.rawId)
        }

        val adapter = binding.fragmentList.adapter as? ContactsAdapter ?: return
        val position = adapter.contacts.indexOfFirst { it.rawId == contact.rawId }
        if (position >= 0) {
            adapter.notifyItemChanged(position)
        }
    }

    private fun saveEditedListMembers() {
        val group = editingListGroup
        val groupId = group?.id
        if (group == null || groupId == null) {
            exitEditListMembersMode()
            return
        }

        val checked = HashSet(editingCheckedKeys)
        val added = allContacts.filter {
            checked.contains(it.rawId) && it.groups.none { g -> g.id == groupId }
        } as ArrayList<Contact>
        val removed = allContacts.filter {
            !checked.contains(it.rawId) && it.groups.any { g -> g.id == groupId }
        } as ArrayList<Contact>

        exitEditListMembersMode()

        if (added.isEmpty() && removed.isEmpty()) {
            return
        }

        val activity = activity as? SimpleActivity ?: return
        ensureBackgroundThread {
            if (added.isNotEmpty()) {
                activity.addContactsToGroup(added, groupId)
            }
            if (removed.isNotEmpty()) {
                activity.removeContactsFromGroup(removed, groupId)
            }
            activity.runOnUiThread { refreshItems() }
        }
    }

    private fun exitEditListMembersMode() {
        if (!isEditingListMembers) {
            return
        }

        isEditingListMembers = false
        editingListGroup = null
        editingCheckedKeys.clear()

        binding.addToListLabel.setText(R.string.add_contacts_to_list)
        binding.addToListIcon.setImageResource(R.drawable.ic_plus_vector)
        binding.addToListIcon.applyColorFilter(context.getProperTextColor())
        binding.addToListButton.setOnClickListener { editSelectedListMembers() }

        binding.fragmentList.adapter = null
        gotContacts(getFilteredContacts())
        binding.fragmentList.post { updateAddToListButton() }
    }

    private fun reloadGroups() {
        ContactsHelper(context).getStoredGroups { storedGroups ->
            groups = storedGroups
            activity?.runOnUiThread {
                setupFilterChips()
                gotContacts(getFilteredContacts())
            }
        }
    }

    private fun withWritePermission(callback: () -> Unit) {
        activity?.handlePermission(PERMISSION_WRITE_CONTACTS) { granted ->
            if (granted) {
                callback()
            } else {
                activity?.toast(R.string.could_not_access_contacts)
            }
        }
    }

    // the "add contacts to list" button is only relevant for a specific user list, and only shows once
    // the user has scrolled to the end of the list so it appears right after the last contact
    private fun updateAddToListButton() {
        // while editing members the button acts as "Done" and must always stay visible
        if (isEditingListMembers) {
            binding.addToListButton.beVisible()
            return
        }

        val groupId = context.config.selectedContactGroupId
        val isSpecificList = groupId != ALL_CONTACTS_GROUP_ID && groupId != FAVORITES_GROUP_ID
        // only show the button once the user has scrolled to the very bottom of the list, so it
        // appears right after the last contact instead of permanently taking up space
        val atListEnd = !binding.fragmentList.canScrollVertically(1)
        binding.addToListButton.beVisibleIf(isSpecificList && atListEnd)
    }

    private fun styleChip(chip: MyTextView, selected: Boolean, accentColor: Int = context.getProperPrimaryColor()) {
        val background = context.getDrawable(R.drawable.contact_filter_chip)!!.mutate()
        if (selected) {
            background.applyColorFilter(accentColor)
            chip.setTextColor(accentColor.getContrastColor())
        } else {
            val textColor = context.getProperTextColor()
            background.applyColorFilter(textColor.adjustAlpha(0.1f))
            chip.setTextColor(textColor)
        }
        chip.background = background
    }

    // gives each contact section its own accent colour so it is obvious which one is active;
    // uses the user-picked colour if set, otherwise a stable palette colour ("All" stays neutral)
    private fun listColor(id: Long): Int {
        context.config.getListColor(id)?.let { return it }
        if (id == ALL_CONTACTS_GROUP_ID) {
            return context.getProperPrimaryColor()
        }
        val palette = letterBackgroundColors
        return palette[(abs(id).toInt() % palette.size)].toInt()
    }

    // builds a phone-number -> number of calls map by scanning the existing call log,
    // so contacts can be sorted by how often they are actually called
    private fun loadRecencyMap(callback: () -> Unit) {
        ensureBackgroundThread {
            val map = HashMap<String, Long>()
            if (context.hasPermission(PERMISSION_READ_CALL_LOG)) {
                val projection = arrayOf(CallLog.Calls.NUMBER)
                try {
                    context.queryCursor(CallLog.Calls.CONTENT_URI, projection) { cursor ->
                        val number = cursor.getStringValue(CallLog.Calls.NUMBER) ?: ""
                        val key = comparableNumber(number)
                        if (key.isNotEmpty()) {
                            map[key] = (map[key] ?: 0L) + 1L
                        }
                    }
                } catch (ignored: Exception) {
                }
            }
            recencyMap = map
            activity?.runOnUiThread { callback() }
        }
    }

    private fun sortByRecents(contacts: List<Contact>): ArrayList<Contact> {
        val map = recencyMap ?: return ArrayList(contacts)
        // total number of calls across all of a contact's numbers = how much we communicate with that person
        return ArrayList(contacts.sortedWith(compareByDescending { contact ->
            contact.phoneNumbers.sumOf { map[comparableNumber(it.normalizedNumber.ifEmpty { it.value })] ?: 0L }
        }))
    }

    // normalizes a phone number to its last 9 digits so different formattings of the same number match
    private fun comparableNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= 9) digits.takeLast(9) else digits
    }

    private fun setupLetterFastScroller(contacts: ArrayList<Contact>) {
        binding.letterFastscroller.setupWithContacts(binding.fragmentList, contacts)
    }

    override fun onSearchClosed() {
        val filtered = getFilteredContacts()
        binding.fragmentPlaceholder.beVisibleIf(filtered.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(filtered)
        setupLetterFastScroller(filtered)
    }

    override fun onSearchQueryChanged(text: String) {
        val fixedText = text.trim().replace("\\s+".toRegex(), " ")
        val shouldNormalize = fixedText.normalizeString() == fixedText
        val filtered = getFilteredContacts().filter { contact ->
            getProperText(contact.getNameToDisplay(), shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.nickname, shouldNormalize).contains(fixedText, true) ||
                (fixedText.toLongOrNull() != null && contact.doesContainPhoneNumber(fixedText, true)) ||
                contact.emails.any { it.value.contains(fixedText, true) } ||
                contact.addresses.any { getProperText(it.value, shouldNormalize).contains(fixedText, true) } ||
                contact.IMs.any { it.value.contains(fixedText, true) } ||
                getProperText(contact.notes, shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.organization.company, shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.organization.jobPosition, shouldNormalize).contains(fixedText, true) ||
                contact.websites.any { it.contains(fixedText, true) }
        } as ArrayList

        filtered.sortBy {
            val nameToDisplay = it.getNameToDisplay()
            !getProperText(nameToDisplay, shouldNormalize).startsWith(fixedText, true) && !nameToDisplay.contains(fixedText, true)
        }

        binding.fragmentPlaceholder.beVisibleIf(filtered.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(filtered, fixedText)
        setupLetterFastScroller(filtered)
    }

    private fun requestReadContactsPermission() {
        activity?.handlePermission(PERMISSION_READ_CONTACTS) {
            if (it) {
                binding.fragmentPlaceholder.text = context.getString(R.string.no_contacts_found)
                binding.fragmentPlaceholder2.text = context.getString(R.string.create_new_contact)
                refreshItems()
            }
        }
    }
}
