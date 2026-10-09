# clojure.repl

var のドキュメントとスロー可能オブジェクト（throwable）のスタックトレースを出力する名前空間です。
`clojure.repl` を require すると使えます。Clojure の同名の名前空間について文書化された振る舞いを
もとに rontolisp 向けに書いた Clojure ソースで、すべてのバックエンドで同じように動きます。

| var | 振る舞い |
|---|---|
| `doc` | `(doc name)`: var の名前、引数リスト、マクロなら `Macro`、docstring を、定義に書かれたとおりに出力する。特殊形式なら名前、`Special Form`、clojure.org のページ |
| `pst` | `(pst)`、`(pst e-or-depth)`、`(pst e depth)`: throwable の単純クラス名、メッセージ、`ex-data` を `*err*` に出力し、続けて `Caused by:` のあとに各原因を出力する。throwable を渡さなければ `*e` の根本原因 |
| `root-cause` | `(root-cause t)`: 原因をたどった最も内側の原因。原因がなければ `t` 自身 |
| `demunge` | `(demunge s)`: スタックトレース要素が示す関数のクラス名を Clojure の綴りに戻したもの |
| `stack-element-str` | `(stack-element-str el)`: ホストの `StackTraceElement` を文字列にしたもの。Clojure の関数はデマングルした名前（インタープリターと JVM） |

```clojure
(require '[clojure.repl :refer [doc]])
(defn add-one "Adds one." [x] (inc x))
(doc add-one)
```

```
-------------------------
user/add-one
([x])
  Adds one.
```

```clojure
(require '[clojure.repl :as r])
(r/demunge "my_app.core$valid_QMARK_") ; => "my-app.core/valid?"
```

## 組み込まれていないもの

次の var は、プログラムを変換する時点で名前を挙げて拒否します。

- `dir`、`dir-fn`、`apropos`、`find-doc`: 名前空間の var はプログラムを変換している間にしか
  わからない。
- `source`、`source-fn`: 定義のテキストは実行時に残らない。
- `set-break-handler!`、`thread-stopper`: シグナルハンドラーはなく、JDK はもう
  `Thread.stop` をサポートしない。

## 違い

- 特殊形式の `doc` は名前、`Special Form`、clojure.org へのリンクを出力し、Clojure の構文と説明文は
  出力しません。`clojure.core` の var のメタデータは `:name` と `:ns` だけなので、`doc` は名前だけを
  出力します。
- var でない名前（名前空間を含む）の `doc` は変換時の `Unable to resolve var` です。Clojure は
  名前空間の docstring を出力するか、何も出力しません。キーワード（spec）の `doc` は拒否します。
- ここでの throwable はスタックフレームを持たないため、`pst` はフレームを出力しません。
