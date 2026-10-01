# assoc-in

`(assoc-in m keys v)`

Answers the nested association down the key vector, building missing levels
from empty. With no keys, associates under `nil`, like the oracle. As a value
the key sequence walked at run time.

```clojure
(println (assoc-in {} [:a :b] 1)) ; {:a {:b 1}}
```
