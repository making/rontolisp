# `(setf (find-class ...))` + `(setf (macro-function ...))` alias places; lisp-namespace loads

Difficulty: 中 (both call sites are pure ALIASING, so the semantics can stay
narrow; the work is wiring the setf places through the class/macro registries
on all four backends)

Part of the Mito milestone `.todo/238` (substrate; no dependencies).

## The blockers (probed 2026-08-02)

- `(ql:quickload "dbi")` →
  `UnsupportedOperationException: setf does not support place: FIND-CLASS`.
  cl-dbi src/utils.lisp:14,21 — a macro that aliases `<dbi-error>`-style
  bracket names to existing condition/class metaobjects:
  `(setf (find-class '<foo>) (find-class 'foo))`.
- `(ql:quickload "lisp-namespace")` (a trivia.level2 dep) →
  `setf does not support place: MACRO-FUNCTION`.
  lisp-namespace src/namespace-let.lisp:31 —
  `(setf (macro-function 'nslet) (macro-function 'namespace-let))`, again a
  pure alias.

## Scope decision

Implement the two setf places with REGISTRATION semantics sufficient for
aliasing:

- `(setf (find-class name) class-metaobject)` registers the metaobject under
  the additional name — after it, `(find-class name)`, `make-instance`,
  `typep`/`handler-case` on the alias resolve to the same class. The
  interpreter side is a ClosRegistry entry; the compile paths need the alias
  visible to the static registries (the `%class-meta-table%` machinery,
  `.kb/clos.md`) — since dbi's aliases happen at library LOAD time (which the
  compile paths execute at compile time), a definition-time registration is
  enough; a RUNTIME setf on the compiled backends may stay unsupported with a
  clear error (document the divergence + trigger in `.kb`).
- `(setf (macro-function name) fn)` where fn is `(macro-function 'other)`:
  register `name` as a macro sharing `other`'s expander. Arbitrary
  hand-constructed expander FUNCTIONS (a lambda taking form+env) are NOT
  needed by this closure — reject them with a clear message and note the
  trigger.
- lisp-namespace also `(defmethod make-load-form ...)` (package.lisp:52): the
  generic must exist so the defmethod is accepted; nothing calls it here.

## Acceptance

- `(ql:quickload "dbi")` proceeds past utils.lisp (next gate: symbol-macrolet,
  `.todo/240`).
- `(ql:quickload "lisp-namespace")` completes; smoke: `namespace-let` /
  `nslet` both expand.
- Unit tests: class alias then make-instance + typep + handler-case through
  the alias; macro alias then macroexpansion through the alias; pinned error
  for the unsupported general cases.
- ci-spec.yaml case for the class-alias shape (definition-time) across
  backends.
