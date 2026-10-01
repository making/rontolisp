# Clojure: host-object boolean `false` prints `nil` (b20 follow-up)

Difficulty: Medium (shared `java:` unmarshal + bridge parity; interpreter, JVM
direct sites and the bridge must move together, `.kb/java-interop.md`).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673)

A `java:` call answering a Java boolean prints `nil` for `false` (unmarshal maps
it to `NIL`), where the oracle prints `false`. String receivers are unaffected
(they take the mapped core operations, already `T`-or-false), and b20 wrapped the
static paths it owns (zero-arg static calls, static calls whose overloads at that
arity all answer booleans, member-as-value lambdas). What is left is every
boolean that arrives through a host object:

| Probe (oracle 1.12.6) | rontolisp today |
|---|---|
| `(println (.contains (java.util.ArrayList. [1]) 2))` -> `false` | `nil` |
| `(println (.isEmpty (java.util.ArrayList. [1])))` -> `false` | `nil` |
| `(println (.isEmpty (java.util.ArrayList.)))` -> `true` | `true` (already right) |

Corpus blocks: none in the shcloj4 table print a host boolean today (the gap was
found testing b20's `T`-or-false rule against instance calls); any future corpus
use of a boolean host method (`isEmpty`, `contains`, `equals` on host objects)
prints `nil` for `false`.

## Design sketch

- The rule lives once per path today (`eval/JavaInterop` unmarshal, the JVM
  direct sites' `_junm`, `JavaBridgeTemplate` unmarshal, pinned by
  `JavaBridgeTemplateParityTest`): a Java `false` becomes `NIL` everywhere.
  Answering the false object instead changes every `java:` program's printed
  output and every `if`/`eq` on a host boolean, on all three paths at once.
- Narrower alternative: wrap in the Clojure lowering only (like b20's statics),
  answering `T`-or-false for instance calls whose receiver class is known to
  answer booleans. Needs the receiver's class at lowering (a `let`-inferred or
  declared type, or a literal construction) -- unknown receivers keep unmarshal.
- Either way, decide + document whether `false` (the distinct object) or `nil`
  crosses into Lisp, and mirror the choice in `doc/en/clojure/` and `doc/ja/clojure/`
  with the
  member-value precedent (`(map odd? [1 2])` prints `(true false)`).

## Acceptance

- `ClojureInteropTest`: a host boolean prints `false` on the interpreter + JVM
  (`ArrayList` contains/isEmpty shapes above); wasm legs pin the `java:` refusal
  beside `ClojureWasmInteropRefusalTest`.
- `.kb/clojure-frontend.md` deviation row updated (host booleans); docs en+ja
  same commit.

## Depends on

b20 (this item's static `T`-or-false rule is the precedent). Independent of
b18/b19/b21-b28.
