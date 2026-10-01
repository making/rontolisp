# select-keys

`(select-keys m keys)`

Answers a fresh map holding the present keys only. Of `nil`, the empty map;
anything else that is no map signals. As a value a two-argument lambda.

```clojure
(println (select-keys {:a 1 :b 2} [:a :c])) ; {:a 1}
```
