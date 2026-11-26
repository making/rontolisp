# ccase

`(ccase key (k1 body...) ...)`

`ecase` と同様に、`ccase` は `eql` を使って `key` でディスパッチし、どの節にも一致しない場合に `error` をシグナルします。完全な Common Lisp では `ccase` は *修正可能 (correctable)* で、新しい値を供給するためのリスタートを提供しますが、rontolisp の `ccase` は `store-value` リスタートを確立しないため `ecase` とまったく同じように動作し、主にソースの互換性のために提供されています。

```lisp
(let ((x 1)) (ccase x (1 'one) (2 'two))) ; => ONE
```
