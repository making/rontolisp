# SLY and Lem: the debugger and the inspector in their dialects

Difficulty: Medium

`rontolisp --swank` answers SLY (Slynk) and Lem (micros) on the same port as SLIME
(`.kb/swank.md`, "Dialects"), but an error or an interrupt in SLY's REPL only answers
`(:channel-send R (:evaluation-aborted "cond"))`, and a micros request aborts; the debugger
(h19) and the inspector, `M-.` and xref (`.kb/swank.md`) are measured with SLIME only.

Recorded on SBCL (`.todo/artefacts/h21-slynk-and-lem-micros-protocols/`):

- SLY: an error in the REPL sends `(:channel-send 1 (:prompt pkg prompt 1 n "cond"))` (debug
  level 1) before `(:debug thread 1 ...)` / `(:debug-activate thread 1)`, the thread being the
  REPL channel's; `slynk:throw-to-toplevel` on that thread answers `(:abort "NIL")`, then
  `(:debug-return ...)`, `(:evaluation-aborted "cond")` and a level-0 prompt. The restarts are
  named for the mREPL (`Retry SLY mREPL evaluation request.`). The interrupt is
  `Interrupt from client` with a `CONTINUE` restart.
- Lem: SWANK's `:debug` messages with `micros:` functions (`throw-to-toplevel`,
  `invoke-nth-restart-for-emacs`, `sldb-*`, `frame-locals-and-catch-tags` ...), the interrupt
  `Interrupt from Editor`; `:write-object`'s id names a value Lem's inspector asks for
  (`micros:inspect-printed-object`), as does `pretty-eval`'s `:id`
  (`micros/pretty-eval:inspect-evaluation-value`).

Direction: run SLIME's debugger loop (h19) on SLY's REPL thread and on micros requests, with
the mREPL's prompt levels; keep the printed values by id so Lem's inspector and SLY's
history references (`#v`, `slynk-mrepl:inspect-entry`) reach the inspector; check SLY's and
Lem's `M-.` and xref against their servers. Measure
with the h21 drivers, extend `cli/SwankServerTest#aSlyAndALemSessionAreAnsweredInTheirOwnDialects`.
