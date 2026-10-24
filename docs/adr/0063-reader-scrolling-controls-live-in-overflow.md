# 63. Reader scrolling controls live in the overflow menu

Status: accepted

Amends [ADR-0040](0040-auto-scroll-controls.md),
[ADR-0006](0006-auto-scroll.md), and
[ADR-0020](0020-fixed-layout-reading-settings.md).

## Context

“Read by scrolling” and “Scroll the page for me” were both in the Aa
typography sheet. Readers asked for these controls in the reader’s
top-right overflow menu instead.

## Decision

Both controls live in the overflow menu as compact, stateful menu items.
“Read by scrolling” remains a per-book choice and is disabled for
fixed-layout books. “Scroll the page for me” appears only while the book
is actually scrolling. Turning it on closes the menu and hides the
reader chrome so the page can move. Its speed setting stays in Advanced.

The Aa sheet retains typography and display controls, but no longer
contains either scrolling switch. The overflow is always available to
provide access to “Read by scrolling”, even when no other menu actions
apply.

## Consequences

The menu uses the existing localized labels and exposes each toggle’s
state and checkbox semantics. Auto-scroll remains conditional on actual
scrolling, including vertical-text books; it is unavailable for
fixed-layout books.
