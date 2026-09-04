package com.chmouel.liseur.reader.chrome

/**
 * Which of the two pinch gestures a touch belongs to, once the document
 * has answered late.
 *
 * The page is asked what is under the finger as the finger lands, and the
 * answer arrives whenever it arrives. Usually that is before the second
 * finger is down and the question never comes up. When it is not, the
 * answer must not be allowed to yank the gesture out from under fingers
 * that have spent that time resizing — so a resize that has taken the
 * touch keeps it, unless the answer is young enough to have been part of
 * the same decision.
 *
 * A touch nothing has taken is deliberately not on a clock. Fingers that
 * take their time arriving at a picture, and a long press, are both
 * slower than the budget, and both still get the picture.
 *
 * Kept apart from the pointer loop because the interesting case is a
 * *sequence*: a resize is forgotten the instant it drops to one finger,
 * while the touch itself runs on until the last finger leaves. Reading
 * the live pinch there would make a long resize count as unclaimed again
 * the moment a finger lifted, and a replacement finger landing then would
 * open the picture the first finger had been over. So the claim is held
 * here, for the life of the whole touch, and cleared only by a touch that
 * began with no fingers already down.
 */
class GestureClaim(private val budgetMs: Long) {
    private var startedAt = 0L
    private var resizeHolds = false

    /** True once a resize has had hold of the touch now in progress. */
    val claimed: Boolean get() = resizeHolds

    /** A genuinely new touch: every finger had left before this one landed. */
    fun begin(nowMs: Long) {
        startedAt = nowMs
        resizeHolds = false
    }

    /** A resize has taken this touch, and keeps it until the touch ends. */
    fun resizeTook() {
        resizeHolds = true
    }

    /** Whether an image answer may still decide what this touch means. */
    fun imageMayWin(nowMs: Long): Boolean =
        !resizeHolds || nowMs - startedAt <= budgetMs
}
