# pathname

`(pathname pathspec)`

指定子が指すパス名を返します。パス名はそのまま返り、文字列はそれを
パス名文字列として保持する新しいパス名になり、それ以外はシグナルします。
パス名ファミリ全体が経由する正規のコンストラクタで、ソース中の `#P"..."`
は実行時にこれが作るのと同じ値を表します。

```lisp
(pathname "d/notes.txt")   ; => #P"d/notes.txt"
```

`(pathname #P"d/notes.txt")` は引数そのもの、`(pathnamep (pathname "x"))` は
`T` です。

ファイルストリームは、閉じた後も含め、開いたときのパス名を指します。
`(open "d/notes.txt")` のストリーム `s` に対する `(pathname s)` は
`#P"d/notes.txt"` です。[`namestring`](namestring.md)、[`truename`](truename.md)、
`probe-file`、`delete-file`、`open` などパスを取る演算子も同様に受け付けます。
SBCL はこのパス名をデフォルトのディレクトリとマージするので、絶対パスを返します。
文字列ストリームは何も指さず、シグナルします。

## バックエンド対応

全 4 バックエンド -- rontolisp ソースの 1 つの定義が、参照されたときに
プログラムへスプライスされます。
