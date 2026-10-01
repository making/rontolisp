# Clojure: exact `recur` to variadic clauses via worker split (shcloj4)

Difficulty: Medium (one shape change mirroring the multi-`defn` helper split;
four-backend parity by construction).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673)

```clojure
(defn vr [a & r] (if (empty? r) a (recur (first r) (rest r))))
(vr :start [1]) ; oracle: [1]
(vr :start nil) ; oracle: nil
```

rontolisp today (b17): `recur to a variadic function is not supported yet` (a
named refusal). The oracle binds the rest parameter to the last `recur` argument
itself, while a plain `&rest` self call would wrap it in a one-list (the second
probe would loop forever under wrapping).

## Design sketch

- Split a variadic clause whose recur target is used, like the multi-`defn`
  helpers: a worker (a `defun`/extra `labels` entry) taking the rest as an
  ordinary parameter -- the `recur` call then assigns exactly -- plus the
  `&rest` head for normal calls (wrapping, like the oracle). Single-arity
  `defn`/`fn`/`letfn` entries and multi-arity clauses with a used variadic
  target; an unused variadic keeps today's single shape.
- `#()` stays unchecked (its arity is the highest `%N`, known only after the
  body lowers) -- out of scope here.

## Acceptance

- `clojure-spec.yaml`: variadic `recur` to `defn` and named `fn` (the two
  probes above) green on all four backends; the `ClojureLoweringTest` variadic
  refusal replaced by the split shapes.
- `.kb/clojure-frontend.md` `recur` row updated (refusal line removed).

## Depends on

b17 (recur targets + the refusal). No dependency on b18-b26/b28.
