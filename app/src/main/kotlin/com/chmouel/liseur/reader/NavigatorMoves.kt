package com.chmouel.liseur.reader

import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

// Readium leaves a navigator's pages marked loaded after their views are
// torn down, by a rotation or by the reader closing, and a move sent to it
// then throws instead of returning false. Code that moves the reader after
// waiting can be holding such a navigator, so it asks it only while it is
// still on screen.

fun EpubNavigatorFragment.goIfShown(locator: Locator): Boolean = view != null && go(locator, animated = false)

fun EpubNavigatorFragment.goIfShown(link: Link): Boolean = view != null && go(link, animated = false)
