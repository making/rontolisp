# c69. Clojure: `(defmacro fn ...)` is refused although the reader no longer spells `fn`

Difficulty: Low

`ClojureMacroLowering.READER_HEADS` reserves `fn` because `#(...)` used to read as
`(fn %anon ...)`. Since c67 it reads as the oracle's `(fn* [p1__N# ...] (body))`, and `fn*`
is a special form, so the reason is gone. The oracle (clj 1.12.6, 2026-10-04) accepts it:

```clojure
(defmacro fn [& r] 42)        ; WARNING: fn already refers to: #'clojure.core/fn ...
(println (fn [x] x))          ; 42
(println (map #(inc %) [1]))  ; (2)   -- #() is fn*, untouched
```

Here: `fn cannot name a macro: the reader spells its own forms with it`.

Before dropping `fn` from `READER_HEADS`, check every datum the lowering builds with a
bare `fn` head and lowers again (`ClojureBindingLowering` records `(fn ...)` for
`recordClassDispatchFn`): a program macro named `fn` must not capture it (spell it
`clojure.core/fn` or lower it directly). Then pin the three lines above in
`clojure-spec.yaml` and update doc/en + doc/ja `clojure/deviations.md` and
`clojure/semantics.md`, which list `fn` among the refused names.
