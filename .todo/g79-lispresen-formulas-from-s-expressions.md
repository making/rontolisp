# g79: lispresen cannot show a formula

Difficulty: High

A deck has no way to write a formula: a fraction, a sum, a subscript, a square root, a
matrix. A talk about an algorithm's cost, a numeric method or a neural network writes
`O(n log n)` as plain text at best, and anything with a fraction or an index as an image
made elsewhere, which does not follow the theme, is not reviewable as text and is not
sized with the slide (the reasons `lispresen/DESIGN.md`, Diagrams, gives for drawing
diagrams from S-expressions).

## Value

- For the user: formulas are common in technical talks (complexity, statistics, machine
  learning; rontolisp ships linear algebra and deep learning examples), and a formula is
  read from the back of the room only when it is set at the slide's size.
- Design: a formula written as an S-expression is the natural fit for lispresen: prefix
  notation is already a formula's tree, so `(math (= (f x) (/ (+ x 1) (sqrt x))))` needs
  no second syntax inside the deck, and the deck stays data that is read, never
  evaluated. Laying it out in lispresen keeps the fit solver exact: a formula's box is
  computed from the same width table, so it steps down with the body and overflow is
  reported as for any block.
- Cost: high. A small math layout engine (rows, fractions, scripts, radicals, large
  operators with limits, parentheses that grow, matrices) with measured boxes; an
  output the browser renders at exactly those boxes. A TeX string handed to a script
  library would contradict the single-file, no-network and measured-layout goals, so it
  is not the route.

## Shape (to settle in the design)

- Forms: an inline `(math expr)` in text and a display block `(math :display t expr)`.
  Operators by symbol name: `+ - * / = < <= expt sqrt sum prod int abs`, subscripts
  `(_ x i)` / superscripts, function application `(f x)`, greek letters by name
  (`alpha`), matrices `(matrix (a b) (c d))`. A string is set as text.
- Output: either MathML Core (rendered by Chrome, Safari and Firefox; widths then depend
  on the browser's math font, so measure them with `tools/calibrate.lisp` as the text
  table was) or absolutely placed spans and SVG rules from lispresen's own box layout,
  as diagrams are (exact by construction, the runtime check leaves it as written).
  Decide by measurement; record the numbers in DESIGN.md.
- Accessibility: the formula carries a spoken form (`aria-label`) generated from the
  tree.
- Callouts: `:at` naming a sub-expression by its printed text, if cheap.

## Verification

- Unit tests in `lispresen/test/lispresen-test.lisp` (extend `diagrams` or add one
  `deftest`): box sizes of a fraction, a script, a radical and a matrix at two sizes.
- `lispresen/check.sh`: byte-identical output on every backend.
- Chrome and Safari: a slide of formulas inline and as display blocks in all four
  themes; the runtime check makes no correction.
- README.md and DESIGN.md (Deck format, a section of its own, lint table).
