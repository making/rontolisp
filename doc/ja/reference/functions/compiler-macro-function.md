# compiler-macro-function

`(compiler-macro-function name &optional environment)`

[`define-compiler-macro`](../macros/define-compiler-macro.md) が `name` に登録したコンパイラマクロ、なければ `nil` を返します。`environment` 引数は受け付けますが無視します。

```lisp
(define-compiler-macro square (&whole form x)
  (if (numberp x) (* x x) form))
(defun square (x) (* x x))
(list (and (compiler-macro-function 'square) t)
      (compiler-macro-function 'car)) ; => (T NIL)
```

インタプリタでは値は本物の展開器で、`(funcall f form environment)` として呼び出せます。コンパイル済みプログラムはコンパイラマクロを展開し終えているため、そこでの値は [`macro-function`](macro-function.md) が返すのと同じスタブです。4 バックエンドすべてで述語としては正確ですが、呼び出すとシグナルします。

`(setf (compiler-macro-function name) function)` は `function` をコンパイラマクロとして登録し、`nil` は登録を取り除きます。以後 `compiler-macro-function` はすべてのバックエンドでその関数自身を返します。インタプリタはそれ以降に評価する呼び出しに適用します。コンパイル済みプログラムの呼び出しはコンパイル時に展開済みなので、そこで後続の呼び出しを書き換えるのは、クォートした名前に `lambda` か `#'` の値を与えるトップレベルの `setf` だけです(`define-compiler-macro` と同じ扱い)。

```lisp
(setf (compiler-macro-function 'twice)
      (lambda (form env) (declare (ignore env)) `(* 2 ,(second form))))
(defun twice (x) (+ x x))
(list (twice 21) (funcall (compiler-macro-function 'twice) '(twice 4) nil)) ; => (42 (* 2 4))
```
