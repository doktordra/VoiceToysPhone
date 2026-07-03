package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.viewpager.widget.ViewPager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import me.grantland.widget.AutofitHelper
import org.fossify.commons.dialogs.ChangeViewTypeDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.adapters.ViewPagerAdapter
import org.fossify.phone.databinding.ActivityMainBinding
import org.fossify.phone.dialogs.ChangeSortingDialog
import org.fossify.phone.dialogs.FilterContactSourcesDialog
import org.fossify.phone.dialogs.RankAccountsDialog
import org.fossify.phone.extensions.clearMissedCalls
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.handleFullScreenNotificationsPermission
import org.fossify.phone.extensions.launchCreateNewContactIntent
import org.fossify.phone.fragments.ContactsFragment
import org.fossify.phone.fragments.DialpadFragment
import org.fossify.phone.fragments.FavoritesFragment
import org.fossify.phone.fragments.MyViewPagerFragment
import org.fossify.phone.fragments.RecentsFragment
import org.fossify.phone.helpers.ALL_CONTACTS_GROUP_ID
import org.fossify.phone.helpers.OPEN_DIAL_PAD_AT_LAUNCH
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.SWIPE_MODE_SECTIONS
import org.fossify.phone.helpers.SWIPE_MODE_TABS
import org.fossify.phone.models.Events
import org.fossify.phone.services.KeepAliveService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : SimpleActivity() {
    override var isSearchBarEnabled = true
    
    private val binding by viewBinding(ActivityMainBinding::inflate)

    private var launchedDialer = false
    private var storedShowTabs = 0
    private var storedFontSize = 0
    private var storedStartNameWithSurname = false
    var cachedContacts = ArrayList<Contact>()

    private var pendingIconGroupId = 0L
    private val pickListIconLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: Exception) {
            }
            config.setListIcon(pendingIconGroupId, uri.toString())
            getContactsFragment()?.refreshFilterChips()
        }
    }

    fun pickListIcon(groupId: Long) {
        pendingIconGroupId = groupId
        pickListIconLauncher.launch(arrayOf("image/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainTabsHolder))

        EventBus.getDefault().register(this)
        launchedDialer = savedInstanceState?.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH) ?: false

        // this app no longer takes over call handling; the system dialer shows the in-call screen.
        // we only need contacts access to display and search contacts/recents.
        checkContactPermissions()

        setupTabs()
        Contact.sorting = config.sorting

        if (config.keepAlive) {
            KeepAliveService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (storedShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            System.exit(0)
            return
        }

        updateMenuColors()
        updateTextColors(binding.mainHolder)
        setupTabColors()

        getAllFragments().forEach {
            it?.setupColors(getProperTextColor(), getProperPrimaryColor(), getProperPrimaryColor())
        }

        val configStartNameWithSurname = config.startNameWithSurname
        if (storedStartNameWithSurname != configStartNameWithSurname) {
            getContactsFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            getFavoritesFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            storedStartNameWithSurname = config.startNameWithSurname
        }

        if (!binding.mainMenu.isSearchOpen) {
            refreshItems(true)
        }

        val configFontSize = config.fontSize
        if (storedFontSize != configFontSize) {
            getAllFragments().forEach {
                it?.fontSizeChanged()
            }
        }

        checkShortcuts()
    }

    override fun onPause() {
        super.onPause()
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        // we don't really care about the result, the app can work without being the default Dialer too
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            checkContactPermissions()
        } else if (requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != Activity.RESULT_OK) {
            toast(R.string.must_make_default_caller_id_app, length = Toast.LENGTH_LONG)
            baseConfig.blockUnknownNumbers = false
            baseConfig.blockHiddenNumbers = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, launchedDialer)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshItems()
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    private fun refreshMenuItems() {
        val currentFragment = getCurrentFragment()
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.clear_call_history).isVisible = currentFragment == getRecentsFragment()
            findItem(R.id.change_view_type).isVisible = false
            findItem(R.id.column_count).isVisible = false
            findItem(R.id.more_apps_from_us).isVisible = !resources.getBoolean(R.bool.hide_google_relations)
        }
    }

    // shows the active tab's context as the search-field hint, tying the top bar to the screen below
    private fun updateSearchHintForTab() {
        val hintRes = if (getCurrentFragment() == getRecentsFragment()) {
            R.string.search
        } else {
            R.string.search_contacts
        }
        binding.mainMenu.updateHintText(getString(hintRes))
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            requireToolbar().inflateMenu(R.menu.menu)
            toggleHideOnScroll(false)
            setupMenu()

            onSearchClosedListener = {
                getAllFragments().forEach {
                    it?.onSearchQueryChanged("")
                }
            }

            onSearchTextChangedListener = { text ->
                getCurrentFragment()?.onSearchQueryChanged(text)
            }

            requireToolbar().setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.clear_call_history -> clearCallHistory()
                    R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                    R.id.settings -> launchSettings()
                    R.id.change_view_type -> changeViewType()
                    R.id.column_count -> changeColumnCount()
                    else -> return@setOnMenuItemClickListener false
                }
                return@setOnMenuItemClickListener true
            }
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..CONTACTS_GRID_MAX_COLUMNS_COUNT) {
            items.add(RadioItem(i, resources.getQuantityString(R.plurals.column_counts, i, i)))
        }

        val currentColumnCount = config.contactsGridColumnCount
        RadioGroupDialog(this, ArrayList(items), currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.contactsGridColumnCount = newColumnCount
                getFavoritesFragment()?.columnCountChanged()
            }
        }
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this) {
            refreshMenuItems()
            getFavoritesFragment()?.refreshItems()
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun checkContactPermissions() {
        handlePermission(PERMISSION_READ_CONTACTS) {
            initFragments()
        }
    }

    private fun clearCallHistory() {
        val confirmationText = "${getString(R.string.clear_history_confirmation)}\n\n${getString(R.string.cannot_be_undone)}"
        ConfirmationDialog(this, confirmationText) {
            RecentsHelper(this).removeAllRecentCalls(this) {
                runOnUiThread {
                    getRecentsFragment()?.refreshItems(invalidate = true)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val appIconColor = config.appIconColor
        if (isNougatMR1Plus() && config.lastHandledShortcutColor != appIconColor) {
            val launchDialpad = getLaunchDialpadShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = listOf(launchDialpad)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getLaunchDialpadShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.dialpad)
        val drawable = resources.getDrawable(R.drawable.shortcut_dialpad)
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_dialpad_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, DialpadActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "launch_dialpad")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    private fun setupTabColors() {
        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        updateBottomTabItemColors(activeView, true, getSelectedTabDrawableIds()[binding.viewPager.currentItem])

        getInactiveTabIndexes(binding.viewPager.currentItem).forEach { index ->
            val inactiveView = binding.mainTabsHolder.getTabAt(index)?.customView
            updateBottomTabItemColors(inactiveView, false, getDeselectedTabDrawableIds()[index])
        }

        val bottomBarColor = getBottomNavigationBackgroundColor()
        binding.mainTabsHolder.setBackgroundColor(bottomBarColor)
    }

    private fun getInactiveTabIndexes(activeIndex: Int) = (0 until binding.mainTabsHolder.tabCount).filter { it != activeIndex }

    private fun getSelectedTabDrawableIds(): List<Int> {
        return listOf(
            R.drawable.ic_person_vector,
            R.drawable.ic_dialpad_vector,
            R.drawable.ic_clock_filled_vector
        )
    }

    private fun getDeselectedTabDrawableIds(): ArrayList<Int> {
        return arrayListOf(
            R.drawable.ic_person_outline_vector,
            R.drawable.ic_dialpad_vector,
            R.drawable.ic_clock_vector
        )
    }

    private fun initFragments() {
        binding.viewPager.offscreenPageLimit = 2
        // the horizontal swipe behaviour is configurable: off, switch tabs (normal paging),
        // or switch contact sections (a fling on the Contacts page moves between lists)
        val swipeMode = config.swipeMode
        binding.viewPager.swipeEnabled = swipeMode == SWIPE_MODE_TABS
        binding.viewPager.onHorizontalSwipe = if (swipeMode == SWIPE_MODE_SECTIONS) {
            { forward ->
                if (getCurrentFragment() == getContactsFragment()) {
                    getContactsFragment()?.onHorizontalSwipe(forward)
                }
            }
        } else {
            null
        }
        // a subtle fade makes tab changes feel smoother than the default hard cut
        binding.viewPager.setPageTransformer(false) { page, position ->
            page.alpha = 1f - Math.abs(position).coerceAtMost(1f) * 0.3f
        }
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                binding.mainTabsHolder.getTabAt(position)?.select()
                getAllFragments().forEach {
                    it?.finishActMode()
                }
                refreshMenuItems()
                // don't carry a search query over to another screen
                closeSearchBar()
                // the dialpad tab has its own T9 input, so the global search bar is redundant there
                binding.mainMenu.beGoneIf(position == 1)
                if (position == 1) {
                    getDialpadFragment()?.showDialpad()
                }
                // surface the active tab in the top bar so it feels connected to the content below
                updateSearchHintForTab()
            }
        })

        // selecting the proper tab sometimes glitches, add an extra selector to make sure we have it right
        binding.mainTabsHolder.onGlobalLayout {
            Handler().postDelayed({
                var wantedTab = getDefaultTab()

                // open the Recents tab if we got here by clicking a missed call notification
                if (intent.action == Intent.ACTION_VIEW && config.showTabs and TAB_CALL_HISTORY > 0) {
                    wantedTab = binding.mainTabsHolder.tabCount - 1
                }

                // open the dialpad tab at launch if the user opted in
                if (config.openDialPadAtLaunch && !launchedDialer) {
                    wantedTab = 1
                    launchedDialer = true
                }

                binding.mainTabsHolder.getTabAt(wantedTab)?.select()
                refreshMenuItems()
            }, 100L)
        }

        binding.viewPager.onGlobalLayout {
            refreshMenuItems()
        }
    }

    private fun setupTabs() {
        binding.viewPager.adapter = null
        binding.mainTabsHolder.removeAllTabs()
        (0 until 3).forEach { index ->
            binding.mainTabsHolder.newTab().setCustomView(R.layout.bottom_tablayout_item).apply {
                customView?.findViewById<ImageView>(R.id.tab_item_icon)?.setImageDrawable(getTabIcon(index))
                customView?.findViewById<TextView>(R.id.tab_item_label)?.text = getTabLabel(index)
                AutofitHelper.create(customView?.findViewById(R.id.tab_item_label))
                binding.mainTabsHolder.addTab(this)
            }
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                updateBottomTabItemColors(it.customView, false, getDeselectedTabDrawableIds()[it.position])
            },
            tabSelectedAction = {
                getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                binding.viewPager.currentItem = it.position
                updateBottomTabItemColors(it.customView, true, getSelectedTabDrawableIds()[it.position])

                val lastPosition = binding.mainTabsHolder.tabCount - 1
                if (it.position == lastPosition && config.showTabs and TAB_CALL_HISTORY > 0) {
                    clearMissedCalls()
                }
            }
        )

        binding.mainTabsHolder.beGoneIf(binding.mainTabsHolder.tabCount == 1)

        // tapping the already-selected dialpad tab brings the keypad back after it was collapsed
        binding.mainTabsHolder.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {}
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 1) {
                    getDialpadFragment()?.showDialpad()
                }
            }
        })
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
    }

    private fun getTabIcon(position: Int): Drawable {
        val drawableId = when (position) {
            0 -> R.drawable.ic_person_vector
            1 -> R.drawable.ic_dialpad_vector
            else -> R.drawable.ic_clock_vector
        }

        return resources.getColoredDrawableWithColor(drawableId, getProperTextColor())
    }

    private fun getTabLabel(position: Int): String {
        val stringId = when (position) {
            0 -> R.string.contacts_tab
            1 -> R.string.dialpad
            else -> R.string.call_history_tab
        }

        return resources.getString(stringId)
    }

    private fun refreshItems(openLastTab: Boolean = false) {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.apply {
            if (viewPager.adapter == null) {
                viewPager.adapter = ViewPagerAdapter(this@MainActivity)
                viewPager.currentItem = if (openLastTab) config.lastUsedViewPagerPage else getDefaultTab()
                viewPager.onGlobalLayout {
                    refreshFragments()
                }
            } else {
                refreshFragments()
            }
        }
    }

    fun refreshFragments() {
        // load the contacts once into the shared cache, then let the recents/dialpad reuse it
        // instead of each running its own full contacts query (this was the main startup slowdown)
        cacheContacts {
            getRecentsFragment()?.refreshItems()
            getDialpadFragment()?.refreshContactsFromCache()
        }
        getContactsFragment()?.refreshItems()
        getFavoritesFragment()?.refreshItems()
    }

    private fun getAllFragments(): ArrayList<MyViewPagerFragment<*>?> {
        return arrayListOf(getContactsFragment(), getDialpadFragment(), getRecentsFragment())
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? = getAllFragments().getOrNull(binding.viewPager.currentItem)

    fun closeSearchBar() {
        if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
        }
    }

    private fun getContactsFragment(): ContactsFragment? = findViewById(R.id.contacts_fragment)

    private fun getFavoritesFragment(): FavoritesFragment? = findViewById(R.id.favorites_fragment)

    private fun getDialpadFragment(): DialpadFragment? = findViewById(R.id.dialpad_fragment)

    private fun getRecentsFragment(): RecentsFragment? = findViewById(R.id.recents_fragment)

    private fun getDefaultTab(): Int {
        return when (config.defaultTab) {
            TAB_LAST_USED -> if (config.lastUsedViewPagerPage < binding.mainTabsHolder.tabCount) config.lastUsedViewPagerPage else 0
            TAB_CONTACTS -> 0
            else -> binding.mainTabsHolder.tabCount - 1
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_GLIDE or LICENSE_INDICATOR_FAST_SCROLL or LICENSE_AUTOFITTEXTVIEW

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_1_title, R.string.faq_1_text),
            FAQItem(R.string.faq_2_title, R.string.faq_2_text),
            FAQItem(R.string.faq_3_title, R.string.faq_3_text),
            FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons)
        )

        if (!resources.getBoolean(R.bool.hide_google_relations)) {
            faqItems.add(FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons))
            faqItems.add(FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons))
        }

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    private fun showSortingDialog(showCustomSorting: Boolean) {
        ChangeSortingDialog(this, showCustomSorting) {
            getFavoritesFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }
        }
    }

    private fun showFilterDialog() {
        FilterContactSourcesDialog(this) {
            getFavoritesFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getRecentsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }
        }
    }

    private fun showRankAccountsDialog() {
        RankAccountsDialog(this) {
            getContactsFragment()?.refreshItems()
        }
    }

    fun cacheContacts(callback: (() -> Unit)? = null) {
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        // getAll = false so the shared cache (used by the dialpad too) respects the contact sources selected in Settings
        ContactsHelper(this).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            if (SMT_PRIVATE !in config.ignoredContactSources) {
                val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                    contacts.sort()
                }
            }

            try {
                cachedContacts.clear()
                cachedContacts.addAll(contacts)
            } catch (ignored: Exception) {
            }

            if (callback != null) {
                runOnUiThread { callback() }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        getRecentsFragment()?.refreshItems()
    }
}
