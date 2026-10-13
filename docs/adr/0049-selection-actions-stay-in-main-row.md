# 49. Selection actions stay in the main row

Status: accepted

## Context

ADR-0047 put Translate and Read aloud behind More to keep the selection
bar compact. That made both actions take an extra tap, even as page
translation and read-aloud became part of the same reading flow.
This amendment changes only their placement; the one-colour default
from ADR-0047 remains in effect.

## Decision

Keep Translate and Read aloud in the selection bar's horizontally
scrolling action row. The bar keeps its fixed height and does not open a
second popup, which could dismiss a live WebView selection.

## Consequences

- Readers can start either action with one tap.
- On narrow screens, readers may need to scroll the actions row.
- The bar keeps the same height and outside-touch behavior.
