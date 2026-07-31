# uiop:directory-files

`(uiop:directory-files pathspec)`

ディレクトリのうち、ディレクトリでないエントリを返します。`(directory "<pathspec>/*.*")`
からサブディレクトリを除いたものです。`pathspec` にはディレクトリ自身を（末尾の `/` は
あってもなくても）指定します。ワイルドカードはこちらで補うので、これが「とにかく一覧する」
書き方になります。

```lisp
(uiop:directory-files "no-such-directory/")   ; => NIL
```

本家 UIOP は絞り込み用のワイルドカードパス名を省略可能な第 2 引数として取りますが、
rontolisp にはワイルドカードパス名の機構がないため、1 引数形式のみを提供します。
絞り込みは返ってきたリストに対して Lisp 側で行ってください。

## バックエンドサポート

4 バックエンドすべてです。`directory` と同じ 1 つのプリミティブの上に定義されています。
