# rplacd

`(rplacd cons object)`

`cons` の cdr を `object` で破壊的に置き換え、コンスセルをその場で変更します。変更後のコンスセル自身を返すため、元の参照は新しい末尾を見るようになります。これは `cdr` に対する `setf` が展開される際のプリミティブであり、`nconc` のような連結操作の構成要素です。

```lisp
(let ((c (cons 1 2))) (rplacd c 99) c) ; => (1 . 99)
```
