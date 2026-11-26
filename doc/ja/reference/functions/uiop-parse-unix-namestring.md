# uiop:parse-unix-namestring

`(uiop:parse-unix-namestring name &key type defaults dot-dot ensure-directory &allow-other-keys)`

`name` を Unix 構文でパス名に型強制します -- UIOP の可搬なパス名リーダです。パス名は
そのまま通り、`nil` は `nil` のまま、シンボルは小文字化して文字列として読まれます。
空と `"."` のディレクトリ成分は落とされ、`".."` は 1 段上として残ります。文字列の
`:type` は最後の成分全体を NAME にしてその型を付け、`:ensure-directory`
(または `:type :directory`) はディレクトリ形式を強制します。残りのキーは
[`uiop:ensure-pathname`](uiop-ensure-pathname.md) へ渡されます
(`:want-relative t` は絶対な文字列を拒否します)。

```lisp
(uiop:parse-unix-namestring "a//b/./c.txt")   ; => #P"a/b/c.txt"
```

```lisp
(uiop:parse-unix-namestring "foo/bar" :type "lisp")   ; => #P"foo/bar.lisp"
```

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
