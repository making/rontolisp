# b61. `deftype` mutable fields (^:mutable) and `set!` on them

Difficulty: Medium

`set! is not supported yet: mutable fields need a design` -- a corpus program
defines `(deftype Instant [&] ^:mutable millis ...)` and mutates fields in
methods, the one `set!` use that survives everywhere (instance fields, no
host).

## Design

A `^:mutable` field makes the deftype's value cell per-instance mutable: the
deftype struct gains one `setf`-able slot holder per mutable field (a vector
cell like the atom's, or a defstruct with a setf accessor -- whichever the
record/deftype table row already supports). `set!` lowers to the field write
only inside a method body where the field name is bound; anywhere else, the
oracle's wording (`No field: ...` style -- verify) as a named refusal.
Immutability of non-^:mutable fields stays enforced (the oracle refuses
`(set! ...)` on them; pin that error too).

## Oracle

```bash
clj -M -e '
(defnode T [a] :allowed) ; replace with: (deftype C [^:mutable x y])
(deftype C [^:mutable x y]
  (bump! [] (set! x (inc x)) x))
(def c (C. 1 2)) (println (.bump! c)) (println (.-x c) (.-y c))'
```

Also pin: `set!` on an immutable field's error, `set!` outside a method, and
the `(.-x c)` read after mutation.

## Acceptance

`clojure-spec.yaml` on all four backends (pure runtime, no host), refusals in
`ClojureLoweringTest`; the corpus instant/mutable program lowers and its
mutation sequence matches the oracle.
