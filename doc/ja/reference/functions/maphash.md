# maphash

`(maphash function table)`

`table` の各エントリに対して `function` を 1 回ずつ呼び出し、キーと値を 2 つの引数として渡します。これは副作用のためだけのもので、結果は捨てられ、`maphash` は nil を返します。どのバックエンドも、キーが最初に格納された順にエントリをたどります。テーブルに既にあるキーへの格納は位置を保ち、削除してから再び格納したキーは最後に来ます。ANSI は順序を規定していない（たとえば SBCL は削除したエントリのスロットを再利用します）ため、ポータブルなコードはこれに依存すべきではありません。

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (maphash (lambda (k v) (print (list k v))) h))
```

```
(A 1)
```
