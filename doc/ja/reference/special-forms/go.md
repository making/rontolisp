# go

`(go tag)`

(動的に) 囲んでいる [`tagbody`](tagbody.md) の指定した go タグへ制御を移し、ジャンプ先以降のフォームの実行を続けます。タグを持つ `tagbody` が囲んでいない場合はエラーです。

JVM / WASM コンパイラでの `go` は字句的です: 字句的に囲む `tagbody` のタグのみジャンプできます(インタープリタは関数呼び出しの境界を越える動的な `go`、つまり*呼び出し元*が確立したタグへのジャンプも追加でサポートします)。ネストした `lambda` の内側から囲む関数のタグへジャンプする形 — ループを `go` で再開する [`handler-bind`](../macros/handler-bind.md) ハンドラがまさにこれです — は、そのタグで `tagbody` に再入する非局所脱出へ低位化されるため、すべてのバックエンドで動作します。このプログラムは例外処理モードでコンパイルされるので、wasm の実行には `wasmtime -W exceptions=y` が必要です。

```lisp
(let ((acc nil))
  (tagbody
    (push :a acc)
    (go skip)
    (push :never acc)
   skip
    (push :b acc))
  (nreverse acc)) ; => (:A :B)
```
