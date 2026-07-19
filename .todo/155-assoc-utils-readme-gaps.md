# assoc-utils README — three examples don't work

Probe date 2026-07-19. `(ql:quickload :assoc-utils)` succeeds (real
`assoc-utils-20241012-git`) and most README examples run, but 3 fail. They
trace to 3 independent infrastructure gaps, each of which affects many CL
libraries, not just this one. Source under probe:
`~/.rontolisp/quicklisp/software/assoc-utils-20241012-git/src/assoc-utils.lisp`.

## Progress

- **Gap C — DONE (2026-07-19).** A user
  `deftype` registry lives on `ClosRegistry` (`registerDeftype`/`findDeftype`);
  `LispMacroExpander.expandDeftype(cons, closRegistry)` registers a
  zero-parameter `(deftype name () 'spec)` literal expansion (docstring
  tolerated), `makeTypeTest` resolves an otherwise-unknown symbol specifier
  through it (recursing, so `(satisfies pred)`, chained names, and ranged
  numerics all work), and `expandTopLevelDefinitions` registers deftypes on the
  compile path (guard widened). Interpreter registers at eval time. Works in
  `typep` AND `typecase` (both thread the registry); `check-type` still uses the
  registry-less `makeTypeTest` overload (left as-is — not needed for assoc-utils).
  Tests: `LispEvaluatorTest` (3), `JvmLispCompilerTest` (1),
  `WasmLispCompilerIntegrationTest` (1), `ci-spec.yaml` case
  `user-deftype-typep-satisfies` (native CiSpecE2eTest green on all 4 backends),
  docs updated (`deftype`/`typep`/`typecase`, en+ja). Note: a `(unsigned-byte N)`
  body still fails on WASM — a PRE-EXISTING, separate WASM-bignum limitation
  (the bound is a `LispBigInteger`), unrelated to deftype; use the `satisfies`
  shape for cross-backend deftypes.
- **Gap B — DONE (2026-07-19).** `define-setf-expander`
  + `defsetf` (short and long forms) + a real `get-setf-expansion` on all four backends.
  Interpreter: `LispEvaluator` keeps a `setfExpanders` registry (`SetfExpanderForm`/
  `DefsetfShort`/`DefsetfLong`); `setf` routes a registered user place through
  `expandSetfMaybeUserExpander` -> `expandUserSetfPlace` -> `userPlaceFiveValues`
  (a `define-setf-expander` place runs its expander via `callSetfExpander`, which rebuilds
  the expander as a lambda and collects the five values with `multiple-value-list`). The
  five values assemble into `(let* ((temp val)... (store new)) store-form)`. `incf`/`decf`
  on a user place work (they expand to `setf`). `get-setf-expansion` is the EXISTING
  `LispPreludeLibrary` Lisp defun (not a new Java builtin) -- it returns five values via
  the `%mv-spill` channel, which an expander body's `multiple-value-bind` reads back.
  Compile path: `UserMacroExpander` registers the definition into its macro-time evaluator
  and rewrites `(setf/incf/decf (user-place ...) ...)` call sites through it before the
  compilers (which never see the expander). `PackageResolver` now treats a
  `define-setf-expander`/`defsetf` body as template context (like `defmacro`), so a
  backquote-template helper (`%aput`) resolves in the DEFINING package, not the call site's
  -- this is what made the real `assoc-utils` `(setf (aget ...) ...)` work end-to-end.
  Tests: `LispEvaluatorTest` (2), `JvmLispCompilerTest` (2), `WasmLispCompilerIntegrationTest`
  (1), ci-spec `user-setf-expander-and-defsetf`, docs (`define-setf-expander`/`defsetf` new/
  updated, en+ja). Verified the REAL `(ql:quickload :assoc-utils)` setf example on all four
  backends. Compile-path limitation: `push`/`pop`/`pushnew`/`rotatef`/`psetf` on a USER
  place are interpreter-only (only `setf`/`incf`/`decf` are rewritten in
  `UserMacroExpander`); a plain-place use of those is unaffected.
- **Gap A — DEFERRED to a separate session (major work, user 2026-07-19).** Kept out of
  the Gap C+B commit; the design analysis below is the starting point for that session.

## README example status (interpreter)

| Example | Status |
|---|---|
| `aget` read / default | OK |
| `(setf (aget ...) ...)` | FIXED — gap B DONE |
| `alist-get` | **FAIL — gap A** (returns nil) |
| `with-keys` | **FAIL — gap A** (`variable name is unbound`) |
| `remove-from-alist` / `delete-from-alist` | OK |
| `alist-plist` / `plist-alist` | OK |
| `alist-hash` / `hash-alist` | OK |
| `alist-keys` / `alist-values` | OK |
| `alistp` | OK |
| `(typep ... 'alist)` | FIXED — gap C DONE |
| `alist=` | OK |

## Gap A — reader case-folding vs. rontolisp's case-preserving reader

rontolisp keeps symbol case verbatim (deliberate: `symbol-name` is
case-preserving, no intern table). Standard CL's reader `:upcase`s unescaped
symbols, and both the library and the README rely on that folding:

- `alist-get`: data keys are written `:ELEMENTS :TAGS :NOTE` (upper), the query
  is `'(:elements 0 :tags :note)` (lower). In CL both fold to `:ELEMENTS` and
  `assoc` matches; in rontolisp `:elements /= :ELEMENTS`, so `assoc` misses and
  the reduce returns nil. Minimal: `(eq :elements :ELEMENTS)` => nil here.
- `with-keys`: the macro binds `(intern (string-upcase (format nil "~A" entry)))`
  => `NAME` (upper) but the body references `name` (lower). CL folds the body
  ref to `NAME`; rontolisp leaves them distinct => `variable name is unbound`.

This is the widest-reaching and most design-loaded of the three: any CL code
that mixes symbol case and leans on reader folding hits it.

### Design analysis (2026-07-19, before the separate session)

Decision so far: the DEFAULT case-preserving reader stays; add an OPT-IN mode
(off by default) -- but it is a MAJOR change, not a reader flag. Findings:

- **It must UPCASE, not downcase.** `with-keys` builds the binding symbol with
  `(intern (string-upcase (format nil "~A" entry)))` => `NAME` and expects the
  body reference `name` to also fold to `NAME` (CL's upcase reader). A downcase
  fold would make the body `name` while the binding stays `NAME` => still
  unbound. Only upcasing the body reference matches. `alist-get` (keyword fold)
  works with either direction, but `with-keys` pins it to upcase.
- **Why upcase is invasive here (the load-bearing part).** rontolisp has NO
  intern table -- a symbol IS its verbatim name string, matched CASE-SENSITIVELY
  everywhere -- and EVERYTHING is spelled lowercase: builtins (`list`/`car`/`+`),
  special forms (`defun`/`let`), packages (`cl`/`rontolisp`), lambda-list
  keywords (`&optional`/`&rest`), `t`/`nil`, AND keyword arguments (`:test`/
  `:key`/`:start`...) which every builtin matches by exact lowercase spelling.
  `symbol-name` is verbatim lowercase (was CHANGED from CL-upcase to verbatim for
  cl-base64's macro-time `(intern (concatenate ... (symbol-name x)))` name
  synthesis, todo-085). A naive upcase reader turns `list`->`LIST`,
  `&optional`->`&OPTIONAL`, `t`->`T`, `:test`->`:TEST` and breaks ALL of them.
  Rationale for case-preservation: `.kb/symbol-runtime-api.md` (no-intern-table
  assessed stable 2026-07-05); CL can upcase only because it has an intern table
  + all-uppercase standard symbols + case-INSENSITIVE identity, the three things
  rontolisp deliberately dropped.
- **So a faithful upcase mode = reader upcases unescaped symbols PLUS
  case-insensitive matching (mode-gated) for: every CL/builtin/special-form/macro
  name, lambda-list keywords, `t`/`nil`/`otherwise`, car/cdr compositions, and --
  the hardest -- keyword ARGUMENTS in every builtin's keyword parser.** Escaped
  (`|...|`) symbols and string literals stay verbatim. Must hold on all four
  backends (reader Features flag threaded like `--upcase`), and `symbol-name`
  should then return the upcased name in that mode.

### Two candidate approaches for the separate session

1. **Scoped upcase (smaller, recommended start).** Reader upcases unescaped
   symbols; a normalization maps a name back to its canonical lowercase when the
   lowercased form is a known CL name / lambda-list keyword / `t`/`nil` /
   package prefix; keyword-argument PARSING in the builtins lowercases the
   keyword before matching (so `:TEST` still binds `test`). User symbols stay
   upcased, so `with-keys` (`name`->`NAME`) and `alist-get` (`:elements`->
   `:ELEMENTS`) both fold. Risk: quoted symbol DATA upcases (changes
   `symbol-name`/string compares under the flag -- acceptable, opt-in), and the
   keyword-parser change touches many builtins (find the central `&key` parsing
   helper first).
2. **Full case-insensitive identity (largest, most CL-faithful).** Make symbol
   identity case-insensitive across the core (reader + resolver + special-form
   dispatch + Environment/`_env` lookup + the compilers' name mangling). Highest
   risk; effectively re-introduces the machinery no-intern-table dropped.

Either way: gate on a new `Features` flag + a CLI `--upcase`, keep default output
byte-identical, and add a ci-spec case exercising the REAL assoc-utils
`with-keys` + `alist-get` (currently the only two README rows still failing).

## Gap B — `define-setf-expander` is a no-op, so user setf places fail

`assoc-utils` gives `aget` a setf via `define-setf-expander`. rontolisp's
`LispMacroExpander.expandDefineSetfExpander()` (LispMacroExpander.java:11789)
intentionally expands it to `nil`, so the place is unusable:
`setf does not support place: assoc-utils:aget`. The full five-value
expansion protocol (`get-setf-expansion` / `&environment`) is unimplemented.
Feature add, not a bug fix; also unlocks `defsetf` short/long forms if done
generally. Interpreter first, then both compilers' setf front-ends.

## Gap C — `typep` doesn't resolve a user `deftype` that expands to `satisfies`

`(deftype alist () '(satisfies alistp))` is accepted, but `typep` never
consults user deftypes, so `(typep x 'alist)` is always nil even though
`(alistp x)` is correct. Minimal repro:
```lisp
(deftype my-even () '(satisfies evenp))
(typep 4 'my-even)  ; => nil, want t
```
Smallest/most local of the three: `typep` needs a user-deftype registry +
`satisfies` handling (call the named predicate). Shared static type-test
builder is in `LispMacroExpander` (see `typep`/`subtypep` machinery); the
compilers fold literal type specifiers, so a user deftype has to be expanded
at that layer too, or fall through to a runtime predicate call.

## Suggested order

C (local, clear cost) -> B (feature, bounded) -> A (design decision first).
Each is independently shippable; none blocks the others.
