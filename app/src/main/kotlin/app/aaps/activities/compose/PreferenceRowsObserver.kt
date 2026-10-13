package app.aaps.activities.compose

import androidx.recyclerview.widget.RecyclerView

/** Keep the Compose snapshot in sync with native preference changes, including non-persistent rows. */
internal class PreferenceRowsObserver(private val refresh: () -> Unit) : RecyclerView.AdapterDataObserver() {
    override fun onChanged() = refresh()
    override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = refresh()
    override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = refresh()
    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refresh()
    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refresh()
    override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = refresh()
}
