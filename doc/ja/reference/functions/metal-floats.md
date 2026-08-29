# metal:floats

`(metal:floats values)`

数値のリストからパックド単精度配列を作ります。`objc:data` が Metal バッファのバイト列そのものに変換する表現です。`linalg` の結果や `geom:mesh` はすでにこの配列なので変換は不要です。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (metal:floats '(1 2 3))
#f(1.0 2.0 3.0)
```
