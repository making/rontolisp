# g71: lispresen has no footer line on its slides

Difficulty: Low

A talk often carries one line on every slide: the event's name or hashtag, the
speaker's handle, a copyright or "社外秘". lispresen's bottom band (the 72 px bottom
margin, `lispresen/DESIGN.md`, Canvas) holds only the page number on the right
(`.page`, `src/html.lisp`); the deck header has no way to put text there, so an author
repeats it in every slide's `(source ...)`, which is meant for the content's origin,
costs body height, and is one per slide.

## Value

- For the user: a hashtag or handle on every slide is what lets the audience post about
  the talk; a confidentiality label is required by some employers. Today it cannot be
  written at all without misusing `source`.
- Design: a label outside the body type scale in the reserved bottom band, as the page
  number and kicker are. The author writes content, not geometry: the position is fixed,
  never given in the deck. It takes no room from the body, so the fit solver is
  untouched.
- Cost: an option, a measured one-line label, an emit, lint rules.

## Shape

- `(deck :footer inline...)` or `:footer "text"`: inline content (a `link` allowed),
  set on the left of the bottom band at the page number's size and color, on every
  slide but the title slide (as the page number). A slide can drop it (`:footer nil` on
  `slide`) where it would compete, e.g. a statement.
- It shares the band with the page number and the `:progress` bar: measure it against
  the band's width less the page number's widest value, and report (warning) a footer
  cut with an ellipsis, as a long kicker is.
- The QR code of the title slide and a `(source ...)` keep their places; the source
  stays above the band.
- Shown in print and the overview (it is part of the slide), unlike the progress bar.

## Verification

- A unit test in `lispresen/test/lispresen-test.lisp` (extend an existing deck-header
  test) for the parsed option and the emitted element; the golden deck
  (`examples/intro.lisp`) gains a footer only if the intro wants one.
- `lispresen/check.sh`: byte-identical output on every backend.
- Chrome and Safari: the footer sits on one line in the band at the width measured, on
  16:9 and 4:3, in all four built-in themes; the contrast lint already covers `muted` on
  `bg`.
- README.md form table and DESIGN.md (Deck format, HTML output, lint table) updated.
