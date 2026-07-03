# defun

`(defun name (params...) body...)`

指定したパラメータリストと本体を持つ `name` という名前の関数を関数名前空間に定義し、名前シンボルを返します。`body` は定義時には評価されず、呼び出しごとに実行され、本体の最後のフォームの値を返します。Lisp-2 に従い、定義は関数名前空間に存在するため、同名の変数と衝突することなく、呼び出し位置で(および `#'name` を通じて)その名前を参照できます。

```lisp
(defun sq (x) (* x x)) ; => sq
```

```lisp
(defun sq (x) (* x x))
(sq 6) ; => 36
```

## ラムダリストキーワード

パラメータリストは Common Lisp のラムダリストキーワード `&optional`、`&rest`、`&key`、`&allow-other-keys`、`&aux`(この順序)をサポートします。デフォルトフォームは引数が省略されたときのみ評価され、左側で束縛済みのパラメータを参照できます。オプショナル/キーワードパラメータには supplied-p 変数を宣言でき、呼び出し側が引数を渡した場合に `t` になります。

```lisp
(defun greet (name &optional (greeting "Hello"))
  (concatenate 'string greeting ", " name))
(greet "world" "Hi") ; => "Hi, world"
```

```lisp
(defun sum (&rest xs)
  (reduce #'+ xs :initial-value 0))
(sum 1 2 3 4) ; => 10
```

```lisp
(defun make-point (&key (x 0) (y 0 y-supplied-p))
  (list x y y-supplied-p))
(make-point :y 5) ; => (0 5 t)
```

未知のキーワード引数は、ラムダリストが `&allow-other-keys` を宣言しているか、呼び出し側が `:allow-other-keys t` を渡さない限りエラーを通知します。`&aux` は末尾の `let*` のように束縛される補助変数を導入します。`&whole` はサポートされません。

```lisp
(defun area (w &optional (h w) &aux (a (* w h)))
  a)
(area 3) ; => 9
```

必須引数より少ない引数で関数を呼び出すと(固定アリティ関数では多すぎる場合も)、インタプリタではエラーを通知し、JVM/WASM バックエンドではコンパイルエラーになります。

```console
> (defun f (a b) (+ a b))
> (f 1)
Function expects 2 arguments, got 1
```
