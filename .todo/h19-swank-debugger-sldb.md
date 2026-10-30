# SWANK: the debugger (SLDB) for `rontolisp --swank`

Difficulty: High

`rontolisp --swank` (`.kb/swank.md`) has no debugger: an error, like an interrupt, aborts
the request (`(:return (:abort "#<SIMPLE-ERROR \"boom\">") id)`), and SLIME prints
`; Evaluation aborted on ...`. SWANK on SBCL instead keeps the erring thread alive in its
debugger loop and sends `(:debug thread level (message type nil) restarts frames conts)`
then `(:debug-activate thread level nil)`; SLIME's SLDB buffer then sends
`swank:invoke-nth-restart-for-emacs level n`, `swank:throw-to-toplevel`,
`swank:sldb-abort`, `swank:sldb-continue`, `swank:backtrace`, `swank:frame-locals-and-catch-tags`,
`swank:eval-string-in-frame` to that thread (a numeric thread id in `:emacs-rex`), and the
debugger answers `(:debug-return thread level nil)` when it leaves. The recorded exchange
for `(error "boom ~a" 1)` and for an interrupted `(loop)` is in
`.todo/artefacts/h18-common-lisp-swank-server/sbcl-wire.log`.

Direction: intercept the condition where it is signalled, before the stack unwinds -- the
interpreter's `handler-bind` dispatch is the seam -- so the restarts the program
established (`restart-case`, `with-simple-restart`, `cerror`'s `continue`, the interrupt's
own continue) are live and listed, then run SWANK's debugger loop on the erring thread,
answering the requests directed at it until a restart is invoked. Frames: what the
interpreter can name (the uncaught report's `ConditionTrace` lines are a start);
`frame-locals` needs the lexical environment at each frame. An interrupt becomes SWANK's
`simple-break` (a `CONTINUE` restart resuming the loop) instead of an abort.

Done when SLIME's SLDB opens on an error and on an interrupt, lists the restarts and
frames, and `q`, a numbered restart and `c` behave as on SBCL, pinned in
`cli/SwankServerTest` against the recorded messages; `.kb/swank.md` and
`doc/*/getting-started/swank.md` updated.
