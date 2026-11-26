# areduce

`(areduce array index result init expr)`

Reduces `expr` across `array` by index: evaluates the array once, then walks the index
from zero with the accumulator `result` set to the value `expr` answers at each step
from `init`, and answers the accumulator. `index` names the loop's index and stays
local to the form.

```clojure
(def ar-a (int-array [1 2 3]))
(println (areduce ar-a i r 0 (+ r (aget ar-a i)))) ; 6
(println (areduce ar-a i r [] (conj r (aget ar-a i)))) ; [1 2 3]
```
