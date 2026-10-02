# take-nth

`(take-nth n coll)` / `(take-nth n)`

`coll` の seq の先頭から `n` 個ごとの要素を返します。lazy な入力には lazy seq を、strict な
入力には strict なリストを返します。`(take-nth n)` は[トランスデューサー](transducers.md)です。
値としては1引数か2引数を取ります。

仕様との差異: `n` が 0 だとシグナルし、seq 形は負の `n` を絶対値で進みます。オラクルの seq 形は
先頭要素を無限に繰り返します。

```clojure
(println (take-nth 2 [1 2 3 4 5])) ; (1 3 5)
(println (into [] (take-nth 3) (range 10))) ; [0 3 6 9]
```
