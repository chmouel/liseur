package com.chmouel.liseur.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.sync.LibrarySyncBackend
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.theme.BrandPalette
import com.chmouel.liseur.ui.theme.LiseurTypography
import com.chmouel.liseur.ui.theme.MonoPalette
import com.chmouel.liseur.ui.theme.homePalette
import com.chmouel.liseur.ui.windowWidth
import kotlinx.coroutines.delay

/**
 * The shape every one of the library's empty screens takes.
 *
 * All four of them used to build this by hand, and had started to drift
 * apart doing it -- two carried an icon and two did not, the buttons
 * were of three different kinds. One scaffold so that saying nothing is
 * on the shelf looks the same whichever reason put it there.
 *
 * The column scrolls even when it fits. An empty library is exactly
 * when someone needs to pull from the top: they have just added a
 * folder or connected an account and are waiting for books to turn up,
 * and the refresh has to stay in reach.
 *
 * [art] is handed the height the column has been given, because that is
 * the only thing that can decide whether there is room for a picture:
 * in landscape, on a short window, there is not.
 */
@Composable
internal fun LibraryEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    art: @Composable ((available: Dp) -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val cap = contentWidthCap(windowWidth())
    BoxWithConstraints(modifier) {
        val available = maxHeight
        // A phone on its side. There is no room here for a picture and
        // not much for air either, so the screen gives both up rather
        // than push the last way out of an empty library off the
        // bottom.
        val cramped = available < ROOM_FOR_ART
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .heightIn(min = available)
                .padding(horizontal = 24.dp, vertical = if (cramped) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Column(
                modifier = Modifier.widthIn(max = cap),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (art != null && !cramped) {
                    Box(Modifier.entrance(delayMillis = 0)) { art(available) }
                }
                Column(
                    modifier = Modifier.entrance(delayMillis = ENTRANCE_STEP_MS),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        textAlign = TextAlign.Center,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (!cramped) Spacer(Modifier.height(4.dp))
                actions()
            }
        }
    }
}

internal enum class ActionCardProminence {
    PRIMARY,
    SECONDARY,
    COMPACT,
}

/**
 * A route out of an empty screen: what it is, what it does, and an
 * arrow saying it leads somewhere.
 *
 * The supporting line is the point of the card. Three buttons in a
 * column read as three flavours of one action; the difference between
 * pointing at a folder and logging into a server is not something a
 * four-word label can carry.
 */
@Composable
internal fun LibraryActionCard(
    icon: ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable (Dp) -> Unit)? = null,
    busy: Boolean = false,
    prominence: ActionCardProminence = ActionCardProminence.SECONDARY,
) {
    val eInk = LocalEInk.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = homePalette()
    // Held under a second name because the semantics block below has an
    // onClick of its own, and inside it the plain name is the wrong one
    // to reach for.
    val activate = onClick

    val shape = when (prominence) {
        ActionCardProminence.PRIMARY -> RoundedCornerShape(24.dp)
        ActionCardProminence.SECONDARY -> RoundedCornerShape(20.dp)
        ActionCardProminence.COMPACT -> RoundedCornerShape(16.dp)
    }

    val containerColor = when {
        eInk -> when (prominence) {
            ActionCardProminence.PRIMARY -> MaterialTheme.colorScheme.surfaceContainerHigh
            ActionCardProminence.SECONDARY -> MaterialTheme.colorScheme.surfaceContainer
            ActionCardProminence.COMPACT -> MaterialTheme.colorScheme.surfaceContainerLow
        }
        prominence == ActionCardProminence.PRIMARY ->
            if (dark) HeroCardDark else HeroCardLight
        prominence == ActionCardProminence.SECONDARY ->
            palette?.card ?: MaterialTheme.colorScheme.surfaceContainer
        else ->
            if (dark) {
                palette?.card?.copy(alpha = 0.72f) ?: MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
    }

    val border = when {
        eInk -> BorderStroke(
            width = if (prominence == ActionCardProminence.PRIMARY) 2.dp else 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        prominence == ActionCardProminence.PRIMARY ->
            if (dark) null else BorderStroke(1.dp, HeroBorderLight)
        palette != null -> BorderStroke(
            width = 1.dp,
            color = if (prominence == ActionCardProminence.COMPACT && !dark) {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            } else {
                palette.cardBorder
            },
        )
        else -> null
    }

    val badgeColor = when {
        eInk -> MaterialTheme.colorScheme.secondaryContainer
        prominence == ActionCardProminence.PRIMARY ->
            if (dark) HeroBadgeDark else HeroBadgeLight
        palette != null -> palette.pill
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val badgeContentColor = when {
        eInk -> MaterialTheme.colorScheme.onSecondaryContainer
        prominence == ActionCardProminence.PRIMARY ->
            if (dark) HeroInkDark else HeroIconLight
        palette != null -> palette.onPill
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    val titleColor = when {
        eInk -> MaterialTheme.colorScheme.onSurface
        prominence == ActionCardProminence.PRIMARY ->
            if (dark) HeroInkDark else HeroInkLight
        else -> MaterialTheme.colorScheme.onSurface
    }

    val hintColor = when {
        eInk -> MaterialTheme.colorScheme.onSurfaceVariant
        prominence == ActionCardProminence.PRIMARY ->
            if (dark) HeroSubtitleDark else HeroSubtitleLight
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val chevronColor = when {
        eInk -> MaterialTheme.colorScheme.onSurfaceVariant
        prominence == ActionCardProminence.PRIMARY ->
            if (dark) HeroInkDark else HeroInkLight
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val minHeight = when (prominence) {
        ActionCardProminence.PRIMARY -> 88.dp
        ActionCardProminence.SECONDARY -> 74.dp
        ActionCardProminence.COMPACT -> 64.dp
    }

    val badgeSize = when (prominence) {
        ActionCardProminence.PRIMARY -> 52.dp
        ActionCardProminence.SECONDARY -> 44.dp
        ActionCardProminence.COMPACT -> 38.dp
    }

    val iconSize = when (prominence) {
        ActionCardProminence.PRIMARY -> 26.dp
        ActionCardProminence.SECONDARY -> 22.dp
        ActionCardProminence.COMPACT -> 20.dp
    }

    val horizontalPadding = when (prominence) {
        ActionCardProminence.PRIMARY -> 18.dp
        ActionCardProminence.SECONDARY -> 16.dp
        ActionCardProminence.COMPACT -> 14.dp
    }

    val verticalPadding = when (prominence) {
        ActionCardProminence.PRIMARY -> 16.dp
        ActionCardProminence.SECONDARY -> 13.dp
        ActionCardProminence.COMPACT -> 11.dp
    }

    val titleStyle = when (prominence) {
        ActionCardProminence.PRIMARY -> MaterialTheme.typography.titleLarge.copy(
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        ActionCardProminence.SECONDARY -> MaterialTheme.typography.titleLarge.copy(
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        ActionCardProminence.COMPACT -> MaterialTheme.typography.titleLarge.copy(
            fontSize = 15.5.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }

    val hintStyle = when (prominence) {
        ActionCardProminence.PRIMARY -> MaterialTheme.typography.bodyMedium.copy(
            lineHeight = 19.sp,
        )
        ActionCardProminence.SECONDARY, ActionCardProminence.COMPACT -> MaterialTheme.typography.bodySmall
    }

    Card(
        onClick = onClick,
        enabled = !busy,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = titleColor,
            disabledContainerColor = containerColor,
            disabledContentColor = titleColor,
        ),
        // A shadow is invisible on e-paper, so there the card is drawn
        // with an edge instead of lifted off the page.
        elevation = CardDefaults.cardElevation(
            defaultElevation = when {
                eInk -> 0.dp
                prominence == ActionCardProminence.PRIMARY && !dark -> 2.dp
                prominence == ActionCardProminence.COMPACT -> 0.dp
                else -> 1.dp
            },
        ),
        border = border,
        // One target, and one thing said about it: read out label by
        // label, the icon and the arrow turn a single choice into three
        // fragments to listen through.
        //
        // Clearing takes the card's own click semantics with it, so the
        // role and a working action have to be put back by hand.
        // Without them the card still looks like a button and still
        // answers a finger, but a screen reader is handed a paragraph
        // it cannot press.
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clearAndSetSemantics {
                contentDescription = "$title. $hint"
                role = Role.Button
                // Nothing to press while the card is already doing what
                // pressing it asks for, and offering the action anyway
                // would let a screen reader start a second one.
                if (busy) {
                    disabled()
                } else {
                    onClick(label = title) {
                        activate()
                        true
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = badgeColor,
                contentColor = badgeContentColor,
                modifier = Modifier.size(badgeSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (leadingIcon != null) {
                        leadingIcon(iconSize)
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }
            }
            Spacer(Modifier.width(if (prominence == ActionCardProminence.COMPACT) 14.dp else 16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = titleColor,
                )
                Text(
                    text = hint,
                    style = hintStyle,
                    color = hintColor,
                )
            }
            Spacer(Modifier.width(8.dp))
            // In the arrow's place rather than beside it: the arrow says
            // "this leads somewhere", and while the card is already on
            // its way that is the one thing it no longer needs to say.
            if (busy) {
                BusyIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = chevronColor,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = chevronColor,
                )
            }
        }
    }
}

/** The glyph the reasons-other-than-empty screens lead with. */
@Composable
private fun LibraryEmptyIcon(icon: ImageVector) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(80.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

/**
 * The first screen anyone sees, and the only chance the app gets to say
 * what it is for.
 *
 * The primary hero action opens a sheet for picking a single EPUB or a
 * watched folder; below it sit free public-domain reads and a bundled
 * sample book so a new reader can explore Liseur immediately, followed
 * by quieter cards for connecting a library server or restoring a
 * backup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmptyLibrary(
    onAddBook: () -> Unit,
    onAddFolder: () -> Unit,
    onConnectServer: () -> Unit,
    modifier: Modifier = Modifier,
    onStartWithFreeBooks: () -> Unit = {},
    onTrySampleBook: () -> Unit = {},
    openingSample: Boolean = false,
    onRestoreBackup: () -> Unit = {},
    librarySync: LibrarySyncBackend = LibrarySyncBackend.None,
) {
    // Which cut of the mark to draw is asked of the theme in force
    // here, not of a -night qualifier: those follow the system, and the
    // app's own light/dark setting is allowed to disagree.
    val darkMark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }

    if (addSheetOpen) {
        AddFirstBookSheet(
            onDismiss = { addSheetOpen = false },
            onPickBook = {
                addSheetOpen = false
                onAddBook()
            },
            onPickFolder = {
                addSheetOpen = false
                onAddFolder()
            },
        )
    }

    LibraryEmptyState(
        title = stringResource(R.string.welcome_title),
        subtitle = stringResource(R.string.welcome_subtitle),
        art = { available ->
            WelcomeHeroEmblem(
                darkMark = darkMark,
                height = heroHeight(available),
            )
        },
        modifier = modifier,
    ) {
        LibraryActionCard(
            icon = Icons.Outlined.LibraryAdd,
            title = stringResource(R.string.welcome_add_first_book),
            hint = stringResource(R.string.welcome_add_first_book_hint),
            onClick = { addSheetOpen = true },
            prominence = ActionCardProminence.PRIMARY,
            modifier = Modifier.entrance(delayMillis = 2 * ENTRANCE_STEP_MS),
        )
        LibraryActionCard(
            icon = Icons.Outlined.AutoStories,
            title = stringResource(R.string.welcome_browse_free_books),
            hint = stringResource(R.string.welcome_browse_free_books_hint),
            onClick = onStartWithFreeBooks,
            prominence = ActionCardProminence.SECONDARY,
            modifier = Modifier.entrance(delayMillis = 3 * ENTRANCE_STEP_MS),
        )
        LibraryActionCard(
            icon = Icons.Outlined.AutoAwesome,
            title = stringResource(R.string.welcome_try_sample_book),
            hint = stringResource(R.string.welcome_try_sample_book_hint),
            onClick = onTrySampleBook,
            busy = openingSample,
            prominence = ActionCardProminence.SECONDARY,
            modifier = Modifier.entrance(delayMillis = 4 * ENTRANCE_STEP_MS),
        )
        MoreWaysDivider(
            text = stringResource(R.string.welcome_more_ways),
            modifier = Modifier
                .padding(top = 6.dp)
                .entrance(delayMillis = 5 * ENTRANCE_STEP_MS),
        )
        LibraryActionCard(
            icon = Icons.Outlined.CloudQueue,
            leadingIcon = if (librarySync.supportsBookServerOption) {
                { size -> librarySync.WelcomeConnectIcon(Modifier.size(size)) }
            } else {
                null
            },
            title = stringResource(R.string.welcome_connect_library),
            hint = librarySync.welcomeConnectHint()
                ?: stringResource(R.string.welcome_connect_library_hint),
            onClick = onConnectServer,
            prominence = ActionCardProminence.COMPACT,
            modifier = Modifier.entrance(delayMillis = 6 * ENTRANCE_STEP_MS),
        )
        LibraryActionCard(
            icon = Icons.Outlined.History,
            title = stringResource(R.string.welcome_restore_backup),
            hint = stringResource(R.string.welcome_restore_backup_hint),
            onClick = onRestoreBackup,
            prominence = ActionCardProminence.COMPACT,
            modifier = Modifier.entrance(delayMillis = 7 * ENTRANCE_STEP_MS),
        )
        WelcomeReassuranceFooter(
            text = stringResource(R.string.welcome_footer_reassurance),
            modifier = Modifier
                .padding(top = 4.dp)
                .entrance(delayMillis = 8 * ENTRANCE_STEP_MS),
        )
    }
}

/**
 * The welcome emblem, with a playful tap Easter egg: tapping the illustration
 * makes the reader look up, smile, wave, and greet you with a speech bubble
 * before gently returning to her book.
 */
@Composable
private fun WelcomeHeroEmblem(
    darkMark: Boolean,
    height: Dp,
) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    var sayingHello by remember { mutableStateOf(false) }

    LaunchedEffect(sayingHello) {
        if (sayingHello) {
            delay(EMBLEM_HELLO_DURATION_MS)
            sayingHello = false
        }
    }

    val appName = stringResource(R.string.app_name)
    val helloLabel = stringResource(R.string.welcome_emblem_hello)
    val helloProgress by animateFloatAsState(
        targetValue = if (sayingHello) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (eInk) 0 else EMBLEM_CROSSFADE_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "welcomeHeroEmblemCrossfade",
    )
    val readingRes = if (darkMark) {
        R.drawable.ic_brand_emblem_night_transparent
    } else {
        R.drawable.ic_brand_emblem_transparent
    }
    val helloRes = if (darkMark) {
        R.drawable.ic_brand_emblem_hello_night_transparent
    } else {
        R.drawable.ic_brand_emblem_hello_transparent
    }

    val bubbleColors = remember(palette, darkMark, eInk) {
        emblemHelloBubbleColors(palette, darkMark, eInk)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .semantics(mergeDescendants = true) {
                contentDescription = if (sayingHello) "$appName, $helloLabel" else appName
                role = Role.Button
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = helloLabel,
                role = Role.Button,
            ) {
                sayingHello = !sayingHello
            },
        contentAlignment = Alignment.Center,
    ) {
        // Both drawables share identical dimensions and framing so the arch,
        // leaves, armchair, and lamp stay locked in place across the dissolve.
        if (helloProgress < 1f) {
            Image(
                painter = painterResource(readingRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .graphicsLayer { alpha = 1f - helloProgress },
            )
        }
        if (helloProgress > 0f) {
            Image(
                painter = painterResource(helloRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .graphicsLayer { alpha = helloProgress },
            )
        }

        val fontScale = LocalDensity.current.fontScale
        val bubbles = if (height >= 150.dp && fontScale <= 1.15f) {
            EMBLEM_HELLO_BUBBLES
        } else {
            EMBLEM_HELLO_BUBBLES_COMPACT
        }
        bubbles.forEachIndexed { index, spec ->
            val tint = bubbleColors[index % bubbleColors.size]
            val delayMs = if (eInk) 0 else index * EMBLEM_BUBBLE_STAGGER_MS
            val yOffset = height * spec.yFraction
            AnimatedVisibility(
                visible = sayingHello,
                enter = if (eInk) {
                    fadeIn(tween(0))
                } else {
                    fadeIn(tween(240, delayMillis = delayMs)) +
                        scaleIn(initialScale = 0.78f, animationSpec = tween(260, delayMillis = delayMs))
                },
                exit = if (eInk) {
                    fadeOut(tween(0))
                } else {
                    fadeOut(tween(180)) + scaleOut(targetScale = 0.88f, animationSpec = tween(180))
                },
                modifier = Modifier
                    .align(spec.alignment)
                    .offset(x = spec.xOffset, y = yOffset),
            ) {
                Surface(
                    shape = if (spec.alignment == Alignment.TopStart) {
                        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp)
                    } else {
                        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp)
                    },
                    color = tint.container,
                    contentColor = tint.content,
                    border = BorderStroke(1.dp, tint.border),
                    shadowElevation = if (eInk) 0.dp else 1.dp,
                ) {
                    Text(
                        text = spec.text,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = MaterialTheme.typography.headlineSmall.fontFamily,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

private data class HelloBubbleSpec(
    val text: String,
    val alignment: Alignment,
    val xOffset: Dp,
    val yFraction: Float,
)

private data class HelloBubbleTint(
    val container: Color,
    val content: Color,
    val border: Color,
)

private val EMBLEM_HELLO_BUBBLES = listOf(
    HelloBubbleSpec("Bonjour !", Alignment.TopStart, 14.dp, 0.00f),
    HelloBubbleSpec("Hallo!", Alignment.TopStart, 0.dp, 0.20f),
    HelloBubbleSpec("¡Hola!", Alignment.TopStart, 6.dp, 0.40f),
    HelloBubbleSpec("Ciao!", Alignment.TopStart, 0.dp, 0.61f),
    HelloBubbleSpec("مرحباً!", Alignment.TopStart, 6.dp, 0.81f),
    HelloBubbleSpec("Hello!", Alignment.TopEnd, (-16).dp, 0.00f),
    HelloBubbleSpec("Olá!", Alignment.TopEnd, 0.dp, 0.17f),
    HelloBubbleSpec("Ahoj!", Alignment.TopEnd, (-4).dp, 0.34f),
    HelloBubbleSpec("Привет!", Alignment.TopEnd, 0.dp, 0.51f),
    HelloBubbleSpec("שלום!", Alignment.TopEnd, (-4).dp, 0.68f),
    HelloBubbleSpec("你好！", Alignment.TopEnd, (-12).dp, 0.85f),
)

private val EMBLEM_HELLO_BUBBLES_COMPACT = listOf(
    HelloBubbleSpec("Bonjour !", Alignment.TopStart, 6.dp, 0.04f),
    HelloBubbleSpec("مرحباً!", Alignment.TopStart, 0.dp, 0.54f),
    HelloBubbleSpec("Hello!", Alignment.TopEnd, (-6).dp, 0.04f),
    HelloBubbleSpec("שלום!", Alignment.TopEnd, 0.dp, 0.54f),
)

private fun emblemHelloBubbleColors(
    palette: com.chmouel.liseur.ui.theme.HomePalette?,
    darkMark: Boolean,
    eInk: Boolean,
): List<HelloBubbleTint> {
    if (eInk) {
        val fg = if (darkMark) Color.White else Color.Black
        val bg = if (darkMark) Color.Black else Color.White
        return listOf(HelloBubbleTint(bg, fg, fg))
    }
    if (palette != null) {
        val rose = if (darkMark) {
            HelloBubbleTint(Color(0xFF3A2535), Color(0xFFF5B2CC), Color(0xFF55364E))
        } else {
            HelloBubbleTint(Color(0xFFFCEBF2), Color(0xFF9C3560), Color(0xFFF3CFE0))
        }
        val sage = if (darkMark) {
            HelloBubbleTint(Color(0xFF213128), Color(0xFFA7E8BE), Color(0xFF324C3D))
        } else {
            HelloBubbleTint(Color(0xFFEAF7EE), Color(0xFF206B43), Color(0xFFCDEBD7))
        }
        return listOf(
            HelloBubbleTint(palette.toRead.container, palette.toRead.content, palette.toRead.glow),
            HelloBubbleTint(palette.series.container, palette.series.content, palette.series.glow),
            HelloBubbleTint(palette.finished.container, palette.finished.content, palette.finished.glow),
            rose,
            sage,
            HelloBubbleTint(palette.reading.container, palette.reading.content, palette.reading.glow),
            HelloBubbleTint(palette.pill, palette.onPill, palette.cardBorder),
            HelloBubbleTint(palette.series.container, palette.series.content, palette.series.glow),
            HelloBubbleTint(palette.toRead.container, palette.toRead.content, palette.toRead.glow),
            rose,
            HelloBubbleTint(palette.finished.container, palette.finished.content, palette.finished.glow),
        )
    }
    return listOf(
        HelloBubbleTint(Color(0xFFEEE7FF), Color(0xFF4F2FBF), Color(0xFFD9CCFA)),
    )
}

private const val EMBLEM_HELLO_DURATION_MS = 5000L
private const val EMBLEM_CROSSFADE_MS = 420
private const val EMBLEM_BUBBLE_STAGGER_MS = 45

@Composable
private fun MoreWaysDivider(
    text: String,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    val labelColor = when {
        eInk -> MaterialTheme.colorScheme.onSurfaceVariant
        palette != null -> palette.onPill
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val lineColor = when {
        eInk -> MaterialTheme.colorScheme.outline
        palette != null -> palette.onPill.copy(alpha = 0.28f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = labelColor,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = lineColor,
        )
    }
}

@Composable
private fun WelcomeReassuranceFooter(
    text: String,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    val sparkleColor = when {
        eInk -> MaterialTheme.colorScheme.onSurfaceVariant
        palette != null -> palette.onPill
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = sparkleColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFirstBookSheet(
    onDismiss: () -> Unit,
    onPickBook: () -> Unit,
    onPickFolder: () -> Unit,
) {
    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.welcome_add_first_book),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LibraryActionCard(
                icon = Icons.Outlined.LibraryAdd,
                title = stringResource(R.string.welcome_pick_single_book),
                hint = stringResource(R.string.add_book_hint),
                onClick = onPickBook,
                prominence = ActionCardProminence.SECONDARY,
            )
            LibraryActionCard(
                icon = Icons.Outlined.CreateNewFolder,
                title = stringResource(R.string.welcome_pick_folder),
                hint = stringResource(R.string.add_folder_hint),
                onClick = onPickFolder,
                prominence = ActionCardProminence.SECONDARY,
            )
        }
    }
}

/**
 * Shown when the shelf has books on it but a search or a filter is
 * hiding every one of them.
 */
@Composable
internal fun NothingMatched(
    searching: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    titleRes: Int? = null,
) {
    LibraryEmptyState(
        title = stringResource(
            titleRes ?: if (searching) R.string.no_books_match else R.string.no_books_in_filter,
        ),
        art = { LibraryEmptyIcon(Icons.Outlined.Search) },
        modifier = modifier,
    ) {
        FilledTonalButton(
            onClick = onClear,
            modifier = Modifier.entrance(delayMillis = 2 * ENTRANCE_STEP_MS),
        ) {
            Text(stringResource(if (searching) R.string.clear_search else R.string.show_all_books))
        }
    }
}

/**
 * What the library says once every book on it has been archived.
 *
 * It has to lead somewhere, because the way to those books is an entry
 * in a menu, and a menu is not where anyone looks when the screen in
 * front of them is empty.
 */
@Composable
internal fun EverythingArchived(
    onShowArchived: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryEmptyState(
        title = stringResource(R.string.everything_archived),
        art = { LibraryEmptyIcon(Icons.Outlined.Archive) },
        modifier = modifier,
    ) {
        FilledTonalButton(
            onClick = onShowArchived,
            modifier = Modifier.entrance(delayMillis = 2 * ENTRANCE_STEP_MS),
        ) {
            Text(stringResource(R.string.filter_archived))
        }
    }
}

/**
 * What the library says once every book on it has been read.
 *
 * The shelf is hiding them by a rule of the app's own rather than by
 * anything the reader asked for, so this cannot merely report an empty
 * shelf: it has to hand back the books it took.
 */
@Composable
internal fun EverythingFinished(
    onShowFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryEmptyState(
        title = stringResource(R.string.everything_finished),
        art = { LibraryEmptyIcon(Icons.Outlined.CheckCircle) },
        modifier = modifier,
    ) {
        FilledTonalButton(
            onClick = onShowFinished,
            modifier = Modifier.entrance(delayMillis = 2 * ENTRANCE_STEP_MS),
        ) {
            Text(stringResource(R.string.show_finished_books))
        }
    }
}

/**
 * How tall the reading mark may be drawn given [available] height.
 *
 * The scaffold has already ruled out the windows too short for a
 * picture at all; this is only the difference between a tall phone and
 * a merely tall-ish one.
 */
private fun heroHeight(available: Dp): Dp =
    if (available < HERO_FULL_ROOM) 110.dp else 180.dp

/**
 * Shortest window that still has room for a picture above the words.
 *
 * Below it the three routes out of an empty library matter more than
 * the mark above them, and a hero that pushes the last one off the
 * bottom is worse than no hero.
 */
private val ROOM_FOR_ART = 480.dp
private val HERO_FULL_ROOM = 600.dp

private val HeroCardDark = Color(0xFFCAB8FF)
private val HeroBadgeDark = Color.White.copy(alpha = 0.35f)
private val HeroInkDark = Color(0xFF1A1526)
private val HeroSubtitleDark = Color(0xFF2D2540)

private val HeroCardLight = Color(0xFFE5D9FF)
private val HeroBorderLight = Color(0xFFD2BFFF)
private val HeroBadgeLight = Color.White.copy(alpha = 0.65f)
private val HeroIconLight = Color(0xFF4F2FBF)
private val HeroInkLight = Color(0xFF1A142B)
private val HeroSubtitleLight = Color(0xFF3E3459)

/**
 * The screen assembling itself in order, top to bottom.
 *
 * A fade and a small rise, and nothing at all under e-ink, where a
 * fade is a sequence of full-page flashes rather than a fade.
 */
@Composable
private fun Modifier.entrance(delayMillis: Int): Modifier {
    if (LocalEInk.current) return this
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = ENTRANCE_MS, delayMillis = delayMillis),
        label = "libraryEmptyEntrance",
    )
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * ENTRANCE_RISE.toPx()
    }
}

private const val ENTRANCE_MS = 260
private const val ENTRANCE_STEP_MS = 50
private val ENTRANCE_RISE = 12.dp

@Preview(name = "Empty Library - Dark", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun EmptyLibraryDarkPreview() {
    MaterialTheme(colorScheme = BrandPalette.dark, typography = LiseurTypography) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            EmptyLibrary(
                onAddBook = {},
                onAddFolder = {},
                onConnectServer = {},
            )
        }
    }
}

@Preview(name = "Empty Library - Light", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun EmptyLibraryLightPreview() {
    MaterialTheme(colorScheme = BrandPalette.light, typography = LiseurTypography) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            EmptyLibrary(
                onAddBook = {},
                onAddFolder = {},
                onConnectServer = {},
            )
        }
    }
}

@Preview(name = "Empty Library - E-Ink", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun EmptyLibraryEInkPreview() {
    androidx.compose.runtime.CompositionLocalProvider(LocalEInk provides true) {
        MaterialTheme(colorScheme = MonoPalette.light, typography = LiseurTypography) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                EmptyLibrary(
                    onAddBook = {},
                    onAddFolder = {},
                    onConnectServer = {},
                )
            }
        }
    }
}
