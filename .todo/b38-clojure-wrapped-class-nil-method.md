# Clojure: a `class` call wrapped in another dispatch function misses the nil method

Difficulty: Trivial (dispatcher `nilTest` in `defmultiForms`).

## Gap (oracle `clj` 1.12.6.1673, measured 2026-10-01 during b32)

b32 keys a nil method on the `(:C%NIL)` marker. The dispatcher maps a true nil
onto it by a null test -- except for a bare `class` dispatch, which answers the
`:nil` keyword for a nil argument and maps that spelling onto the marker instead
(a literal `:nil` argument answers `:keyword` there and stays apart, like the
oracle). A `class` call wrapped in another function keeps the keyword and still
misses the nil method:

```clojure
(defmulti w (fn [x] (class x)))
(defmethod w nil [_] "was-nil")
(defmethod w :default [x] "dflt")
(println (w nil))  ; oracle "was-nil", repo "dflt"
(println (w :nil)) ; oracle "dflt", repo "dflt" (already correct)
```

The bare-`class` shape (`(defmulti m class)`) is pinned by
`nil-method-stays-distinct-from-nil-keyword` on all four backends; the wrapped
shape has no pin. Pinned as the documented deviation until then
(`.kb/clojure-frontend.md`, `doc/*/clojure/deviations.md`).

## Acceptance

- `clojure-spec.yaml`: wrapped-class nil pins (nil hits the nil method, `:nil`
  still falls to the default), green on all four backends.
- `.kb` rows updated, deviation note removed/corrected; docs en+ja same commit
  if user-facing.

## Depends on

b19 (host-class dispatch), b32 (the `(:C%NIL)` marker this maps onto).
