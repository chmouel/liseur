package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.bookorbit.BookOrbitCfi.Component.Indirection
import com.chmouel.liseur.data.bookorbit.BookOrbitCfi.Component.Step

/**
 * Identifies a spine resource without claiming that its DOM location was verified.
 * A later reader stage must resolve both document paths before using either as exact.
 */
data class BookOrbitCfiResource(
    val href: String,
    val start: BookOrbitCfi.Path,
    val end: BookOrbitCfi.Path?,
) {
    companion object {
        fun locate(cfi: BookOrbitCfi, publication: BookOrbitEpubPackage): BookOrbitCfiResource? {
            val endpoints = when (cfi) {
                is BookOrbitCfi.Point -> cfi.path to null
                is BookOrbitCfi.Range -> {
                    val start = join(cfi.parent, cfi.start) ?: return null
                    val end = join(cfi.parent, cfi.end) ?: return null
                    start to end
                }
            }
            val components = endpoints.first.components
            if (components.size < 4 || components[2] != Indirection ||
                components.drop(3).any { it == Indirection }
            ) return null
            val spine = components[0] as? Step ?: return null
            val itemref = components[1] as? Step ?: return null
            if (!spine.matches(publication.spineStep)) return null
            val item = publication.spine.singleOrNull { itemref.matches(it.step) } ?: return null
            val localStart = BookOrbitCfi.Path(components.drop(3), endpoints.first.offset)
            val localEnd = endpoints.second?.let { endpoint ->
                if (endpoint.components.take(3) != components.take(3) ||
                    endpoint.components.size < 4 || endpoint.components.drop(3).any { it == Indirection }
                ) return null
                BookOrbitCfi.Path(endpoint.components.drop(3), endpoint.offset)
            }
            return BookOrbitCfiResource(item.href, localStart, localEnd)
        }

        private fun Step.matches(local: Step): Boolean =
            index == local.index && (id == null || id == local.id)

        private fun join(parent: BookOrbitCfi.Path, endpoint: BookOrbitCfi.Path): BookOrbitCfi.Path? {
            val components = parent.components + endpoint.components
            if (components.zipWithNext().any { (a, b) -> a == Indirection && b == Indirection }) {
                return null
            }
            return BookOrbitCfi.Path(components, endpoint.offset ?: parent.offset)
        }
    }
}
