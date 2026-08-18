# copy-symbol

`(copy-symbol symbol &optional copy-properties)`

`symbol` と同じ名前を持つ、新しい非インターンシンボルを返します。属性リストを移す
ための `(setf symbol-plist)` が存在しないため、`copy-properties` は受け付けたうえで
無視されます。

コピーは [`make-symbol`](make-symbol.md) の同一性に関する差異をそのまま引き継ぎます。
rontolisp のシンボルは綴りそのものであり、インターンテーブルがありません。したがって
同じ名前の非インターンシンボル 2 つは `eq` であり、コピーは同じシンボルの他のコピーと
区別できません。他と衝突しない名前だけが必要なコードは [`gensym`](gensym.md) を
使ってください。こちらは呼び出しごとに新しい名前を返します。

```lisp
(symbol-name (copy-symbol 'foo)) ; => "FOO"
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。`make-symbol` の上に書かれた rontolisp ソース
による 1 つの定義があります。
