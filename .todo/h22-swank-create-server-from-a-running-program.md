# `swank:create-server` from a running program (clack's `:swank-port`)

Difficulty: Medium

The built-in `swank` shim system's `swank:create-server` signals ("a program cannot serve
its REPL; rontolisp --swank serves one to SLIME"), so `(clack:clackup app :swank-port 4005)`
and a program that starts SWANK itself to be inspected while it runs both fail. The server
(`cli/SwankServer`, `.kb/swank.md`) lives in `cli`, which `eval` may not import.

Direction: the command line hands the interpreter a starter before the program runs (an
evaluator hook the interpreter's `swank:create-server` calls: port, `:dont-close`), so an
interpreted program -- a file, `-e`, the REPL -- starts the same server on its own
evaluator and goes on; `swank:stop-server` stops it. The compiled backends keep the
signal (they have no interpreter to serve), with the message saying so. Pin it with an
interpreted program starting the server and a client connecting, and update the shim's
compatibility notes, `doc/*/guides/clack.md` and `doc/*/guides/asdf-systems.md`.
