# Clojure: worker split for variadic `recur` in stored method lambdas (b27 follow-up)

Difficulty: Medium (same labels-split shape b27 used for anonymous `fn`;
four-backend parity by construction).

## Gap (found implementing b27, oracle `clj` 1.12.6.1673)

A `recur` to a variadic `defmethod` body or an inline protocol-method
implementation keeps b27's named refusal
(`recur to a variadic function is not supported yet`), where the oracle
binds the rest parameter to the last `recur` argument itself:

```clojure
(defmulti m class)
(defmethod m String [s & r] (if (empty? r) s (recur (first r) (rest r))))
```

## Design sketch

- Apply the same worker split b27 used for anonymous `fn` to stored
  method lambdas: a worker taking the rest as an ordinary parameter
  (the `recur` call assigns exactly) plus the `&rest` head for normal
  calls.
- Verify every shape against the host oracle before and after.

## Acceptance

- `clojure-spec.yaml`: variadic `recur` in `defmethod` (+ inline
  protocol-method impl if it shares the path) green on all four
  backends.
- `.kb/clojure-frontend.md` `recur` row updated; doc pages en+ja if the
  row has user-facing surface.

## Depends on

b27 (worker split). No dependency on b28/b30-b35.
