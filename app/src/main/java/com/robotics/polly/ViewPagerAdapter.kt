package com.robotics.polly

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ViewPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    
    override fun getItemCount(): Int = 5
    
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PollyFragmentCompose()
            1 -> ArduinoFragmentCompose()
            2 -> LidarFragment()
            3 -> FlirThermalFragment()
            4 -> LogsFragmentCompose()
            else -> PollyFragmentCompose()
        }
    }
}
