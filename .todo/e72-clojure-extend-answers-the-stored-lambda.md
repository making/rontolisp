# e72. Clojure: `extend-protocol`/`extend-type`/`extend` answer the stored lambda

Difficulty: Low

The three answer `nil` in the oracle; here they answer the last row's lambda, the value of
the `setf` that stored it. Measured 2026-10-08 (clj 1.12.6):

```clojure
(defprotocol Q (q [x]))
(prn (extend-protocol Q Long (q [n] n))) ; oracle nil; here #<procedure>
```

A `clojure>` session echoes `#<procedure>` for each of the three forms.

## Plan

1. A failing clojure-spec line and a session transcript line first.
2. End the lowered rows in `nil` where the value is read (a session echo, a non-top-level
   form); measure whether a top-level form whose value is dropped can keep its bytes.
3. `doc/*/clojure/reference/extend*.md` if the answer is documented there.
