# sinh cosh tanh

`(sinh number)` `(cosh number)` `(tanh number)`

双曲線関数で、それぞれ浮動小数点数を返します。`sinh` は双曲線正弦、`cosh` は双曲線余弦、`tanh` は双曲線正接です。`tanh` は 3 つのバックエンドすべてで動きます。インタプリタと JVM は `Math.tanh` を使い、WASM バックエンドはソフトウェア近似の `exp` から導出するため、最下位桁でわずかに結果が異なる場合があります。`sinh` と `cosh` はインタプリタと JVM バックエンドでのみ利用可能です (WASM では利用できません)。

```lisp
(tanh 0) ; => 0.0
```
