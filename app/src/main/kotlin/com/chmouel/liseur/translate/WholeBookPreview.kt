package com.chmouel.liseur.translate

internal data class WholeBookInfo(
    val title: String,
    val source: String?,
    val target: String,
    val eligible: Boolean,
    val identityMatches: Boolean?,
)

internal data class WholeBookPreview(
    val bookUrl: String,
    val title: String,
    val source: String?,
    val target: String,
    val identity: String,
    val total: Int,
    val cached: Int,
    val failed: Int,
    val characters: Long,
    val destination: String?,
    val cost: ClosedFloatingPointRange<Double>?,
    val sourceFingerprint: String,
    val retryFailures: Boolean = false,
    val retentionStamp: RetentionStamp? = null,
    /** Built from a paused record without a check; it only goes on while that very pause is still there. */
    val resumed: Boolean = false,
    val configuration: String? = null,
    val evictionRevision: Long? = null,
)

internal data class RetentionStamp(val cleared: Long, val epoch: Long, val generation: Long?)
