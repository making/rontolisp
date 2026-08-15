# enough-namestring

`(enough-namestring pathname &optional defaults)`

`defaults` (省略時は `*default-pathname-defaults*`、初期値は `#P""`) に対して
マージし直したときに同じファイルを指す、最短の名前文字列を返します。返り値は
パス名ではなく**文字列**です。

これは [`merge-pathnames`](merge-pathnames.md) の逆操作です。マージは相対的な
名前文字列に `defaults` の**ディレクトリ**部分を前置するので、最短の名前文字列は
そのディレクトリ接頭辞を取り除いたものになります。パスが接頭辞で始まらない場合は
削れるものがないので、名前文字列全体がそのまま答えになります。

```lisp
(list (enough-namestring "/a/b/c.lisp" "/a/")
      (enough-namestring "/a/b/c.lisp" "/x/")
      (namestring (merge-pathnames (enough-namestring "/a/b/c.lisp" "/a/") "/a/")))
; => ("b/c.lisp" "/a/b/c.lisp" "/a/b/c.lisp")
```

`*default-pathname-defaults*` はどのバックエンドでも本物の動的変数なので、パス処理の
まとまりを囲んで束縛できます。

```lisp
(let ((*default-pathname-defaults* #P"/a/b/"))
  (enough-namestring "/a/b/c.lisp"))   ; => "c.lisp"
```

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
