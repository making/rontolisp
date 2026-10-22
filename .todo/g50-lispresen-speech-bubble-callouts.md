# g50: lispresen speech-bubble callouts on a slide

Difficulty: High

lispresen (`lispresen/`, spec `lispresen/DESIGN.md`) has no way to attach a comment to a
piece of slide content. Add speech-bubble callouts: a short comment in a bubble whose
tail points at what it is about (a phrase, a list item, a table cell, a code line, an
image).

Constraints the design must keep (`DESIGN.md` "Goals", "Deck format", "Layout model"):

- The author writes content, not geometry: the deck names what the bubble points at,
  never coordinates. lispresen places the bubble.
- All layout arithmetic is integer and the output is byte-identical on every backend
  and SBCL (`check.sh`).
- Readability is enforced: bubble text has a floor size like every role, and a bubble
  that cannot be placed without covering its anchor or other text is reported, not
  squeezed.
- The static HTML is correct with JavaScript off and when printed; the player's runtime
  check may correct it, as it does for overflow.
- A new tag is matched by `symbol-name` and joins `*element-names*` in
  `src/template.lisp`. If it adds a theme color, the contrast lint covers it.

Decide in `DESIGN.md` first:

1. The form. One option is an inline wrapper whose content is the anchor, e.g.
   `(say "comment" (mark "文節"))`; block-level anchors (a cell, a code line, an image)
   may need a different spelling.
2. Room: an overlay that takes no room, like the runnable-code output panel, or space
   reserved in the fit solver. An overlay needs free space found from the computed
   geometry. That takes the anchor's line and x offset from the line breaker
   (`metrics.lisp`), and the width model over-estimates Latin text, so the tail can
   land slightly off.
3. Candidate positions (beside the anchor's line, above, below, the right margin), the
   order they are tried in, and what counts as a collision.
4. Visibility: always shown, or toggled. The deck has no build steps, and animations
   are a non-goal. Say what print, the overview and the presenter window show.

Done when `DESIGN.md` specifies the feature, `README.md` documents it, the golden deck
`examples/intro.lisp` has a callout, and `lispresen/check.sh` passes. Placement against
the browser is checked by hand in Chrome and Safari, recorded in `DESIGN.md`
"Verification".
