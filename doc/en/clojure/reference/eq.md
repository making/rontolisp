# =

`(= x...)`

Compares the neighbours of the chain: `true` when every adjacent pair is equal, one
argument alone always `true`. Maps compare entry-wise and sets member-wise, both deeply
and structurally; vectors, lists and lazy seqs compare element-wise, across kinds. The comparison is `equal`-shaped, not truthiness: `(= false nil)` is
`false`, the two objects being distinct. Works as a function value of the same arity.

```clojure
(println (= 1 1 1)) ; true
(println (= 1 1 2)) ; false
(println (= {:a [1 2]} {:a [1 2]})) ; true
(println (= [1 2] '(1 2))) ; true
(println (= false nil)) ; false
```
