# Clojure: `lazy-seq` body as a zero-arity `recur` target (shcloj4)

Difficulty: Easy (one checked target around an existing body lowering + the
anonymous-`fn` labels-iff-used precedent; no new runtime).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673)

```clojure
((fn [n] (lazy-seq (if (zero? n) nil (recur (dec n))))) 3))
; oracle: Mismatched argument count to recur, expected: 0 args, got: 1
```

i.e. the `lazy-seq` thunk IS a recur target, of arity 0 (`(lazy-seq (recur))`
recurs the thunk itself).

rontolisp today (b17): the `lazy-seq` body lowering is transparent, so the
`recur` targets the outer `fn` with its arity instead of the thunk with arity
0.

## Design sketch

- Push a zero-arity checked target around the `lazy-seq` body lowering; when
  used, wrap the thunk lambda in a `labels` self-binding under a fresh name
  (the b17 anonymous-`fn` shape), else keep today's bare lambda.
- `b26` (tail enforcement) later refuses the non-tail uses; the target itself
  is still needed for the check to name arity 0.

## Acceptance

- `ClojureLoweringTest` pins the arity-0 target (shape + wrong-count refusal
  naming 0); a `clojure-spec.yaml` case with a terminating zero-arg
  `lazy-seq` recur green on all four backends.
- `.kb/clojure-frontend.md` `lazy-seq` row updated.

## Depends on

b17 (recur targets) + b11 (`lazy-seq`). No dependency on b18-b26/b27.
