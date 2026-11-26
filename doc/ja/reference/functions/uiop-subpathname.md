# uiop:subpathname

`(uiop:subpathname pathname subpath &key type)`

`subpath` を `pathname` の DIRECTORY の下にマージします -- ライブラリがベース基準の
ファイルを名指す可搬な方法です。絶対なパス名オブジェクトはそのまま通り、それ以外は
相対の Unix 名前文字列としてパースされ (`type` が与えられれば最後の成分全体が NAME に、
`type` が型になります)、マージされます。絶対な文字列の subpath はエラーです
(`:want-relative`)。

```lisp
(uiop:subpathname #P"/tmp/foo/" "bar/baz.txt")   ; => #P"/tmp/foo/bar/baz.txt"
```

`uiop:subpathname*` は nil を許容するベース付きの同じもので、`nil` は `nil` を返し、
非 nil のベースはまずディレクトリ形式にされます。

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
[`uiop:merge-pathnames*`](uiop-merge-pathnames-star.md) と同様、コンパイルパスは
リテラル引数の呼び出しをパス名リテラルへ畳み込みます。
