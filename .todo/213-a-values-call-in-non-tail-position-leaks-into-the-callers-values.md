# A `values` call in non-tail position leaks into the caller's values

Found 2026-07-30 while making the REPL echo multiple values, by diffing against
SBCL 2.2.9 on the host. **Pre-existing** -- the `%mv-spill` channel has documented
this deviation since todo-061 (`doc/*/guides/missing-features.md`; `.kb/multiple-values.md` now
states the opposite invariant, "A tail settles the channel (compile paths)" and "The
interpreter's value-count register"). What changed is that it is now VISIBLE: the
REPL echo is a consumer, so the leftover shows up as an extra echoed line rather
than only inside an explicit `multiple-value-bind`.

```console
$ sbcl --noinform                  $ rontolisp
* (progn (values 1 2) 42)          > (progn (values 1 2) 42)
42                                 42
                                   2       <- leftover from the non-tail (values 1 2)
```

The channel's protocol is "`values` writes, the next consumer reads". Nothing
CLEARS it on an ordinary return, so a `values` call that no consumer picks up
stays queued until the next consumer runs. 2026-07-30 narrowed this by clearing
the spill as a consumer snapshots it (`(prog1 %mv-spill (setq %mv-spill nil))`,
pinned by `evalMultipleValueConsumerClearsTheSpillChannel` and the
`multiple-values-core` ci-spec case), which killed the common case -- a callee
that internally CONSUMES another function's values used to look multi-valued to
its own caller. What remains is a `values` call whose values nobody consumes.

## Why this cannot be patched at the edges

Clearing the spill "when a function returns without calling values" is exactly
the information the current design does not have: `values` writing a global is a
one-way channel, and every other return path is a plain value. Any real fix is
the same fix:

- **A runtime multiple-value representation.** A callee returns its values as a
  first-class carrier (a `LispValues` box, or a primary value plus a count) that
  every consumer unwraps and every single-value context collapses; the spill
  global and its clear/snapshot dance disappear, and so do `.todo/212` and the
  `funcall #'values` deviation. This is the honest solution and it is a large
  change: the JVM and wasm-GC calling conventions, `apply`/`funcall`, the eval
  runtime, `values` as a first-class function, and the `--no-gc` backend (which
  has no heap to box into and would need the current pure-`prog1` semantics as a
  documented carve-out).
- A cheaper HALF-measure worth considering first, if the full carrier is too big
  a step: make the compilers CLEAR the spill at every call site whose result is
  used in a single-value context. That is a lot of emitted `setq`s for a
  correctness gap only a REPL echo usually notices -- measure before choosing.

## Scope of the eventual fix

- One representation shared by the interpreter and all four backends (the
  governing "all four backends" rule); `--no-gc` keeps `expandValuesPrimary`
  with the reason recorded.
- Retire the `%mv-spill` global, `injectMvSpillGlobal`, `MvProducer.rest` and the
  clear/snapshot pair when the carrier lands -- leaving both mechanisms alive
  would be two sources of truth.
- `LispEvaluator.evalValues` (the REPL echo) then becomes a thin unwrap of the
  carrier, and its syntactic-producer special case can go away.

## Non-goals

- Making the REPL echo alone correct by some tail-position heuristic (e.g.
  "trust the spill only when the form's tail could be a `values` call"). It would
  make the REPL disagree with `multiple-value-bind` on the same form, which is
  worse than one documented deviation.

## Verification

- `(progn (values 1 2) 42)` echoes `42` only, and
  `(multiple-value-bind (a b) (progn (values 1 2) 42) (list a b))` is `(42 NIL)`
  on the interpreter, the JVM and both wasm backends.
- `funcall #'values` through a compiled first-class wrapper yields all values
  (the deviation in `.kb/multiple-values.md` disappears with the carrier).
- The whole `multiple-values-core` / `multiple-value-setq-and-rotatef` /
  `split-sequence-residue-features` ci-spec output stays byte-identical, native
  E2E re-run.
- Re-diff the REPL against SBCL with the case list in `.todo/214`.

## What it costs on the ANSI suite (measured 2026-09-08)

**264 wrong-value tests** -- the largest single FAIL cluster in the report
(`ansi-test/results/logs/`, the `got (X T) want (X)` shape), 58 of them `LOOP.*`
in `iteration` alone. All of them come from one aux macro the suite uses
everywhere (`auxiliary/ansi-aux.lsp:1189`, 132 of its files call it):

```lisp
(defmacro expand-in-current-env (macro-form &environment env)
  (macroexpand macro-form env))
```

The EXPANDER returns two values (`macroexpand`'s form and its expanded-p flag),
nobody consumes the second, and it leaks into the value of whatever the expansion
sits inside. Reduced:

```lisp
(defmacro %m (z) z)
(defmacro eice (form) (macroexpand form))
(multiple-value-list (eice (%m 1)))                        ; => (1 T)     SBCL: (1)
(multiple-value-list (loop for i from (eice (%m 1)) to 5 collect i))
;                                    => ((1 2 3 4 5) T)    SBCL: ((1 2 3 4 5))
(defmacro eice2 (form) (values (macroexpand form)))
(multiple-value-list (loop for i from (eice2 (%m 1)) to 5 collect i))
;                                    => ((1 2 3 4 5))      -- the single-value expander is fine
```

CLHS 3.1.2.1.2.2: a macro function's secondary values are discarded; only the
first is the expansion. So a macro EXPANDER is one concrete non-tail `values`
producer whose leftover has a defined answer, and it is worth checking whether
the carrier design below fixes it for free or whether the expander seam needs its
own truncation. `.todo/715` ranks this second among all open items.
