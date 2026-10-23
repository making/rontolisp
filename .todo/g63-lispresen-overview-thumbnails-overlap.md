# g63: lispresen's overview grid overlaps its thumbnails

Difficulty: Low

Pressing O opens the overview (`#ov`, `lispresen/src/html.lisp`). In Chrome the grid rows
come out shorter than the thumbnails inside them, so each row overlaps the next. At a
1440px-wide window the row pitch was 146px against a 186px thumbnail (measured
2026-10-23 on `examples/intro.html`; the deck before that day showed the same).

Suspect: `#ov`'s `grid-template-columns:repeat(auto-fill,minmax(300px,1fr))` sizes the
rows before the player scales each `.thumb>.slide` with a transform, and the `.thumb`
box's height (its `aspect-ratio` or the scaled slide) does not reach the grid's row size.

## Goal

Every overview row is as tall as its thumbnails at any window width and both aspects
(16:9, 4:3), in Chrome and Safari; the presenter window's thumbnails (`.pv-cur`,
`.pv-next`) are unaffected.
