# metal:+triangle+ metal:+line+ metal:+point+ metal:+triangle-strip+ metal:+cull-none+ metal:+cull-front+ metal:+cull-back+ metal:+winding-clockwise+ metal:+winding-counter-clockwise+ metal:+compare-less+ metal:+compare-always+

`metal:+triangle+ metal:+line+ metal:+point+ metal:+triangle-strip+
metal:+cull-none+ metal:+cull-front+ metal:+cull-back+
metal:+winding-clockwise+ metal:+winding-counter-clockwise+
metal:+compare-less+ metal:+compare-always+`

Metal の列挙はワイヤ上では単なる整数で、ここにあるのは描画プログラムが実際に書き下すメンバーです。描画するプリミティブ (`drawPrimitives:...`)、エンコーダに設定するカルモードと巻き、`metal:depth-state` に渡す深度比較。ピクセルフォーマット、ロード/ストアアクション、ブレンド係数、ストレージモードは `metal:attach` / `metal:pipeline` / `metal:frame` 自身の仕事であり、公開されていません。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (list metal:+point+ metal:+line+ metal:+triangle+ metal:+triangle-strip+)
(0 1 3 4)
CL-USER> (objc:send encoder "setCullMode:" metal:+cull-back+)
```
