# objc パッケージの関数

`objc` パッケージは Foreign Function API で Objective-C ランタイムと AppKit をバインドします。リフレクションを使わないため、`java:` と違って `java -jar` だけでなく**ネイティブバイナリ**でも動作します。**macOS 専用**で (インタプリタ、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません)、**Common Lisp の一部ではありません**。関数は `objc:` 修飾子付きで参照します。各名前は個別のページにリンクしています。変換、スレッド、所有権、ネイティブバイナリの形テーブルについては [macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

## LispWorks のインターフェース

LispWorks 8.1 の Objective-C インターフェースの名前とラムダリストをそのまま持つため、そのマニュアルに沿って書いたコードがここで動きます。型もこのパッケージが名付けます。`objc:objc-object-pointer` (すべてのオブジェクト)、`objc:objc-class` (クラス。オブジェクトポインタでもある)、`objc:sel` (セレクタ) は、以下の関数が返す値の型です。`objc:objc-bool`、`objc:objc-c++-bool`、`objc:objc-c-string`、`objc:objc-at-question-mark`、`objc:objc-unknown` は、リスト形式のメソッドと `objc:objc-class-method-signature` が使う型指定子です。マクロ [`objc:with-autorelease-pool`](../macros/objc-with-autorelease-pool.md) は本体を自動解放プールの中で評価します。下の `objc:on-main` はパッケージの両方の部分で共有されます。

| 関数 | 例 | 結果 |
|------|-----|------|
| [`objc:ensure-objc-initialized`](objc-ensure-objc-initialized.md) | `(objc:ensure-objc-initialized :modules '("/path/Framework"))` | `nil` (モジュールをロードする) |
| [`objc:invoke`](objc-invoke.md) | `(objc:invoke "NSString" "stringWithUTF8String:" "hi")` | 宣言型に従って変換されたメソッドの値 |
| [`objc:invoke-bool`](objc-invoke-bool.md) | `(objc:invoke-bool s "hasPrefix:" "h")` | `t` か `nil` |
| [`objc:invoke-into`](objc-invoke-into.md) | `(objc:invoke-into 'string s "description")` | 第一引数の指示どおりに変換または格納された値 |
| [`objc:can-invoke-p`](objc-can-invoke-p.md) | `(objc:can-invoke-p s "length")` | `t` か `nil` |
| [`objc:alloc-init-object`](objc-alloc-init-object.md) | `(objc:alloc-init-object "NSObject")` | 新しいインスタンス |
| [`objc:description`](objc-description.md) | `(objc:description s)` | `description` の文字列 |
| [`objc:trace-invoke`](objc-trace-invoke.md) | `(objc:trace-invoke "length")` | 名前 (以後その送信をトレースする) |
| [`objc:untrace-invoke`](objc-untrace-invoke.md) | `(objc:untrace-invoke "length")` | 名前 (トレースを解除する) |
| [`objc:coerce-to-objc-class`](objc-coerce-to-objc-class.md) | `(objc:coerce-to-objc-class "NSString")` | クラスポインタ |
| [`objc:objc-class-name`](objc-objc-class-name.md) | `(objc:objc-class-name c)` | クラスの名前 |
| [`objc:coerce-to-selector`](objc-coerce-to-selector.md) | `(objc:coerce-to-selector "frame")` | セレクタ |
| [`objc:selector-name`](objc-selector-name.md) | `(objc:selector-name sel)` | セレクタの名前 |
| [`objc:objc-class-method-signature`](objc-objc-class-method-signature.md) | `(objc:objc-class-method-signature "NSString" "length")` | 引数の型、結果の型、エンコーディング |
| [`objc:retain`](objc-retain.md) | `(objc:retain p)` | retain した `p` |
| [`objc:release`](objc-release.md) | `(objc:release p)` | `nil` (参照を一つ手放す) |
| [`objc:autorelease`](objc-autorelease.md) | `(objc:autorelease p)` | `p` (参照を一つプールに渡す) |
| [`objc:retain-count`](objc-retain-count.md) | `(objc:retain-count p)` | `retainCount` |
| [`objc:make-autorelease-pool`](objc-make-autorelease-pool.md) | `(objc:make-autorelease-pool)` | 解放するまで現在のプールになるプール |
| [`objc:ns-string-to-string`](objc-ns-string-to-string.md) | `(objc:ns-string-to-string s)` | Lisp 文字列 |
| [`objc:string-to-ns-string`](objc-string-to-ns-string.md) | `(objc:string-to-ns-string "hi")` | プログラムが所有する `NSString` |
| [`objc:objc-object-pointer`](objc-objc-object-pointer.md) | `(objc:objc-object-pointer p)` | オブジェクトのポインタ |
| [`objc:objc-object-from-pointer`](objc-objc-object-from-pointer.md) | `(objc:objc-object-from-pointer p)` | 対応する Lisp オブジェクト (`nil`) |

## 最初の動詞

`appkit`、`metal`、`scene` の各層が土台にしている、パッケージ本来の動詞です。これらの層が LispWorks のインターフェースに移るまで残ります。

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

