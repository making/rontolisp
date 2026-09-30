# b08: Clojure protocols, hierarchies, ex-info and regex (b05 follow-up)

Difficulty: High

## Premise (measured, 2026-09-30)

b05 lowered the independently shippable state/dispatch/platform/reader bullets
(atoms, `try`/`catch`, multimethods, `ns` + `clojure.string`, interop, chars,
radix integers, exact `M` decimals) and left the rest as named refusals, each
pinned in `ClojureReaderTest` / `ClojureLoweringTest`:

- `defprotocol` / `defrecord` / `deftype` / `definterface` / `reify` /
  `extend-protocol` / `extend-type` / `extend` / `satisfies?` / `proxy` /
  `gen-class` / `gen-interface` are `protocols are not supported yet: <name>`.
- `derive` / `underive` / `isa?` / `parents` / `ancestors` / `descendants` /
  `make-hierarchy` and `prefer-method` / `defmulti :hierarchy` are
  `hierarchies are not supported yet: <name>`.
- `ex-info` / `ex-data` are `<name> is not supported yet: exception data needs
  a design` (`throw` renders through `princ-to-string`, so only strings
  round-trip with a message today).
- `#"..."` is `regex literals are not supported yet` (there is no regex
  runtime; `clojure.string/split` and `replace` match literal strings only,
  `String/split` lowers with literal semantics).
- `set!`, `memfn`, backquote (`syntax-quote` / `unquote`), `var` / `#'` and
  `^` metadata (`with-meta`) are named refusals.
- Interop gaps against the `java:` surface (`.kb/java-interop.md`): `proxy`
  could map to `java:proxy` with a name-dispatching lambda; mutable field
  assignment (`set!` on `.-field`) has no lowering; a zero-argument
  `Class/member` reads a static field (a zero-argument static method spells
  `(. Class method)` instead); instance calls on non-string Lisp values go to
  `java:call` and fail there (only strings get the mapped core operation).

## Shape

- Dispatch design for protocols is the open question (b05's table + dispatcher
  `defun` is the model to follow or reject); hierarchies decide with it.
- `ex-info` needs a condition carrying data on all four backends
  (`.kb/error-handling.md`); check what `handler-case` can bind first.
- A regex runtime (or a documented literal-only position) precedes `#"..."`.
- Each bullet stays independently shippable with `clojure-spec.yaml` cases
  (`ClojureSpecE2eTest` on all four backends); interop stays in
  `ClojureInteropTest` (interpreter + JVM, since wasm rejects `java:`).

## Tests

- `clojure-spec.yaml` cases per bullet as each lands; `ClojureSpecE2eTest` on
  all four backends.
