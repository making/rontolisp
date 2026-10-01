# seq

`(seq coll)`

`coll` の seq ビューを返します。lazy ラッパーは1レベルだけ realize します（それ以上は辿らないため、無限 seq は無限のままです）。リストはそのまま渡され、ベクタ・文字列は要素ごとに強制され、マップはエントリごとの2要素ベクタ、セットは要素ごとのメンバーを出します（どちらもテーブルの walk 順で、順序不定です）。`nil` と `false` は空です。それ以外はオラクルと同様にシグナルします。

各 seq 動詞の空の結果は `nil` です。chunk 化はありません。lazy seq は1要素ずつ realize します。

```clojure
(println (seq '(1 2)))       ; (1 2)
(println (seq [1 2]))        ; (1 2)
(println (seq nil))          ; nil
(println (first {:a 1}))     ; [:a 1]
(println (count (seq "ab"))) ; 2
```
