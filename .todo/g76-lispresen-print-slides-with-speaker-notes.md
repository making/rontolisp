# g76: lispresen's speaker notes never leave the presenter window

Difficulty: Low

`(notes "...")` is written as `<aside class="notes">`, hidden in the audience view and
in print (`.notes{display:none}`, `lispresen/src/html.lisp`), and shown only in the
presenter window. A speaker cannot print the slides with their notes to rehearse on
paper or to hand a co-presenter, and a deck cannot be distributed as a handout that
carries the explanation the slides leave out.

## Value

- For the user: rehearsing from a printed script and handing out slides with the
  spoken text are routine; with the notes locked in the presenter window the author
  keeps a second copy of them elsewhere, which drifts.
- Design: the slides stay exactly as laid out; the notes are added below each slide on
  a taller print page, so nothing the fit solver placed moves. It is a print mode of the
  same single HTML file, with no new output format.
- Cost: print CSS and a switch in the player; no layout work for the slides, a plain
  wrapped text block for the notes.

## Shape

- `?notes` in the URL (with or without `?presenter`) switches print to a handout: each
  page holds one slide at a reduced scale and its notes under it, in the body font at a
  readable print size; a slide without notes prints alone. The default print stays one
  slide per page.
- The page size: A4 portrait, or the canvas width with extra height; pick one and
  document why.
- The presenter window gets a "Print with notes" button that opens the handout print.
- Steps print fully revealed, as print does today.

## Verification

- Chrome and Safari: headless `--print-to-pdf` (Chrome) of the golden deck with
  `?notes` gives one page per slide with the notes under it; the plain print is
  unchanged.
- `lispresen/check.sh`: byte-identical output on every backend (the stylesheet and
  player change, the golden HTML is regenerated).
- README.md (Presenting) and DESIGN.md (HTML output, Overview and presenter window).
