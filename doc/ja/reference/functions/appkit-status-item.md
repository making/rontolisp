# appkit:status-item

`(appkit:status-item title &key menu (dock t))`

システムのメニューバーに可変幅の `NSStatusItem` を置きます。`title` はステータスバーが持つボタンが描き、`menu` ([`appkit:menu`](appkit-menu.md)) をそこにぶら下げます。`:dock nil` はアクセサリのアクティベーションポリシーを要求します。Dock アイコンもアプリケーションスイッチャの項目もない、メニューバープログラムの姿です。この場合の出口は [`appkit:quit`](appkit-quit.md) です。返り値は変数に保持してください。ステータスバーの外ではこの値が項目の唯一の参照を持っており、回収されると項目はメニューバーから消えます。[`appkit:set-text`](appkit-set-text.md) と [`appkit:text`](appkit-text.md) はこの値を受け取るので、タイマーからタイトルを書き替えられます。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *item*
    (appkit:status-item "λ" :dock nil
                        :menu (appkit:menu (list (list "Quit" #'appkit:quit "q")))))
#<objc NSStatusItem>
CL-USER> (appkit:set-text *item* "λ 42")
"λ 42"
```
