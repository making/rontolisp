# pathnamep

`(pathnamep object)`

`object` がパス名かどうか、つまりここでは文字列かどうかを返します。rontolisp のパス名は
パス名文字列そのものなので、文字列はパス名であり、それ以外の値はパス名ではありません。
Common Lisp が要求するとおり、`(typep object 'pathname)` と同じ答えになります。

これは Common Lisp から逸脱しています。本来は名前文字列はパス名**指定子**であって
パス名ではありません。ここでは他にパス名になりうる値がないため、実用的な答えと標準の
答えが分かれます。

`typecase` / `etypecase` では、`pathname` 節は文字列を受け取れる兄弟節 (catch-all、
または `string` / `vector` / `array` / `sequence` 節) があるとき、その文字列を譲ります。
これが 2 つのイディオムを区別します。「これはパスか」を問う
`(etypecase file (null ...) (pathname ...))` は `pathname` の分岐を取り、ファイルと
文字列**内容**を判別する `(typecase in (pathname (open in)) (t ...))` では文字列は
catch-all に届きます。

```lisp
(pathnamep "/tmp/data.json") ; => T
```

`(pathnamep 42)` は `NIL` です。
