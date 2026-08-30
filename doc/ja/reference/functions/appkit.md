# appkit パッケージの関数

`appkit` パッケージは `objc` の上に rontolisp で書かれた Cocoa ウィジェット層で、`linalg` と同様に初回使用時に読み込まれます。macOS のディスプレイが必要で、インタプリタと `.class` / `.jar` にコンパイルしたプログラムで動作します (`.wasm` は不可)。**Common Lisp の一部ではありません**。関数は `appkit:` 修飾子付きで参照します。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

| 関数 | 例 | 結果 |
|------|-----|------|
| `appkit:window` | `(appkit:window "title" :width 480 :height 300)` | 表示済み・中央配置の `NSWindow` |
| `appkit:label` | `(appkit:label win "text" :x 20 :y 120 :width 380)` | 矩形内で文字列を中央寄せした `NSTextField` ラベル |
| `appkit:button` | `(appkit:button win "Click" :x 20 :y 40 :on-click (lambda () ...))` | アクションでクロージャを実行する `NSButton` |
| `appkit:panel` | `(appkit:panel win :x 20 :y 20 :width 34 :height 34 :fill c :radius 7)` | ウィンドウ内の塗りつぶした角丸 `NSBox` |
| `appkit:color` | `(appkit:color 90 200 250)` | 0-255 の成分から作った `NSColor` |
| `appkit:font` | `(appkit:font 19 :bold t)` | そのサイズのシステム `NSFont` |
| `appkit:set-text` | `(appkit:set-text label "new text")` | 表示されたテキスト |
| `appkit:set-color` | `(appkit:set-color tile (appkit:color 214 69 65))` | 色。パネルなら塗りつぶし色、それ以外なら文字色 |
| `appkit:text` | `(appkit:text label)` | コントロールのテキスト (文字列) |
| `appkit:on-click` | `(appkit:on-click tile (lambda (button) ...))` | ビュー。クリック時にクロージャが走る (1 が左、3 が右) |
| `appkit:click` | `(appkit:click button)` | `nil`。クリックと同じようにアクションが実行される |
| `appkit:timer` | `(appkit:timer 0.12 (lambda () ...))` | 繰り返す `NSTimer`。`nil` を返すと止まる |
| `appkit:menu` | `(appkit:menu (list (list "Quit" #'appkit:quit "q")))` | 項目が Lisp のクロージャである `NSMenu`。`:separator` は区切り線 |
| `appkit:status-item` | `(appkit:status-item "λ" :menu m :dock nil)` | システムのメニューバーの `NSStatusItem`。`:dock nil` は Dock アイコンを消す |
| `appkit:quit` | `(appkit:quit)` | 返らない。Cmd-Q と同じようにアプリケーションが終了する |
| `appkit:close` | `(appkit:close win)` | `nil`。ウィンドウは閉じられる (隠される) |
| `appkit:visible-p` | `(appkit:visible-p win)` | ウィンドウが画面上にあるかどうか |
| `appkit:wait` | `(appkit:wait win)` | ウィンドウが閉じられた後に `nil`。ウィンドウを渡さなければアプリケーション終了までブロック |

