package com.chmouel.liseur.ui.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LaunchTarget(val action: String) {
    CONTINUE("com.chmouel.liseur.action.CONTINUE_READING"),
    LIBRARY("com.chmouel.liseur.action.LIBRARY"),
    STATS("com.chmouel.liseur.action.READING_STATISTICS");

    companion object {
        fun fromAction(action: String?): LaunchTarget? = entries.firstOrNull { it.action == action }
    }
}

data class LaunchRequest(
    val id: Long,
    val target: LaunchTarget,
    val shortcut: Boolean,
    val bookUrl: String? = null,
)

/** Widget payloads enter only through the unexported widget activity; shortcuts carry fixed actions. */
class LaunchRequests {
    private var generation = 0L
    private val state = MutableStateFlow<LaunchRequest?>(null)
    val pending = state.asStateFlow()

    val latestId: Long
        @Synchronized get() = generation

    @Synchronized
    fun shortcut(target: LaunchTarget): LaunchRequest =
        post(target, shortcut = true)

    @Synchronized
    internal fun widget(stats: Boolean, bookUrl: String?): LaunchRequest =
        post(if (stats) LaunchTarget.STATS else LaunchTarget.LIBRARY, shortcut = false, bookUrl)

    private fun post(target: LaunchTarget, shortcut: Boolean, bookUrl: String? = null): LaunchRequest {
        val request = LaunchRequest(++generation, target, shortcut, bookUrl)
        state.value = request
        return request
    }

    @Synchronized
    fun restore(target: LaunchTarget): LaunchRequest? =
        if (state.value == null) post(target, shortcut = true) else null

    /**
     * A reader opened outside this queue, such as a downloaded widget book, is the newest navigation.
     * The generation is left alone so the visible library can keep opening books.
     */
    fun supersede() {
        state.value = null
    }

    fun owns(request: LaunchRequest): Boolean = state.value == request

    fun consume(request: LaunchRequest) {
        state.compareAndSet(request, null)
    }

    companion object {
        val shared = LaunchRequests()
    }
}
