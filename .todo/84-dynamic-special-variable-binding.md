# 84: Dynamic (special) variable binding

Status: **not started** (design task). Elevated from `.todo/54` Phase 4's
one-line "Dynamic/special variable binding" bullet into its own tracked item,
because it is the shared root cause behind two live workarounds (`.todo/82`,
`.todo/83`) and a hard prerequisite for the condition system (`.todo/39`). A deep
evaluator/compiler change -- worth designing properly before writing code.

## The gap

Common Lisp has two binding disciplines. rontolisp implements only one:

- **Lexical** (the default) -- implemented across all three backends.
- **Dynamic / special** -- NOT implemented. Today `defvar`/`defparameter` create
  a plain **global**; `(let ((*x* v)) ...)` rebinds it **lexically** (or, for a
  global, just assigns), with no dynamic extent and no automatic restore on exit
  (normal, via a non-local exit, or via an unwinding error). `progv` does not
  exist. There is no `declaim special` / per-variable "specialness".

Consequences already felt:

- **`.todo/82` (macro-time setf replay)**: cl-who reads the global `*html-mode*`
  at macro-EXPANSION time; a top-level `(setf (html-mode) :html5)` has to be
  replayed into the macro-time evaluator because there is no special-variable
  model to bind/observe. Pure hack, data-driven registry.
- **`.todo/83` (load/`*package*` leak)** -- FIXED, but with a hand-rolled
  dynamic binding: `load`/`asdf:load-system` must bind `*package*` for the
  duration of the load and restore it after (CL binds `*package*` and
  `*readtable*` dynamically around `load`). The shipped fix is a dedicated
  save/restore stack on `PackageResolver` (`pushPackage`/`popPackage`) plus
  `%push-package`/`%pop-package` markers on the compile path. That IS dynamic
  binding of one specific variable, done by hand -- see "Improvement room" below.
- **`.todo/39` (conditions/restarts)**: `handler-bind`/`restart-bind` and
  `unwind-protect` are inherently dynamic-extent constructs; a real condition
  system wants special binding underneath.

## What "done" would give

- `(let ((*special* v)) body)` / `(let* ...)` establishes a dynamic binding for
  the extent of `body`, restored on ANY exit (return, `%block` non-local exit,
  `do`/`return`, error unwind). `setq`/`setf` of a special inside sees the
  binding; nested `let`s stack.
- `progv` (runtime-computed list of specials + values).
- A notion of "specialness": `defvar`/`defparameter` mark a name special;
  optionally `declaim`/`declare special`. The earmuffs convention (`*x*`) is a
  style hint, NOT the mechanism (CL does not treat `*x*` as special by name).
- `defvar` keeps its "only-if-unbound" init semantics; `defparameter` always
  assigns.

## Design sketch (per backend -- the hard part)

- **Interpreter** (`LispEvaluator`/`Environment`): a per-special dynamic value
  stack (or a shadowing chain), pushed on dynamic `let` entry and popped in a
  `finally` so unwinds restore it. Note the HTTP handler serves one virtual
  thread per request -- dynamic bindings must be **thread-scoped** (or explicitly
  captured) so concurrent requests do not clobber each other's specials.
- **JVM compiler**: a thread-local dynamic-binding stack per special (or one
  combined stack keyed by name); `let` of a special emits push + `try/finally`
  pop, and the pop must fire on the `%block`/`return` non-local-exit paths too
  (which today assume an empty operand stack -- interaction to work out).
- **WASM**: a dynamic-binding stack in linear memory / a global; save/restore
  around the `let`, restored on the non-local-exit boundary. Must stay inside the
  GC and (separately) the `--no-gc` scalar constraints.
- Static resolution vs runtime specials: today a bare symbol is resolved to a
  lexical slot at compile time. Specials need a runtime lookup path (like
  `boundp`/`symbol-value`, which already see globals only). The compilers must
  learn which names are special (defvar/defparameter/declaim seen in Pass 1).

## Improvement room from `.todo/83` (do this as part of the design)

The `.todo/83` fix is effectively "dynamic binding of `*package*`, hand-rolled".
When the general mechanism lands, fold it back in -- but mind the twist:

- `*package*` (and `*readtable*`, `*read-default-float-format*`) are resolved at
  **read/compile time** by `PackageResolver`, NOT as runtime variables. So a
  RUNTIME special-binding mechanism does **not** automatically cover the
  compile-path package scoping. What `.todo/83` actually needs is dynamic binding
  of **compile-time resolver state**.
- So the clean generalization is a small **"scoped resolver state"** facility in
  `PackageResolver`: bind `*package*`/`*readtable*`/... for the extent of a
  spliced load and restore after. `pushPackage`/`popPackage` +
  `%push-package`/`%pop-package` are the first (only) instance; generalize them
  to a keyed stack so `*readtable*` etc. get the same treatment for free instead
  of a second bespoke pair.
- The interpreter's `loadFile` push/pop would become the natural
  `(let ((*package* *package*) (*readtable* *readtable*)) ...)` IF `*package*`
  were a real runtime special the interpreter reads/writes at eval time -- but
  rontolisp deliberately resolves packages before eval, so keep the two models
  (runtime specials vs compile-time resolver-state specials) distinct and don't
  try to force `*package*` into the runtime one.

Net: `.todo/83` does not "go away" when runtime dynamic binding lands (same
caveat as `.todo/82` option 1). It gets *generalized* -- one keyed compile-time
save/restore facility replacing the per-variable hand-rolled pair.

## Open questions

- One combined dynamic stack vs one stack per special? (Perf vs simplicity.)
- Thread scoping model for the interpreter's virtual-thread-per-request server.
- How specials interact with the `%block`/`return`/`do` non-local-exit boundary
  (which currently requires an empty operand stack on the compile path).
- Minimum viable subset: `let`-binding of `defvar`/`defparameter` specials +
  restore-on-unwind, deferring `progv`/`declare special`?
- Does landing this let `.todo/82` be deleted, or only reframed as a principled
  "macro-time configuration" model (the expansion is still at compile time)?

## Acceptance

- `(let ((*x* 2)) ...)` restores `*x*` on normal exit, non-local exit and error
  unwind, on all four backends; nested and `progv` work; concurrent HTTP-handler
  requests do not see each other's bindings.
- `.todo/83`'s `pushPackage`/`popPackage` are re-expressed through the shared
  scoped-resolver-state facility (and `*readtable*` rides along), OR a conscious
  decision is recorded to keep them separate.
- Reassess `.todo/82`: delete or reframe.

Related: `.todo/54` Phase 4, `.todo/39` (conditions), `.todo/82` (setf replay),
`.todo/83` (load/`*package*`), `.todo/41` (readtables), `.kb/packages.md`
(load/`*package*` scoping), `.kb/symbol-runtime-api.md` (globals-only `boundp`).
