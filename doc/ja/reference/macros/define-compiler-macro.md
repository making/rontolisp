# define-compiler-macro

`(define-compiler-macro name lambda-list body...)`

[`declaim`](declaim.md) や [`deftype`](deftype.md) と同様に、パース済み no-op として受理され `nil` を返します。コンパイラマクロは最適化のヒントにすぎないため、除去しても挙動は変わりません。`name` の通常の関数定義がそのまま有効です（結果は同じで、手書きの最適化が効かないだけです）。`&whole` パラメータと本体は無視されます。

```lisp
(defun myinc (x) (+ x 1))
(define-compiler-macro myinc (x) `(+ ,x 100)) ; 無視される
(myinc 10) ; => 11
```
