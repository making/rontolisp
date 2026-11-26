# EMPTY-NODE

`EMPTY-NODE`

The oracle's persistent-vector node, which is the empty vector here: `=`, `count`,
`seq`, printing and every verb read it as `[]`, and two references are the same object
(`identical?`). A call reads like one of `[]`: a key and an optional default through
`nth`, any other count refused at lower time (see [Deviations](../deviations.md)).

```clojure
(prn EMPTY-NODE (= EMPTY-NODE [])) ; [] true
(prn (EMPTY-NODE 0 :dflt)) ; :dflt
```
