# dynamic-wind

`(dynamic-wind before thunk after)`

引数なしで `before`、`thunk`、`after` の順に呼び出し、`thunk` の値を返します。`after` は `thunk` をどのように抜けても実行されます（通常の終了、脱出する継続、プログラムを巻き戻す `error`、`exit`）。継続は再突入できないため、`before` はちょうど 1 回だけ実行されます。

```scheme
(dynamic-wind (lambda () (display "before ")) (lambda () (display "during ")) (lambda () (display "after")))
(newline)
```

```
before during after
```
