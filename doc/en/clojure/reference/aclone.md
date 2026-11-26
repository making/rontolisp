# aclone

`(aclone array)`

Answers a fresh array of the elements of `array`, which changes apart from it; a byte array's
copy is a byte array. As a value a one-argument function.

```clojure
(def ac-a (byte-array [1 2]))
(def ac-b (aclone ac-a))
(aset ac-b 0 (byte 9))
(println (vec ac-a) (vec ac-b)) ; [1 2] [9 2]
```
