# pathnamep

`(pathnamep object)`

`object` がパス名かどうかを返します。パス名は `#P"..."` が表す独立した値で、
パス名文字列を保持するオブジェクトです。文字列はパス名では**ありません**
(標準の Common Lisp と同じく、パス名を**指定**するだけです)。それ以外の値も
パス名ではありません。Common Lisp が要求するとおり、`(typep object 'pathname)`
と同じ答えになります。

生成側 -- `pathname`、`make-pathname`、`merge-pathnames`、`probe-file`、
`truename`、`directory` と `uiop:` のディレクトリ走査群 -- はすべてパス名を
返し、パスを取る演算子はパス名と名前文字列の両方を受け付けます。そのため
この述語がライブラリにファイルとテキストの判別を可能にします:
`(typecase in (pathname (open in)) (t ...))` は `#P"..."` 引数を開き、文字列
引数は内容としてパースします。

```lisp
(pathnamep #P"/tmp/data.json") ; => T
```

`(pathnamep "/tmp/data.json")` と `(pathnamep 42)` は `NIL` です。
