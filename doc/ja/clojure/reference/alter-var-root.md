# alter-var-root

`(alter-var-root v f & args)`

var `v` のルートを `(apply f root args)` に置き換え、新しいルートを返します。
`alter-var-root` が `#'f` で名前を挙げた `defn` は var 経由で呼び出されるので、`with-redefs`
の対象と同じく、すべての呼び出し元が新しいルートを見ます。計算で得た var（ローカル、値としての
`alter-var-root`）を変更するプログラムでは、`#'` で var を取り出したすべての `defn` が var 経由で
呼び出されます。

`clojure.core` の var、マルチメソッド、プロトコルメソッド、レコードのコンストラクタ、テストは
置き換えるルートを持たないので、名前を挙げて拒否します。以前の REPL 入力が `^:redef` なしで
定義した `defn` も拒否します。var でない値は `ClassCastException`、`nil` は
`NullPointerException` です。

仕様との差異: ルートは var の値セルなので、`^:dynamic` な var の `binding` の内側では、
その var の `alter-var-root` はルートではなく束縛を変更します。

```clojure
(def counter 1)
(println (alter-var-root #'counter + 10) counter) ; 11 11
(defn greet [] "hello")
(defn welcome [] (greet))
(alter-var-root #'greet (fn [old] (fn [] (str (old) "!"))))
(println (welcome)) ; hello!
```
