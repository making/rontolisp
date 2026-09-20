# Runtime packages have no member (intern) table

Difficulty: High (a symbol IS its spelling here; giving a package a membership
record touches `intern`, `find-symbol`, `symbol-package`, the enumeration walk
and all four backends)

Split out of `.todo/904` (2026-09-20), which measured the ANSI `packages`
chapter down to this one cause. **Read `.kb/packages.md`, "Runtime tier", the
paragraph "A runtime package has no MEMBER table" -- the numbers live there and
are not duplicated here.**

## What it is

`(intern "X" p)` on a `make-package` product records nothing: the symbol is its
spelling, and a runtime package keeps a name, a use list and nicknames and
nothing else. So `(find-symbol "X" p)` cannot answer `nil` before the intern and
the symbol after, and it cannot answer `:internal` / `:external` / `:inherited`
for a member nothing wrote down.

## What it costs, in the ANSI packages chapter (2026-09-20, suite `ca06bd9`)

Per failing test NAME, after `.todo/904`:

| family | tests | what it asks for |
|---|---:|---|
| `use-package.1`-`.23` | 21 | the `:inherited` status of an interned, exported symbol |
| `intern` | 17 | the status second value, and that a second intern answers the same symbol |
| `with-package-iterator` | 16 | walking a package's own members |
| `unintern` | 16 | removing a member |
| `find-symbol` | 15 | nil before, the symbol after |
| `shadowing-import` | 13 | `package-shadowing-symbols` answering the imported symbol |
| `shadow` | 10 + 2 | the same, plus the status flipping `:inherited` -> `:internal` |
| `import` | 12 + 2 | the same shape as shadowing-import |

**`shadow` / `shadowing-import` are NOT missing operators.** `.todo/904` planned
them as such and the measurement said otherwise: every one of their tests opens
with an `intern` whose effect has to be visible. Adding the two operators over
the present model moves none of them. Do not file them again separately.

## The decision to make first

Giving a package a member table is a change to what a symbol IS -- today the
canonical spelling, decided at read/compile time, is the identity, which is why
`unintern` is documented as cannot-exist. Either:

- **(a) a runtime-only member table**, carried by `LispPackage` and consulted by
  the interpreter's `intern`/`find-symbol`/`symbol-package` and by
  `%do-symbols-list`, with the compiled backends carrying it in
  `%runtime-packages%` beside the use list; the read/compile-time tier keeps
  answering from the baked universes as it does now. Smaller, and it is exactly
  the tier the failing tests exercise (every one of them builds its packages
  with `make-package`); or
- **(b) a real intern table everywhere**, which reopens `.kb/packages.md`'s
  "canonical spelling IS the symbol" premise and every backend that bakes a
  spelling. Very likely not worth it -- record the reasoning if it is rejected.

Take the measurement as the deliverable either way: re-measure the `packages`
chapter as a DIFF of failing test NAMES and report fixed AND regressed.
