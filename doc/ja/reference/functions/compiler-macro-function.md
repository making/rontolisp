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

インタプリタでは値は本物の展開器で、`(funcall f form environment)` として呼び出せます。コンパイル済みプログラムはコンパイラマクロを展開し終えているため、そこでの値は [`macro-function`](macro-function.md) が返すのと同じスタブです。4 バックエンドすべてで述語としては正確ですが、呼び出すとシグナルします。`setf` には対応していません。
