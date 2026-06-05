package iam699030.gmail.movitop.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import iam699030.gmail.movitop.R

/**
 * Renders [ChatMessage]s. A single item layout is reused for both senders; the
 * bubble alignment, background and text color are switched per [Sender].
 */
class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.MessageViewHolder>(DIFF) {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: TextView = view.findViewById(R.id.messageBubble)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position)
        val context = holder.itemView.context
        holder.bubble.text = message.text

        val params = holder.bubble.layoutParams as LinearLayout.LayoutParams
        when (message.sender) {
            Sender.BOT -> {
                params.gravity = Gravity.START
                holder.bubble.setBackgroundResource(R.drawable.bg_bubble_incoming)
                holder.bubble.setTextColor(
                    ContextCompat.getColor(context, R.color.movitop_on_bubble_incoming)
                )
            }
            Sender.USER -> {
                params.gravity = Gravity.END
                holder.bubble.setBackgroundResource(R.drawable.bg_bubble_outgoing)
                holder.bubble.setTextColor(
                    ContextCompat.getColor(context, R.color.movitop_on_bubble_outgoing)
                )
            }
        }
        holder.bubble.layoutParams = params
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(old: ChatMessage, new: ChatMessage) = old.id == new.id
            override fun areContentsTheSame(old: ChatMessage, new: ChatMessage) = old == new
        }
    }
}
