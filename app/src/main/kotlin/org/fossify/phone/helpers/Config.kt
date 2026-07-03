package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.helpers.BaseConfig
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.phone.extensions.getPhoneAccountHandleModel
import org.fossify.phone.extensions.putPhoneAccountHandle
import org.fossify.phone.models.SpeedDial
import androidx.core.content.edit
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    private val regionHint: String by lazy {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            Locale.getDefault().country
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()
    }

    fun getSpeedDialValues(): ArrayList<SpeedDial> {
        val speedDialType = object : TypeToken<List<SpeedDial>>() {}.type
        val speedDialValues = Gson().fromJson<ArrayList<SpeedDial>>(speedDial, speedDialType) ?: ArrayList(1)

        for (i in 1..9) {
            val speedDial = SpeedDial(i, "", "")
            if (speedDialValues.firstOrNull { it.id == i } == null) {
                speedDialValues.add(speedDial)
            }
        }

        return speedDialValues
    }

    fun saveCustomSIM(number: String, handle: PhoneAccountHandle) {
        prefs.edit().putPhoneAccountHandle(
            key = getKeyForCustomSIM(number),
            parcelable = handle
        ).apply()
    }

    fun getCustomSIM(number: String): PhoneAccountHandle? {
        val key = getKeyForCustomSIM(number)
        prefs.getPhoneAccountHandleModel(key, null)?.let {
            return it.toPhoneAccountHandle()
        }

        // fallback for old unstable keys. should be removed in future versions
        val migratedHandle = prefs.all.keys
            .filterIsInstance<String>()
            .filter { it.startsWith(REMEMBER_SIM_PREFIX) }
            .firstOrNull {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.compare(
                    it.removePrefix(REMEMBER_SIM_PREFIX),
                    normalizeCustomSIMNumber(number)
                )
            }?.let { legacyKey ->
                prefs.getPhoneAccountHandleModel(legacyKey, null)?.let {
                    val handle = it.toPhoneAccountHandle()
                    prefs.edit {
                        remove(legacyKey)
                        putPhoneAccountHandle(key, handle)
                    }
                    handle
                }
            }

        return migratedHandle
    }

    fun removeCustomSIM(number: String) {
        prefs.edit().remove(getKeyForCustomSIM(number)).apply()
    }

    private fun getKeyForCustomSIM(number: String): String {
        return REMEMBER_SIM_PREFIX + normalizeCustomSIMNumber(number)
    }

    private fun normalizeCustomSIMNumber(number: String): String {
        val decoded = Uri.decode(number).removePrefix("tel:")
        val formatted = PhoneNumberUtils.formatNumberToE164(decoded, regionHint)
        return formatted ?: PhoneNumberUtils.normalizeNumber(decoded)
    }

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit().putInt(SHOW_TABS, showTabs).apply()

    var groupSubsequentCalls: Boolean
        get() = prefs.getBoolean(GROUP_SUBSEQUENT_CALLS, true)
        set(groupSubsequentCalls) = prefs.edit().putBoolean(GROUP_SUBSEQUENT_CALLS, groupSubsequentCalls).apply()

    var openDialPadAtLaunch: Boolean
        get() = prefs.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH, false)
        set(openDialPad) = prefs.edit().putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, openDialPad).apply()

    var disableProximitySensor: Boolean
        get() = prefs.getBoolean(DISABLE_PROXIMITY_SENSOR, false)
        set(disableProximitySensor) = prefs.edit().putBoolean(DISABLE_PROXIMITY_SENSOR, disableProximitySensor).apply()

    var disableSwipeToAnswer: Boolean
        get() = prefs.getBoolean(DISABLE_SWIPE_TO_ANSWER, false)
        set(disableSwipeToAnswer) = prefs.edit().putBoolean(DISABLE_SWIPE_TO_ANSWER, disableSwipeToAnswer).apply()

    var wasOverlaySnackbarConfirmed: Boolean
        get() = prefs.getBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, false)
        set(wasOverlaySnackbarConfirmed) = prefs.edit().putBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, wasOverlaySnackbarConfirmed).apply()

    var dialpadVibration: Boolean
        get() = prefs.getBoolean(DIALPAD_VIBRATION, true)
        set(dialpadVibration) = prefs.edit().putBoolean(DIALPAD_VIBRATION, dialpadVibration).apply()

    var hideDialpadNumbers: Boolean
        get() = prefs.getBoolean(HIDE_DIALPAD_NUMBERS, false)
        set(hideDialpadNumbers) = prefs.edit().putBoolean(HIDE_DIALPAD_NUMBERS, hideDialpadNumbers).apply()

    var dialpadBeeps: Boolean
        get() = prefs.getBoolean(DIALPAD_BEEPS, true)
        set(dialpadBeeps) = prefs.edit().putBoolean(DIALPAD_BEEPS, dialpadBeeps).apply()

    var alwaysShowFullscreen: Boolean
        get() = prefs.getBoolean(ALWAYS_SHOW_FULLSCREEN, false)
        set(alwaysShowFullscreen) = prefs.edit().putBoolean(ALWAYS_SHOW_FULLSCREEN, alwaysShowFullscreen).apply()

    // view type (list/grid) used specifically by the Contacts tab, kept separate from Favorites
    var contactsViewType: Int
        get() = prefs.getInt(CONTACTS_VIEW_TYPE, VIEW_TYPE_LIST)
        set(value) = prefs.edit().putInt(CONTACTS_VIEW_TYPE, value).apply()

    // when true, the Recents tab shows the most frequently contacted numbers first
    var recentsSortedByFrequency: Boolean
        get() = prefs.getBoolean(RECENTS_SORTED_BY_FREQUENCY, false)
        set(value) = prefs.edit().putBoolean(RECENTS_SORTED_BY_FREQUENCY, value).apply()

    // id of the contact group/list to show on the Contacts tab, ALL_CONTACTS_GROUP_ID = all
    var selectedContactGroupId: Long
        get() = prefs.getLong(SELECTED_CONTACT_GROUP_ID, ALL_CONTACTS_GROUP_ID)
        set(value) = prefs.edit().putLong(SELECTED_CONTACT_GROUP_ID, value).apply()

    // when true, the Contacts tab is sorted by most recent communication instead of alphabetically
    var contactsSortedByRecents: Boolean
        get() = prefs.getBoolean(CONTACTS_SORTED_BY_RECENTS, false)
        set(value) = prefs.edit().putBoolean(CONTACTS_SORTED_BY_RECENTS, value).apply()

    // custom left-to-right order of the list filter chips, stored as comma-separated group ids
    var contactListOrder: String
        get() = prefs.getString(CONTACT_LIST_ORDER, "") ?: ""
        set(value) = prefs.edit().putString(CONTACT_LIST_ORDER, value).apply()

    // manual account ranking used to deduplicate contacts, stored as comma-separated source identifiers
    var contactSourcesPriority: String
        get() = prefs.getString(CONTACT_SOURCES_PRIORITY, "") ?: ""
        set(value) = prefs.edit().putString(CONTACT_SOURCES_PRIORITY, value).apply()

    // keeps a lightweight foreground service alive so the system is unlikely to kill the app
    var keepAlive: Boolean
        get() = prefs.getBoolean(KEEP_ALIVE, false)
        set(value) = prefs.edit().putBoolean(KEEP_ALIVE, value).apply()

    // when enabled, contacts that exist in several accounts are collapsed to the highest-ranked copy
    var hideDuplicateContacts: Boolean
        get() = prefs.getBoolean(HIDE_DUPLICATE_CONTACTS, true)
        set(value) = prefs.edit().putBoolean(HIDE_DUPLICATE_CONTACTS, value).apply()

    // hides the built-in Favorites filter chip when the user doesn't want it
    var favoritesChipHidden: Boolean
        get() = prefs.getBoolean(FAVORITES_CHIP_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(FAVORITES_CHIP_HIDDEN, value).apply()

    // custom label for the Favorites chip (e.g. an emoji); empty means use the default name
    var favoritesChipLabel: String
        get() = prefs.getString(FAVORITES_CHIP_LABEL, "") ?: ""
        set(value) = prefs.edit().putString(FAVORITES_CHIP_LABEL, value).apply()

    // what a horizontal swipe does: off, switch bottom tabs, or switch contact sections
    var swipeMode: Int
        get() = prefs.getInt(SWIPE_MODE, SWIPE_MODE_SECTIONS)
        set(value) = prefs.edit().putInt(SWIPE_MODE, value).apply()

    // custom per-list chip images, stored as "groupId\turi" entries separated by newlines
    private var listIcons: String
        get() = prefs.getString(LIST_ICONS, "") ?: ""
        set(value) = prefs.edit().putString(LIST_ICONS, value).apply()

    private fun listIconsMap(): LinkedHashMap<Long, String> {
        val map = LinkedHashMap<Long, String>()
        listIcons.split("\n").forEach { entry ->
            val parts = entry.split("\t")
            if (parts.size == 2) {
                val id = parts[0].toLongOrNull()
                if (id != null && parts[1].isNotEmpty()) {
                    map[id] = parts[1]
                }
            }
        }
        return map
    }

    fun getListIcon(groupId: Long): String = listIconsMap()[groupId] ?: ""

    fun setListIcon(groupId: Long, uri: String?) {
        val map = listIconsMap()
        if (uri.isNullOrEmpty()) {
            map.remove(groupId)
        } else {
            map[groupId] = uri
        }
        listIcons = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    }

    // custom per-list accent colours, stored as "groupId\tcolorInt" entries separated by newlines
    private var listColors: String
        get() = prefs.getString(LIST_COLORS, "") ?: ""
        set(value) = prefs.edit().putString(LIST_COLORS, value).apply()

    private fun listColorsMap(): LinkedHashMap<Long, Int> {
        val map = LinkedHashMap<Long, Int>()
        listColors.split("\n").forEach { entry ->
            val parts = entry.split("\t")
            if (parts.size == 2) {
                val id = parts[0].toLongOrNull()
                val color = parts[1].toIntOrNull()
                if (id != null && color != null) {
                    map[id] = color
                }
            }
        }
        return map
    }

    fun getListColor(groupId: Long): Int? = listColorsMap()[groupId]

    fun setListColor(groupId: Long, color: Int?) {
        val map = listColorsMap()
        if (color == null) {
            map.remove(groupId)
        } else {
            map[groupId] = color
        }
        listColors = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    }
}
