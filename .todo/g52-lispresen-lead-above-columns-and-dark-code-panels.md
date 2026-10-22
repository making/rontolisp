# g52: lispresen slides with a lead above columns, content-sized columns, dark code panels

Difficulty: High

A common technical slide cannot be written in lispresen (`lispresen/`, spec
`lispresen/DESIGN.md`) today:

- the title;
- a full-width lead paragraph under it, e.g. `問題 ... → 解決 ...`, set in the muted
  color with its labels bold (the second one in the accent color);
- below it two columns: on the left two code blocks on dark panels (a source excerpt and
  a shell transcript), on the right a bullet list.

Checked 2026-10-22 by rendering a deck of that shape: the lead is an error and dropped,
the code column overflows at the smallest code size, and a dark `:code-bg` fails the
contrast lint on every code pair.

## Gaps

1. **Blocks beside columns.** `parse-slide` (`src/model.lisp`) reports "a slide with
   columns holds only (column ...) blocks" and drops the rest. Needed: full-width blocks
   before (and possibly after) a row of columns. The body becomes a vertical stack of
   full-width blocks and one column row; the fit solver (`fit-body`, `regions` in
   `src/layout.lisp`) sums them, the columns still sharing one size. Everything that
   assumes "columns or one region" follows: the HTML (`write-slide`, `.body.columns`),
   the runtime check's regions (`check()` in `src/player.lisp`), callout geometry
   (`place-at` gives each column the body top), layout selection (`columns`).
2. **Column widths.** Every column is `(1696 - 80 (n - 1)) / n` px. A column of code
   whose longest line needs more than that steps the code down to the hard floor and
   overflows while the list column has room to spare. The author writes content, not
   geometry, so widths should come from content, in the spirit of the table width
   algorithm (`column-widths`): a minimum (the widest code line at the code floor, the
   widest unbreakable unit) and a maximum (lines set unwrapped) per column. Decide how
   this meets "columns share one size", and whether an author hint is ever needed.
3. **Code panel colors independent of the page.** The theme has `code-bg`, but code text
   is `fg` and the highlight colors are tuned to the page background, so
   `(theme :code-bg "#1f2430")` on a light theme makes code unreadable. Add a code
   foreground (and the muted/highlight colors a dark panel needs), or a code palette
   choice; the contrast lint (`*contrast-pairs*`, `src/lint.lisp`) must cover every new
   pair, and the player's highlighter shares the classes.

## Smaller gaps (decide in DESIGN.md whether they belong here)

4. A lead style: a muted paragraph whose labels are bold, one in the accent color. There
   is no inline color role besides `mark` and `link`.
5. A kicker: a small accent label above the title, e.g. the title of the enclosing
   `(section ...)`.
6. Shell transcripts: `:lang "shell"` (or similar) highlighting a `$` prompt line apart
   from the output.
7. Long output lines in a transcript: code never wraps and steps down instead; a block
   of command output may be better wrapped.

Done when `DESIGN.md` specifies the chosen features, `README.md` documents them, the
golden deck `examples/intro.lisp` has such a slide, `lispresen/check.sh` passes, and the
browser placement (runtime check included) is checked by hand in Chrome and Safari.
