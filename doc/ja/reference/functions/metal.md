# metal パッケージの関数

`metal` パッケージは `appkit` ウィンドウ上の Metal 描画サーフェスです。レイヤ、デバイス、
コマンドキュー、レンダーパス、ドローアブル、present と commit、そしてどの Metal
プログラムも同じように書くシェーダ・パイプライン・バッファのヘルパをまとめています。
`objc` の動詞の上に rontolisp で書かれ初回使用時に読み込まれるので、macOS 専用です
(インタプリタとコンパイル済み `.class` / `.jar`。`.wasm` は不可)。
**Common Lisp の一部ではありません**。名前は `metal:` 修飾子で参照してください。
`metal:context` は CLOS クラス名でもあります。単独で成立しており (`geom` や `scene`
は不要)、`examples/macos/metal-*.lisp` の 4 本が直接これを使っています。
以下の各名前は個別ページへリンクします。サーフェス全体は
[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

| 関数 | 例 | 結果 |
|------|-----|------|
| `metal:attach` | `(metal:attach win :depth t)` | `metal:context`。ウィンドウのコンテンツビュー上の `CAMetalLayer`、そのデバイスとコマンドキュー |
| `metal:offscreen` | `(metal:offscreen :width 256 :height 192)` | ウィンドウを持たない `metal:context`。パイプラインも `metal:frame` も同じまま、テクスチャに描く |
| `metal:pixels` | `(metal:pixels ctx)` | 直前のオフスクリーンフレーム。`width * height * 4` バイト、BGRA、行 0 が上 |
| `metal:device` | `(metal:device ctx)` | `MTLDevice`。GPU そのもので、あらゆる `new...` セレクタのレシーバ |
| `metal:layer` | `(metal:layer ctx)` | フレームが提示される `CAMetalLayer` |
| `metal:queue` | `(metal:queue ctx)` | コマンドバッファをコミットする `MTLCommandQueue` |
| `metal:library` | `(metal:library ctx source)` | `MTLLibrary`。Metal Shading Language を実行時にコンパイルし、失敗時はコンパイラ自身の診断で送出 |
| `metal:pipeline` | `(metal:pipeline ctx lib "v" "f")` | 2 つの名前付きシェーダ関数から作る `MTLRenderPipelineState`。`:blend t` で加算合成 |
| `metal:depth-state` | `(metal:depth-state ctx :writes nil)` | `MTLDepthStencilState`。パイプラインが深度アタッチメントをどう使うか |
| `metal:floats` | `(metal:floats '(1 2 3))` | パックド単精度配列。Metal バッファのバイト列そのもの |
| `metal:buffer` | `(metal:buffer ctx (geom:mesh s))` | その数値を保持する `MTLBuffer`。一度コピーされたきり変更されない |
| `metal:shared-buffer` | `(metal:shared-buffer ctx 4096)` | 共有ストレージ上の `MTLBuffer`。CPU が中身を書き換える |
| `metal:upload` | `(metal:upload buf values)` | 共有バッファに数値をコピー |
| `metal:uniform` | `(metal:uniform enc 1 m)` | フレームごとの小さなユニフォームをインラインで設定。`:stage` は `:vertex` (既定) か `:fragment` |
| `metal:frame` | `(metal:frame ctx fn)` | 1 フレーム描画し、レンダーコマンドエンコーダを引数に `fn` を呼ぶ。空きドローアブルがなければスキップ |
| `metal:run` | `(metal:run ctx fn :fps 30)` | `metal:frame` を呼ぶタイマー。メインスレッド上の `NSTimer` |
| `metal:resize` | `(metal:resize ctx 1024 640)` | レイヤ・ドローアブルサイズ・深度テクスチャを新しいコンテンツサイズに追随させる |
| `metal:set-clear-color` | `(metal:set-clear-color ctx '(0 0 0 1))` | フレーム開始時の色 |
| `metal:+triangle+` ほか | `metal:+line+` | 描画プログラムが実際に書き下す列挙メンバー。プリミティブ、カルモード、巻き、深度比較 |

