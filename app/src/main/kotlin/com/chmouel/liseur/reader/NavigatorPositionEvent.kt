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
