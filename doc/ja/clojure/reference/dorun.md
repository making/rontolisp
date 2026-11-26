# dorun

`(dorun coll)` / `(dorun n coll)`

効果のためにコレクションを realize し `nil` を答えます。lazy seq は最後まで辿られ、
strict なコレクションは既に realize されています。カウントを付けるとオラクル同様、
`n + 1` 個の要素が realize された時点で止まります。関数値にもなります。

```clojure
(println (dorun [1 2 3])) ; nil
```
