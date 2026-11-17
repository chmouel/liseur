package com.chmouel.liseur.domain

object PersonalEvaluation {
    const val RATING = "rating"
    const val REVIEW = "review"
    const val MAX_REVIEW_BYTES = 8192

    fun valid(field: String, value: String?): Boolean = when (field) {
        RATING -> value == null || value.toIntOrNull()?.let { it in 2..10 && it.toString() == value } == true
        REVIEW -> value == null || (value.isNotBlank() && '\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= MAX_REVIEW_BYTES)
        else -> false
    }
}
