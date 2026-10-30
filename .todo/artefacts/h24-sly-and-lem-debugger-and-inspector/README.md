# h24 SLY's and Lem's debugger and inspector against their servers and rontolisp (2026-10-30)

h21's setup (`../h21-slynk-and-lem-micros-protocols/README.md`): `@H21@` is its work
directory (the SLY checkout in `@H21@/dl/sly`, Slynk's start file in `@H21@/sly-oracle/`),
`@SCRATCH@` holds h18's `emacs.sh` and proxy (`@SCRATCH@/oracle/swank_proxy.py`), `@WORK@`
this item's work directory, `@CHECKOUT@` this repository.

- `drive-sly.el` -- h21's SLY driver plus the `sldb` steps (a numbered restart, `cerror`'s
  continue, a worker's `a`, frames, locals and an evaluation in a frame, an interrupt
  continued then quit, an error typed at level 1 and its level returned from) and the `nav`
  steps (`C-c C-k`, `M-.`, xref, the inspector through `slynk:eval-for-inspector`, REPL
  history entries inspected, described and pretty-printed, `#v`). `run-sly.sh sbcl|ronto
  OUTDIR sldb|nav`. Recordings: `sly-sbcl-sldb-wire.log`, `sly-rontolisp-sldb-wire.log`,
  `sly-sbcl-nav-wire.log`, `sly-rontolisp-nav-wire.log` (`:indentation-update` dropped).
- `start-lem-rontolisp.sh`, `drive-lem.sh connect error_step restart_step interrupt`,
  `stop-lem.sh` -- h21's Lem driver plus a restart step (a numbered restart, `cerror`'s
  continue) against rontolisp: `lem-rontolisp-wire.log`; micros's shapes are h21's
  `lem-sbcl-wire.log` (`## STEP error`, `## STEP interrupt`).

The deltas are `.kb/swank.md`, "Dialects" and "Differences from the oracle".
