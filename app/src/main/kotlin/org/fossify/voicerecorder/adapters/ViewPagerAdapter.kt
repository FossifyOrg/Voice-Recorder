package org.fossify.voicerecorder.adapters

import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.fragments.MyViewPagerFragment
import org.fossify.voicerecorder.fragments.PlayerFragment
import org.fossify.voicerecorder.fragments.TrashFragment

class ViewPagerAdapter(private val activity: SimpleActivity, val showRecycleBin: Boolean) : PagerAdapter() {
    private val mFragments = SparseArray<MyViewPagerFragment>()

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val layout = when (position) {
            0 -> R.layout.fragment_recorder
            1 -> R.layout.fragment_player
            2 -> R.layout.fragment_trash
            else -> throw IllegalArgumentException("Invalid position. Count = $count, requested position = $position")
        }
        val view = activity.layoutInflater.inflate(layout, container, false)
        container.addView(view)

        mFragments.put(position, view as MyViewPagerFragment)

        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
        container.removeView(item as View)
    }

    override fun getCount() = if (showRecycleBin) {
        3
    } else {
        2
    }

    override fun isViewFromObject(view: View, item: Any) = view == item

    fun onResume() {
        for (i in 0 until mFragments.size()) {
            mFragments[i].onResume()
        }
    }

    fun onDestroy() {
        for (i in 0 until mFragments.size()) {
            mFragments[i].onDestroy()
        }
    }

    fun finishActMode() {
        (mFragments[1] as? PlayerFragment)?.finishActMode()
        if (showRecycleBin) {
            (mFragments[2] as? TrashFragment)?.finishActMode()
        }
    }

    fun searchTextChanged(text: String) {
        (mFragments[1] as? PlayerFragment)?.onSearchTextChanged(text)
        if (showRecycleBin) {
            (mFragments[2] as? TrashFragment)?.onSearchTextChanged(text)
        }
    }
}
