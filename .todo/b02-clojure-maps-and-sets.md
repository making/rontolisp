# b02: Clojure maps and sets

Difficulty: High

## Premise (measured, 2026-09-30)

Map and set literals are refused by name (`a map literal is not supported yet`);
a map needs the hash-table runtime (`.kb/clojure-frontend.md`, "Deviations").
Measured:

- `{:a 1}` fails here, answers a map there.
- `#{1 2}` fails here with the MAP message (the set reader reuses the
  `%hash-map` marker) -- the refusal names the wrong literal.
- Every map verb is `unknown name`: `assoc`, `dissoc`, `get`, `contains?`,
  `keys`, `vals`, `merge`, `conj`, `disj`, `set`, `hash-map`, `array-map`.
- `(:a {:a 1})` (keyword lookup, [[b01]]) has nothing to look into.

## Shape

- Decide the runtime first: the hash-table runtime (`.kb/hash-tables.md`) or a
  persistent-map library in Common Lisp spliced like `scheme.lisp`. Vectors print
  in CL notation already, so a map printing choice comes free with the decision.
- Reader: split the set marker off `%hash-map` so the refusal (and later the
  lowering) names the literal it saw.
- Lowering: `{k v ..}` to a map construction, `#{..}` to a set construction;
  the verbs above to their operations; `count`/`empty?`/`=` extended onto maps.
- `get` with a default, `assoc!`-style transients: explicitly in or out.

## Tests

- `clojure-spec.yaml` cases for literals, `assoc`/`dissoc`/`get`/`contains?`/
  `conj`/`disj`, and the set-literal refusal message; `ClojureSpecE2eTest` on all
  four backends.
