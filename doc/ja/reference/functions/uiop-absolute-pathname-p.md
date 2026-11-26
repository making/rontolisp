# uiop:absolute-pathname-p

`(uiop:absolute-pathname-p pathspec)`

`pathspec` が絶対ならパース済みのパス名を (本家と同じく一般化ブーリアン)、
そうでなければ `nil` を返します。rontolisp の名前文字列はホストの綴りなので、
「絶対」とは先頭の `/` のことです -- 重みづけすべきデバイスやホスト成分はありません。

```lisp
(uiop:absolute-pathname-p "/tmp/x")   ; => #P"/tmp/x"
```

```lisp
(uiop:absolute-pathname-p "tmp/x")   ; => NIL
```

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
