package com.chmouel.liseur.readaloud

import android.content.Context
import com.chmouel.liseur.AppContainer

/** The F-Droid build reads nothing aloud: it carries no voice and calls no voice service. */
object ReadAloudFeatureFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, container: AppContainer): ReadAloudFeature = ReadAloudFeature.None
}
