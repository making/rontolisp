# appkit:on-click

`(appkit:on-click view handler)`

パネルやラベルがクリックに応えるようにします。`handler` はボタン番号 (左クリックなら 1、右クリック (または Ctrl クリック) なら 3) とともに、AppKit のイベントループの中、メインスレッドで呼ばれるので、GUI を自由に触れます。ボタンを渡した場合はそのボタンのアクションを設定するので、1 つの動詞でどのウィジェットも配線できます。ボタン自身の `:on-click` クロージャが引数を取らないのは、ボタンに右クリックがないからです。ビューを返します。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (appkit:on-click *tile*
    (lambda (button)
      (appkit:set-text *label* (if (= button 3) "flagged" "opened"))))
#<objc RontoLispAppKitPanel>
```
