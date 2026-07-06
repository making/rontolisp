# mapl

`(mapl function list)`

[`maplist`](maplist.md) と同様ですが、`function` は `list` の連続する cdr (末尾リスト) に副作用のためだけに適用され、結果のリストではなく元の `list` を返します。単一リスト形式のみ対応します。

引数はリストでなければなりません (空リスト `nil` は受理されます)。文字列などの非リストを渡すとエラーがシグナルされます。

```lisp
(mapl #'identity '(1 2 3)) ; => (1 2 3)
```
