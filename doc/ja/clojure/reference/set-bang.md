# set!

`(set! field value)`

`^:unsynchronized-mutable` または `^:volatile-mutable` を付けた deftype フィールドに、
その型自身のインラインメソッド内で代入し、値を返します。メソッド内で作った
クロージャは作成時にフィールドをコピーするため、その中の `set!` は拒否されます。

外側の `binding` で束縛された `^:dynamic` な var へも同様に代入できます。書き込みは
スレッドローカルな値に設定され、その値を返します。束縛内で呼ばれる関数からでも
同様です。いかなる束縛の外側では、値の評価後に実行時エラー
`Can't change/establish root binding of: ... with set` をシグナルします。これは非
dynamic グローバルの `set!` と同じエラーです。`clojure.core` の特殊変数（`*out*`、
`*err*`、`*print-dup*` など）も同様に代入しますが、`clojure.main` がスクリプトの周りで
束縛するフラグ（`*warn-on-reflection*`、`*unchecked-math*`、`*print-length*`、
`*print-level*`、`*assert*` など）は常に束縛されているため、オラクル同様どこでも
`set!` で代入できます。`*ns*` は値をそのまま返し、効果はありません。

それ以外の代入先はオラクル同様に拒否されます。ローカル・パラメータ・不変フィールドは
`Cannot assign to non-mutable: ...`、ホストフィールドはまだサポートしていません
（`java:` 表面にフィールド書き込みがありません）。

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

```clojure
(def ^:dynamic *volume* 1)
(println (binding [*volume* 5] (set! *volume* 2)))
(println *volume*)
(println (set! *warn-on-reflection* true))
```

```
2
1
true
```
