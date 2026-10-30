# h21 SLY and Lem against their own servers and against rontolisp (2026-10-30)

Two real clients, each through h18's logging proxy (`swank_proxy.py`), once against its own
server on SBCL 2.2.9 and once against `rontolisp --swank`. `@WORK@` is a work directory,
`@CHECKOUT@` this repository.

- SLY 1.0.43 (commit 70350623eecd1f41837c9e57cbb6d5c06678a60d, `sly-fancy`) in h18's Emacs
  29.3 batch mode against Slynk from the same checkout (`@WORK@/dl/sly`).
  `start-sbcl-slynk.lisp`, `drive-sly.el` (REPL, input, `C-x C-e`, `C-c C-c`, `C-c C-k`,
  `C-c C-l`, simple and flex completions, `completion-at-point` in a buffer and the REPL,
  autodoc, describe, macroexpand, an error, an interrupt) and `run-sly.sh sbcl|ronto OUTDIR`
  live in `@WORK@/sly-oracle/`. Recordings: `sly-sbcl-wire.log`, `sly-rontolisp-wire.log`.
- Lem 2.3.0 (commit 9b4345140bd5bc6cf1a112b77a479679c0350b2c, ncurses) in Docker
  (`Dockerfile.lem`, Lem's own without webkit2gtk, build context the Lem checkout), run in
  tmux with `--network host` and driven by `tmux send-keys` (`drive-lem.sh`: `M-x
  slime-connect`, REPL, `C-c C-e`, `C-M-x`, `C-c C-c`, `C-c C-k`, `C-c C-l`, Tab completion,
  autodoc, describe, macrostep, `C-c M-m`, an error, an interrupt) against micros (commit
  2ec61c8d6028d219cc511a0e41d9228761c84251, `start-sbcl-micros.lisp`). `start-lem.sh` /
  `start-lem-rontolisp.sh` start the server, the proxy and Lem; `stop-lem.sh`. Recordings:
  `lem-sbcl-wire.log` (steps marked `## STEP`), `lem-rontolisp-wire.log`.

`:indentation-update` messages are dropped from the recordings, and so is a SLIME session of
another worktree that reached the Lem proxy's port during both runs. The deltas between each
client's two recordings are `.kb/swank.md`, "Dialects" and "Differences from the oracle".
