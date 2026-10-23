# g82. lispresen: `:step t` on every block, not only lists and callouts

Difficulty: Medium

Today a page advance can reveal only the items of `(ul :step t ...)` / `(ol :step t ...)`,
the line groups of `(code :steps ...)` and step callouts (`lispresen/DESIGN.md`, the steps
section; `src/model.lisp` collects them in `*steps*`). A paragraph, an image, a quote, a
table or a diagram is always on screen with its slide, so "show the photo after the point
is made" or "reveal the conclusion last" needs a second slide that repeats the first.

## Goal

`:step t` on any block where revealing it later makes sense, numbered in source order with
the existing steps:

- `p` (and a bare string written as `(p :step t ...)`), `lead`, `quote`, `image`, `video`,
  `table`, `flow`, `cells`, `bars`, `column`, `code` (the whole block; `:steps` still walks
  its lines after it appears), and the formula block once it exists.
- Items of `table` rows or `flow` / `cells` / `bars` parts stepping one by one is a separate
  question; decide per kind whether `:step t` reveals the whole block or its parts the way
  `ul` does, and write the rule in DESIGN.md.
- Blocks where a step makes no sense (`notes`, `source`, a full-bleed image slide's only
  image) reject `:step` with a diagnostic rather than ignoring it.

## Constraints

- The fit solver sizes the slide with every step shown, as for lists today: a hidden step
  keeps its box, so nothing moves when it appears.
- Overview, print (with and without notes), the presenter's next-slide preview and `#N`
  links behave as for the current steps.
- A video with `:autoplay` that is a step starts when its step is revealed, not when the
  slide is entered.
- Output stays byte-identical across the six legs of `lispresen/check.sh`.

## Done when

- The model, HTML and player handle the new steps; the unit test for steps is extended
  (no new deftest unless no existing one can reach it).
- README.md, DESIGN.md, `skills/lispresen/references/deck-format.md` and
  `examples/intro.lisp` (a demo slide, golden updated) describe it.
- Checked in a browser, including `?check` reporting no corrections.
