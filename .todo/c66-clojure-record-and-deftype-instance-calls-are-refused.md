# c66. Clojure: an instance call naming a record's, deftype's or reify's own method or field is refused

Difficulty: Medium

Measured 2026-10-04, exec jar vs oracle `clj` 1.12.6 (all backends: a record, deftype or reify
is a tagged list, so the instance call takes the value arm of `ClojureValueMethodLowering`
and is refused by name; before that arm it was `java:call expects a java object ...`):

```clojure
(defprotocol P (m [this]) (k [this x]))
(defrecord R [a] P (m [this] (str "m" a)) (k [this x] (+ a x)))
(deftype T [x] P (m [this] (str "t" x)) (k [this y] y))
```

| form | oracle | ronto |
|---|---|---|
| `(.m (->R 1))` | `"m1"` | `Method m taking 0 args is not supported for class user.R` |
| `(.k (->R 1) 2)` | `3` | same refusal, `taking 1 args` |
| `(.a (->R 1))` | `1` (the field) | same refusal |
| `(.m (T. 5))` / `(.x (T. 5))` | `"t5"` / `5` | refusal naming `java.lang.Object` (a deftype value carries no class name) |
| `(.m (reify P (m [this] :r) ...))` | `:r` | refusal |
| `(.q (->R 1))`, `q` from an `extend-type` | `No matching field found: q for class user.R` | `Method q taking 0 args is not supported ...` |

Only an INLINE implementation is a method of the class (an `extend-type`/`extend-protocol`
row is not), and a zero-argument name that is no method reads the field. Plan: in the value
arm, a typed receiver (`ClojureProtocolLowering.isTypedForm`) whose tag has an inline row of a
protocol method of that name and arity calls the protocol dispatcher; a zero-argument name
reads the field table like `fieldRead`; anything else keeps the refusal in the oracle's words.
The method names are known at lower time (`ctx` protocols), so a site naming no protocol
method costs nothing. Give a deftype value its class name (records carry `user.R`) for the
refusal. Pin in `clojure-spec.yaml`.
