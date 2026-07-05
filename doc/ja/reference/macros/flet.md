# flet

`(flet ((name lambda-list body...)...) body...)`

本体に対して局所関数を束縛します。Lisp-2 のため各名前は関数名前空間にのみ存在し、呼び出し位置（`(name args...)`）と `#'name` から参照されます。裸の `name` は変数参照のままです。定義同士（および自分自身）は互いに見えません -- 定義本体は同名の外側の関数を参照します（再帰には [`labels`](labels.md) を使ってください）。ラムダリストは `defun` と同じ拡張（`&optional`/`&rest`/`&key`/`&aux`）をサポートします。

`let` で束縛したラムダと本体の書き換えに展開されるため、局所関数は通常のクロージャです。周囲の変数を捕捉でき、`#'name` を通じて `mapcar`/`funcall`/`apply` に渡せます。

```lisp
(flet ((sq (x) (* x x))
       (dbl (x) (* 2 x)))
  (list (sq (dbl 3)) (mapcar #'sq '(1 2 3)))) ; => (36 (1 4 9))
```
