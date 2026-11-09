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
`set!` で代入できます。`*ns*`、`*file*`、`*1` も同様です。`*repl*` が束縛されているのは
REPL の中だけです。

それ以外の代入先はオラクル同様に拒否されます。ローカル・パラメータ・不変フィールドは
`Cannot assign to non-mutable: ...` です。

フィールド形式（`(.f x)`、`(.-f x)`、`(. x f)`）は、対象、値の順に評価した後、実行時に
決まります。fn の `__methodImplCache` は値を受け取ってその値を返し、それ以外の Clojure の
値はそのフィールドを持ちません（`No matching field found: f for class ...`。`nil` は
`NullPointerException`）。ホストオブジェクトの public フィールドには書き込み、値を返します。
値はオラクルのリフレクタと同じく変換し、数値はプリミティブ数値型のフィールドへ縮小変換、
受け取れないその他の値は `IllegalArgumentException` です。static フィールド
（`(. Class f)` や `Class/f`）にも同様に書き込みます。WASM にはホストオブジェクトがなく、
static フィールドへの書き込みは `UnsupportedOperationException` です。

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
