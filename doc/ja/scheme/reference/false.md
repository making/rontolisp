# false

`false`

`#f` を保持する変数です。リテラルではなく通常の変数なので、プログラムが束縛し直せます。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
false ; => #f
(if false 'yes 'no) ; => no
```
