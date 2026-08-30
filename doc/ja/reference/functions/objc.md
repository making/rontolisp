# objc パッケージの関数

`objc` パッケージは Foreign Function API で Objective-C ランタイムと AppKit をバインドします。リフレクションを使わないため、`java:` と違って `java -jar` だけでなく**ネイティブバイナリ**でも動作します。**macOS のインタプリタ専用**であり (コンパイル済み `.class` や `.wasm` は拒否します)、**Common Lisp の一部ではありません**。関数は `objc:` 修飾子付きで参照します。各名前は個別のページにリンクしています。マーシャリング、スレッド、所有権、ネイティブバイナリの形テーブルについては [macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

| 関数 | 例 | 結果 |
|------|-----|------|
| `objc:class` | `(objc:class "NSWindow")` | クラス (`#<objc NSWindow>`) |
| `objc:send` | `(objc:send (objc:string "hi") "length")` | セレクタの宣言型に従ってマーシャリングされた結果 |
| `objc:define-class` | `(objc:define-class "Target" "NSObject" (list (list "invoke:" fn)))` | メソッドが Lisp 関数であるクラス |
| `objc:on-main` | `(objc:on-main (lambda () ...))` | メインスレッドで計算された関数の値 |
| `objc:string` | `(objc:string "hi")` | `NSString` |
| `objc:data` | `(objc:data buffer)` | バッファのバイト列を持つ `NSMutableData` |
| `objc:bytes` | `(objc:bytes data)` | `NSData` のバイト列 (パックされた `(unsigned-byte 8)` ベクタ) |
| `objc:address` | `(objc:address obj)` | オブジェクトのアドレス (整数) |
| `objc:objectp` | `(objc:objectp x)` | Objective-C オブジェクトなら `t` |

