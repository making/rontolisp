# uiop:relative-pathname-p

`(uiop:relative-pathname-p pathspec)`

`pathspec` が相対 -- `/` で始まらない (空のパス名も含む) -- ならパース済みの
パス名を、そうでなければ `nil` を返します。
[`uiop:absolute-pathname-p`](uiop-absolute-pathname-p.md) の鏡像です。

```lisp
(uiop:relative-pathname-p "a/b")   ; => #P"a/b"
```

```lisp
(uiop:relative-pathname-p "/a/b")   ; => NIL
```

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
