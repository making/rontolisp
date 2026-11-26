# gensym

`(gensym)` / `(gensym prefix)`

新しい uninterned シンボル（`#:` 綴り）を返します。評価のたびに別物になります。
マクロ本体の中では展開ごとに新しくなり（syntax-quote の `x#` が束縛するもの）、
実行時には呼び出しごとに新しくなります。文字列は prefix、整数 suffix はそのままの
綴りになります（`(gensym 5)` は `#:G5`）。関数値としても使えます。

```clojure
(println (= (gensym "g") (gensym "g"))) ; false
```
