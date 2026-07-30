# princ

`(princ object &optional stream)`

`object` を人間が読みやすい形式で標準出力に書き出します。文字列を囲むクオートや文字の `#\` プレフィックスは付けず、末尾に改行も付けません。これは読み戻すためではなく表示するための形式です。シンボルは [`symbol-name`](symbol-name.md) のみが印字されます: キーワードの先頭の `:`、gensym の `#:`、そしてパッケージ修飾子 (`quri:uri` は `URI` と印字) はいずれもシンボルの所在を示すもので名前の一部ではないため、印字されません(`prin1`/`print` は保ちます)。コンディションオブジェクトはその [`:report`](../macros/define-condition.md) を印字します(`prin1` は `#<...>` のインスタンス構文のままです)。オプションの stream 引数を指定すると、標準出力の代わりにそのストリームに出力されます。`object` を返します。

```lisp
(princ "hello")
(princ :ready)
```

```
helloREADY
```
