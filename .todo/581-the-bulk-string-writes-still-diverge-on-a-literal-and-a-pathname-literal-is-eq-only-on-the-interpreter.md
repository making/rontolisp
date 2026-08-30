# The bulk string writes still diverge on a literal, and a `#P` literal is `eq` only on the interpreter

Difficulty: Medium

Found 2026-08-29 while closing `.todo/580`, which gave `%schar-set` -- the
`(setf (char|schar|aref|elt s i) c)` family -- a rule that holds on all four backends: a
write through a place holding a string LITERAL rebuilds and rebinds, the source constant
is never touched, and a place that cannot be rebound is an error
(`.kb/string-write-runtime.md`, "A string LITERAL is never written"). Two neighbours of
that rule were measured in the same pass and left alone, deliberately, because neither
has an answer `%schar-set`'s transfers to.

## 1. The BULK writes still corrupt a literal, and no backend is right

Measured 2026-08-29, all four backends, one `(defun r () "abc")` per row and the row's
form run as `(let ((a (r))) <form> a)` followed by a fresh `(r)`:

| form | interpreter | JVM / WASM P1 / WASM component |
|---|---|---|
| `(replace a "Z")` | `"Zbc"`, and the next `(r)` is `"Zbc"` | `"abc"`, the write DISCARDED |
| `(fill a #\Q)` | `"QQQ"`, next `(r)` is `"QQQ"` | `"abc"`, discarded |
| `(setf (subseq a 0 1) "Y")` | `"Ybc"`, next `(r)` is `"Ybc"` | `"abc"`, discarded |
| `(nstring-upcase a)` | `"ABC"`, next `(r)` is `"ABC"` | returns `"ABC"`, `a` and the next `(r)` both `"abc"` |
| `(sort a #'char<)` | `"abc"` | `"abc"` |

So on the interpreter the source constant is gone -- exactly the bug 580 fixed for
`%schar-set` -- while on the three compile paths the write is silently LOST, because
`expandReplace` / `expandFill` take their functional branch (`%arrayp` is false for an
immutable string), build the right string, and drop it in statement position. 580's
answer does not transfer: it works only because `expandScharSetFunctional` already
`setq`s its result back into the place, and `replace`/`fill` have no such setq -- they
are FUNCTION calls whose result the caller usually ignores.

Any fix has to pick one answer for all four and it will not be "rebind", since
`(replace a b)` is not a place form. The candidates, none costed yet:

- **Signal on a literal target** (`replace`/`fill`/`nstring-*`/`(setf (subseq ...))` into
  a source constant is an error on all four). Matches what 580 does for a
  non-rebindable place, and turns a silent wrong answer into a diagnosable one on both
  sides. Needs the compile paths to be able to TELL a literal at run time -- on WASM the
  discriminator is free and already exists (`_str_build` ids are interned offsets below
  `heapBase`, `.kb/wasm-gc-strings.md`, and `.todo/559` step 3 names the same test); on
  the JVM there is no such bit on a bare `String` and finding one is the whole cost.
- **Make the compile paths' functional branch reach further** -- explicitly rejected by
  `.todo/559` ("Do NOT 'fix' it by making `expandScharSetFunctional` write further. The
  setq is a symptom").
- **Leave it and document it.** Defensible only while the interpreter half is not a
  source-constant corruption, which it is.

Related and probably the real gate: `.todo/559` (a string the compiled backends did not
build mutable has no identity). If 559 lands, `replace`/`fill` write in place on every
backend and the only open question left here is what a LITERAL target does -- which 559
already answers in principle ("a literal must stay immutable either way") but not in
code.

## 2. A `#P"..."` pathname literal is `eq` to itself only on the interpreter

```lisp
(defun fp () #P"a/b.txt")
(eq (fp) (fp))          ; interpreter T, JVM NIL, WASM P1 NIL, WASM component NIL
(namestring (fp))       ; "a/b.txt" on all four
```

`LispReader.readPathname` folds `#P` to a `LispInstance` over `LispLayout.PATHNAME`,
which self-evaluates here (`LispEvaluator.eval`'s `LispInstance` arm hands the datum
back) while both compile backends rebuild the instance at the site. This is the same
shape `.todo/578` settled for an array literal, one level up: the array literal answer
was fresh-per-evaluation, which is what the compile paths already do, so applying it here
is one `LiteralArrays`-style materialization in the `LispInstance` arm -- **except** that
arm also carries every runtime instance the evaluator splices back through
`(quote <value>)`, which is precisely the hazard `.kb/array-literals.md` records under
"What `quote` still shares" and `.todo/579` owns. Measure before assuming it is cheap.

Nothing writes into a pathname today, so this is a latent `eq` divergence and not a
corruption. It is recorded here so the next reader does not rediscover it; if it is ever
worth fixing it should move with `.todo/579`, not on its own.

## Do

- Decide and land ONE answer for the bulk writes into a literal, on all four backends,
  with a `ci-spec.yaml` case beside `string-literal-write-cross-backend`. If the
  measurement says the answer belongs to `.todo/559`, say so there and delete this half.
- Either fix the `#P` `eq` divergence together with `.todo/579` or write the decision not
  to into `.kb/array-literals.md`'s "What `quote` still shares" section.

## What `.todo/579` left here (2026-08-30)

579 landed "a quoted datum is one shared constant per quote site on all four backends"
(`.kb/quoted-data.md`) and deliberately did NOT take part 2: a BARE `#P"..."` / `#S(...)`
instance literal in code position is still rebuilt per evaluation on the three compile
paths while the interpreter's self-evaluating `LispInstance` arm shares it. What 579
leaves you:

- The memoization machinery to reuse: `JvmLispCompiler.QuotePool` (lazy volatile
  `_qd$N` statics -- lazy because `JvmClassShaker` must drop a shaken wrapper's
  constants; a `<clinit>` initializer measured +13 KB per program) and
  `WasmLispCompiler.QuoteGlobals` (lazy `(mut (ref null eq))` globals appended after
  every fixed index, shared into `WasmAsyncEmit`'s contexts). An instance literal
  nested INSIDE quoted data is already shared through them; wrapping
  `Jvm/WasmQuoteCompiler.compileLiteralInstance`'s bare-position callers in the same
  intern is the remaining step.
- The constraint that still stands: the interpreter's `LispInstance` arm also carries
  every live instance the evaluator splices back through `(quote <value>)`, so the fix
  must stay on the compile side (share there too) -- materializing on the interpreter
  is the direction 579's decision record rejects.

## Related

- `.todo/580` -- the `%schar-set` half, closed; `.kb/string-write-runtime.md` carries its
  measurement table and the boundary this item sits on.
- `.todo/559` -- string identity on the compiled backends; the likely gate for part 1.
- `.todo/579` -- the quoted-datum divergence; the likely home for part 2.
