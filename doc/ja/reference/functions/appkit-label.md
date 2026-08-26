# appkit:label

`(appkit:label window text &key (x 20) (y 20) (width 200) (height 24) (size 13) color (align :left) bold)`

そのテキストを持つ `NSTextField` ラベルをウィンドウの content view に追加して返します。座標系は AppKit のもので、原点はウィンドウの左下です。文字列は与えられた矩形の中で垂直方向に中央寄せされます。素の `NSTextField` は自分のフレームの上端に描くので、背の高い矩形を渡したラベルは天井から吊り下がってしまうからです。これがタイルの中央に数字を置いてくれます。`:align` は `:left`、`:center`、`:right` のいずれかで、`:size` と `:bold` がフォントを、`:color` が文字色を決めます。ラベルは `appkit:on-click` でクリックに応えます。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *label* (appkit:label *win* "no clicks yet" :x 20 :y 120 :width 380))
CL-USER> (appkit:text *label*)
"no clicks yet"
CL-USER> (appkit:label *win* "3" :x 20 :y 20 :width 34 :height 34 :size 19 :align :center :bold t)
#<objc RontoLispAppKitLabel>
```
