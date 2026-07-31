# make-synonym-stream

`(make-synonym-stream symbol)`

`symbol` -- `*standard-output*` のようなストリームを指す特殊変数 -- が保持しているストリームへ転送するストリーム指定子を返します。標準ストリームと同じ出力先を既定値に持つ `defvar` を書くのが典型的な使い方です。

`(make-synonym-stream '*standard-output*)` と `(make-synonym-stream '*standard-input*)` は Common Lisp と同じ挙動です。いずれも `nil` 指定子を返し、出力 (入力) 操作はその時点の `*standard-output*` / `*standard-input*` を通して `nil` を解決するため、後から変数を再束縛すると、先に構築されたシノニムストリームの出力先も **変わります**。

それ以外のシンボルについてはライト実装です。rontolisp は操作のたびではなく、ストリームを作った場所でシンボルを **一度だけ** 解決します。したがって、後からその変数を再束縛してもシノニムストリームの出力先は変わりません。リダイレクトが必要な場合は、ストリームを明示的に渡すか、`*standard-output*` を束縛してストリーム引数なしの print 系関数を使ってください。

```lisp
(defvar *report-output* (make-synonym-stream '*standard-output*))
(write-string "hello" *report-output*) ; => "hello"
```
