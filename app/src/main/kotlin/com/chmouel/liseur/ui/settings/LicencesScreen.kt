package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One thing Liseur is built out of.
 *
 * [notice] is the copyright line the licence requires to travel with the
 * work, and [licenceAsset] the full text under `assets/licences`. Fonts
 * have both, because the Open Font License asks for the notice and the
 * licence to be distributed with every copy of the font; libraries are
 * named and licensed but not reproduced.
 */
private data class Component(
    val name: String,
    val licence: String,
    val notice: String? = null,
    val licenceAsset: String? = null,
)

/**
 * Everything Liseur is built out of, and the licence it comes under.
 *
 * Kept as a hand-written list rather than generated: the dependency set is
 * small, deliberately all free software, and being able to read it without
 * building the app is the point. The fonts include the ones Readium brings
 * with it, because they ship inside the app whether or not we chose them.
 */
private val Components = listOf(
    Component("Readium Kotlin Toolkit", "BSD 3-Clause"),
    Component("AndroidX / Jetpack Compose", "Apache 2.0"),
    Component("Material Components", "Apache 2.0"),
    Component("Kotlin standard library and coroutines", "Apache 2.0"),
    Component("OkHttp", "Apache 2.0"),
    Component("Coil", "Apache 2.0"),
    Component(
        name = "Literata",
        licence = "SIL Open Font License 1.1",
        notice = "Copyright 2017 The Literata Project Authors",
        licenceAsset = "licences/literata.txt",
    ),
    Component(
        name = "Vollkorn",
        licence = "SIL Open Font License 1.1",
        notice = "Copyright 2017 The Vollkorn Project Authors",
        licenceAsset = "licences/vollkorn.txt",
    ),
    Component(
        name = "Atkinson Hyperlegible",
        licence = "SIL Open Font License 1.1",
        notice = "Copyright 2020 Braille Institute of America, Inc.",
        licenceAsset = "licences/atkinson-hyperlegible.txt",
    ),
    Component(
        name = "Inter",
        licence = "SIL Open Font License 1.1",
        notice = "Copyright (c) 2016 The Inter Project Authors",
        licenceAsset = "licences/inter.txt",
    ),
    Component(
        name = "OpenDyslexic",
        licence = "SIL Open Font License 1.1",
        notice = "Copyright (c) 2019 Abbie Gonzalez",
        licenceAsset = "licences/opendyslexic.txt",
    ),
    Component(
        name = "Accessible DfA",
        licence = "SIL Open Font License 1.1",
        notice = "Copyright (c) Orange 2015",
        licenceAsset = "licences/accessible-dfa.txt",
    ),
    Component(
        name = "iA Writer Duospace",
        licence = "SIL Open Font License 1.1",
        notice = "Copyright (c) 2017 IBM Corp.",
        licenceAsset = "licences/ia-writer-duospace.txt",
    ),
)

/** The third-party components list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var showing by remember { mutableStateOf<Component?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_licences)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.licences_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Components.forEach { component ->
                        ComponentRow(component) { showing = component }
                    }
                }
            }
            Text(
                text = stringResource(R.string.about_licence_line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }

    showing?.let { component -> LicenceDialog(component) { showing = null } }
}

@Composable
private fun ComponentRow(component: Component, onClick: () -> Unit) {
    val readable = component.licenceAsset != null
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (readable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(bottom = 12.dp),
    ) {
        Text(component.name, style = MaterialTheme.typography.bodyLarge)
        component.notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (readable) {
                stringResource(R.string.licences_read, component.licence)
            } else {
                component.licence
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (readable) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** The licence text as shipped, read straight out of the assets. */
@Composable
private fun LicenceDialog(component: Component, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val asset = component.licenceAsset ?: return
    val text by produceState("", asset) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(asset).bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(component.name) },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
