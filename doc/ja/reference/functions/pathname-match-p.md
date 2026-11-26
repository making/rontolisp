# pathname-match-p

`(pathname-match-p pathname wildcard)`

`pathname` が `wildcard` に一致するかを、[`translate-pathname`](translate-pathname.md) と
同じ規則で構成要素ごとに判定します。`*` と `?` は 1 つの構成要素の中だけで照合し、
ディレクトリの `**` は任意個 (0 個を含む) の階層に一致します。`wildcard` に無い構成要素は
何にでも一致し、`*` は無い構成要素にも一致します。

```lisp
(list (pathname-match-p "x/y/a.lisp" "x/*/*.lisp")
      (pathname-match-p "x/a.lisp" "x/*/*.lisp")
      (pathname-match-p "a" "*.*")
      (pathname-match-p "sub/b.lisp" "*.lisp"))
; => (T NIL T T)
```

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
