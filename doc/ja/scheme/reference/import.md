# import

`(import import-set...)`

どのライブラリもエクスポートしないプログラム構文で、プログラムが使う束縛のライブラリを指定します。受け付けるライブラリは `(scheme base)`、`(scheme write)`、`(scheme read)`、`(scheme inexact)`、`(scheme cxr)`、`(scheme lazy)`、`(scheme process-context)`、`(scheme eval)`、`(scheme repl)` で、それぞれ `only`、`except`、`prefix`、`rename` で包めます。それ以外のライブラリは名前を挙げて拒否されます。ファイルではすべての `import` を他のどの形式よりも前に書く必要があり、そのファイルからはインポートしたものだけが見えます。`import` のないファイルからは 9 つのライブラリすべてと SICP 互換の名前、R5RS の名前が見えます。REPL ではいつでも `import` を入力でき、名前を追加するだけです。

```scheme
(import (scheme base) (scheme write))
(import (rename (only (scheme base) car) (car first)))
(display (first '(9 8)))
(newline)
```

```
9
```
