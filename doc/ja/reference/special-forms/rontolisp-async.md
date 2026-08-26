# rontolisp:async

`(rontolisp:async (defun name (params...) body...))` /
`(rontolisp:async (lambda (params...) body...))`

通常の定義フォームをラップして非同期版に変えるマクロで、JavaScriptの `async function` / `async (...) =>` に近い記法を提供します。[`defun`](defun.md) をラップすると [`rontolisp:async-defun`](rontolisp-async-defun.md) と完全に同じ、[`lambda`](lambda.md) をラップすると [`rontolisp:async-lambda`](rontolisp-async-lambda.md) と完全に同じになります — ラッパーは純粋な書き換えなので、セマンティクス (eager start、future、[`rontolisp:await`](rontolisp-await.md) の配置ルール) とバックエンドのサポートは正規フォームのものがそのまま適用されます。

```lisp
(rontolisp:async (defun add-later (a b)
  (+ a b)))
(rontolisp:await (add-later 20 22))   ; => 42
```

```lisp
(rontolisp:await (funcall (rontolisp:async (lambda (x) (* x 2))) 21))   ; => 42
```

ラッパーの中身が単一の `defun` / `lambda` フォーム以外の場合はエラーになります:

```console
CL-USER> (rontolisp:async (+ 1 2))
Error: rontolisp:async expects a single (defun ...) or (lambda ...) form to make asynchronous, got: (rontolisp:async (+ 1 2))
```
