package com.chmouel.liseur.reader.footnotes

import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeping a book's notes out of the page they were written for.
 *
 * EPUB 3 says a note should be marked up where it belongs and *hidden* by
 * the reading system until the reader asks for it. Readium does not hide it:
 * there is no footnote rule anywhere in Readium CSS — not in the default
 * sheet, not in the CJK or RTL variants, not before the publisher's styles
 * and not after. So a book that puts its notes in an `<aside>` at the top of
 * the chapter, as many translated editions do, shows the translator's gloss
 * before the sentence being glossed. The reader meets the answer first and
 * the question second.
 *
 * The marker fares no better. A book that draws its reference as a small
 * image — a circled 注, a printer's dagger — has that image painted at its
 * natural size, because `ReadiumCSS-before.css` sets `width:auto;height:auto`
 * on every `img`, which beats the `width` and `height` attributes the
 * publisher put on the element (a presentation attribute loses to any author
 * rule). What is left holding it back is `max-height: 95vh`, which is not a
 * limit so much as a permission: a 200-pixel glyph in the middle of a
 * paragraph, and the sentence torn in half around it.
 *
 * Both are fixed here, in the book's own document, by the same means
 * [com.chmouel.liseur.reader.WideContentFit] uses and for the same reason:
 * Readium decides whether to link its default stylesheet by looking for
 * `<style` in the resource, so shipping one ahead of it would make an
 * unstyled book look styled and cost it Readium's typography entirely.
 *
 * ### What is hidden, and what is carefully not
 *
 * A note body is hidden only when it is both a note by [NoteVocabulary] *and*
 * pointed at by an anchor in the same document. That second condition is the
 * whole safety of this file. A chapter of endnotes is referenced from the
 * chapters it serves, not from itself, so nothing in it is touched and it
 * stays as readable as its author meant it to be. An `<aside>` holding a pull
 * quote is a note by the generous tag rule but nothing links to it, so it
 * stays too.
 *
 * Two narrower guards sit behind that. An anchor that is itself inside a note
 * is a backlink or one note citing another, and neither says the note it
 * points at was ever referenced from the text. And a document that is *mostly*
 * notes is a notes document however its links run — a numbered list with a
 * contents entry per note would otherwise vanish entirely — so past
 * [MOSTLY_NOTES] of the document's own words nothing is hidden at all.
 *
 * ### Ownership
 *
 * The attribute's *name* carries a token minted per document, rather than a
 * fixed name marked in its value. A publisher is free to ship
 * `data-liseur-note` on their own markup: taking the name and writing our
 * token into it would overwrite theirs on any element we also wanted to
 * hide, and lose it for good when the reader revealed the note. A name they
 * cannot have guessed is never read, never written over and never stripped.
 */
internal object FootnoteLayout {

    /**
     * How much of a document may be notes before it is treated as a document
     * *of* notes and left alone.
     *
     * Duplicated as a literal inside [SCRIPT] for the reason given there;
     * `FootnoteLayoutTest` keeps the two the same number.
     */
    internal const val MOSTLY_NOTES = 0.6

    internal enum class Result {
        /** Something was hidden, sized or released: the page may have moved. */
        CHANGED,

        /** Nothing to do; the layout is untouched. */
        STABLE,

        /** The book's Content-Security-Policy refused the stylesheet. */
        BLOCKED,

        /** No reflowable page to talk to, or the script did not answer. */
        FAILED,
    }

    /**
     * Runs in the book's own document, repeatedly, so it must end where it
     * started when there is nothing to do.
     *
     * The vocabulary is spelled out here as JavaScript literals rather than
     * interpolated from [NoteVocabulary], so that the harness in
     * `hack/verify-footnotes` can lift the script out of this file as plain
     * text and run the very lines the app ships. `FootnoteLayoutTest` is what
     * keeps the two lists honest: it fails the build if a word is added to
     * one and not the other.
     *
     * Notes are found through the anchors that reference them rather than by
     * sweeping the document for asides. That is not an optimisation — it is
     * the rule itself. An anchor pointing at a note in this document is the
     * evidence that the note was referenced here, and the evidence is what
     * distinguishes a gloss from a chapter.
     */
    const val SCRIPT: String = """
        (function () {
          var NOTE_TYPES = ["footnote", "endnote", "rearnote", "note"];
          var NOTE_ROLES = ["doc-footnote", "doc-endnote"];
          var NOTE_TAG = "aside";
          var REF_TYPES = ["noteref"];
          var REF_ROLES = ["doc-noteref"];
          var MOSTLY_NOTES = 0.6;
          var EPUB_NS = "http://www.idpf.org/2007/ops";

          var state = window.__liseurNotes || (window.__liseurNotes = {});
          var tok = state.token ||
                    (state.token = "n" + Math.random().toString(36).slice(2, 10));
          // The token is part of the attribute's *name*, not its value. A
          // publisher who ships `data-liseur-note` of their own is then
          // never read from, never written over and never stripped, which
          // taking the name and marking ourselves in the value could not
          // promise: the element we wanted to hide might be theirs.
          var NOTE_ATTR = state.noteAttr ||
                          (state.noteAttr = "data-liseur-note-" + tok);
          var MARK_ATTR = state.markAttr ||
                          (state.markAttr = "data-liseur-mark-" + tok);
          // A bare object answers for `constructor` and `toString`, which
          // are legal ids, and would report a note revealed that never was.
          var revealed = state.revealed || (state.revealed = Object.create(null));
          var installed = false;

          // Served as XHTML the attribute is namespaced and `getAttribute`
          // may not see it; served as HTML there is no namespace and
          // `getAttributeNS` will not. Books arrive both ways.
          function typeOf(el) {
            return el.getAttributeNS(EPUB_NS, "type") ||
                   el.getAttribute("epub:type") ||
                   el.getAttribute("type") || "";
          }

          function names(value, vocabulary) {
            var parts = String(value).split(/\s+/);
            for (var i = 0; i < parts.length; i++) {
              var word = parts[i], colon = word.lastIndexOf(":");
              if (colon >= 0) word = word.slice(colon + 1);
              if (vocabulary.indexOf(word) >= 0) return true;
            }
            return false;
          }

          function isNote(el) {
            if (names(typeOf(el), NOTE_TYPES)) return true;
            if (names(el.getAttribute("role") || "", NOTE_ROLES)) return true;
            return String(el.localName || "").toLowerCase() === NOTE_TAG;
          }

          function isRef(el) {
            return names(typeOf(el), REF_TYPES) ||
                   names(el.getAttribute("role") || "", REF_ROLES);
          }

          function insideNote(el) {
            for (var p = el.parentElement; p; p = p.parentElement) {
              if (isNote(p)) return true;
            }
            return false;
          }

          function weight(el) {
            return String(el.textContent || "").replace(/\s+/g, "").length;
          }

          // A book writes a reference to a note in its own chapter either as
          // "#n1" or as "ch1.xhtml#n1", and both name the same element.
          // Reading only the first spelling left the second kind of book
          // exactly as broken as before, so the href is resolved against the
          // document's own address and counted as local when everything but
          // the fragment agrees.
          function localFragment(anchor) {
            var href = anchor.getAttribute("href") || "";
            if (!href) return "";
            if (href.charAt(0) === "#") return href.slice(1);
            try {
              var there = new URL(href, document.baseURI);
              var here = new URL(document.baseURI);
              if (there.origin !== here.origin) return "";
              if (there.pathname !== here.pathname) return "";
              if (there.search !== here.search) return "";
              return there.hash.slice(1);
            } catch (e) {
              return "";
            }
          }

          // The attribute is set from a list rather than toggled in place, so
          // a note that stops qualifying — because the reader asked to see
          // it, because the document was rewritten under us — is released
          // rather than left hidden forever.
          function sync(attr, wanted) {
            var previous = document.querySelectorAll('[' + attr + ']');
            var dirty = false, i;
            for (i = 0; i < previous.length; i++) {
              if (wanted.indexOf(previous[i]) < 0) {
                previous[i].removeAttribute(attr);
                dirty = true;
              }
            }
            for (i = 0; i < wanted.length; i++) {
              if (!wanted[i].hasAttribute(attr)) {
                wanted[i].setAttribute(attr, "");
                dirty = true;
              }
            }
            return dirty;
          }

          if (!state.styleEl || !state.styleEl.isConnected) {
            var css = document.createElement("style");
            css.textContent =
              '[' + NOTE_ATTR + ']{display:none!important}' +
              '[' + MARK_ATTR + ']{' +
                'height:1em!important;width:auto!important;' +
                'max-height:1.2em!important;max-width:4em!important;' +
                'min-height:0!important;min-width:0!important;' +
                'vertical-align:super!important;margin:0 .1em!important;' +
                'object-fit:contain;display:inline-block}';
            document.head.appendChild(css);
            state.styleEl = css;
            installed = true;
            if (!css.sheet) return "blocked";
          }

          var anchors = document.getElementsByTagName("a");
          var notes = [], marks = [], i, j;
          for (i = 0; i < anchors.length; i++) {
            var anchor = anchors[i];
            var id = localFragment(anchor);
            var note = null;
            if (id) {
              try { id = decodeURIComponent(id); } catch (e) { /* keep it raw */ }
              var target = document.getElementById(id);
              // An anchor inside a note is a backlink, or one note citing
              // another. Neither is the text referring to a note.
              if (target && isNote(target) && !insideNote(anchor)) note = target;
            }
            if (note && notes.indexOf(note) < 0) notes.push(note);
            if (note || isRef(anchor)) {
              var media = anchor.querySelectorAll("img, svg, image");
              for (j = 0; j < media.length; j++) {
                if (marks.indexOf(media[j]) < 0) marks.push(media[j]);
              }
            }
          }

          var page = document.body ? weight(document.body) : 0;
          var held = 0;
          for (i = 0; i < notes.length; i++) held += weight(notes[i]);
          if (page > 0 && held / page > MOSTLY_NOTES) notes = [];

          var wanted = [];
          for (i = 0; i < notes.length; i++) {
            if (!revealed[notes[i].getAttribute("id")]) wanted.push(notes[i]);
          }

          var changed = installed;
          if (sync(NOTE_ATTR, wanted)) changed = true;
          if (sync(MARK_ATTR, marks)) changed = true;
          return changed ? "changed" : "stable";
        })();
    """

    /**
     * Puts the note called [fragment] back on the page, for good.
     *
     * The card's "go to note" carries the reader to the note itself, and a
     * note that is hidden is a note they arrive at and cannot see. Revealing
     * is therefore permanent for as long as the document lives: they asked
     * for it, and having it disappear again on the next layout pass would be
     * the same bug wearing a different hat.
     *
     * It runs before the jump rather than after, which is the only order that
     * works: after, the reader is already looking at the place the note ought
     * to be. A note in *another* resource is never hidden in the first place
     * — nothing in that document references it — so there is nothing there
     * for this to miss.
     */
    fun revealScript(fragment: String): String =
        """
        (function () {
          var id = ${jsString(fragment)};
          var state = window.__liseurNotes || (window.__liseurNotes = {});
          var revealed = state.revealed || (state.revealed = Object.create(null));
          revealed[id] = true;
          var el = document.getElementById(id);
          if (!el) {
            try { el = document.getElementById(decodeURIComponent(id)); } catch (e) { }
          }
          if (!el) return "absent";
          // Keyed on the element's own id as well as on the spelling the
          // link used, because that is what the pass above reads back.
          revealed[el.getAttribute("id")] = true;
          // The attribute's name carries this document's token, so it is
          // read back from the same place the pass above wrote it. Nothing
          // has been hidden yet if that pass has not run.
          if (state.noteAttr) el.removeAttribute(state.noteAttr);
          return "revealed";
        })();
        """.trimIndent()

    /**
     * [value] as a JavaScript string literal.
     *
     * A fragment identifier is whatever the book's author typed, so it is
     * quoted rather than trusted. Anything outside plain printable ASCII goes
     * out as an escape, and so do the three characters that make markup —
     * `<`, `>` and `&` — which is what stops a `</script` in an id from
     * ending whatever the script is one day embedded in.
     */
    internal fun jsString(value: String): String =
        value.map { char ->
            when {
                char == '"' -> "\\\""
                char == '\\' -> "\\\\"
                char in "<>&" -> "\\u" + char.code.toString(16).padStart(4, '0')
                char.code in 0x20..0x7E -> char.toString()
                else -> "\\u" + char.code.toString(16).padStart(4, '0')
            }
        }.joinToString(separator = "", prefix = "\"", postfix = "\"")

    /**
     * `evaluateJavascript` hands back the JSON encoding of the value, so a
     * plain string arrives wearing quotes.
     */
    internal fun parse(result: String?): Result {
        val value = result?.trim()?.removeSurrounding("\"")?.trim()
        return when (value) {
            "changed" -> Result.CHANGED
            "stable" -> Result.STABLE
            "blocked" -> Result.BLOCKED
            else -> Result.FAILED
        }
    }

    @OptIn(ExperimentalReadiumApi::class)
    internal suspend fun apply(navigator: EpubNavigatorFragment): Result =
        parse(evaluate(navigator, SCRIPT))

    /** Reveals [fragment], returning whether the page may have moved. */
    @OptIn(ExperimentalReadiumApi::class)
    internal suspend fun reveal(navigator: EpubNavigatorFragment, fragment: String): Boolean {
        val answer = evaluate(navigator, revealScript(fragment))
        return answer?.trim()?.removeSurrounding("\"")?.trim() == "revealed"
    }

    /**
     * [script] in the book's page, or null if the page would not run it.
     *
     * A cancellation is not a failed evaluation, and swallowing it here
     * would be the worst kind of quiet: the caller carries on to navigate a
     * navigator whose screen has already gone.
     */
    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun evaluate(navigator: EpubNavigatorFragment, script: String): String? =
        try {
            navigator.evaluateJavascript(script)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refused: Exception) {
            null
        }
}
