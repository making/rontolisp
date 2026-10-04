# defmacro

`(defmacro name doc? attr? [params] body...)` /
`(defmacro name doc? attr? ([params] body...)+)`

コンパイル時マクロを定義します。各呼び出し位置は lower 中に展開され、バックエンドが
動く前に済むため、すべてのバックエンドは展開後のコードを実行します。パラメータには
呼び出し側の引数フォームが未評価で束縛されます（`&` 以降はリストとして残り、
destructuring も可能です。複数のアリティは引数の個数で切り替わります（`defn` と同様）。
docstring と attr map は読み飛ばされます。`&form` と `&env` は拒否されます。マクロ本体
は引数のみで動作し、コンパイル環境を受け取りません。

定義は `nil` を返し、同じ expander を実行時テーブルにも登録するため、
`macroexpand-1` は実行時にも同じ関数で展開します。マクロ本体が見えるのは核の
built-in と `clojure.lisp` ライブラリであり、プログラム自身の定義は見えません。定義より
上での呼び出しはエラーとなり、マクロに関数値は存在しません。

核の名前（`inc` のような関数も `with-out-str` のようなフォームも）の `defmacro` は、
定義以降でその名前を覆い隠します。定義より上の呼び出し位置は oracle のフォーム単位の
コンパイルと同じく核の意味を保ち、`clojure.core/name` はプログラムが何を定義していても
核の var を指します。special form（`if`、`do`、`let*`、`new` など）とリーダーが綴る先頭
（`deref`、`with-meta`、`syntax-quote`、`ns`、`in-ns`）はマクロの名前にできません。

```clojure
(defmacro doc-unless [c t] (list 'if c nil t))
(println (doc-unless false 42)) ; 42
```

```clojure
(defmacro with-out-str [& body] `(str "<" (clojure.core/with-out-str ~@body) ">"))
(println (with-out-str (print 1))) ; <1>
```
