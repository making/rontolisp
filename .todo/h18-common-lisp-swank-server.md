# Common Lisp: a SWANK server so SLIME, SLY and Lem attach to rontolisp

Difficulty: High

SWANK is Common Lisp's editor protocol: Emacs SLIME speaks it, SLY speaks its fork Slynk, Lem
its fork micros. rontolisp has only `src/main/resources/am/ik/rontolisp/eval/swank.lisp`, a stub
satisfying clack's ASDF dependency whose `swank:create-server` signals. Its premise -- "no
backend can hand out its own evaluator over a socket" -- no longer holds: `--nrepl` serves the
interpreter's Clojure session over a socket (`.kb/nrepl.md`).

Direction: a `--swank[=PORT]` server on the interpreter for Common Lisp source, reusing what
`cli/NreplServer` built (loopback socket, a virtual thread per connection, sessions, a real
`interrupt`, a redefinition re-pointing its callers' cells), with a SWANK transport (6-hex-digit
length-prefixed S-expression messages, `(:emacs-rex form package thread id)` answered by
`(:return (:ok ...) id)`, `:write-string` for output). Measure against upstream SWANK on SBCL
with a real SLIME (or a scripted client replaying a recorded SLIME conversation), message for
message.

First scope:

1. Connection handshake (`swank:connection-info`, `swank:create-repl`), REPL evaluation
   (`swank-repl:listener-eval`, `swank:interactive-eval`, `swank:eval-and-grab-output`), output
   streaming, `*package*` switching.
2. `swank:compile-string-for-emacs` (`C-c C-c`), `swank:compile-file-for-emacs` /
   `swank:load-file` (`C-c C-k`), with notes for warnings.
3. Completion (`swank:simple-completions`, `swank:fuzzy-completions` if cheap), arglists
   (`swank:autodoc` / `swank:operator-arglist`), `swank:describe-symbol`, macroexpansion
   (`swank:swank-macroexpand-1`, `swank:swank-macroexpand-all`).
4. Interrupt (`:emacs-interrupt`).

Later, weighed separately: SLDB (`:debug` / `:debug-return`, restarts listed and invoked,
backtrace frames) -- the condition system is where SLIME pays most, and the heaviest part;
the inspector; xref / `find-definitions-for-emacs`; Slynk and micros deltas.

Done when SLIME (and SLY through its Slynk deltas, if they stay small) connects to
`rontolisp --swank`, evaluates, compiles a buffer and a file, completes and shows arglists,
with the protocol pinned by a scripted-client test; the stub's `create-server` either starts
the server or keeps refusing with the reason updated. User docs in `doc/en` and `doc/ja`.
