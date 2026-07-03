package org.fossify.phone.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms.Intents.SECRET_CODE_ACTION
import android.telephony.TelephonyManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColorStateList
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isDefaultDialer
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.KeypadHelper
import org.fossify.commons.helpers.LOWER_ALPHA_INT
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.isOreoPlus
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.ContactsAdapter
import org.fossify.phone.databinding.FragmentDialpadBinding
import org.fossify.phone.databinding.FragmentDialpadLayoutBinding
import org.fossify.phone.extensions.addCharacter
import org.fossify.phone.extensions.areMultipleSIMsAvailable
import org.fossify.phone.extensions.boundingBox
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.disableKeyboard
import org.fossify.phone.extensions.getKeyEvent
import org.fossify.phone.extensions.setupWithContacts
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.DIALPAD_TONE_LENGTH_MS
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.ToneGeneratorHelper
import org.fossify.phone.models.SpeedDial
import java.util.Locale
import kotlin.math.roundToInt

class DialpadFragment(context: Context, attributeSet: AttributeSet) :
    MyViewPagerFragment<MyViewPagerFragment.DialpadInnerBinding>(context, attributeSet) {
    private lateinit var binding: FragmentDialpadLayoutBinding

    private var allContacts = ArrayList<Contact>()
    // precomputed (contact -> T9 digit string of its name) so filtering on each keystroke is cheap
    private var convertedContacts = ArrayList<Pair<Contact, String>>()
    private var speedDialValues = ArrayList<SpeedDial>()
    private var privateCursor: Cursor? = null
    private var toneGeneratorHelper: ToneGeneratorHelper? = null
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val clearInputHandler = Handler(Looper.getMainLooper())
    private val pressedKeys = mutableSetOf<Char>()

    private var hasRussianLocale = false
    private val russianCharsMap by lazy {
        hashMapOf(
            'а' to 2, 'б' to 2, 'в' to 2, 'г' to 2,
            'д' to 3, 'е' to 3, 'ё' to 3, 'ж' to 3, 'з' to 3,
            'и' to 4, 'й' to 4, 'к' to 4, 'л' to 4,
            'м' to 5, 'н' to 5, 'о' to 5, 'п' to 5,
            'р' to 6, 'с' to 6, 'т' to 6, 'у' to 6,
            'ф' to 7, 'х' to 7, 'ц' to 7, 'ч' to 7,
            'ш' to 8, 'щ' to 8, 'ъ' to 8, 'ы' to 8,
            'ь' to 9, 'э' to 9, 'ю' to 9, 'я' to 9
        )
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentDialpadLayoutBinding.bind(FragmentDialpadBinding.bind(this).dialpadFragment)
        innerBinding = DialpadInnerBinding(binding)
    }

    @Suppress("LongMethod")
    override fun setupFragment() {
        hasRussianLocale = Locale.getDefault().language == "ru"

        binding.dialpadWrapper.apply {
            if (context.config.hideDialpadNumbers) {
                dialpad1Holder.isVisible = false
                dialpad2Holder.isVisible = false
                dialpad3Holder.isVisible = false
                dialpad4Holder.isVisible = false
                dialpad5Holder.isVisible = false
                dialpad6Holder.isVisible = false
                dialpad7Holder.isVisible = false
                dialpad8Holder.isVisible = false
                dialpad9Holder.isVisible = false
                dialpadPlusHolder.isVisible = true
                dialpad0Holder.visibility = View.INVISIBLE
            }

            arrayOf(
                dialpad0Holder,
                dialpad1Holder,
                dialpad2Holder,
                dialpad3Holder,
                dialpad4Holder,
                dialpad5Holder,
                dialpad6Holder,
                dialpad7Holder,
                dialpad8Holder,
                dialpad9Holder,
                dialpadPlusHolder,
                dialpadAsteriskHolder,
                dialpadHashtagHolder
            ).forEach {
                it.background = ResourcesCompat.getDrawable(resources, R.drawable.pill_background, context.theme)
                it.background?.alpha = LOWER_ALPHA_INT
            }
        }

        speedDialValues = context.config.getSpeedDialValues()
        privateCursor = activity?.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        toneGeneratorHelper = ToneGeneratorHelper(context, DIALPAD_TONE_LENGTH_MS)

        binding.dialpadWrapper.apply {
            if (hasRussianLocale) {
                dialpad2Letters.append("\nАБВГ")
                dialpad3Letters.append("\nДЕЁЖЗ")
                dialpad4Letters.append("\nИЙКЛ")
                dialpad5Letters.append("\nМНОП")
                dialpad6Letters.append("\nРСТУ")
                dialpad7Letters.append("\nФХЦЧ")
                dialpad8Letters.append("\nШЩЪЫ")
                dialpad9Letters.append("\nЬЭЮЯ")

                val fontSize = resources.getDimension(R.dimen.small_text_size)
                arrayOf(
                    dialpad2Letters,
                    dialpad3Letters,
                    dialpad4Letters,
                    dialpad5Letters,
                    dialpad6Letters,
                    dialpad7Letters,
                    dialpad8Letters,
                    dialpad9Letters
                ).forEach {
                    it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                }
            }

            setupCharClick(dialpad1Holder, '1')
            setupCharClick(dialpad2Holder, '2')
            setupCharClick(dialpad3Holder, '3')
            setupCharClick(dialpad4Holder, '4')
            setupCharClick(dialpad5Holder, '5')
            setupCharClick(dialpad6Holder, '6')
            setupCharClick(dialpad7Holder, '7')
            setupCharClick(dialpad8Holder, '8')
            setupCharClick(dialpad9Holder, '9')
            setupCharClick(dialpad0Holder, '0')
            setupCharClick(dialpadPlusHolder, '+', longClickable = false)
            setupCharClick(dialpadAsteriskHolder, '*', longClickable = false)
            setupCharClick(dialpadHashtagHolder, '#', longClickable = false)
        }

        binding.apply {
            dialpadClearChar.setOnClickListener { clearChar(it) }
            dialpadClearChar.setOnLongClickListener { clearInput(); true }
            dialpadAddContact.setOnClickListener { startAddContact() }
            dialpadCallButton.setOnClickListener { initCall(dialpadInput.value) }
            dialpadCallButton.setOnLongClickListener { initCallWithSimSelector() }
            dialpadInput.onTextChangeListener { dialpadValueChanged(it) }
            dialpadInput.disableKeyboard()
        }

        // when the user starts scrolling the results, collapse the keypad to show the full list
        binding.dialpadList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    hideDialpad()
                }
            }
        })

        // reuse MainActivity's shared contacts cache instead of running our own full query at startup
        refreshContactsFromCache()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        activity?.updateTextColors(binding.dialpadHolder)
        binding.dialpadClearChar.applyColorFilter(textColor)
        binding.dialpadAddContact.applyColorFilter(textColor)

        val callIcon = resources.getColoredDrawableWithColor(
            drawableId = R.drawable.ic_phone_vector,
            color = properPrimaryColor.getContrastColor()
        )
        binding.dialpadCallButton.setImageDrawable(callIcon)
        binding.dialpadCallButton.background.applyColorFilter(properPrimaryColor)

        binding.letterFastscroller.textColor = textColor.getColorStateList()
        binding.letterFastscroller.pressedTextColor = properPrimaryColor
        binding.letterFastscrollerThumb.setupWithFastScroller(binding.letterFastscroller)
        binding.letterFastscrollerThumb.textColor = properPrimaryColor.getContrastColor()
        binding.letterFastscrollerThumb.thumbColor = properPrimaryColor.getColorStateList()
    }

    override fun onSearchClosed() {}

    override fun onSearchQueryChanged(text: String) {}

    private fun startAddContact() {
        activity?.startAddContactIntent(binding.dialpadInput.value)
    }

    private fun dialpadPressed(char: Char, view: View?) {
        binding.dialpadInput.addCharacter(char)
        if (context.config.dialpadVibration) {
            view?.performHapticFeedback()
        }
    }

    private fun clearChar(view: View) {
        binding.dialpadInput.dispatchKeyEvent(binding.dialpadInput.getKeyEvent(KeyEvent.KEYCODE_DEL))
        if (context.config.dialpadVibration) {
            view.performHapticFeedback()
        }
    }

    // fully collapse the T9 keypad so the results list can use the whole screen
    private fun hideDialpad() {
        dialpadInputViews().forEach { it.beGone() }
    }

    // restore the T9 keypad (called when the dialpad tab is (re)selected)
    fun showDialpad() {
        dialpadInputViews().forEach { it.beVisible() }
    }

    private fun dialpadInputViews() = listOf(
        binding.dialpadInput,
        binding.dialpadAddContact,
        binding.dialpadClearChar,
        binding.dialpadDivider,
        binding.dialpadWrapper.root,
        binding.dialpadCallButton
    )

    private fun clearInput() {
        binding.dialpadInput.setText("")
    }

    private fun clearInputWithDelay() {
        clearInputHandler.removeCallbacksAndMessages(null)
        clearInputHandler.postDelayed({ clearInput() }, 1000)
    }

    fun refreshContactsFromCache() {
        val cached = (activity as? MainActivity)?.cachedContacts
        if (cached.isNullOrEmpty()) {
            // cache not ready yet; load directly as a fallback
            ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                    contacts.sort()
                }
                setContacts(contacts)
            }
        } else {
            // the shared cache already contains private contacts
            setContacts(ArrayList(cached))
        }
    }

    private fun setContacts(contacts: ArrayList<Contact>) {
        allContacts = dedupContacts(contacts)
        buildConvertedContacts()
        activity?.runOnUiThread {
            dialpadValueChanged(binding.dialpadInput.value)
        }
    }

    // the shared cache pulls contacts from all accounts, so the same person can appear several times;
    // collapse entries that share the same display name and the same set of numbers
    private fun dedupContacts(list: List<Contact>): ArrayList<Contact> {
        val seen = HashSet<String>()
        val result = ArrayList<Contact>()
        list.forEach { contact ->
            val numbersKey = contact.phoneNumbers
                .map { it.normalizedNumber.ifEmpty { it.value }.filter { ch -> ch.isDigit() } }
                .sorted()
                .joinToString(",")
            val key = contact.getNameToDisplay().lowercase(Locale.getDefault()) + "|" + numbersKey
            if (seen.add(key)) {
                result.add(contact)
            }
        }
        return result
    }

    // convert each contact name to its T9 digit representation once, up front
    private fun buildConvertedContacts() {
        convertedContacts = ArrayList(allContacts.map { contact ->
            var convertedName = KeypadHelper.convertKeypadLettersToDigits(
                contact.name.normalizeString()
            ).filterNot { it.isWhitespace() }

            if (hasRussianLocale) {
                var currConvertedName = ""
                convertedName.lowercase(Locale.getDefault()).forEach { char ->
                    val convertedChar = russianCharsMap.getOrElse(char) { char }
                    currConvertedName += convertedChar
                }
                convertedName = currConvertedName
            }

            contact to convertedName
        })
    }

    private fun dialpadValueChanged(text: String) {
        val len = text.length
        if (len > 8 && text.startsWith("*#*#") && text.endsWith("#*#*")) {
            val secretCode = text.substring(4, text.length - 4)
            // we are not the default dialer anymore, so dispatch secret codes via broadcast
            val intent = Intent(SECRET_CODE_ACTION, "android_secret_code://$secretCode".toUri())
            context.sendBroadcast(intent)
            return
        }

        (binding.dialpadList.adapter as? ContactsAdapter)?.finishActMode()

        // show nothing until the user starts typing a T9 query
        if (text.isEmpty()) {
            (binding.dialpadList.adapter as? ContactsAdapter)?.updateItems(ArrayList())
            binding.dialpadPlaceholder.beGone()
            binding.dialpadList.beGone()
            return
        }

        // filtering uses the precomputed T9 names. Letter (name) matches are prioritized over plain phone
        // number matches so that spelling a name is not overridden by someone who happens to own that number.
        val filtered = convertedContacts.filter { (contact, convertedName) ->
            convertedName.contains(text, true) || contact.doesContainPhoneNumber(text)
        }.sortedWith(
            compareBy<Pair<Contact, String>>(
                // a contact whose whole T9 name equals the typed digits (e.g. "J" -> 5) comes first
                { (_, convertedName) -> !convertedName.equals(text, true) },
                { (_, convertedName) -> !convertedName.startsWith(text, true) },
                { (_, convertedName) -> !convertedName.contains(text, true) },
                { (contact, _) -> !contact.doesContainPhoneNumber(text) }
            )
        ).map { it.first }.toMutableList() as ArrayList<Contact>

        binding.letterFastscroller.setupWithContacts(binding.dialpadList, filtered)

        val currAdapter = binding.dialpadList.adapter as? ContactsAdapter
        if (currAdapter == null) {
            ContactsAdapter(
                activity = activity as SimpleActivity,
                contacts = filtered,
                recyclerView = binding.dialpadList,
                highlightText = text,
                itemClick = {
                    startCall(it as Contact)
                    clearInputWithDelay()
                },
                profileIconClick = {
                    activity?.startContactDetailsIntent(it as Contact)
                }
            ).apply {
                binding.dialpadList.adapter = this
            }
        } else {
            currAdapter.updateItems(filtered, text)
        }

        binding.dialpadPlaceholder.beVisibleIf(filtered.isEmpty())
        binding.dialpadList.beVisibleIf(filtered.isNotEmpty())
    }

    private fun startCall(contact: Contact) {
        (activity as SimpleActivity).startCallWithConfirmationCheck(contact)
    }

    private fun initCall(number: String = binding.dialpadInput.value, name: String? = null) {
        if (number.isNotEmpty()) {
            (activity as SimpleActivity).startCallWithConfirmationCheck(number, name ?: number)
            clearInputWithDelay()
        } else {
            RecentsHelper(context).getRecentCalls(queryLimit = 1) {
                val mostRecentNumber = it.firstOrNull()?.phoneNumber
                if (!mostRecentNumber.isNullOrEmpty()) {
                    activity?.runOnUiThread {
                        binding.dialpadInput.setText(mostRecentNumber)
                    }
                }
            }
        }
    }

    private fun initCallWithSimSelector(): Boolean {
        val number = binding.dialpadInput.value
        return if (context.areMultipleSIMsAvailable() && number.isNotEmpty()) {
            (activity as SimpleActivity).startCallWithConfirmationCheck(
                recipient = number,
                name = number,
                forceSimSelector = true
            )
            true
        } else {
            false
        }
    }

    private fun speedDial(id: Int): Boolean {
        if (binding.dialpadInput.value.length == 1) {
            val speedDial = speedDialValues.firstOrNull { it.id == id }
            if (speedDial?.isValid() == true) {
                initCall(speedDial.number, speedDial.getName(context))
                return true
            }
        }
        return false
    }

    private fun startDialpadTone(char: Char) {
        if (context.config.dialpadBeeps) {
            pressedKeys.add(char)
            toneGeneratorHelper?.startTone(char)
        }
    }

    private fun stopDialpadTone(char: Char) {
        if (context.config.dialpadBeeps) {
            if (!pressedKeys.remove(char)) return
            if (pressedKeys.isEmpty()) {
                toneGeneratorHelper?.stopTone()
            } else {
                startDialpadTone(pressedKeys.last())
            }
        }
    }

    private fun performLongClick(view: View, char: Char) {
        if (char == '0') {
            clearChar(view)
            dialpadPressed('+', view)
        } else {
            val result = speedDial(char.digitToInt())
            if (result) {
                stopDialpadTone(char)
                clearChar(view)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCharClick(view: View, char: Char, longClickable: Boolean = true) {
        view.isClickable = true
        view.isLongClickable = true
        var typed = false
        var longPressFired = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    typed = true
                    longPressFired = false
                    dialpadPressed(char, view)
                    startDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                        longPressHandler.postDelayed({
                            longPressFired = true
                            performLongClick(view, char)
                        }, longPressTimeout)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    stopDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    stopDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                    }
                    // a cancel here means the ViewPager took over for a horizontal page swipe,
                    // so undo the digit that was inserted on ACTION_DOWN instead of typing it
                    if (typed && !longPressFired) {
                        binding.dialpadInput.dispatchKeyEvent(
                            binding.dialpadInput.getKeyEvent(KeyEvent.KEYCODE_DEL)
                        )
                        typed = false
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val viewContainsTouchEvent = if (event.rawX.isNaN() || event.rawY.isNaN()) {
                        false
                    } else {
                        view.boundingBox.contains(event.rawX.roundToInt(), event.rawY.roundToInt())
                    }

                    if (!viewContainsTouchEvent) {
                        stopDialpadTone(char)
                        if (longClickable) {
                            longPressHandler.removeCallbacksAndMessages(null)
                        }
                    }
                }
            }
            false
        }
    }
}
