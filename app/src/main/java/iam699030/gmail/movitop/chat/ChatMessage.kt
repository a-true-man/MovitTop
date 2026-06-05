package iam699030.gmail.movitop.chat

/** A single chat bubble. [id] gives DiffUtil a stable identity. */
data class ChatMessage(
    val id: Long,
    val text: String,
    val sender: Sender
)

enum class Sender {
    /** Movitop assistant (incoming, left-aligned). */
    BOT,

    /** The user (outgoing, right-aligned). */
    USER
}
