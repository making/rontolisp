# uiop:directory-files

`(uiop:directory-files pathspec &optional pattern)`

ディレクトリのうち、ディレクトリでないエントリを返します。`(directory "<pathspec>/*.*")`
からサブディレクトリを除いたものです。`pathspec` にはディレクトリ自身を（末尾の `/` は
あってもなくても）指定します。ワイルドカードはこちらで補うので、これが「とにかく一覧する」
書き方になります。

`pattern` は UIOP 自身の省略可能な第 2 引数で、名前と型のみのワイルドカードのパス名
文字列です。ディレクトリの後ろに連結され、[`directory`](directory.md) とまったく同じ
規則で照合されます (`*` は任意の並び、`?` は 1 文字)。したがって
`(uiop:directory-files "db/" "*.up.sql")` は up マイグレーションだけを一覧します。
省略するとすべてを一覧します。ディレクトリ部分を含むパターンは、本家 UIOP と同じく
エラーです。走査するディレクトリを決めるのは第 1 引数の役目だからです。

```lisp
(uiop:directory-files "no-such-directory/" "*.up.sql")   ; => NIL
```

## バックエンドサポート

4 バックエンドすべてです。`directory` と同じ 1 つのプリミティブの上に定義されています。
