# g55: lispresen morphs code from one slide to the next

Difficulty: High

When a talk rewrites a function step by step, consecutive slides show two versions of
the same code, and the audience has to find the difference themselves. The player
should animate the change: tokens the two versions share move to their new place,
removed tokens fade out, added ones fade in.

## Goal

- Opt in per slide: `(slide :morph t ...)` (or on the code block) morphs its first code
  block from the previous slide's. Other slides keep the instant switch.
- The token diff is computed at render time in portable Lisp (the highlighter already
  splits code into tokens), so every backend emits the same pairing; the player only
  plays it: shared tokens carry matching ids, positions come from the laid-out lines.
- Going back plays it in reverse, a jump (`#N`, overview) does not animate, and
  `prefers-reduced-motion` turns it into a plain switch.
- Runnable code: the morph target is the static text; edits made in the editor on the
  previous slide do not take part.

## Open design points

- Diff granularity: token-level LCS on the highlighted tokens; whitespace and
  indentation changes move tokens rather than fade them.
- Timing: one duration for the whole morph (about 600 ms), not per token.
- Print and the no-JS view show each slide as it is.

Verification: `lispresen/check.sh`; a deck of three versions of a function, stepped
through forward and back in Chrome and Safari, with and without reduced motion.
