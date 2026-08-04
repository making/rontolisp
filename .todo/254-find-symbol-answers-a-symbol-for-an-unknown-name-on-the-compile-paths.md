# 254. `find-symbol` answers a SYMBOL for an unknown name on the compile paths

Difficulty: 高 (the honest fix is the symbol-identity axis of `.todo/156` --
a per-artifact table of what a package actually owns, on TWO backends, with a
runtime-`intern` story; the cheap fixes all trade one wrong answer for another)

Found 2026-08-04 while authoring `MitoE2eTest` (`.todo/250`). It is the FIRST
consumer for which the deviation already recorded in `.kb/symbol-runtime-api.md`
is not harmless, and it costs a whole family of sxql/mito calls on two of the
three supported backends.

## The deviation

`.kb/symbol-runtime-api.md` states it: 2-argument `find-symbol` on the compiled
backends BUILDS the canonical spelling (`(intern "PKG:NAME")`) instead of asking
whether the package owns the name, so **an unknown name yields a symbol instead
of `nil`**. The interpreter is registry-backed and answers `nil`.

The note calls that "harmless where find-symbol feeds a plist lookup that then
answers nil anyway". The harmless reading does not survive the
`find-symbol` -> `symbol-function` idiom.

## The repro (no database needed)

```lisp
(ql:quickload "sxql")
(print (sxql:yield (sxql:select ((:as (:count :*) :count)) (sxql:from :users))))
```

| backend | result |
| --- | --- |
| interpreter | `"SELECT COUNT(*) AS count FROM users"` |
| JVM class | `The function SXQL/OPERATOR:MAKE-COUNT-OP is undefined` |
| WASM component | traps (`wasm backtrace: <wasm function 58>`) |

`sxql/operator.lisp:107-123`: `find-make-op` resolves an operator through
`(find-constructor op-name "-OP" :package pkg :errorp nil)`, which is
`(find-symbol "MAKE-COUNT-OP" pkg)` and then `(symbol-function ...)` **only when
the lookup found something**. `:count` has no `define-op` struct -- by design,
it is meant to fall through to the generic `make-function-op` -- so upstream
depends on `find-symbol` answering `nil`. We answer a symbol, `symbol-function`
signals, and the fallback is never reached.

Scope: **every sxql SQL FUNCTION operator** (`:count`, `:sum`, `:max`, `:min`,
`:avg`, any `(:some-pg-function ...)`), and therefore `mito:count-dao`, on the
JVM and WASM backends. Ordinary comparison/logic operators are unaffected --
they DO have op-structs, so the lookup is supposed to succeed.

## Why the cheap fixes are not fixes

- *"Make `find-symbol` return nil when the name is not baked in the artifact."*
  The artifact's string table is a table of spellings the program MENTIONS, not
  of what a package OWNS. `MAKE-=-OP` is mentioned nowhere either (sxql builds
  that name at run time too), so this would break the operators that currently
  work -- swapping a loud failure on the rare path for a loud failure on the
  common one.
- *"Special-case `symbol-function` to answer nil instead of signalling."* Wrong
  in CL and it would hide real undefined-function errors.
- *"Bake the resolver's registry."* Closest to right, and this is the actual
  work: `PackageResolver` knows what each package owns, but on the compile paths
  it knows it at RESOLVE time, before the `define-op` macro has produced the
  `defstruct` whose `:constructor` names `MAKE-=-OP`. So the table has to be
  built after user-macro expansion, and it has to include definitions the
  backends synthesize.

## Acceptance

- The repro above prints the same string on all four backends.
- `mito:count-dao` joins the byte-identical set: delete
  `MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends` and widen
  `countDaoOnTheInterpreter` to the three legs (that test is the tripwire and
  names this file).
- A ci-spec case for the `find-symbol` -> `symbol-function` shape on all four
  backends, SBCL-checked.
- `.kb/symbol-runtime-api.md`'s deviation paragraph either goes away or states
  what is left, with a re-evaluation trigger; `.kb/mito.md`'s "Known rontolisp
  gaps" entry goes with it.
- Whatever remains a deviation gets its reason recorded, not just its "how".

## Relation to `.todo/156`

`.todo/156` deferred A1 (a real intern table with symbol identity) to a go/no-go
decision, on the finding that "none of which any current library or roadmap item
needs". **This is the counter-example**: it is a symbol-IDENTITY question (does
this package own this name?), not a spelling question, and a real library depends
on the answer. Decide A1 with this item in hand rather than separately.
