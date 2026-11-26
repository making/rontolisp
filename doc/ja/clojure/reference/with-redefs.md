# with-redefs

`(with-redefs [var value ...] body...)`

すべての値を順に評価し、本体の間だけ各 var のルートを置き換え、本体を抜けるときに（例外を投げたときも）元のルートに戻します。置き換えたルートはすべての呼び出し元に届きます。プログラムのどこかの `with-redefs` が名前を挙げた `defn` と、`^:redef` を付けた `defn` は、直接呼び出しではなく var 経由で呼び出されます。同じ var を二度束縛したときは後の束縛が勝ちます。本体の前に取り出した関数値は元の関数のままです。

再定義できるのは `def`、`defn`、`declare` が作った var だけです。ローカルと未知の名前は `Unable to resolve var` として拒否します。`clojure.core` の var、マクロ、マルチメソッド、プロトコルメソッド、レコードのコンストラクタ、テストは名前を挙げて拒否します。REPL では、以前の入力が `^:redef` なしで定義した `defn` を拒否します。その呼び出しはすでに直接呼び出しとして変換されているからです。

仕様との差異: ルートは var の値セルなので、`^:dynamic` な var の `binding` の内側では、その var の `with-redefs` はルートではなく束縛を変更します。

```clojure
(defn fetch [id] (str "real-" id))
(defn report [id] (str "report of " (fetch id)))
(println (with-redefs [fetch (fn [id] (str "stub-" id))] (report 7))) ; report of stub-7
(println (report 7))                                                    ; report of real-7
```
