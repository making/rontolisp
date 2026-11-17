# j85. lispresen: PPTX export

Difficulty: High

## Goal

`lispresen DECK -o OUT.pptx` writes a PowerPoint file beside (or instead of) the HTML. The
deck's layout, line breaks and block geometry are lispresen's; colors and fonts are not
baked in, so a theme can be applied afterwards in PowerPoint or on an uploaded Google
Slides copy. Default mode emits theme references only; a later mode may bake the deck's
theme in.

Out of scope: the browser editor and `serve` (neither has `read-file`, so neither can
embed images), runnable code in PowerPoint, morph animation, page-advance animations.

## Why native DrawingML, not one raster per slide

A raster export has no text to edit and needs PNG/JPEG/GIF/WebP decoders; lispresen only
writes PNG (`src/png.lisp`). Native output embeds image bytes untouched and lets
PowerPoint do the decoding. HTML and PPTX become two emitters over one model and one fit.

## Current assets to build on

- `src/layout.lisp` already computes absolute per-line geometry: `run-geometry` (1117),
  `blocks-geometry` (1335), `table-geometry` (1221), `code-geometry` (1184),
  `diagram-geometry` (1263), `title-rows` (875), `place-callouts` (1466).
- `src/png.lisp` has CRC-32, Adler-32, deflate and `put-u32`; a ZIP writer reuses them.
- `src/truetype.lisp` and `src/raster.lisp` give glyph outlines, `draw-svg` and the PNG
  writer (for the SVG subset).
- `src/theme.lisp` has the 19 colors; `src/diagram.lisp` has shapes as `:rect`, `:poly`,
  `:dot`, `:slice`.
- `src/metrics.lisp` `flatten-inlines` (209) flattens inline content; it records only the
  width style, so it needs a parallel per-character attribute vector (bold, italic, mono,
  accent, mark, link, and inline formula boxes).
- `src/page-image.lisp` shows how to walk `title-rows` and draw the title slide.

## New files

```
src/zip.lisp          ZIP container, fixed timestamps, deterministic
src/opc.lisp          [Content_Types].xml, rels, presentation/master/layout/theme skeleton
src/pptx.lisp         DrawingML emitter: slides, shapes, text, tables, notes
src/image-size.lisp   intrinsic size for PNG/JPEG/GIF/WebP/SVG
```

Add them to `src/all.lisp`; wire the CLI in `cli.lisp` (`main`, 335).

## Geometry recording

Add a dynamic `*geometry-sink*`; the geometry functions above push records only when it
is bound:

```lisp
(:text run start end size x y width align role)
(:panel x y w h radius role)
(:picture figure x y w h)
(:cell row col run x y w h align)
(:shape x y w h class)
(:formula x y w h formula)
```

HTML leaves the sink nil, so its output does not change. Two hazards:

- `blocks-geometry` runs today only for slides with callouts or a growing chart.
  `fit-body` (730) must run one final geometry pass for every slide after `grow-charts`
  (802), so every slide has records.
- `place-at` (1502) repeats per callout size; reset the sink at the start of each pass and
  keep only the final pass.

v1 does not emit the kicker, footer, source line or page number, so none of their CSS
constants (`src/html.lisp:245-251`) are mirrored. Section and statement slides are still
placed: a `centered-box` helper in layout returns the box the fit used (safe area less the
source's height), and the emitter centers the text in it by line count. The title slide
uses `title-rows`, which is already a layout function.

## PPTX mapping

- Coordinates: 1 px = 6350 EMU (1920 x 1080 px = 13.333 x 7.5 in); font size 1 px = 0.5 pt
  (56 px = 28 pt), centipoints integer.
- Text: one textbox per paragraph, explicit line breaks at lispresen's wrap points,
  `wrap="none"`, `noAutofit`, insets 0, `lnSpc` as `spcPts` of the computed line height,
  `spcBef`/`spcAft` 0. Never let PowerPoint re-wrap.
- Lists: real `buChar`/`buAutoNum` with `marL`/`indent` from `list-indent`.
- Code: rounded rect `bg2` + monospace textbox; keep the band the fit reserved for Run and
  Reset but draw no buttons (print does the same).
- Tables: native `a:tbl` with `gridCol` widths and computed row heights, zero margins,
  column alignment; caption as its own textbox.
- Diagrams and charts: `:rect` -> roundRect, `:poly` -> custGeom, `:dot` -> ellipse,
  `:slice` -> pie/arc; labels as textboxes. Charts stay shapes, not native chart objects.
- Formulas: one textbox per placed glyph from the `mbox` items.
- `say`: rounded rect + tail polygon + text, from the placed callout.
- QR: dark modules in one custGeom path.
- Images: embed original bytes; contain uses the intrinsic aspect centered in the box;
  cover (full/split) uses `a:srcRect` crop percentages. SVG in the `draw-svg` subset is
  rasterized to PNG through `raster.lisp`; outside it, warn and embed the SVG.
- Video: poster image plus a hyperlink; no poster -> placeholder with the file name.
  Frame: URL text plus hyperlink.
- Notes: `notesSlide` parts.
- Steps: show every step (as print/overview do). Omit the progress bar (print does).
- Not emitted in v1: the kicker, footer, source line and page number. A quote's `:cite`
  is part of the quote block and stays.

## Theme-free output (default)

- Only `schemeClr` (`tx1`, `tx2`, `bg2`, `accent1`-`accent6`, `hlink`), never `srgbClr`.
- No slide-level `p:bg`; the destination theme supplies the background.
- Runs reference `+mn-lt`/`+mj-lt`; only monospace names `Consolas` explicitly.
- Shapes carry `<p:style>` theme references; series colors are `accentN` explicitly.
- Tables use "No Style, No Grid" plus explicit scheme fills so a theme's table style does
  not replace them.
- `theme1.xml` is the standard Office theme. Deck `:theme` and CLI `--theme` are ignored
  in this mode (they still affect HTML).
- `--pptx-theme deck` (later) bakes the deck's colors and font names, with font embedding.

## Determinism and portability

Output must be byte-identical on every backend. Fixed ZIP timestamps, fixed entry order,
no clock reads (use the deck's `:date` or omit docProps dates), integers only, the existing
deterministic deflate. No host-dependent font family names except the explicit monospace.

## CLI and API

```lisp
(lispresen:render-pptx source &key runtime read-file theme)
;; => (values octets diagnostics)
```

```
lispresen DECK -o OUT.pptx [--pptx-theme none|deck] [--check]
```

The extension selects the format. `--embed` is always on for PPTX; `--runtime` is
irrelevant. `-` writes octets to standard output.

## Diagnostics

Warnings: unreadable image, unknown intrinsic size, unsupported SVG, video without a
poster, relative frame URL, formats PowerPoint may not read (AVIF, some WebP). `--strict`
fails on them. `--check` reports without writing.

## Verification

- `check.sh` gains a PPTX leg: every backend renders `examples/intro.lisp` to a byte-equal
  `examples/intro.pptx`; python3's `zipfile`/`ElementTree` (already a check.sh dependency)
  validates well-formed XML, content-type coverage and that every rels target exists.
- Unit tests: ZIP headers, CRC and sizes, EMU/point conversion, image-size readers, XML
  escaping, a small deck's part contents, geometry records for one slide.
- Manual: open in PowerPoint (macOS), LibreOffice and Keynote; calibrate
  `*pptx-baseline*` against rendered output.
- Manual theme checks, recorded in DESIGN.md: (1) does Google Slides keep scheme colors
  as theme colors, (2) does a Google theme change restyle text boxes. If text does not
  follow, document that Google only changes background/layout, and add an optional
  placeholder mode later.
- lispresen changes verify with `lispresen/check.sh`, not `./mvnw test`.

## Done when

Phases 1 and 2 below pass: the golden decks export with no content block silently missing
(the decorative labels listed under "Not emitted in v1" are intentionally absent),
`check.sh` is green on every backend, the result opens in PowerPoint and LibreOffice, and
DESIGN.md, README and the skill are updated. Phases 1 and 2 are one deliverable: they share
the geometry recorder and the emitter, and shipping phase 1 alone would drop diagrams on a
slide without a usable state.

## Phases of this todo

1. ZIP/OPC skeleton, theme-free text/list/code/image/table/notes, CLI, golden check.
2. Diagrams, formulas, QR, callouts, full/split, video/frame.

Estimate: 2,200-2,600 lines.

## Later, not this todo

Not filed as todos: each is picked up only if wanted, after the export works.

- `--pptx-theme deck`: bake the deck's colors and fonts, with `:pptx-font` embedding (the
  existing TrueType reader plus the `.fntdata` obfuscation).
- The decorative labels v1 leaves out: kicker, footer, source line and page number, with
  the positions the stylesheet uses. Add them with the theme export.
- Step animations, or one slide per advance.
- Native chart objects.
- Layout placeholders, only if the Google Slides measurement shows text must restyle.

## Documentation

- DESIGN.md: remove "PowerPoint export" from Non-goals (line 39-40), add a "PPTX export"
  section with the mapping, the theme-free rule, determinism and the manual verification.
- README.md: usage, `--pptx-theme none|deck`, font substitution caveat, steps fully shown,
  Google Slides limits.
- `skills/lispresen` references: one line for `-o talk.pptx`.

## Open decisions

- Tables as native `a:tbl` (editable, but row height can grow under a substitute font and
  overlap later blocks) versus cells as textboxes (fixed, no longer a table). Recommendation:
  native table; font embedding removes the risk in `deck` mode.
- Google text restyling: placeholders only if measurement shows it is needed.
- Video: poster + link first; embedding the MP4 is a later option.
