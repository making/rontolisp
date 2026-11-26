# metal:buffer

`(metal:buffer ctx values)`

`values` (リスト、またはすでにパックド単精度配列) を保持する `MTLBuffer`。一度コピーされたきり変更されません。動かないメッシュが入るべきバッファです。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *mesh* (metal:buffer *ctx* (geom:mesh (geom:box 100))))
```
