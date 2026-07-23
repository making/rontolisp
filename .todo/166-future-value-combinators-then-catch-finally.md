# Future-as-value combinators: `rl:then` / `rl:then*` / `rl:catch` / `rl:finally`

Today the only way to compose asynchronous work is lexical: `(await f)` inside
an `async-defun`/`async-lambda`, with `handler-case` / `unwind-protect`
wrapped around the await site. That covers pipelines you author top-to-bottom,
but it breaks the moment a future has to cross a boundary as a value -- e.g.
you receive a future from a callee and want to attach a transform, a fallback,
or a cleanup without pulling the whole callee inside your `async` body. There
is no value-shaped analogue of JavaScript's `.then` / `.catch` / `.finally`.

Before / after -- attaching a transform to a future obtained from a factory:

```lisp
;; today: expose the future by wrapping the call in an async-defun
(rl:async-defun caller ()
  (* 2 (rl:await (some-future-producer))))

;; proposed: the transform rides on the future value; caller is a plain defun
(defun caller ()
  (rl:then (some-future-producer) (lambda (v) (* 2 v))))
```

Ship the four operators together as one coherent combinator set so users do
not learn a partial vocabulary that later grows a second dispatch model.

## API surface

Each operator returns a **fresh future**; the input future is unchanged and
may still be awaited independently.

```
(rl:then    future function)         ; function :: value -> any-or-future
(rl:then*   future &rest functions)  ; each fn :: value -> any-or-future
(rl:catch   future handler)          ; handler :: condition -> any
(rl:finally future thunk)            ; thunk   :: () -> any (return value discarded)
```

Callback return values that are themselves futures are auto-flattened by
`await`, so callers never see `future<future<T>>` in practice; the sketches
below rely on that invariant.

**`rl:then`** -- on success invoke `(function value)`; the return value
becomes the new future's settled value. On upstream error the callback is
skipped and the condition propagates through the returned future unchanged.

```lisp
(await (rl:then (rl:wait-for 10) (lambda (_) 42)))   ; => 42
```

**`rl:then*`** -- variadic chain sugar: `(rl:then* f g1 g2 g3)` reads as
`((f -> g1) -> g2) -> g3` without the nesting. Each function receives the
previous stage's settled value; if a stage returns a future the next stage
receives its flattened value (via `await` in the expansion). With no
callbacks the operator degenerates to the input future unchanged.

```lisp
(await (rl:then* (rl:wait-for 10) (lambda (_) 40) #'1+ #'1+))   ; => 42
```

**`rl:catch`** -- on upstream error invoke `(handler condition)`; the
handler's return value becomes the new future's success value. On upstream
success the handler is skipped and the value passes through. If the handler
itself signals, the new future carries THAT condition.

```lisp
(await
  (rl:catch (async-defun-that-signals)
            (lambda (c) (format t "caught: ~a~%" c) :fallback)))    ; => :fallback
```

**`rl:finally`** -- runs the thunk exactly once when the future settles, on
both success and error channels. Return value is discarded; the returned
future carries the ORIGINAL settlement. If the thunk itself signals, that
condition replaces the pending outcome (matches `unwind-protect`).

```lisp
(let ((log nil))
  (await (rl:finally (rl:wait-for 10) (lambda () (push :done log))))
  (reverse log))                                                    ; => (:DONE)
```

## Design decisions

- **JS-style single-handler `catch`, not typed-clause `handler-case`-shape.**
  The value-shaped counterpart to `handler-case` already exists lexically:
  `(handler-case (await f) (some-type (c) ...))` gives full CLOS-hierarchy
  typed dispatch today and needs no new mechanism. `rl:catch` earns its slot
  only as the JS-style value combinator you attach when the future crosses a
  boundary. A user who wants typed dispatch inside the handler writes it
  explicitly: `(rl:catch f (lambda (c) (handler-case (signal c) (my-err (e) ...))))`.
  Rejected alternative: a macro `(rl:catch f (my-err (c) ...) (t (c) ...))`
  that lowers onto `handler-case` clauses. It is more Lispy and reuses
  `LispMacroExpander.makeHandlerTypeTest`, but forks the combinator surface
  into a "sometimes a function, sometimes a macro" split (`then`/`then*`/
  `finally` are functions; `catch` alone would be a macro), and duplicates a
  dispatch model that already works via `handler-case (await ...)`. Keep the
  set uniform and JS-faithful; add a `handler-case`-shaped sugar later if
  demand appears.

- **Both `then` and `then*` are functions, not macros.** No implicit-binding
  body-form sugar. Callers write `(rl:then f (lambda (v) ...))` verbatim.
  Rejected: `(rl:then (v f) body...)` macro expanding to a lambda. It reads
  well but doubles the surface (each op has a "value-flavored" and a
  "body-flavored" spelling), and encourages callers to reach for the macro
  before understanding what the function does. The threading needed for
  readable chains belongs in a separate general `->`-style macro, not in
  every async combinator individually.

- **All four are first-class functions in `rontolisp:` (rl: nickname).** They
  are ordinary values passable to `funcall`/`mapcar`. They are not put in
  `cl:` -- CL has no future concept, and `rl:catch` deliberately shadows the
  ability of a bare `(catch ...)` call to mean this operator (users writing
  in the `rontolisp` package or with `rl:` explicitly get this operator;
  users in `cl-user` still get `cl:catch` unless they explicitly qualify).
  Document the collision in the `rl:catch` reference page.

- **Implementation is a bundled Lisp prelude, not per-backend Java combinators.**
  All four expand naturally on top of `async-lambda` + `await` +
  `handler-case` + `unwind-protect`, which already work on all four backends.
  Model = `WaitForLibrary` + `wait.lisp`: a compile-time source splice with
  eval-side `defun` registration. This inherits, for free, the eager-start
  contract, `LispEvalException` re-throw through `awaitValue`, the JVM
  `_condTl` restore across the await barrier, the wasm-component scheduler
  integration, the WASM EH-mode gate (because `handler-case`/`unwind-protect`
  head symbols appear in the expansion, the existing scanner in
  `WasmLispCompiler.compile` flips EH mode automatically), and the
  `--no-gc` name-rejection of the whole async surface. Rejected: hand-emit
  `CompletableFuture.thenCompose`/`whenComplete` on JVM and per-backend
  scheduler hooks on wasm-component. That is a real optimisation opportunity
  (skip one virtual thread per combinator on JVM, skip one round through the
  P1 degenerate loop), but the correctness surface -- condition-type
  dispatch across the await barrier, dynamic-env crossover, ThreadLocal
  restore, EH-mode gating -- is delicate enough that the pure-Lisp path
  should ship first and the Java-level optimisation should follow only when
  measurement justifies it.

- **Callback errors always replace the pending outcome** on `then` / `then*`
  / `catch`; on `finally` the thunk's return value is discarded but a
  condition raised inside the thunk replaces the outcome. Matches JS
  `.then/.catch/.finally` and CL `unwind-protect` respectively.

- **No new virtual thread sites.** Because the four are `defun`s that call
  `async-lambda`, each combinator invocation adds exactly one `async-lambda`
  invocation -- the same cost the user would pay writing the equivalent
  `(funcall (async-lambda () ...))` by hand. Eager-start still applies to
  the outer `async-lambda`, which sees the input future's `await` as its
  first suspend point.

- **Non-future first argument signals a `type-error`.** No JS-style
  auto-coercion to a resolved promise. Users who need one write
  `(funcall (async-lambda () v))`.

## Implementation plan

Order follows CLAUDE.md ("Implementation Order") and mirrors the
`WaitForLibrary` layout.

1. **Interpreter -- pure Lisp splice.**
   - Create `src/main/resources/am/ik/rontolisp/eval/async-combinators.lisp`
     with the four `defun`s (sketch below).
   - Create `src/main/java/am/ik/rontolisp/eval/AsyncCombinatorsLibrary.java`
     copy-adapted from `WaitForLibrary`: same shape (compile-time source
     splice hook for the wasm-component path, `defun` registration on the
     interpreter/JVM path, reachable-name gate).
   - Wire it into the library loader chain at the same call site as
     `WaitForLibrary`.
   - Run the new `AsyncEvalTest` cases (see Test plan).
2. **JVM.** Expected zero code changes -- the expansion is ordinary
   `async-lambda` + `await` + `handler-case` + `unwind-protect`, all of
   which the JVM backend already compiles. If `JvmLispCompiler`'s
   `programUsesSymbol` gate for the async runtime emission does not fire on
   the new names, extend the gate list to include them so `_condTl` and
   `_await` are wired when a program references only the combinators (not
   the underlying primitives directly). Verify with `JvmAsyncCompilerTest`.
3. **WASM component + Preview 1.** Expected zero code changes. EH mode
   flips automatically because `handler-case` / `unwind-protect` head
   symbols land in the AST via the splice. Preview 1 inherits its
   degenerate-synchronous semantics from the underlying `wait-for` /
   `await` (futures settle at call time). Verify with
   `WasmLispCompilerIntegrationTest` (P1 + component both).
4. **`--no-gc`.** Expected zero code changes -- `WasmNoGcRejections`
   already rejects the async surface (`async-defun`/`async-lambda`/`await`
   /`wait-for` etc.). A program that references `rl:then` reaches the
   rejection through the splice. Pin with a `NoGcWasmCompilerTest` case
   asserting the by-name rejection message.
5. **Symbol registration.**
   - `src/main/java/am/ik/rontolisp/LispNames.java`: add string constants
     `THEN`, `THEN_STAR`, `CATCH`, `FINALLY`.
   - `src/main/java/am/ik/rontolisp/PackageRegistry.java`: add the four
     names to the `rontolisp` package externals set (near `WAIT_FOR`).
     Do NOT touch `CL_SYMBOLS` -- these are `rontolisp:`, not `cl:`.
   - `src/main/java/am/ik/rontolisp/compiler/LibraryDefunPruner.java`: add
     the four to the survives-tree-shake list if `wait-for` is treated
     specially there (grep the existing entry).
6. **`ci-spec.yaml`.** Add one case per operator (see Test plan) covering
   all four backends byte-identically.
7. **Docs.** Four detail pages + catalog entries + overview table rows,
   EN+JA byte-identical (see Docs plan).
8. **Native-image E2E.** Rebuild `target/rontolisp` and rerun
   `CiSpecE2eTest` per the CLAUDE.md dance (mandatory after touching
   `ci-spec.yaml`).

Sketch of `async-combinators.lisp`:

```lisp
(in-package :rontolisp)

(defun then (fut fn)
  (funcall (async-lambda () (funcall fn (await fut)))))

(defun then* (fut &rest fns)
  (funcall (async-lambda ()
             (let ((v (await fut)))
               (dolist (fn fns) (setq v (await (funcall fn v))))
               v))))

(defun catch (fut handler)
  (funcall (async-lambda ()
             (handler-case (await fut)
               (error (c) (funcall handler c))))))

(defun finally (fut thunk)
  (funcall (async-lambda ()
             (unwind-protect (await fut)
               (funcall thunk)))))
```

## Test plan

- **`src/test/java/am/ik/rontolisp/eval/AsyncEvalTest.java`** (async lives
  here, not `LispEvaluatorTest`): `thenChainsOnFutureSettledValue`,
  `thenStarVariadicChainsAcrossStages`,
  `thenStarNoCallbacksReturnsInputFuture`,
  `thenStarStageMayReturnFuture`,
  `catchRunsOnlyWhenBodySignals`,
  `catchPassesUpstreamValueThroughOnSuccess`,
  `finallyRunsOnSuccessPath`,
  `finallyRunsOnFailurePath`,
  `finallyPreservesOriginalCondition`,
  `finallyThunkErrorReplacesOutcome`.
- **`src/test/java/am/ik/rontolisp/codegen/jvm/JvmAsyncCompilerTest.java`**:
  mirror the same names; add `catchRestoresCondTlAcrossAwait` to pin the
  ThreadLocal crossover.
- **`src/test/java/am/ik/rontolisp/codegen/wasm/WasmLispCompilerIntegrationTest.java`**:
  double each -- one `p1<Name>` and one `component<Name>` -- exactly as
  `wait-for` is doubled today (`componentWaitForSettlesToNil` +
  `waitFor...` sibling).
- **`src/test/java/am/ik/rontolisp/codegen/wasm/NoGcWasmCompilerTest.java`**:
  `noGcRejectsThenByName` (piggy-backs on the async-surface rejection).
- **`src/test/resources/ci-spec.yaml`**: one concatenated case, e.g.
  `then-catch-finally`, with `ci-` prefixed symbols (cases share global
  state and run in fixed order). Shape:

  ```yaml
    - name: then-catch-finally
      source: |
        (rontolisp:async-defun ci-add (a b) (+ a b))
        (print (rontolisp:await
                 (rontolisp:then (ci-add 1 2) (lambda (v) (* v 10)))))
        (rontolisp:async-defun ci-boom () (error "boom"))
        (print (rontolisp:await
                 (rontolisp:catch (ci-boom) (lambda (c) "caught"))))
        (setq *ci-log* nil)
        (rontolisp:await
          (rontolisp:finally (ci-add 3 4)
                             (lambda () (push "cleanup" *ci-log*))))
        (print (reverse *ci-log*))
      expected: |
        30
        "caught"
        ("cleanup")
  ```

- **Native-image E2E** (mandatory after `ci-spec.yaml` change):
  ```
  ./mvnw -V --no-transfer-progress -Pnative clean package -DskipTests
  ./mvnw -V --no-transfer-progress \
    -Dtest=CiSpecE2eTest -DfailIfNoTests=false \
    -Drontolisp.binary="$PWD/target/rontolisp" test
  ```
  Because the case uses `handler-case` / `unwind-protect` internally, both
  `wasmtime run` invocations need `-W exceptions=y` (the driver already
  supplies this; confirm on failure).

## Docs plan

Four detail pages, EN + JA byte-identical (only prose translated), page
shape modelled on `rontolisp-wait-for.md` (H1 = qualified name, signature,
paragraph, one runnable ```lisp block with `; => value`, `## Backend
support` section):

- `doc/en/reference/functions/rontolisp-then.md` + `doc/ja/.../rontolisp-then.md`
- `doc/en/reference/functions/rontolisp-then-star.md` + JA mirror
- `doc/en/reference/functions/rontolisp-catch.md` + JA mirror
  (include a note on the `cl:catch` name shadowing)
- `doc/en/reference/functions/rontolisp-finally.md` + JA mirror

Catalog: append four `- { slug: ..., name: "rontolisp:..." }` lines in the
`rontolisp Package` block of `doc/en/reference/functions/_catalog.yaml` and
its JA sibling, next to `rontolisp-wait-for`.

Overview table: add four rows in the async cluster of
`doc/en/reference/functions.md` (and JA), next to `rontolisp:wait-for`.

Fix + verify pass:
```
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixDetailResults test
./mvnw -Dtest=DocExamplesTest test
```

Then mirror any rewritten EN prose into JA so fences stay byte-identical.

## Backend applicability

- **Interpreter**: full support. Callbacks run on the body's virtual
  thread; `LispEvalException` re-throws through `awaitValue` as usual.
- **JVM**: full support. `_condTl` restored across await via the existing
  EMARKER path; condition-type dispatch inside `catch`'s handler works
  because the handler itself runs on the awaiting thread.
- **WASM component (`--component`, WASI 0.3)**: full support. The
  splice's `async-lambda` bodies lower onto the existing subtask
  scheduler; EH mode flips because `handler-case` / `unwind-protect`
  appear in the AST.
- **WASM Preview 1**: degenerate-synchronous, matching `wait-for` /
  `await` on P1. Combinators complete at call time.
- **`--no-gc`**: rejected by name -- consistent with the async-surface
  rejection policy.

## Non-goals

- **`rl:all` / `rl:race` / `rl:any` / `rl:all-settled`.** Multi-future
  combinators are a separate concept and would benefit from a proper
  scheduler primitive rather than being built on top of these four.
  Deferred until a real caller needs them.
- **JS-style "thenable" auto-unwrap of arbitrary objects.** A non-future
  first argument is a `type-error`, not a resolved future. Reduces the
  discriminator burden on `awaitValue`.
- **Cancellation.** JS `Promise` has no cancellation either. Structured
  concurrency + cancellation is a much larger design and belongs with
  the eventual scheduler surface.
- **Typed-clause `catch` macro** -- see Design decisions.
- **Body-form sugar for `then`/`then*`** -- see Design decisions.
- **`:connect-timeout` / deadline options on combinators.** Timeouts
  belong on the future producer (see fetch-timeout todo elsewhere in
  `.todo/`), not on the combinator.
- **Java-level `thenCompose` / `whenComplete` optimisation.** Ship pure
  Lisp first; measure; open a follow-up only if the added virtual-thread
  hop shows up in a real workload.

## Open questions

- **Name collision `rl:catch` vs `cl:catch`.** Both exist in their own
  packages; qualified names never collide, but a user in a package that
  `:use`s both would need an explicit `rontolisp:catch` or
  `common-lisp:catch` at the reference. Document prominently on the
  `rl:catch` page. If community feedback pushes back, a rename to
  `rl:on-error` / `rl:rescue` is cheap to do before the API ships.
- **`then*` earns its slot as variadic sugar, not as a flatten variant.**
  A `thenCompose`-shape `then*` would be observationally identical to
  `then` here because `await` flattens on read. The variadic-chain shape
  gives a real ergonomic win for 3+ stage pipelines (avoids parenthesis
  nesting). Downside: users familiar with `thenApply` / `thenCompose`
  will expect the JS/Java meaning; document the divergence on the
  `rl:then*` page and cite this todo's rationale.
