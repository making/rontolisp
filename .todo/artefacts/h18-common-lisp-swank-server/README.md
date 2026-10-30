# h18 SWANK oracle: a real SLIME against SBCL's SWANK and against rontolisp (2026-10-30)

A real SLIME 2.32 (`slime-fancy`) in Emacs 29.3 batch mode drives one session through a
logging proxy, once against SWANK 2.32 on SBCL 2.2.9 and once against `rontolisp --swank`.
Paths are placeholders: `@WORK@` is a work directory, `@CHECKOUT@` this repository.

- `@WORK@/dl/slime/slime-2.32` -- the SLIME release tarball (GitHub tag `v2.32`), its
  `swank` compiled once by `start-sbcl-swank.lisp`'s loader into `@WORK@/oracle/fasl`.
- `@WORK@/emacs-root` -- `apt-get download emacs-nox emacs-common emacs-bin-common
  emacs-el libgccjit0 libtree-sitter0` (Ubuntu 24.04), each `dpkg -x`'d there; `emacs.sh`
  runs it with its own data and lisp directories, nothing installed.
- `swank_proxy.py LISTEN TARGET LOG` -- forwards one port to another, appending every
  framed message to LOG (`>>` editor to Lisp, `<<` Lisp to editor, newlines as `\n`).
- `drive.el` -- connects (`slime-connect` to `SWANK_PORT`) and runs the REPL, the
  editor's evaluations, `C-c C-c`, `C-c C-k`, `C-c C-l`, completions, autodoc, describe,
  expansions, `set-package`, an error and an interrupt, through SLIME's own commands where
  it has them (`slime-repl-return`, `slime-compile-defun`, `slime-compile-and-load-file`,
  `completion-at-point`, `slime-autodoc`, `slime-macroexpand-1`, `slime-interrupt`).
- `run-sbcl.sh OUTDIR [STEPS]` / `run-ronto.sh OUTDIR [STEPS]` -- start the Lisp, the
  proxy and the driver, then stop them. `sbcl-wire.log` (SBCL's `:indentation-update`
  messages dropped) and `rontolisp-wire.log` are the two recordings of the `basic` steps;
  `sbcl-nav-wire.log` and `rontolisp-nav-wire.log` of the `nav` steps (`M-.`, xref, the
  inspector, macrostep; work paths as `@WORK@/out/work`). `cli/SwankServerTest` replays
  the requests and pins rontolisp's answers.
- `gen-arglists.lisp OUT` -- writes `src/main/resources/am/ik/rontolisp/eval/cl-arglists.txt`
  (after its four comment lines): `sbcl --non-interactive --load gen-arglists.lisp
  --end-toplevel-options OUT`.

The differences between the two recordings are `.kb/swank.md`, "Differences from the
oracle".
