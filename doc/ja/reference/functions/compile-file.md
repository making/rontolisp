# compile-file

`(compile-file input-file &key ...)`
`(compile-file-pathname input-file &key ...)`

これらに言及するプログラムがロードできるように存在し、実際に呼び出されるとエラーを
通知する 2 つの標準名です。

`compile-file` と `compile-file-pathname` には名前を付ける対象がありません。rontolisp に
ファイルコンパイラは無く、プログラムは 1 パスで**丸ごと**コンパイルされ、`load` された
ファイルはそこへ差し込まれます。したがって fasl は生成されず、それを指すパス名も存在
しません。`*compile-file-pathname*` と `*compile-file-truename*` が恒久的に nil なのも
同じ理由です。両者は、決して存在しない
ファイルのために捏造したパス名を返すのではなく通知します。コンパイルするにはコンパイラを
実行してください: `rontolisp prog.lisp -o Prog.class`、`-o prog.wasm`。

```console
$ rontolisp -e '(compile-file "x.lisp")'
Unhandled condition: compile-file is not supported (no file compiler: a program is compiled whole)
```

## バックエンドサポート

どちらも 4 つすべてのバックエンドで同一に振る舞います。
