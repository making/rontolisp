# SLDB: a frame's source, inspecting from the debugger, restarting a frame

Difficulty: Medium

`rontolisp --swank`'s debugger (`.kb/swank.md`, "The debugger") lists restarts and frames
and answers `q`, `a`, `c`, numbered restarts, frame locals and evaluation in a frame. Still
missing from SLDB, each measured against SWANK 2.32 on SBCL with
`.todo/artefacts/h18-common-lisp-swank-server/drive-sldb.el`:

1. `v` (`swank:frame-source-location`) answers `(:error "No source location")`. A
   function's body forms carry their file and line (`LocatedCons`), the same location
   `M-.` answers once `swank:find-definitions-for-emacs` does
   (`.todo/h20-swank-definitions-xref-inspector.md`); answer the form the frame waits in.
2. `i` (`swank:inspect-in-frame`), `C` (`swank:inspect-current-condition`) and a local's
   `RET` (`swank:inspect-frame-var`) open the inspector, which h20 adds; the debugger
   level holds the frame scopes and the condition (`CommonLispSwank.Debugging`).
3. `r` (`swank:restart-frame`) and `R` (`swank:sldb-return-from-frame`): a frame records its
   function and arguments (`CallFrame`), so restarting is calling it again from its caller
   and returning is a transfer to the frame's caller; frames would then answer
   `(:restartable t)` as SBCL's do. Stepping (`s`, `x`, `o`) and
   `sldb-break-with-default-debugger` stay out unless a user needs them.
4. `frame-locals-and-catch-tags` answers no catch tags: the interpreter's `catch` is not
   on the frame stack.
