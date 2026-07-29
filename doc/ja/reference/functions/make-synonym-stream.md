# make-synonym-stream

`(make-synonym-stream symbol)`

`symbol` -- `*standard-output*` のようなストリームを指す特殊変数 -- が現在保持しているストリーム指定子を返します。標準ストリームと同じ出力先を既定値に持つ `defvar` を書くのが典型的な使い方です。

ライト実装であり、頼る前に知っておくべき点があります。Common Lisp のシノニムストリームはすべての操作を *その操作の時点での* シンボルの値へ転送しますが、rontolisp はストリームを作った場所でシンボルを **一度だけ** 解決します。したがって、後から変数を再束縛しても、先に構築されたシノニムストリームの出力先は変わりません。リダイレクトが必要な場合は、ストリームを明示的に渡すか、`*standard-output*` を束縛してストリーム引数なしの print 系関数を使ってください。

```lisp
(defvar *report-output* (make-synonym-stream '*standard-output*))
(write-string "hello" *report-output*) ; => "hello"
```
