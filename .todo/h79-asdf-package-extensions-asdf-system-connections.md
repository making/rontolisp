# h79. Code that extends ASDF from `(in-package :asdf)`: asdf-system-connections

Difficulty: High

Measured 2026-11-05: `(ql:quickload "cl-graph")` stops with `ASDF:DEFSYSTEM
cl-graph/with-cl-variates: unsupported option :SYSTEMS-REQUIRED`. `cl-graph.asd` does
`(load-system "asdf-system-connections")`, whose only source starts `(in-package :asdf)`.
cl-containers takes the same branch whenever asdf-system-connections is registered (`.kb/asdf.md`,
"Determinism"). The list below is from 2026-10-31 and not re-measured.

What the file then needs from ASDF itself (all used by `dev/asdf-system-connections.lisp`):
- `(defclass system-connection (system) ...)` and `defsystem ... :class system-connection` with
  an extra initarg (`:systems-required`) -- a user subclass of `asdf:system` as a defsystem class.
- `map-systems`, `system-registered-p`/`registered-system`, `component-operation-time`,
  `make-operation`, `uiop:if-let` (visible through `asdf`'s use of `uiop`).
- `(defmethod operate :after ((operation t) (component t) &key &allow-other-keys) ...)` -- a user
  method on `asdf:operate` that runs after every load.

Blocks: cl-graph, cl-containers (+ `defsystem-connection` systems in general). Done when
`(ql:quickload "cl-graph")` loads and the README's `make-graph`/`add-edge-between-vertexes`/`size`
example prints what SBCL 2.2.9 prints on the same tree (SBCL loads it). `.kb/asdf.md`.
