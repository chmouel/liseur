package com.chmouel.liseur.companion

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.chmouel.liseur.providers.ServiceOption
import kotlinx.coroutines.flow.Flow

/**
 * How much one lookup and one recap request may send. A model that reads
 * little at once, such as the one on the phone, gets less of the book
 * per request.
 */
data class CompanionLimits(
    val investigation: InvestigationLimits = InvestigationLimits(),
    val recapBatch: Int = Recaps.BATCH_CHARACTERS,
)

/** The picker row while a service is in use. */
internal data class ServiceHeading(val icon: ImageVector, val headline: String, val supporting: String)

/**
 * Something the reading companion can ask: a listed server, or in the
 * Play build Gemini on the reader's key.
 */
internal interface CompanionService {
    val id: String

    /** Whether this phone and build can use it at all. */
    val offered: Flow<Boolean>

    /** What lookups and recaps use while this service is chosen; null until it is set up. */
    val choice: Flow<CompanionChoice?>

    /** Where book text goes, shown under the settings. */
    @get:StringRes
    val privacy: Int

    /** A chat bound to [choice]; it fails with a [com.chmouel.liseur.translate.TranslationError] once that no longer holds. */
    fun chat(choice: CompanionChoice): CompanionChat

    @Composable
    fun heading(): ServiceHeading

    /** The settings entry's summary while set up; null otherwise. */
    @Composable
    fun summary(): String?

    /** Its rows in the picker; [pick] closes the picker and runs what makes it the one in use. */
    @Composable
    fun options(inUse: Boolean, pick: (suspend () -> Unit) -> Unit): List<ServiceOption>

    @Composable
    fun SettingsRows(onManageServices: () -> Unit)

    companion object {
        /** A listed server; also what an unset choice means, as before there were others. */
        const val SERVER = "server"
    }
}
