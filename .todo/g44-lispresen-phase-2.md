# g44: lispresen phase 2 -- runnable code, presenter window, the remaining layouts

Difficulty: Medium

lispresen (`lispresen/`) renders an S-expression deck to one HTML file with font sizes
and line breaks it computes itself; phase 1 (reader, model, phrase segmentation, width
model, fit solver, the title / section / statement / standard / code / image / table
layouts, navigation, full screen, CLI, `check.sh`) is done. `lispresen/DESIGN.md` is the
spec for everything below; read it first, and `lispresen/README.md` for usage.

## Scope

Phase 2 of `DESIGN.md` "Phases":

- **Runnable code** (`DESIGN.md` "Runnable code"): `lisp` / `scheme` / `clojure` code
  blocks editable and runnable in the deck on the playground runtime
  (`web/dist/rontoplayground.js*`, `web/README.md`), in a Blob Web Worker, preloaded on
  open, `:runtime` / `--runtime`, `:timeout`, `:fresh`, `:stdin`, `:output-lines`
  reserved in the fit solver, `:output`, and the Keyboard Lock handling of `Esc` in full
  screen (`DESIGN.md` "Full screen").
- **Presenter window** (`P`): current and next slide, notes, timer, runtime state,
  `BroadcastChannel` sync, Window Management API "Start" on the external display.
- **Layouts**: `columns` (`column` blocks sharing one size), `quote` (`:cite`).
- **Overview** (`O`) and the **runtime check** (re-measure after `document.fonts.ready`,
  step down on overflow, `?check` overlay).
- **Highlighting** of Lisp / Scheme / Clojure code at build time (`highlight.lisp`) and
  the same rules in the editor.
- **Theme overrides** (`(theme ...)` in the deck) and the WCAG contrast lint.
- **Browser editor**: `lispresen/web/` -- a `--no-wasi` reactor exporting `render`, and
  an editor page with live preview.

## Verify first

The open points in `DESIGN.md` "Open points": the playground runtime loads in a Blob
worker from `https://making.github.io/rontolisp/` (its `.wasm` is served with
`Access-Control-Allow-Origin: *`, checked 2026-10-22), and `rontoSetLanguage("clojure")`
then `rontoEval` keeps Clojure definitions across calls. Also run
`tools/calibrate.lisp`'s page in Safari; the model was calibrated in Chrome only.

## Done when

`lispresen/check.sh` passes (the golden deck may grow a runnable block and a columns
slide; regenerate with `--update` and review the diff), and the manual checks in
`DESIGN.md` "Verification" pass in Chrome and Safari.
