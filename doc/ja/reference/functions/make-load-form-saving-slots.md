# make-load-form-saving-slots

`(make-load-form-saving-slots object &key slot-names environment)`

[`make-load-form`](make-load-form.md) の出来合いの答えです: 現在のスロット値でオブジェクトを再構築するフォームを返します。特に言うことのないメソッドはこれに委譲します。cl-ppcre の `charmap` と `charset` のメソッドがまさにそれです。

返されるフォームは内部のインスタンスコンストラクタ——メソッドが一つもない型に対してコンパイラが書き出すものと同じ——なので、これに委譲することは組み込みの挙動を変えるのではなく明示的に要求することを意味します。

```lisp
(defstruct mlfss-pt x y)
(make-load-form-saving-slots (make-mlfss-pt :x 1 :y 2))
; => (%OBJ-NEW (QUOTE %struct-MLFSS-PT) (QUOTE 1) (QUOTE 2))
```

```lisp
(defstruct mlfss2-pt x y)
(defmethod make-load-form ((p mlfss2-pt) &optional env)
  (make-load-form-saving-slots p :environment env))
(defparameter *mlfss2* (make-mlfss2-pt :x 3 :y "four"))
(defmacro mlfss2-splice () *mlfss2*)
(list (mlfss2-pt-x (mlfss2-splice)) (mlfss2-pt-y (mlfss2-splice)))
; => (3 "four")
```

ライト版: `:slot-names` は無視され(全スロットが運ばれます)、`:environment` は受け取るだけで使いません。オブジェクトは確保してから埋めるのではなく一度に再構築されるため、自分自身への参照を持つことはできません。コンパイラが書き出せないもの(ハッシュテーブル、ストリーム)を保持するスロットがあれば依然としてコンパイルに失敗します。そうしたスロットを飛ばす必要があるメソッドは、自前でフォームを組み立ててください。
