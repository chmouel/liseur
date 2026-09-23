package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.bookorbit.BookOrbitConflictPreview
import com.chmouel.liseur.data.bookorbit.BookOrbitOpenedEpub
import com.chmouel.liseur.data.bookorbit.BookOrbitPullOffer

/** Reader-lifetime proposals and proof, discarded when exact opening fails. */
internal class BookOrbitOpening {
    var pull: BookOrbitPullOffer? = null
        private set
    var choice: Pair<BookOrbitConflictPreview, BookOrbitPullOffer?>? = null
        private set
    var verifiedPull: BookOrbitPullOffer? = null
        private set
    var verifiedEpub: BookOrbitOpenedEpub? = null
        private set
    var chosen: Pair<BookOrbitConflictPreview, Boolean>? = null
        private set

    fun propose(
        pull: BookOrbitPullOffer?,
        choice: Pair<BookOrbitConflictPreview, BookOrbitPullOffer?>?,
    ) {
        failed()
        this.pull = pull
        this.choice = choice
    }

    fun verify(locatorJson: String, opened: BookOrbitOpenedEpub?): BookOrbitConflictPreview? {
        if (opened == null) return null
        choice?.takeIf { (_, offer) ->
            offer != null && locatorJson == offer.locatorJson && opened.context == offer.context
        }?.let { (preview, _) ->
            verifiedEpub = opened
            return preview
        }
        pull?.takeIf {
            locatorJson == it.locatorJson && opened.context == it.context
        }?.let {
            verifiedPull = it
            verifiedEpub = opened
        }
        return null
    }

    fun choose(preview: BookOrbitConflictPreview, takeRemote: Boolean): Boolean {
        if (choice?.first != preview ||
            (takeRemote && choice?.second == null) ||
            (choice?.second == null && !preview.retryRequired) ||
            (choice?.second != null && verifiedEpub == null)
        ) return false
        chosen = preview to takeRemote
        return true
    }

    fun failed() {
        pull = null
        choice = null
        verifiedPull = null
        verifiedEpub = null
        chosen = null
    }

    fun navigatorLost() {
        // View teardown also precedes closing; keep automatic pulls and accepted choices.
        if (chosen != null || choice?.second == null) return
        verifiedPull = null
        verifiedEpub = null
    }
}
