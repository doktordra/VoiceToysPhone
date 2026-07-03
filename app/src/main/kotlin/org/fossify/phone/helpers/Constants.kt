package org.fossify.phone.helpers

import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS

// shared prefs
const val SPEED_DIAL = "speed_dial"
const val REMEMBER_SIM_PREFIX = "remember_sim_"
const val GROUP_SUBSEQUENT_CALLS = "group_subsequent_calls"
const val OPEN_DIAL_PAD_AT_LAUNCH = "open_dial_pad_at_launch"
const val DISABLE_PROXIMITY_SENSOR = "disable_proximity_sensor"
const val DISABLE_SWIPE_TO_ANSWER = "disable_swipe_to_answer"
const val SHOW_TABS = "show_tabs"
const val FAVORITES_CONTACTS_ORDER = "favorites_contacts_order"
const val FAVORITES_CUSTOM_ORDER_SELECTED = "favorites_custom_order_selected"
const val WAS_OVERLAY_SNACKBAR_CONFIRMED = "was_overlay_snackbar_confirmed"
const val DIALPAD_VIBRATION = "dialpad_vibration"
const val DIALPAD_BEEPS = "dialpad_beeps"
const val HIDE_DIALPAD_NUMBERS = "hide_dialpad_numbers"
const val ALWAYS_SHOW_FULLSCREEN = "always_show_fullscreen"
const val CONTACTS_VIEW_TYPE = "contacts_view_type"
const val RECENTS_SORTED_BY_FREQUENCY = "recents_sorted_by_frequency"
const val SELECTED_CONTACT_GROUP_ID = "selected_contact_group_id"
const val CONTACTS_SORTED_BY_RECENTS = "contacts_sorted_by_recents"

// special value meaning "show contacts from all groups/lists"
const val ALL_CONTACTS_GROUP_ID = -1L

// special value meaning "show only favorite (starred) contacts"
const val FAVORITES_GROUP_ID = -2L

// local view type: full list on top + a grid of the most contacted people at the bottom
const val VIEW_TYPE_COMBINED = 3

const val ALL_TABS_MASK = TAB_CONTACTS or TAB_CALL_HISTORY

val tabsList = arrayListOf(TAB_CONTACTS, TAB_CALL_HISTORY)

private const val PATH = "org.fossify.phone.action."
const val ACCEPT_CALL = PATH + "ACCEPT_CALL"
const val DECLINE_CALL = PATH + "DECLINE_CALL"

const val DIALPAD_TONE_LENGTH_MS = 150L // The length of DTMF tones in milliseconds
