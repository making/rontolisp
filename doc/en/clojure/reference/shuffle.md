# shuffle

`(shuffle coll)`

Fisher-Yates over a fresh vector of the realized members, drawn from the
program-owned generator. Membership and count pin, never order; `nil`, strings
and maps signal (none shuffle on the oracle either). As a value a one-argument
lambda.

```clojure
(println (shuffle [])) ; []
(println (shuffle [1])) ; [1]
```
