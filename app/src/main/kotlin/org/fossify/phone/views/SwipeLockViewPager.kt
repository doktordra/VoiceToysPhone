package org.fossify.phone.views

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import org.fossify.commons.views.MyViewPager
import kotlin.math.abs

// a ViewPager whose horizontal paging swipe is disabled, so tabs are only switched by tapping.
// while paging is off, a horizontal fling anywhere on screen is reported through onHorizontalSwipe,
// which the Contacts page uses to move between contact lists (mimicking the old full-screen swipe feel).
class SwipeLockViewPager(context: Context, attrs: AttributeSet) :
    MyViewPager(context, attrs) {

    var swipeEnabled = true

    // called on a horizontal fling; forward = true means swipe right (next), false = swipe left (previous)
    var onHorizontalSwipe: ((forward: Boolean) -> Unit)? = null

    private val swipeDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val startX = e1?.x ?: return false
            val dx = e2.x - startX
            val dy = e2.y - e1.y
            if (abs(dx) > abs(dy) * 1.2f && abs(dx) > 100f && abs(velocityX) > 250f) {
                onHorizontalSwipe?.invoke(dx > 0)
                return true
            }
            return false
        }
    })

    // watch every touch for a horizontal fling without consuming it, so child views keep
    // handling taps and vertical scrolling normally
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled && onHorizontalSwipe != null) {
            swipeDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return swipeEnabled && super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return swipeEnabled && super.onTouchEvent(ev)
    }
}
