# aclone

`(aclone array)`

`array` の要素からなる新しい配列を返します。返した配列を書き換えても元の配列は変わりません。
バイト配列の写しはバイト配列です。値としては 1 引数の関数です。

```clojure
(def ac-a (byte-array [1 2]))
(def ac-b (aclone ac-a))
(aset ac-b 0 (byte 9))
(println (vec ac-a) (vec ac-b)) ; [1 2] [9 2]
```
