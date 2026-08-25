# appkit:visible-p

`(appkit:visible-p window)`

ウィンドウが画面上にあるかどうか: `appkit:window` の後は `t`、閉じられた後は `nil` です。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (appkit:visible-p *win*)
T
```
