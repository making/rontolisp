# go

`(go tag)`

(動的に) 囲んでいる [`tagbody`](tagbody.md) の指定した go タグへ制御を移し、ジャンプ先以降のフォームの実行を続けます。タグを持つ `tagbody` が囲んでいない場合はエラーです。

JVM / WASM コンパイラでの `go` は字句的です: 同一関数内で字句的に囲む `tagbody` のタグのみジャンプできます(インタープリタは関数境界を越える動的な `go` も追加でサポートします)。

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
