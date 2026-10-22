# g56: lispresen bar charts, slide sources and a progress bar

Difficulty: Medium

Three small, independent additions. Each can land as its own commit.

## Bar charts

`(bars ("interpreter" 2.1) ("jvm" 0.4) ("wasm" 0.6) :unit "s")` draws horizontal bars as
inline SVG in the theme's colors, labels on the left, values at the bar ends. Values are
numbers (integers or decimals written as strings, so no float printing reaches the
output). The fit solver sizes it like a table: a label size step and a row height,
with the same row-count warning a table has.

## Sources

`(source "Alan Perlis, Epigrams on Programming, 1982")` puts a short line in the muted
color at the bottom of the slide, above the page number row, and takes its height out
of the body before fitting. One per slide; a second is an error. Links inside it work
as links do elsewhere.

## Progress bar

`(deck :progress t)` draws a thin bar along the bottom edge of the audience view whose
length is the current slide's position in the deck. It is drawn by the player (it
changes on navigation), hidden in the presenter window's thumbnails and in print.

Verification: `lispresen/check.sh`; the golden deck gains one use of each, so emission
stays covered without piecewise HTML assertions.
