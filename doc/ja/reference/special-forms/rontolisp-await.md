# rontolisp:await

`(rontolisp:await value)`

future を与えると、future が確定するまで現在の非同期関数をサスペンドし、確定値を返します。確定済みの future はサスペンドせず、ネストした future はフラット化され、future でない値はそのまま通過します — JavaScript でプロミス以外を `await` した場合と同じで、「future かもしれない値」に一律に `await` を適用できます。

```lisp
(rontolisp:await 42)   ; => 42
```

```lisp
(rontolisp:async-defun inner () 10)
(rontolisp:async-defun outer () (+ (rontolisp:await (inner)) 1))
(rontolisp:await (outer))   ; => 11
```

`await` は特殊形式で、[`rontolisp:async-defun`](rontolisp-async-defun.md) / [`rontolisp:async-lambda`](rontolisp-async-lambda.md) の本体内とトップレベル (トップレベルは暗黙に非同期です) でのみ使えます。それ以外の場所 — 普通の `defun` や `lambda` の本体、たとえ非同期本体の内側にネストしていても — ではコンパイル/定義時のエラーになります:

```console
> (defun bad () (rontolisp:await 1))
rontolisp:await is only allowed inside rontolisp:async-defun/async-lambda or at top level
```

## エラー

エラーで確定した future は、そのコンディションを `await` の時点で再シグナルします — await を囲む `handler-case` で捕捉できます:

```lisp
(rontolisp:async-defun failing () (error "boom"))
(handler-case (rontolisp:await (failing))
  (error (e) "caught"))   ; => "caught"
```
