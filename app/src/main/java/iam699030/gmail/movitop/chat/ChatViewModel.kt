package iam699030.gmail.movitop.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.MotisRepository
import iam699030.gmail.movitop.data.RealMotisRepository
import iam699030.gmail.movitop.data.RouteDetail
import iam699030.gmail.movitop.data.RouteOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the chat conversation state. Drives the dialog state machine:
 *
 *   AwaitingDestination -> AwaitingOrigin -> Calculating -> ResultsReady
 *                                                             -> ViewingRouteDetails
 *
 * User input is parsed by [HebrewParser]; missing slots are requested, and once
 * both origin and destination are known the routes are fetched from MOTIS and
 * the results bottom sheet is triggered. Selecting a route loads its
 * step-by-step detail + map polyline.
 */
class ChatViewModel(
    application: Application
) : AndroidViewModel(application) {

    // Dependency injection point: the real MOTIS-backed repository.
    private val repository: MotisRepository = RealMotisRepository()

    sealed interface ConversationState {
        data object AwaitingDestination : ConversationState
        data object AwaitingOrigin : ConversationState
        data object Calculating : ConversationState
        data object ResultsReady : ConversationState
        data class ViewingRouteDetails(val selectedRoute: RouteOption) : ConversationState
    }

    private val idGenerator = AtomicLong(0L)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _state = MutableStateFlow<ConversationState>(ConversationState.AwaitingDestination)
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _routes = MutableStateFlow<List<RouteOption>?>(null)
    val routes: StateFlow<List<RouteOption>?> = _routes.asStateFlow()

    private val _routeDetail = MutableStateFlow<RouteDetail?>(null)
    val routeDetail: StateFlow<RouteDetail?> = _routeDetail.asStateFlow()

    private var pendingOrigin: String? = null
    private var pendingDestination: String? = null

    init {
        postGreeting()
    }

    private fun postGreeting() {
        val greetings = appContext().resources.getStringArray(R.array.chat_greetings)
        addBot(greetings[greetings.indices.random()])
    }

    fun onUserInput(rawInput: String) {
        val text = rawInput.trim()
        if (text.isEmpty()) return

        addUser(text)

        if (_state.value == ConversationState.Calculating) return

        if (_state.value == ConversationState.ResultsReady ||
            _state.value is ConversationState.ViewingRouteDetails
        ) {
            startNewSearch()
        }

        val parsed = HebrewParser.parse(text)
        if (_state.value == ConversationState.AwaitingOrigin) {
            pendingOrigin = parsed.origin ?: parsed.destination ?: text
        } else {
            parsed.origin?.let { pendingOrigin = it }
            parsed.destination?.let { pendingDestination = it }
        }

        advance()
    }

    private fun advance() {
        when {
            pendingDestination == null -> {
                _state.value = ConversationState.AwaitingDestination
                addBot(getString(R.string.chat_ask_destination))
            }
            pendingOrigin == null -> {
                _state.value = ConversationState.AwaitingOrigin
                addBot(getString(R.string.chat_ask_origin))
            }
            else -> calculateRoutes()
        }
    }

    private fun calculateRoutes() {
        val origin = pendingOrigin ?: return
        val destination = pendingDestination ?: return
        _state.value = ConversationState.Calculating
        addBot(getString(R.string.chat_calculating, origin, destination))

        viewModelScope.launch {
            try {
                val result = repository.getRoutes(origin, destination)
                if (result.isEmpty()) {
                    addBot(getString(R.string.chat_no_routes))
                    pendingDestination = null
                    _state.value = ConversationState.AwaitingDestination
                    return@launch
                }
                _routes.value = result
                addBot(getString(R.string.chat_results_found, result.size))
                _state.value = ConversationState.ResultsReady
            } catch (e: Exception) {
                addBot(getString(R.string.chat_error_network))
                _state.value = ConversationState.AwaitingDestination
            }
        }
    }

    /** Called when the user selects a mode in the summary list. */
    fun selectRoute(option: RouteOption) {
        if (_state.value != ConversationState.ResultsReady) return
        val origin = pendingOrigin ?: return
        val destination = pendingDestination ?: return
        _routeDetail.value = null
        _state.value = ConversationState.ViewingRouteDetails(option)

        viewModelScope.launch {
            try {
                _routeDetail.value = repository.getRouteDetail(origin, destination, option.mode)
            } catch (e: Exception) {
                addBot(getString(R.string.chat_error_network))
            }
        }
    }

    /** Returns from the detail view back to the 5-option summary. */
    fun backToSummary() {
        if (_state.value is ConversationState.ViewingRouteDetails) {
            _routeDetail.value = null
            _state.value = ConversationState.ResultsReady
        }
    }

    /** Clears slots/results so the next message begins a new trip query. */
    private fun startNewSearch() {
        pendingOrigin = null
        pendingDestination = null
        _routes.value = null
        _routeDetail.value = null
        _state.value = ConversationState.AwaitingDestination
    }

    private fun addBot(text: String) = appendMessage(text, Sender.BOT)
    private fun addUser(text: String) = appendMessage(text, Sender.USER)

    private fun appendMessage(text: String, sender: Sender) {
        val message = ChatMessage(idGenerator.incrementAndGet(), text, sender)
        _messages.value = _messages.value + message
    }

    private fun appContext(): Application = getApplication()
    private fun getString(resId: Int, vararg args: Any): String =
        appContext().getString(resId, *args)
}
