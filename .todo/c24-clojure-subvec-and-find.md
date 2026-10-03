# c24. Clojure `subvec` and `find`

Difficulty: Low

Both are unknown names (2026-10-03, exec jar at `093172641`):

- `(subvec [1 2 3 4] 1 3)` -> `error: unknown name: subvec`; oracle `[2 3]`.
- `(find {:a 1} :a)` -> `error: unknown name: find`; oracle `[:a 1]`.

Add both as Clojure built-ins (`.kb/adding-primitives.md`), in call position and as values, matching `clj` 1.12.6:

- `subvec` with 2 and 3 arguments; out-of-range or `start > end` signals `IndexOutOfBoundsException`; the result is a vector.
- `find` on maps (structural keys, `.kb/clojure-frontend.md` "Structural keys"), records, vectors (index -> `[i x]`), and nil -> nil; a missing key -> nil.

Pin in `clojure-spec.yaml` on all four backends against the oracle, and add `doc/{en,ja}/clojure/reference` pages with catalog entries.
