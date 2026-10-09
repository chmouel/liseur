package com.chmouel.liseur.reader

import com.chmouel.liseur.translate.PageSentence
import com.chmouel.liseur.translate.PageSwap
import org.json.JSONArray
import org.json.JSONObject

/**
 * The page side of a translated page: the script that puts a sentence's
 * translation where the sentence was, and puts everything back.
 *
 * Each text node it changes keeps its original text and the list of
 * changes made to it, as offsets in that original. A node is always
 * drawn again from its original with the changes applied from the end,
 * so offsets never drift and the elements around the text, such as links
 * and emphasis, stay where they are. A sentence that runs over several
 * nodes gets its translation in the first that is the sentence's own
 * text rather than an emphasised part of it, and empties the rest. A
 * note's number keeps its text and its link. Any other link that shares
 * the sentence with text outside it leaves the sentence untranslated: one
 * translation cannot be cut to fit the link's words, and emptying the
 * link would lose it. A sentence wholly inside one link, such as an
 * entry in a table of contents, is translated in the link.
 *
 * Sentences are found the way [SpokenPassage] finds the one being read:
 * in their element, by the text before them and their own, over the
 * page's text with its whitespace collapsed as Readium collapses it.
 *
 * The document keeps a count of the changes it was sent, so a reloaded
 * one, which counts from zero, is sent them all again, and the run they
 * came from, so a view Readium hands back from an earlier run starts
 * clean. Every call names the document it was meant for and does
 * nothing in another: Readium reuses web views between chapters.
 */
internal object PageSwaps {
    /**
     * Sends [swaps] from the [from]th of [run] to the document at [url];
     * the page answers how many it holds, fewer when it needs earlier ones
     * again, or null when it is another document.
     */
    fun sync(url: String, run: Int, from: Int, swaps: List<PageSwap>): String {
        val list = JSONArray()
        swaps.forEach {
            list.put(it.sentence.json().put("translation", it.translation))
        }
        return call("sync(${JSONObject.quote(url)}, $run, $from, $list)")
    }

    /**
     * The index in [sentences] of the last one on screen, or, when none
     * is, of the last one the reader has gone past with a later one still
     * to come; [PAST] when the reader has gone past them all, [BEFORE] when
     * the reader is before them all, [UNSEEN] when none is found, null when
     * the document is not at [url].
     */
    fun reached(url: String, sentences: List<PageSentence>): String {
        val list = JSONArray()
        sentences.forEach { list.put(it.json()) }
        return call("reached(${JSONObject.quote(url)}, $list)")
    }

    /** Whether [sentence] is in the document at [url] and the reader has gone past it. */
    fun behind(url: String, sentence: PageSentence): String = call("behind(${JSONObject.quote(url)}, ${sentence.json()})")

    const val PAST = -2
    const val BEFORE = -1
    const val UNSEEN = -3

    private fun PageSentence.json() = JSONObject()
        .put("selector", selector ?: JSONObject.NULL)
        .put("before", before.orEmpty().takeLast(BEFORE_CHARS))
        .put("text", text)

    /** Puts every changed node back as it was. Harmless on a page that was never changed. */
    const val RESTORE = "(() => window.__liseurPage ? window.__liseurPage.restore() : 0)()"

    private fun call(what: String) = "(() => { $INSTALL return window.__liseurPage.$what; })()"

    private const val BEFORE_CHARS = 40

    private val INSTALL = """
        if (!window.__liseurPage) {
          const orig = new Map();
          const edits = new Map();
          const collapse = s => s.replace(/\s+/g, " ");
          const original = node => orig.has(node) ? orig.get(node) : node.data;
          const here = url => location.href.split("#")[0] === url.split("#")[0];
          // Where an offset in a node's original text is in the text shown now.
          const shown = (node, at) => {
            let shift = 0;
            const list = (edits.get(node) || []).slice().sort((a, b) => a.from - b.from);
            for (const e of list) {
              if (at >= e.to) shift += e.text.length - (e.to - e.from);
              else if (at > e.from) return e.from + shift + Math.min(at - e.from, e.text.length);
              else break;
            }
            return at + shift;
          };
          const render = node => {
            let text = orig.get(node);
            const list = edits.get(node).slice().sort((a, b) => b.from - a.from);
            for (const e of list) text = text.slice(0, e.from) + e.text + text.slice(e.to);
            node.data = text;
          };
          const locate = (selector, before, text) => {
            let block = null;
            try { block = selector ? document.querySelector(selector) : null; } catch (e) {}
            const root = block || document.body;
            if (!root) return null;
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            let flat = "";
            const nodes = [];
            const offsets = [];
            let space = false;
            let node;
            while ((node = walker.nextNode())) {
              const data = original(node);
              for (let i = 0; i < data.length; i++) {
                const blank = /\s/.test(data[i]);
                if (blank && space) continue;
                flat += blank ? " " : data[i];
                nodes.push(node);
                offsets.push(i);
                space = blank;
              }
            }
            const quote = collapse(text).trim();
            const prefix = collapse(before);
            if (quote.length === 0) return null;
            let start = -1;
            if (prefix.length > 0) {
              const found = flat.indexOf(prefix + quote);
              if (found >= 0) start = found + prefix.length;
            }
            if (start < 0) start = flat.indexOf(quote);
            if (start < 0) return null;
            return { nodes, offsets, start, end: start + quote.length - 1 };
          };
          const linkOf = node => node.parentElement ? node.parentElement.closest("a") : null;
          const noteRef = a => {
            const kind = ((a.getAttribute("epub:type") || "") + " " + (a.getAttribute("role") || "")).toLowerCase();
            // A bare note mark: a number, a footnote sign, a roman numeral or one letter, maybe bracketed. A short word is a link.
            const mark = /^[\[(]?(\d+|[*\u2020\u2021\u00a7]+|[ivxlc]+|[a-z])[\])]?$/i.test(a.textContent.trim());
            return kind.includes("noteref") || !!a.closest("sup") || !!a.querySelector("sup") || mark;
          };
          const apply = swap => {
            const at = locate(swap.selector, swap.before, swap.text);
            if (!at) return;
            const first = at.nodes[at.start];
            const last = at.nodes[at.end];
            const planned = [];
            for (let i = at.start; i <= at.end; i++) {
              const node = at.nodes[i];
              if (planned.length > 0 && planned[planned.length - 1].node === node) continue;
              const link = linkOf(node);
              // A note's number stays as it is, and stays a link.
              if (link && noteRef(link)) continue;
              planned.push({
                node,
                link,
                from: node === first ? at.offsets[at.start] : 0,
                to: node === last ? at.offsets[at.end] + 1 : original(node).length,
                text: "",
              });
            }
            if (planned.length === 0) return;
            const links = new Set(planned.map(p => p.link));
            if (links.size > 1 && Array.from(links).some(a => a)) return;
            // The sentence's own text, not an emphasised word that opens it.
            let common = planned[0].node.parentNode;
            while (common && !planned.every(p => common.contains(p.node))) common = common.parentNode;
            (planned.find(p => p.node.parentNode === common) || planned[0]).text = swap.translation.trim();
            // A sentence found over one already changed would garble both.
            for (const p of planned) {
              for (const e of (edits.get(p.node) || [])) if (p.from < e.to && e.from < p.to) return;
            }
            for (const p of planned) {
              if (!orig.has(p.node)) {
                orig.set(p.node, p.node.data);
                edits.set(p.node, []);
              }
              edits.get(p.node).push({ from: p.from, to: p.to, text: p.text });
              render(p.node);
            }
          };
          // 1 on screen, 0 gone past (above, or on an earlier page), -1 still to come.
          const where = at => {
            const range = document.createRange();
            const first = at.nodes[at.start];
            const last = at.nodes[at.end];
            range.setStart(first, Math.min(shown(first, at.offsets[at.start]), first.data.length));
            range.setEnd(last, Math.min(shown(last, at.offsets[at.end] + 1), last.data.length));
            const rects = Array.from(range.getClientRects()).filter(r => r.width > 0 && r.height > 0);
            if (rects.length === 0) return -1;
            const rtl = getComputedStyle(document.documentElement).direction === "rtl";
            // Lines set down the page run right to left in vertical-rl, whatever the direction says.
            const style = getComputedStyle(document.body || document.documentElement);
            const mode = style.writingMode || style.webkitWritingMode || "";
            const pastRight = /^(vertical|sideways)-rl/.test(mode) || (!/^(vertical|sideways)/.test(mode) && rtl);
            if (rects.some(r => r.right > 0 && r.bottom > 0 && r.left < window.innerWidth && r.top < window.innerHeight)) return 1;
            return rects.every(r => r.bottom <= 0 || (pastRight ? r.left >= window.innerWidth : r.right <= 0)) ? 0 : -1;
          };
          window.__liseurPage = {
            run: null,
            received: 0,
            sync(url, run, from, swaps) {
              if (!here(url)) return null;
              // Not all its text is there yet: nothing is taken, and the next pass sends them again.
              if (document.readyState === "loading") return 0;
              if (this.run !== run) {
                this.restore();
                this.run = run;
              }
              if (from > this.received) return this.received;
              for (let i = this.received - from; i < swaps.length; i++) {
                try { apply(swaps[i]); } catch (e) {}
              }
              this.received = Math.max(this.received, from + swaps.length);
              return this.received;
            },
            reached(url, sentences) {
              if (!here(url)) return null;
              // Whether a later sentence was found still to come, so a passed one is not the walk's end.
              let ahead = false;
              for (let i = sentences.length - 1; i >= 0; i--) {
                const s = sentences[i];
                let at = null;
                try { at = locate(s.selector, s.before, s.text); } catch (e) {}
                if (!at) continue;
                const w = where(at);
                if (w === 1) return i;
                if (w === 0) return ahead ? i : $PAST;
                ahead = true;
              }
              return ahead ? $BEFORE : $UNSEEN;
            },
            behind(url, s) {
              if (!here(url)) return false;
              let at = null;
              try { at = locate(s.selector, s.before, s.text); } catch (e) {}
              return !!at && where(at) === 0;
            },
            restore() {
              for (const [node, data] of orig) node.data = data;
              orig.clear();
              edits.clear();
              this.run = null;
              this.received = 0;
              return 0;
            },
          };
        }
    """.trimIndent()
}
