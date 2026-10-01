# unchecked-add

`(unchecked-add a b)`

Answers the sum without the overflow check. Integers here are bignums, so no
wrapping ever happens -- where the oracle wraps past `Long/MAX_VALUE`, this
keeps counting. As a value a two-argument lambda.

```clojure
(println (unchecked-add 3 4)) ; 7
```
