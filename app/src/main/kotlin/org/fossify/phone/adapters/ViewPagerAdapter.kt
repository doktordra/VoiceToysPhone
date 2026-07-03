package org.fossify.phone.adapters

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.fragments.MyViewPagerFragment

class ViewPagerAdapter(val activity: SimpleActivity) : PagerAdapter() {

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val layout = getFragment(position)
        val view = activity.layoutInflater.inflate(layout, container, false)
        container.addView(view)

        (view as MyViewPagerFragment<*>).apply {
            setupFragment(activity)
        }

        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
        container.removeView(item as View)
    }

    // fixed layout: Contacts | Dialpad | Recents, so the bottom nav bar is always present
    override fun getCount() = 3

    override fun isViewFromObject(view: View, item: Any) = view == item

    private fun getFragment(position: Int): Int {
        return when (position) {
            0 -> R.layout.fragment_contacts
            1 -> R.layout.fragment_dialpad
            else -> R.layout.fragment_recents
        }
    }
}
