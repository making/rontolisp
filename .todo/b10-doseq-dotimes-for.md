# b10 — doseq/dotimes/for (imperative loops + comprehensions)

Difficulty: Medium (lowering-only datum rewrites, but `for` nesting + `:while`/`:let` semantics need care)

Status: open. Covers the `shcloj4-code.zip` iteration backbone the frontend
currently refuses by name (`iteration forms are not supported yet`, `ClojureLowering:453`).

Corpus: https://media.pragprog.com/titles/shcloj4/code/shcloj4-code.zip
(`code/src/examples/*.clj`, `code/hangman/src/hangman/*.clj`).

## Gap (measured 2026-10-01)

`doseq` x6, `dotimes` x10, `for` x4 in the corpus; all three lower to a refusal
today. Representative uses:

- `exploring.clj`: `demo-loop`/`countdown` already work via `loop`/`recur`, but
  `index-filter` uses `for [[idx val] (indexed coll) :when (pred val)] idx`.
- `interop.clj`: `demo-threads` (`dotimes`), `demo-sax-parse` path, `describe-class` loop.
- `snake.clj` / `atom_snake.clj`: `paint :snake` (`doseq [point body]`).
- `eager.clj`: `preds-seq`/`line-count` (`doseq`-shaped `for` + `dotimes` timing harness in comments).
- `sequences.clj`: `clojure-loc` (`for [file (file-seq ...) :when ...]`), `demo-xml-seq`.
- `pi.clj`: `parallel-guess-pi` (`for [_ (range agent-count)]`).

## Oracle (host `clj` 1.12.6, verified 2026-10-01)

```clojure
(doseq [x [1 2 3]] (print x)) ; prints 123, answers nil
(dotimes [i 3] (print i))     ; prints 012, answers nil
(for [x [1 2 3] :when (odd? x)] (* x 10)) ; => (10 30)
(for [x [1 2] y [3 4]] [x y]) ; => ([1 3] [1 4] [2 3] [2 4])
```

`doseq`/`dotimes` answer `nil`, run for side effects. `for` answers a (lazy)
seq; `:when`/`:while`/`:let` modifiers filter/terminate/bind.

## Scope

- `doseq` over the existing seq view (vectors/strings/maps/sets/nil, same
  coercion as `map`/`filter`), body in an implicit `do`, answer `nil`.
- `dotimes [i n]` 1-binding strict loop, answer `nil`. Non-integer `n` signals
  like the oracle (class-cast path, not the CL type error).
- `for` over the same seq view with `:when`/`:let` (`:while` terminates the
  whole comprehension, not just the current binding level), nested bindings
  cross-product, answer a strict list (`nil` for empty — the established
  `rest`/`take` divergence, not `()`).
- Destructuring in the binding vectors reuses the `let` pattern lowering
  (vector/map patterns, `&` rest, `:as`); malformed shapes are the same named
  refusals as `let`.
- `dorun`/`doall` as the strict companion (realize + answer `nil`/the seq);
  needed by `concurrency.clj` (`dorun` in `demo-memoize`).

Out: parallel/lazy chunking (strict-only per b03 stays), `:when` with side-effect
ordering beyond left-to-right (documented left-to-right).

## Design constraints

- Lowering only (`ClojureLowering` + `ClojureReader` unchanged except new heads);
  no backend learns a Clojure name (`.kb/clojure-frontend.md`, `.kb/architecture.md`).
- `doseq`/`dotimes` lower to `dolist`/`dotimes`-shaped core (`LispMacroExpander`
  already has both); `for` lowers to nested strict accumulation (no new runtime).
- `CompileFrontend.expand` pipeline untouched; false/ex-info/hierarchy runtimes
  unaffected.
- Four-backend parity: interpreter + JVM + both WASM, pinned in
  `clojure-spec.yaml` (concatenated-program slicing, `ci-spec.yaml` idea).

## Acceptance

- New `clojure-spec.yaml` cases: `doseq-prints-and-answers-nil`,
  `dotimes-counts`, `for-with-when-let-while`, `for-nesting-and-destructuring`,
  `dorun-doall` — each green on all four backends.
- Refusal removal: `doseq`/`dotimes`/`for` leave `ClojureLoweringTest` refusal
  pins; `:while` termination + odd-modifier refusal covered there.
- Docs: `doc/en+ja/clojure/{semantics,deviations,reference}.md` rows + lowering-table
  row in `.kb/clojure-frontend.md` (strict-`for` deviation: empty is `nil`).
- `.kb/adding-primitives.md` does not apply (no new CL primitive; macro-level
  lowering only).
