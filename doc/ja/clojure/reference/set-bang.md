# set!

`(set! field value)`

`^:unsynchronized-mutable` または `^:volatile-mutable` を付けた deftype フィールドに、
その型自身のインラインメソッド内で代入し、値を返します。メソッド内で作った
クロージャは作成時にフィールドをコピーするため、その中の `set!` は拒否されます。
それ以外の代入先はオラクル同様に拒否されます。ローカル・パラメータ・不変フィールドは
`Cannot assign to non-mutable: ...`、非 dynamic なグローバルは実行時に
`Can't change/establish root binding of: ... with set` をシグナルします。dynamic・
コアの var とホストフィールドはまだサポートしていません。

```clojure
(defprotocol Counter (bump! [c]) (total [c]))
(deftype Tally [^:unsynchronized-mutable n]
  Counter
  (bump! [_] (set! n (inc n)))
  (total [_] n))
(def t (Tally. 0))
(bump! t)
(println (bump! t) (total t))
```

```
2 2
```
