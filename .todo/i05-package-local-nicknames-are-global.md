# i05. Package-local nicknames are global, so one collides with a real nickname

Difficulty: High

Measured 2026-11-04. `.kb/packages.md` records the design ("Package-local nicknames are GLOBAL
nicknames"). A consumer now stops on it:

```lisp
;; cluffer: Standard-buffer/packages.lisp
(defpackage #:cluffer-standard-buffer
  (:use #:common-lisp)
  (:local-nicknames (#:bt #:clump-binary-tree))
```

with `bordeaux-threads` already holding `bt`: `error: Package already exists: BT`, with no position
and past any `handler-case`. The same happens for `(:local-nicknames (#:uiop #:other))`, and for two
packages each giving one nickname a different target (SBCL answers `"T1"` and `"T2"` by the current
package; rontolisp stops at the second with `Package already exists: N`). McCLIM needs cluffer, so
`clim` does not load.

Needed: a nickname table per package, consulted first by the reader for a qualified token with
that package current, as the SBCL extension specifies. `(find-package "BT")` with
`cluffer-standard-buffer` current answers the local one. On the compile paths the resolver has the
current package at hand, but run-time `find-package` and `intern` only see `*package*` where the
program observes it (`.kb/packages.md`, frozen registry). `uiop:add-package-local-nickname`,
`uiop:package-local-nicknames` and `uiop:remove-package-local-nickname` follow, with their ci-spec
cases `uiop-add-package-local-nickname` and `uiop-package-surgery`. All four backends.
