# find-class

`(find-class symbol &optional errorp environment)`

ライト版スタブ: `errorp` に関わらず常に `nil` を返します — クラスメタオブジェクトは存在しません(`class-of` は名前指定子のみ返します)。

```lisp
(find-class 'my-class nil) ; => nil
```
