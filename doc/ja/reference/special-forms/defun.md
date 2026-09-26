# defun

`(defun name (params...) body...)`

指定したパラメータリストと本体を持つ `name` という名前の関数を関数名前空間に定義し、名前シンボルを返します。`body` は定義時には評価されず、呼び出しごとに実行され、本体の最後のフォームの値を返します。Lisp-2 に従い、定義は関数名前空間に存在するため、同名の変数と衝突することなく、呼び出し位置で(および `#'name` を通じて)その名前を参照できます。

```lisp
(defun sq (x) (* x x)) ; => SQ
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
(make-point :y 5) ; => (0 5 T)
```

未知のキーワード引数は、ラムダリストが `&allow-other-keys` を宣言しているか、呼び出し側が `:allow-other-keys t` を渡さない限りエラーを通知します。`&aux` は末尾の `let*` のように束縛される補助変数を導入します。`&whole` はサポートされません。

```lisp
(defun area (w &optional (h w) &aux (a (* w h)))
  a)
(area 3) ; => 9
```

必須引数より少ない引数で関数を呼び出すと(固定アリティ関数では多すぎる場合も)、インタプリタではエラーを通知し、JVM/WASM バックエンドではコンパイルエラーになります。

```console
CL-USER> (defun f (a b) (+ a b))
CL-USER> (f 1)
Function expects 2 arguments, got 1
```

コンパイル時に検査できるのは、関数を直接名指しする呼び出しだけです。関数*値*を介した呼び出し(`funcall`、`apply`、`mapcar`、`#'f` を保持する変数)は実行時に検査され、すべてのバックエンドで同じ本文を持つ捕捉可能な `program-error` を通知します。

```lisp
(defun f (a b) (+ a b))
(handler-case (apply #'f '(1)) (program-error (c) (princ-to-string c)))
; => "Function expects 2 arguments, got 1"
```

組み込みオペレータの関数値では、`Function` の代わりにオペレータ名が入ります。

```lisp
(handler-case (funcall #'cons 1) (program-error (c) (princ-to-string c)))
; => "CONS expects 2 arguments, got 1"
```

組み込みオペレータを、そのラムダリストが許さない個数の引数で直接呼び出しても、コンパイルエラーにはなりません。引数を評価したうえで、実行時に同じ `program-error` を通知します。これはすべてのバックエンドで共通で、JVM/WASM のコンパイラは警告を表示します。

```lisp
(handler-case (car '(1 2) 2) (program-error (c) (princ-to-string c)))
; => "CAR expects 1 argument, got 2"
```

ラムダリストが `&optional` パラメータで終わる関数(`&rest` も `&key` もない)が受け取れるのは、
必須と省略可能の個数の合計までです。余分な引数は、どのバックエンドでも、デフォルト式を
評価する前に実行時の捕捉可能な `program-error` を通知します。

```lisp
(defun g (a &optional b) (list a b))
(handler-case (g 1 2 3) (program-error (c) (princ-to-string c)))
; => "Function expects at most 2 arguments, got 3"
```

## setf 関数名

`name` にはプレーンなシンボルの代わりに `(setf name)` のリストを指定できます。これは *setf 関数* を定義します。すなわち、`name` を `setf` のプレースとして使ったときに呼び出される書き込み用の関数です。新しい値は最初の引数として渡されます(Common Lisp の慣習どおり、setf ラムダリストの最後の必須パラメータになります)。したがって `(setf (name arg...) value)` は書き込み関数を `value` に続いて `arg...` の順で呼び出します。`#'(setf name)` を通じてファーストクラス値としても扱えます。

```lisp
(defvar *mode* :xml)
(defun (setf my-mode) (m) (setq *mode* m))
(setf (my-mode) :html5)
*mode* ; => :HTML5
```

サポートされるのは `(setf name)` 形式(2 要素のリスト)のみです。`(setf ...)` 名に対する `symbol-function`/`fboundp` はサポートされません。
