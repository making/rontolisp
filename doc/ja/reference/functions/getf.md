# getf

`(getf plist indicator &optional default)`

プロパティリスト (インジケータと値が交互に並ぶ平坦なリスト) の中で `indicator` に続く値を返します。インジケータがない場合は `default` を返します。`default` を省略した場合は `nil` です。`getf` は関数なので、`default` はインジケータが見つかるかどうかにかかわらず評価されます。インジケータが存在してその値が `nil` の場合は、デフォルトではなく `nil` を返します。これは `remf` と対になるものです。`(setf (getf ...) value)` はサポートされていません。プロパティを削除するには `remf` を使ってください。

```lisp
(getf '(:a 1 :b 2) :b) ; => 2
```

```lisp
(getf '(:a 1) :on-delete :restrict) ; => :RESTRICT
```
