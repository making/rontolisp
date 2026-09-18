# letrec*

`(letrec* ((variable init)...) body...)`

`letrec` と同様ですが、`init` を左から右へ評価するため、`init` はそれより前に束縛された変数の値を使えます。本体の内部定義は `letrec*` と同じように振る舞います。

```scheme
(letrec* ((a 1) (b (+ a 1))) (list a b)) ; => (1 2)
(let ((x 10)) (letrec* ((a x) (b (* a 2))) b)) ; => 20
```
