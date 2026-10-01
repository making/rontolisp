# Clojure: a `class` dispatch through a named function still misses the nil method

Difficulty: Small (record class-calling defns at definition time, or map at dispatch).

## Gap (oracle `clj` 1.12.6.1673, measured 2026-10-01 during b38)

b38 lowers a `class` call inside an inline dispatch datum to answer nil itself,
so the dispatcher's null test maps it onto the `(:C%NIL)` marker -- bare or
wrapped in another function. A dispatch function NAMED elsewhere keeps the old
shape: its body was lowered as ordinary code (a nil answers the `:nil` keyword),
so it still misses the nil method:

```clojure
(defn myclass [x] (class x))
(defmulti w2 myclass)
(defmethod w2 nil [_] "was-nil")
(defmethod w2 :default [x] "dflt")
(println (w2 nil))  ; oracle "was-nil", repo "dflt"
(println (w2 :nil)) ; oracle "dflt", repo "dflt" (already correct)
```

Same for a `def`'d `(fn [x] (class x))` value used as the dispatch function.
Pinned as the documented deviation until then (`.kb/clojure-frontend.md`).

## Acceptance

- `clojure-spec.yaml`: named-class-dispatch pin (nil hits the nil method, `:nil`
  still falls to the default), green on all four backends.
- `.kb` rows updated; docs en+ja same commit if user-facing.

## Depends on

b19 (host-class dispatch), b32 (the `(:C%NIL)` marker), b38 (inline wrapped
`class` answers nil itself in dispatch position).
