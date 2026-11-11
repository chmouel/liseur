package com.chmouel.liseur.insights

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow

fun createUsageReporting(context: Context): UsageReporting = object : UsageReporting {
    override val enabled = MutableStateFlow(false)
    override suspend fun setEnabled(enabled: Boolean) = Unit
    override fun capture(event: UsageEvent) = Unit
}

@Composable
fun UsageReportingSettings() = Unit
