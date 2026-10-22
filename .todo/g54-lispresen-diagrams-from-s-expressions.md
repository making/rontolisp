# g54: lispresen draws diagrams from S-expressions

Difficulty: High

A deck can only show a diagram as an image file (`examples/pipeline.png` is the
pipeline of `intro.lisp`). An image does not follow the theme's colors, cannot be
diffed or reviewed as text, and its labels are not phrase-wrapped or size-fitted.

## Goal

Two block elements, emitted as inline SVG whose colors are the theme's variables:

- `(flow "read" "parse" "layout" "emit")`: boxes joined by arrows, left to right, wrapping
  to a second row when the labels do not fit one. Labels are inlines (`(b ...)`,
  `(tt ...)`), measured with the existing width tables.
- `(cells '(1 (2 3)))`: the box-and-pointer drawing of a list (cons cells, `nil` as a
  slash, atoms as labels), the drawing a Lisp talk explains structure with.

## Open design points

- Sizing: the fit solver steps body text down until a slide fits; a diagram needs a
  size step of its own (box height and label size together) and a hard floor that
  reports like the other blocks.
- Layout of `cells`: the classic drawing puts the cdr chain on one row and each car
  below; deep or wide lists need a bound and a diagnostic, not a clipped drawing.
- Callouts: `(say "..." :at "parse" (flow ...))` should anchor to a box, as `:at`
  anchors to a line of code now.
- The golden deck: replace `pipeline.png` in `intro.lisp` with a `flow`, keeping one
  image slide for the image layout.

Verification: `lispresen/check.sh` (all backends byte-identical), and a look in Chrome
and Safari at every built-in theme.
