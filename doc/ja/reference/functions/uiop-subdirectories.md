# uiop:subdirectories

`(uiop:subdirectories pathspec)`

ディレクトリのサブディレクトリを、それぞれ末尾の `/` を付けて返します。
[`uiop:directory-files`](uiop-directory-files.md) の対になる関数で、同じ
`(directory "<pathspec>/*.*")` の上に定義されています。
[`uiop:collect-sub*directories`](uiop-collect-sub-directories.md) が再帰に使うのも
これです。

```lisp
(uiop:subdirectories "no-such-directory/")   ; => NIL
```

## バックエンドサポート

4 バックエンドすべてです。[`directory`](directory.md) と同じ 1 つのプリミティブの上に
定義されています。
