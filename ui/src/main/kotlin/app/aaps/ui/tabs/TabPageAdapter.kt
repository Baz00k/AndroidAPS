package app.aaps.ui.tabs

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.adapter.FragmentViewHolder
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginFragment
import app.aaps.core.ui.activities.DrawsToBottomEdge

class TabPageAdapter(private val activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    private val visibleFragmentList = ArrayList<PluginBase>()

    /** Space a page keeps clear at the bottom for the navigation bar and gesture area. */
    var bottomInset = 0
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, BOTTOM_INSET)
        }

    override fun getItemCount(): Int = visibleFragmentList.size
    override fun createFragment(position: Int): Fragment =
        activity.supportFragmentManager.fragmentFactory.instantiate(ClassLoader.getSystemClassLoader(), fragmentClass(position))
            .also { if (it is PluginFragment) it.plugin = getPluginAt(position) }

    override fun onBindViewHolder(holder: FragmentViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) super.onBindViewHolder(holder, position, payloads)
        val ownBottom = DrawsToBottomEdge::class.java.isAssignableFrom(FragmentFactory.loadFragmentClass(activity.classLoader, fragmentClass(position)))
        holder.itemView.updatePadding(bottom = if (ownBottom) 0 else bottomInset)
    }

    fun getPluginAt(position: Int): PluginBase = visibleFragmentList[position]

    fun registerNewFragment(plugin: PluginBase) {
        visibleFragmentList.add(plugin)
    }

    private fun fragmentClass(position: Int): String = getPluginAt(position).pluginDescription.fragmentClass ?: Fragment::class.java.name

    private companion object {

        const val BOTTOM_INSET = "bottomInset"
    }
}
