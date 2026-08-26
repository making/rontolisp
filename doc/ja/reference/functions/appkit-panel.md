# appkit:panel

`(appkit:panel window &key (x 20) (y 20) (width 100) (height 100) fill (radius 0) (border 0) border-color)`

塗りつぶした矩形 — カスタム形式の `NSBox` で、角丸と枠線は任意 — をウィンドウの content view に追加して返します。座標は AppKit のもので、原点はウィンドウの左下です。色は `appkit:set-color` で変えられ、`appkit:on-click` でクリックに応えるので、パネルはボードゲームを組み立てるタイルになります。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *tile*
    (appkit:panel *win* :x 20 :y 20 :width 34 :height 34
                  :fill (appkit:color 104 116 146) :radius 7))
CL-USER> (appkit:set-color *tile* (appkit:color 230 233 241))
#<objc _NSTaggedPointerColor>
```
