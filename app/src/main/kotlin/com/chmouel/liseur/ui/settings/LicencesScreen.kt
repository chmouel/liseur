package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R

private data class Component(val name: String, val licence: String)

/**
 * Everything Liseur is built out of, and the licence it comes under.
 *
 * Kept as a hand-written list rather than generated: the dependency set is
 * small, deliberately all free software, and being able to read it without
 * building the app is the point.
 */
private val Components = listOf(
    Component("Readium Kotlin Toolkit", "BSD 3-Clause"),
    Component("AndroidX / Jetpack Compose", "Apache 2.0"),
    Component("Material Components", "Apache 2.0"),
    Component("Kotlin standard library and coroutines", "Apache 2.0"),
    Component("OkHttp", "Apache 2.0"),
    Component("Coil", "Apache 2.0"),
    Component("Literata", "SIL Open Font License 1.1"),
    Component("Vollkorn", "SIL Open Font License 1.1"),
    Component("Atkinson Hyperlegible", "SIL Open Font License 1.1"),
    Component("Inter", "SIL Open Font License 1.1"),
)

/** The third-party components list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
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
                        Text(component.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = component.licence,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
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
}
