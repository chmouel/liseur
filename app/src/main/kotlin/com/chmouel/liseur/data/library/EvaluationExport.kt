package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.BookEvaluation
import com.chmouel.liseur.domain.PersonalEvaluation
import org.json.JSONArray
import org.json.JSONObject

fun evaluationExport(rows: List<BookEvaluation>): JSONObject {
    val books = JSONArray()
    for ((url, fields) in rows.groupBy { it.bookUrl }.toSortedMap()) {
        val latest = fields.filter { it.title.isNotBlank() }.maxByOrNull { it.updatedAt } ?: fields.maxBy { it.updatedAt }
        val book = JSONObject().put("book_url", url).put("title", latest.title).put("author", latest.author)
        for (field in fields.sortedBy { it.field }) {
            val value = if (field.field == PersonalEvaluation.RATING) field.value?.toInt() else field.value
            book.put(field.field, JSONObject().put("value", value ?: JSONObject.NULL).put("updated_at", field.updatedAt))
        }
        books.put(book)
    }
    return JSONObject().put("format", "liseur-evaluations").put("version", 1).put("books", books)
}
