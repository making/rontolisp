# =

`(= x...)`

Compares the neighbours of the chain: `true` when every adjacent pair is equal, one
argument alone always `true`. Maps compare entry-wise, sets member-wise, and sequentials (vectors, lists, lazy seqs)
element-wise across kinds, all deeply and structurally; a string is no sequential, and
`nil` equals an empty sequential (it is the empty list here, where the oracle answers
`false` for `(= [] nil)`). The comparison is `equal`-shaped, not truthiness: `(= false nil)` is
`false`, the two objects being distinct. A Java object on the left is asked its own `equals`,
as in the oracle, so `(= o 1)` hands it the number while `(= 1 o)` is `false`; `false`, a
keyword, a symbol or a collection is never handed to `equals` (the oracle hands it), and a Java collection is
not `=` to a Clojure one (the oracle compares them element-wise). Works as a function value of
the same arity.

```clojure
(println (= 1 1 1)) ; true
(println (= 1 1 2)) ; false
(println (= {:a [1 2]} {:a [1 2]})) ; true
(println (= [1 2] '(1 2))) ; true
(println (= false nil)) ; false
```
