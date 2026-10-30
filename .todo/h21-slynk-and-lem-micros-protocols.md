# SLY (Slynk) and Lem (micros) against rontolisp's SWANK server

Difficulty: Medium

`rontolisp --swank` (`.kb/swank.md`) speaks SLIME 2.32's protocol only. SLY speaks Slynk,
SWANK's fork: the same framing and `:emacs-rex`, but its functions live in `slynk`,
`slynk-mrepl`, `slynk-completion` ..., its REPL is a channel (`slynk-mrepl:create-mrepl`,
`:channel-send` / `:emacs-channel-send` with `:process` and `:write-values`), and its
connection info and completion shapes differ. Lem's micros is another fork with its own
package names and REPL.

Direction: record each client against its own server on SBCL (SLY with Slynk, Lem with
micros) the way `.todo/artefacts/h18-common-lisp-swank-server/` records SLIME, then decide
from the recorded deltas whether one server answers all three (dispatching on the package
prefix of the request's function, channels added for the mREPL) or each needs its own
port. SLY's `M-x sly-connect` must reach a REPL, evaluate, compile a buffer and complete.
