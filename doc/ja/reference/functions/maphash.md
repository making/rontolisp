# maphash

`(maphash function table)`

`table` の各エントリに対して `function` を 1 回ずつ呼び出し、キーと値を 2 つの引数として渡します。これは副作用のためだけのもので、結果は捨てられ、`maphash` は nil を返します。反復順序は規定されておらず、バックエンドによって異なる場合があるため、ポータブルなコードはこれに依存すべきではありません。

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (maphash (lambda (k v) (print (list k v))) h))
```

```
(a 1)
```
