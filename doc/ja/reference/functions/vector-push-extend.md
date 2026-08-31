# vector-push-extend

`(vector-push-extend value vector &optional extension)`

[`vector-push`](vector-push.md) と同様ですが、ベクタが満杯の場合に nil を返す代わりにバッキングストレージを拡張するため、プッシュは常に成功し、新しいインデックスが返されます。`:adjustable` で作成されたかどうかに関係なく、フィルポインタを持つ任意のベクタを拡張できます (一般的な慣行に合わせています。[`adjustable-array-p`](adjustable-array-p.md) はフラグをそのまま報告します)。ベクタにフィルポインタがない場合はエラーを通知します。

`extension` は拡張する要素数で、容量にそのまま加算されます。省略した場合は容量が 2 倍になります (容量 0 のベクタは 1 に拡張されます)。そのため、プッシュのループはプッシュ回数に対して線形のままです。新しい容量はすべてのバックエンドで同一であり、[`array-dimension`](array-dimension.md) で観測できます。

```lisp
(defparameter *v* (make-array 1 :fill-pointer 0 :adjustable t))
(vector-push-extend 10 *v*) ; => 0
(vector-push-extend 20 *v* 4) ; => 1
*v* ; => #(10 20)
(array-dimension *v* 0) ; => 5
```
