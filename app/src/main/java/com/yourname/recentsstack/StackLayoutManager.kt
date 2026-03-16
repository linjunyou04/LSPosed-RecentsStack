package com.yourname.recentsstack

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

class StackLayoutManager(private val context: Context) : RecyclerView.LayoutManager() {
    private val maxVisible = 4
    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.MATCH_PARENT)
    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        val itemCount = itemCount
        if (itemCount == 0) return
        val start = 0
        val end = min(itemCount - 1, maxVisible - 1)
        for (i in end downTo start) {
            val view = recycler.getViewForPosition(i)
            addView(view)
            measureChildWithMargins(view, 0, 0)
            val w = getDecoratedMeasuredWidth(view)
            val h = getDecoratedMeasuredHeight(view)
            val left = (width - w) / 2
            val top = (height - h) / 2
            layoutDecoratedWithMargins(view, left, top, left + w, top + h)
            val level = end - i
            val scale = 1f - level * 0.05f
            view.scaleX = scale
            view.scaleY = scale
            view.translationY = level * (h * 0.06f)
            view.elevation = level.toFloat()
            view.alpha = 1f - level * 0.05f
        }
    }
}
