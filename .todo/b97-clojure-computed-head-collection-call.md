# b97. A call whose head is a computed collection or keyword signals "Not a function"

Difficulty: Low

Measured 2026-10-03, oracle `clj` 1.12.6.1673 vs exec jar built from `1da6801c6`, one
`.clj` line per run, interpreter:

| Source | Oracle | rontolisp |
|---|---|---|
| `(println ((set [1]) 1))` | `1` | `Not a function: (:C%SET #<HASH-TABLE ...>)` |
| `(println ((hash-map :a 1) :a))` | `1` | `Not a function: #<HASH-TABLE ...>` |
| `(println ((vec [1 2]) 0))` | `1` | `Not a function: #(1 2)` |
| `(println ((identity {:a 1}) :a))` | `1` | `Not a function: #<HASH-TABLE ...>` |
| `(println ((first [:a]) {:a 5}))` | `5` | `Not a function: (:C%KEYWORD "a")` |

A bound local already works (`(let [s (set [1])] (s 1))` goes through
`rontolisp::%clojure-call`); only a compound head misses it.

Cause: `ClojureLowering.lowerInner`'s `head instanceof LispCons` arm lowers anything that is
no collection literal or var form to `(funcall head args)`; the IFn dispatcher is never
reached.

## Plan

- Lower a compound head that is not a `fn`/`#()` form to `rontolisp::%clojure-call` over
  the argument list (keep the direct `funcall` for a head known to answer a function, so
  `((fn ...) args)` and `((comp ...) x)` keep their shape). Mind `ClojureLowering.builtin`'s
  size (`HugeMethodTest`): the change belongs outside it.

## Pin

- `clojure-spec.yaml` (all four backends): the five rows above.
