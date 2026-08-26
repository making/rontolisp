# ldiff

`(ldiff list object)`

`list` の各末尾を `object` と `eql` で比較し、`object` より前の要素を新しいリストとして返します。`object` が `list` の末尾のいずれでもない場合は、ドット対の末尾も含めてリスト全体をコピーします。

```lisp
(let ((l '(1 2 3 4))) (ldiff l (cddr l))) ; => (1 2)
```
