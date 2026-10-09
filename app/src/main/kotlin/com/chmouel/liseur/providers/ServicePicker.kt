package com.chmouel.liseur.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.LiseurModalBottomSheet

/** One choice in a feature's service picker: a service, or a listed server by name. */
internal data class ServiceOption(
    val selected: Boolean,
    val icon: ImageVector,
    val headline: String,
    val supporting: String,
    val onClick: () -> Unit,
)

/**
 * The service a feature uses, as one row that opens a sheet of them all,
 * like the server kind on the account screen. Choosing a service is rare
 * and the service's own settings follow it, so the choice takes one row's
 * height. Chips in a row could not hold three long names on a phone.
 */
@Composable
internal fun ServiceRow(
    icon: ImageVector,
    overline: String,
    headline: String,
    supporting: String,
    changeLabel: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(icon, contentDescription = null) },
            overlineContent = { Text(overline) },
            headlineContent = { Text(headline) },
            supportingContent = { Text(supporting) },
            trailingContent = {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = changeLabel,
                )
            },
        )
    }
}

/**
 * Every [options] with what it is and where the text goes, then the way
 * to the Services page, with [manageDetail] under it when there is
 * something to say; picking one is the caller's to close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServiceSheet(
    title: String,
    options: List<ServiceOption>,
    manageDetail: String?,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .selectableGroup(),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            options.forEach { option ->
                ListItem(
                    modifier = Modifier.selectable(selected = option.selected, role = Role.RadioButton, onClick = option.onClick),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(option.icon, contentDescription = null) },
                    headlineContent = { Text(option.headline) },
                    supportingContent = { Text(option.supporting) },
                    trailingContent = {
                        // Null, not a second handler: the row carries the click.
                        RadioButton(selected = option.selected, onClick = null)
                    },
                )
            }
            ListItem(
                modifier = Modifier.clickable(role = Role.Button, onClick = onManage),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.services_manage)) },
                supportingContent = manageDetail?.let { { Text(it) } },
            )
        }
    }
}
