# go

`(go tag)`

(動的に) 囲んでいる [`tagbody`](tagbody.md) の指定した go タグへ制御を移し、ジャンプ先以降のフォームの実行を続けます。タグを持つ `tagbody` が囲んでいない場合はエラーです。

現時点では**インタープリタのみ**でサポートされます。JVM / WASM コンパイラは未対応です。

```lisp
(let ((acc nil))
  (tagbody
    (push :a acc)
    (go skip)
    (push :never acc)
   skip
    (push :b acc))
  (nreverse acc)) ; => (:a :b)
```
