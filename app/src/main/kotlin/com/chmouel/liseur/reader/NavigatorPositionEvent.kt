package com.chmouel.liseur.reader

/** Why the navigator emitted a position, and which side effects it is allowed to cause. */
enum class NavigatorPositionEvent(
    val persists: Boolean,
    val teachesPace: Boolean,
    val recordsReadingTime: Boolean,
) {
    READER_MOVEMENT(persists = true, teachesPace = true, recordsReadingTime = true),
    LOCAL_JUMP(persists = true, teachesPace = false, recordsReadingTime = false),
    REMOTE_ADOPTION(persists = false, teachesPace = false, recordsReadingTime = false),
    PREFERENCE_REFLOW(persists = false, teachesPace = false, recordsReadingTime = false),
    FRAGMENT_RECREATION(persists = false, teachesPace = false, recordsReadingTime = false),
    LIFECYCLE_REPLAY(persists = false, teachesPace = false, recordsReadingTime = false),

    /**
     * The navigator reporting itself while a book is still being
     * reopened, before restoration has landed.
     */
    OPENING_RESTORATION(persists = false, teachesPace = false, recordsReadingTime = false),
}

/**
 * What an emission means, and whether it spends the marker that labels it.
 *
 * @param event how the emission should be treated.
 * @param markerSurvives whether the pending marker is still owed to a
 *   later emission. A marker is single use, so this is what stops
 *   opening noise from spending one that belongs to the arrival.
 */
data class NavigatorEmission(
    val event: NavigatorPositionEvent,
    val markerSurvives: Boolean,
)

/**
 * Decide what the navigator just told us.
 *
 * Pure so the interaction between the four inputs can be tested without
 * a navigator: it is subtle enough to have been wrong once. A
 * navigation's marker must outlive an emission the opening gate
 * suppressed as noise, because `go` is asynchronous and the position
 * being left behind can be reported first. Spending the marker there
 * leaves the real arrival looking like the reader turning a page — a
 * jump would teach the pace estimator a distance nobody read, and an
 * adopted remote position would be published straight back as local
 * movement, which is the loop the gate exists to break.
 *
 * The emission that ends the restoration does spend it, so the page turn
 * after it is not labelled a jump.
 *
 * @param requested the marker set alongside a navigation, if any.
 * @param suppressed whether the opening gate refused this emission.
 * @param landed whether this emission is the one that ended the
 *   restoration rather than more of the opening.
 * @param reflowActive whether the page is being rebuilt underneath.
 */
fun classifyNavigatorEmission(
    requested: NavigatorPositionEvent?,
    suppressed: Boolean,
    landed: Boolean,
    reflowActive: Boolean,
): NavigatorEmission = NavigatorEmission(
    event = when {
        suppressed -> NavigatorPositionEvent.OPENING_RESTORATION
        requested != null -> requested
        reflowActive -> NavigatorPositionEvent.PREFERENCE_REFLOW
        else -> NavigatorPositionEvent.READER_MOVEMENT
    },
    markerSurvives = requested != null && suppressed && !landed,
)
