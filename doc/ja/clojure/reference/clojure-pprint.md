# clojure.pprint

プリティプリント、つまりデータを右マージンの内側に収まるようにレイアウトして出力する名前空間です。
レイアウトはディスパッチ関数を通して決まり、プログラムはこの関数を拡張することも置き換えることも
できます。`clojure.pprint` を require すると使えます。Clojure の同名の名前空間について文書化された
振る舞いをもとに rontolisp 向けに書いた Clojure ソースで、改行の判断は Clojure のプリティプリンタと
同じ判断をするレイアウトエンジンが行います。すべてのバックエンドで同じように動きます。

| var | 振る舞い |
|---|---|
| `pprint` | `(pprint x)`、`(pprint x writer)`: `x` を `*print-right-margin*` の内側にレイアウトして `*out*`（または `writer`）へ書き、最後に改行する |
| `pp` | `(pp)`: `*1` の `pprint` |
| `write` | `(write x & options)`: オプションに従って `x` を書く。`:stream`（ライター。既定の `true` は `*out*`、`nil` なら文字列を返す）、`:pretty`、`:right-margin`、`:miser-width`、`:dispatch`、`:length`、`:level`、`:readably`、`:suppress-namespaces`、`:base`、`:radix` |
| `print-table` | `(print-table rows)`、`(print-table ks rows)`: `rows` のマップを表として出力する。列は `ks` のキーごとに 1 つで右寄せ（`ks` を省くと最初の行のキー） |
| `simple-dispatch` | 既定のディスパッチ。`class` で振り分けるマルチメソッドで、プログラムは自分のレコードや型のメソッドを追加できる |
| `code-dispatch` | Clojure のコード用のディスパッチ。これも `class` で振り分けるマルチメソッドで、定義や制御のフォームをそれぞれ固有のレイアウトで出力する |
| `*print-pprint-dispatch*`、`with-pprint-dispatch`、`set-pprint-dispatch` | `pprint` が各値を渡す関数。本体の間だけ束縛するか、置き換える |
| `pprint-logical-block` | `(pprint-logical-block options* body)`: `body` を論理ブロック（レイアウトが改行するかどうかを判断する単位）として実行する。オプションは `:prefix`、`:per-line-prefix`、`:suffix`。`*print-level*` より深いと `#` を書く |
| `print-length-loop` | 本体を最大 `*print-length*` 回実行し、打ち切ったところで `...` を書く `loop` |
| `write-out` | `(write-out x)`: ブロックの中で `x` をディスパッチ経由で書く |
| `pprint-newline` | `(pprint-newline kind)`: 条件付き改行。`:linear`（ブロックが収まらないとき改行）、`:fill`（次の部分が行に収まらないとき改行）、`:miser`（miser スタイルのときだけ改行）、`:mandatory` |
| `pprint-indent` | `(pprint-indent relative-to n)`: ブロックの継続行のインデントを、ブロックの開始位置（`:block`）または現在の桁（`:current`）から `n` 桁にする |
| `fresh-line` | 行頭でなければ改行する |
| `*print-right-margin*`、`*print-miser-width*` | マージン（既定 72、`nil` で無制限）と、ブロックの開始位置がマージンにどこまで近づくと miser スタイルになるか（既定 40、`nil` で常に通常スタイル） |
| `*print-pretty*`、`*print-suppress-namespaces*`、`*print-base*`、`*print-radix*` | `write` の既定値。プリティプリントする、名前空間を残す、10 進、基数の印なし |
| `pprint-tab` | Clojure と同じく `UnsupportedOperationException` を投げる |
| `get-pretty-writer` | 受け取ったライターをそのまま返す |

行の残りに収まるコレクションはその行に出力します。収まらないコレクションは要素を 1 行に 1 つずつ置き、
マップのエントリもそれだけで収まらなければ、値をキーの下の行へ送ります。`*print-length*`、
`*print-level*`、`*print-meta*`、`*print-namespace-maps*` は `pr` と同じように効き、マップやセットの
要素は `pr` が出力する順に並びます。

```clojure
(require '[clojure.pprint :as pp])
(pp/pprint (sorted-map :id 7 :tags [:admin :dev] :scores (vec (range 0 60 3))))
(binding [pp/*print-right-margin* 20]
  (pp/pprint '(defn greet [name] (str "Hello, " name "!"))))
(pp/print-table [:lang :year] [{:lang "Clojure" :year 2007} {:lang "Common Lisp" :year 1984}])
```

```
{:id 7,
 :scores [0 3 6 9 12 15 18 21 24 27 30 33 36 39 42 45 48 51 54 57],
 :tags [:admin :dev]}
(defn
 greet
 [name]
 (str
  "Hello, "
  name
  "!"))

|       :lang | :year |
|-------------+-------|
|     Clojure |  2007 |
| Common Lisp |  1984 |
```

`write` に `:stream nil` を渡すと文字列が返り、`with-out-str` は `pprint` の出力を文字列として捕らえます。

```clojure
(require '[clojure.pprint :as pp])
(pp/write (range 5) :stream nil) ; => "(0 1 2 3 4)"
(with-out-str (pp/pprint [1 2])) ; => "[1 2]\n"
```

`simple-dispatch` にメソッドを追加すると、プログラム自身の型の出力を決められます。

```clojure
(require '[clojure.pprint :as pp])
(defrecord Money [amount currency])
(defmethod pp/simple-dispatch Money [m]
  (print (str "<" (:amount m) " " (:currency m) ">")))
(pp/pprint {:price (->Money 120 "JPY")})
```

```
{:price <120 JPY>}
```

独自のディスパッチ関数は、論理ブロックと条件付き改行で値をレイアウトします。

```clojure
(require '[clojure.pprint :as pp])
(defn angle-dispatch [x]
  (if (sequential? x)
    (pp/pprint-logical-block :prefix "<" :suffix ">"
      (pp/print-length-loop [s (seq x)]
        (when s
          (pp/write-out (first s))
          (when (next s)
            (print " ")
            (pp/pprint-newline :fill)
            (recur (next s))))))
    (pr x)))
(binding [pp/*print-right-margin* 24 pp/*print-miser-width* nil]
  (pp/with-pprint-dispatch angle-dispatch
    (pp/pprint (range 20))))
```

```
<0 1 2 3 4 5 6 7 8 9 10
 11 12 13 14 15 16 17
 18 19>
```

`code-dispatch` は Clojure のプリティプリンタと同じようにコードをレイアウトします。`defn`、`let`、
`if`、`cond`、`condp`、`->`、`ns` などはそれぞれ固有のレイアウトで、無名関数のフォームは `#(...)`
リテラルとして出力します。

```clojure
(require '[clojure.pprint :as pp])
(pp/with-pprint-dispatch pp/code-dispatch
  (pp/pprint '(defn greet "Greets a person." [person]
                (let [n (:name person)]
                  (when (seq n) (println "Hello," n "- glad to see you again"))))))
(pp/with-pprint-dispatch pp/code-dispatch
  (pp/pprint '(fn* [p1 p2] (+ p1 (* p2 p2)))))
```

```
(defn greet
  "Greets a person."
  [person]
  (let [n (:name person)]
    (when (seq n) (println "Hello," n "- glad to see you again"))))
#(+ %1 (* %2 %2))
```

## 違い

- `cl-format`、`formatter`、`formatter-out` は組み込まれておらず、名前を使うとそのことを告げる
  エラーになります。
- `get-pretty-writer` は受け取ったライターをそのまま返します。`pprint` や `write` は呼び出しごとに
  出力を 0 桁目からレイアウトし、マージンには実行時に束縛されている値を使います。Clojure の
  プリティライターは、作られたときのマージンを保ち、それまでにそのライターへ書かれた内容の続きの桁から
  レイアウトします。
