package iam699030.gmail.movitop.data

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import iam699030.gmail.movitop.R

/**
 * The "Line times" search results / saved-lines list — one card per distinct
 * line (see [LineSummary]), its two directions already merged upstream.
 * Tapping a card drills into that line's timetable; the star toggles it as a
 * saved line (keyed by [LineSummary.routeId], the merged line's own stable
 * identity — see FavoriteLinesRepository — not the bare route number, which
 * several unrelated lines can share).
 */
class LineSummaryAdapter(
    private val onSelected: (LineSummary) -> Unit,
    private val onToggleFavorite: (LineSummary) -> Unit
) : ListAdapter<LineSummary, LineSummaryAdapter.ViewHolder>(DIFF) {

    private var favoriteLineIds: Set<String> = emptySet()

    /** Refreshes which cards show as starred without touching the line list itself. */
    fun setFavorites(lineIds: Collection<String>) {
        val updated = lineIds.toSet()
        if (updated == favoriteLineIds) return
        favoriteLineIds = updated
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val badge: TextView = view.findViewById(R.id.lineSummaryBadge)
        val agency: TextView = view.findViewById(R.id.lineSummaryAgency)
        val endpoints: TextView = view.findViewById(R.id.lineSummaryEndpoints)
        val starButton: MaterialButton = view.findViewById(R.id.lineSummaryFavoriteStarButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_line_summary, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        holder.badge.text = item.routeShortName
        holder.agency.text = item.agencyName.ifBlank { item.routeLongName }
        holder.endpoints.text = endpointsLabel(
            item.firstStopName, item.lastStopName, ContextCompat.getColor(context, R.color.movitop_primary)
        )

        val isFavorite = item.routeId in favoriteLineIds
        holder.starButton.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(
                context,
                if (isFavorite) R.color.movitop_star_active else R.color.movitop_text_secondary
            )
        )
        holder.starButton.contentDescription = context.getString(
            if (isFavorite) R.string.favorite_star_remove_description else R.string.favorite_star_add_description
        )
        holder.starButton.setOnClickListener { onToggleFavorite(item) }
        holder.itemView.setOnClickListener { onSelected(item) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onSelected(item)
                true
            } else {
                false
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LineSummary>() {
            override fun areItemsTheSame(old: LineSummary, new: LineSummary) = old.routeId == new.routeId
            override fun areContentsTheSame(old: LineSummary, new: LineSummary) = old == new
        }

        /** "from ⇄ to", with the arrow bold and in [arrowColor] instead of a bare glyph mid-sentence. */
        fun endpointsLabel(from: String, to: String, arrowColor: Int): CharSequence {
            val arrow = " ⇄ "
            val text = "$from$arrow$to"
            val start = from.length
            val end = start + arrow.length
            return SpannableString(text).apply {
                setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(arrowColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
