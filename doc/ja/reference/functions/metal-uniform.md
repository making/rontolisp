# metal:uniform

`(metal:uniform encoder index values &key stage)`

`values` をそのステージのバッファ `index` のバイト列として設定します。Metal がバッファではなくインラインで受け取りたがる程度に小さい、フレームごとのユニフォームです。`:stage` は `:vertex` (既定) か `:fragment`。2 つのステージはバッファ番号を独立に数えるので、一方の 0 番は他方の 0 番ではありません。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (metal:uniform encoder 1 (linalg:transpose view-projection))
CL-USER> (metal:uniform encoder 0 eye :stage :fragment)
```
