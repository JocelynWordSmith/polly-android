package com.robotics.polly

import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ViewPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    
    companion object {
        private const val TAG = "ViewPagerAdapter"
    }
    
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        Log.d(TAG, "createFragment called for position $position")
        val fragment = when (position) {
            0 -> PollyFragmentCompose()        // Bridge status
            1 -> DevicesFragmentCompose()      // Combined device view
            2 -> LogsFragmentCompose()         // Logs
            else -> PollyFragmentCompose()
        }
        Log.d(TAG, "Created fragment: ${fragment.javaClass.simpleName} for position $position")
        return fragment
    }
}
