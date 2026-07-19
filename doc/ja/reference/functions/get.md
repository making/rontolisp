# get

`(get symbol indicator &optional default)`

シンボルの属性リストから `indicator` のエントリを読み、なければ `default` を返します。`(setf (get symbol indicator) value)` で書き込みます。シンボルは名前で比較され plist を保持する実体セルを持たないため、ストアはプログラム全体で 1 つの名前キーのテーブルです。

```lisp
(setf (get 'my-sym 'color) :red)
(get 'my-sym 'color) ; => :red
```

```lisp
(get 'my-sym 'absent :fallback) ; => :fallback
```
