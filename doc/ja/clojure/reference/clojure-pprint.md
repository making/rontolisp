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
| `fresh-line` | 進行中のプリティプリントが行頭でなければ改行する。プリティプリントの外では常に改行する |
| `*print-right-margin*`、`*print-miser-width*` | マージン（既定 72、`nil` で無制限）と、ブロックの開始位置がマージンにどこまで近づくと miser スタイルになるか（既定 40、`nil` で常に通常スタイル） |
| `*print-pretty*`、`*print-suppress-namespaces*`、`*print-base*`、`*print-radix*` | `write` の既定値。プリティプリントする、名前空間を残す、10 進、基数の印なし |
| `pprint-tab` | Clojure と同じく `UnsupportedOperationException` を投げる |
| `get-pretty-writer` | 受け取ったライターをそのまま返す |
| `cl-format` | `(cl-format writer control & args)`: `control` に書いた Common Lisp の format 指示子で `args` を整形する。`writer` が `nil` なら文字列を返し、`true` なら `*out*` へ、それ以外なら `writer` へ書く。[cl-format](#cl-format) を参照 |
| `formatter`、`formatter-out` | `(formatter control)`: ライターと引数を受け取って `cl-format` と同じように整形する関数。制御文字列のコンパイルは一度だけ行う。`(formatter-out control)`: 引数を受け取ってその時点の `*out*` へ書く関数で、ディスパッチ関数の中で使う |

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

## cl-format

`cl-format` は Clojure の値を対象にした Common Lisp の `format` です。`~A` と `~S` は `print` と
`pr` と同じように書き、`~{` は任意のコレクションやシーケンスを走査し、`~:[` は Clojure の真偽で
判定します。そのため `false` は `nil` と同じく最初の節を選びます。

| 指示子 | 書くもの |
|---|---|
| `~A`、`~S` | 引数を `print` / `pr` と同じように書く。整数と比は `*print-base*` と `*print-radix*` に従う。`~mincol,colinc,minpad,padcharA` で幅を埋め、`@` を付けると左側を埋める |
| `~D`、`~B`、`~O`、`~X`、`~radixR` | 整数を 10、2、8、16 進または `radix` 進で書く。`~mincol,padchar,commachar,intervalD` の形で指定し、`:` で桁を区切り、`@` で正の数にも符号を付ける |
| `~R`、`~:R`、`~@R`、`~:@R` | 数を英語の基数詞、英語の序数詞、ローマ数字、古い形式のローマ数字で書く |
| `~P`、`~@P` | 引数が 1 でなければ `s`（`ies`）を書く。`:` を付けると一つ前の引数を見る |
| `~C`、`~:C`、`~@C` | 文字そのもの、文字の名前、`pr` と同じ表記 |
| `~F`、`~E`、`~G`、`~$` | 数を固定小数点、指数、一般、金額の各表記で書く。パラメータは Common Lisp と同じ |
| `~%`、`~&`、`~\|`、`~~`、`~T` | 改行、行頭でなければ改行、改ページ、チルダ、指定の桁までの空白 |
| `~*`、`~:*`、`~@*` | 引数を飛ばす、前に戻る、指定の位置へ移る |
| `~[...~;...~]`、`~:[`、`~@[` | 引数で節を選ぶ。添字で選ぶ、真偽で選ぶ、真のときだけ実行する |
| `~{...~}`、`~:{`、`~@{`、`~:@{` | 本体を繰り返す。コレクションの要素ごと、その部分リストごと、残りの引数ごと、残りの引数を部分リストとして |
| `~^`、`~:^` | 引数（`~:^` は部分リスト）が残っていなければ、囲んでいる実行を終える |
| `~(...~)`、`~:(`、`~@(`、`~:@(` | 本体を小文字にする、各単語の先頭を大文字にする、最初の単語の先頭を大文字にする、大文字にする |
| `~?`、`~@?` | 別の制御文字列を、リスト引数に対して、または残りの引数に対して実行する |
| `~<...~>` | 節を幅の中に揃えて配置する |
| `~W`、`~<...~:>`、`~_`、`~I` | 引数をプリティプリントのディスパッチで書く、論理ブロック、条件付き改行（`:` は fill、`@` は miser、`:@` は mandatory）、インデント |

```clojure
(require '[clojure.pprint :as pp])
(pp/cl-format true "There ~[are~;is~:;are~]~:* ~d result~:p: ~{~d~^, ~}~%" 3 [46 38 22])
(println (pp/cl-format nil "~:d ~r ~:r ~@r" 1234567 42 3 1994))
(println (pp/cl-format nil "~,2f|~10,3e|~$|~8,'0x" 3.14159 12345.678 1.5 255))
(println (pp/cl-format nil "~:(~a~) ~@(~a~) ~:@(~a~)" "hello world" "hELLO" "loud"))
(println (pp/cl-format nil "~{~a~^, ~}|~:{<~a ~a>~}|~20<left~;right~>|" [1 2 3] [[1 2] [3 4]]))
(println (pp/cl-format nil "~a~12t~a~24t~a" "name" "lang" "year"))
```

```
There are 3 results: 46, 38, 22
1,234,567 forty-two third MCMXCIV
3.14|  1.235E+4|1.50|000000ff
Hello World Hello LOUD
1, 2, 3|<1 2><3 4>|left           right|
name        lang        year
```

プリティプリント用の指示子は、進行中のプリティプリントに出力を加えます。そのため、ディスパッチ関数は
`formatter-out` で値をレイアウトできます。プリティプリントの外では、プリティライターを必要とする
制御文字列（`~W`、`~<`、`~T`、`~&` を含むもの）が専用のプリティライターを使い、0 桁目から出力します。

```clojure
(require '[clojure.pprint :as pp])
(defn json-dispatch [x]
  (cond (map? x) ((pp/formatter-out "~<{~;~@{~<~w:~_~w~:>~^, ~_~}~;}~:>")
                  (for [[k v] x] [(name k) v]))
        (sequential? x) ((pp/formatter-out "~<[~;~@{~w~^, ~:_~}~;]~:>") x)
        (string? x) (pr x)
        (nil? x) (print "null")
        :else (print x)))
(binding [pp/*print-right-margin* 50 pp/*print-miser-width* nil]
  (pp/with-pprint-dispatch json-dispatch
    (pp/pprint (sorted-map :name "rontolisp" :tags ["lisp" "clojure" "wasm"] :scores (vec (range 0 60 4))))))
```

```
{"name":"rontolisp",
 "scores":
 [0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44,
  48, 52, 56],
 "tags":["lisp", "clojure", "wasm"]}
```

書式の誤った制御文字列は `RuntimeException` になり、そのメッセージには制御文字列と、誤りの位置を
指す `^` が入ります。

## 違い

- 10 進小数のリテラルはここでは比なので、`1.5M` の `~A` は `3/2` を書きます。また整数が long として
  オーバーフローすることはなく、`-9223372036854775808` の `~D` は、Clojure が例外を投げるところで
  値をそのまま書きます。
- Clojure の拒否メッセージが JVM 自身のもの（`*out*` がプリティライターでないときのプリティプリント用
  指示子の `ClassCastException` や、`NullPointerException`）である場合、クラスは同じでメッセージが
  異なります。
- `get-pretty-writer` は受け取ったライターをそのまま返します。`pprint` や `write` は呼び出しごとに
  出力を 0 桁目からレイアウトし、マージンには実行時に束縛されている値を使います。Clojure の
  プリティライターは、作られたときのマージンを保ち、それまでにそのライターへ書かれた内容の続きの桁から
  レイアウトします。
