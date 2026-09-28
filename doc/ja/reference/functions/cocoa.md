# cocoa パッケージの関数

`cocoa` パッケージは LispWorks 8.1 の `COCOA` です。`objc:invoke` が変換する Foundation の四つの構造体、その設定関数、`cocoa:ns-not-found`、通知の監視を持ちます。**macOS 専用**で、**Common Lisp の一部ではありません**。構造体は `objc:invoke` がそれについて受け渡す Lisp の値 (`NSPoint` はベクタ `#(x y)`、`NSSize` は `#(width height)`、`NSRect` は `#(x y width height)` (いずれも倍精度)、`NSRange` はコンス `(location . length)`) か、その型の外部オブジェクト ([`fli`](fli.md)) です。外部オブジェクトのスロットは `x` `y`、`width` `height`、`origin` `size`、`location` `length` です。設定関数はどちらも埋めます。シンボル `cocoa:ns-point`、`cocoa:ns-size`、`cocoa:ns-rect`、`cocoa:ns-range` は、リスト形式のメソッドの型と `objc:objc-class-method-signature` の答え (`(:struct cocoa:ns-range)`) で構造体を指します。`cocoa:ns-not-found` は `NSNotFound` (9223372036854775807) です。

| 関数 | 例 | 結果 |
|------|-----|------|
| [`cocoa:set-ns-point*`](cocoa-set-ns-point-star.md) | `(cocoa:set-ns-point* (make-array 2) 10 20)` | `#(10 20)` |
| [`cocoa:set-ns-size*`](cocoa-set-ns-size-star.md) | `(cocoa:set-ns-size* (make-array 2) 640 480)` | `#(640 480)` |
| [`cocoa:set-ns-rect*`](cocoa-set-ns-rect-star.md) | `(cocoa:set-ns-rect* (make-array 4) 0 0 640 480)` | `#(0 0 640 480)` |
| [`cocoa:set-ns-range*`](cocoa-set-ns-range-star.md) | `(cocoa:set-ns-range* (cons 0 0) 6 5)` | `(6 . 5)` |
| [`cocoa:add-observer`](cocoa-add-observer.md) | `(cocoa:add-observer w "noticed:" :name "Ping")` | `nil` (`w` が `Ping` を監視する) |
| [`cocoa:remove-observer`](cocoa-remove-observer.md) | `(cocoa:remove-observer w :name "Ping")` | `nil` (`w` が監視をやめる) |
