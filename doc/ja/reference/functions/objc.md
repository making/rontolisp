# objc パッケージの関数

`objc` パッケージは Foreign Function API で Objective-C ランタイムと AppKit をバインドします。リフレクションを使わないため、`java:` と違って `java -jar` だけでなく**ネイティブバイナリ**でも動作します。**macOS 専用**で (インタプリタ、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません)、**Common Lisp の一部ではありません**。関数は `objc:` 修飾子付きで参照するか、LispWorks のコードと同じくパッケージを use します。語彙は LispWorks 8.1 の Objective-C インターフェースで、それにブロック、Objective-C 自身が報告するものを表すコンディション、独自の 4 つの関数が加わります。各名前は個別のページにリンクしています。変換、スレッド、所有権、ネイティブバイナリの形テーブルについては [macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

## LispWorks のインターフェース

LispWorks 8.1 の Objective-C インターフェースの名前とラムダリストをそのまま持つため、そのマニュアルに沿って書いたコードがここで動きます。型もこのパッケージが名付けます。`objc:objc-object-pointer` (すべてのオブジェクト)、`objc:objc-class` (クラス。オブジェクトポインタでもある)、`objc:sel` (セレクタ) は、以下の関数が返す値の型です。`objc:objc-bool`、`objc:objc-c++-bool`、`objc:objc-c-string`、`objc:objc-at-question-mark`、`objc:objc-unknown` は、リスト形式のメソッドと `objc:objc-class-method-signature` が使う型指定子です。マクロ [`objc:with-autorelease-pool`](../macros/objc-with-autorelease-pool.md) は本体を自動解放プールの中で評価します。クラスはマクロ [`objc:define-objc-class`](../macros/objc-define-objc-class.md)、[`objc:define-objc-method`](../macros/objc-define-objc-method.md)、[`objc:define-objc-class-method`](../macros/objc-define-objc-class-method.md)、[`objc:current-super`](../macros/objc-current-super.md)、[`objc:define-objc-struct`](../macros/objc-define-objc-struct.md)、[`objc:define-objc-typedef`](../macros/objc-define-objc-typedef.md)、[`objc:define-objc-protocol`](../macros/objc-define-objc-protocol.md) で定義し、そのインスタンスは `objc:standard-objc-object` です。

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
| [`objc:objc-object-from-pointer`](objc-objc-object-from-pointer.md) | `(objc:objc-object-from-pointer p)` | 対応する Lisp オブジェクト、または `nil` |
| [`objc:objc-object-var-value`](objc-objc-object-var-value.md) | `(objc:objc-object-var-value obj "count")` | インスタンス変数の値 (`setf` 可) |
| [`objc:objc-object-copied`](objc-objc-object-copied.md) | `(defmethod objc:objc-object-copied :after ((old c) (new c)) ...)` | インスタンスが複製されたときに呼ばれる |
| [`objc:objc-object-destroyed`](objc-objc-object-destroyed.md) | `(defmethod objc:objc-object-destroyed :after ((o c)) ...)` | インスタンスが解放されたときに呼ばれる |

## ブロック

Lisp の関数から作るブロックです。LispWorks の `OBJC` にはこのインターフェースがないため、このパッケージ独自の名前を使います。`objc:objc-block` は、ブロックを受け取るメソッドや C 関数にそのまま渡せます。マクロ [`objc:define-objc-block-type`](../macros/objc-define-objc-block-type.md) はシグネチャに名前を付け、[`objc:with-objc-block`](../macros/objc-with-objc-block.md) は本体の間だけ有効なブロックを作ります。`dispatch_async` などの C 関数は [`fli:define-foreign-function`](../macros/fli-define-foreign-function.md) で宣言します。メソッドが参照渡しで埋める外部オブジェクトも [`fli`](fli.md) パッケージにあります。

| 関数 | 例 | 結果 |
|------|-----|------|
| [`objc:make-objc-block`](objc-make-objc-block.md) | `(objc:make-objc-block '(:int (:int :int)) #'+)` | 関数を呼ぶ `objc:objc-block` |
| [`objc:free-objc-block`](objc-free-objc-block.md) | `(objc:free-objc-block b)` | `nil` (呼び出し先が保持したコピーは生き続ける) |
| [`objc:call-objc-block`](objc-call-objc-block.md) | `(objc:call-objc-block '(:int (:int :int)) b 3 4)` | ブロックの値 |
| [`objc:objc-block-pointer`](objc-objc-block-pointer.md) | `(objc:objc-block-pointer b)` | リテラルのアドレス (解放後は `nil`) |
| [`objc:objc-block-live-p`](objc-objc-block-live-p.md) | `(objc:objc-block-live-p b)` | 解放するまで `t` |

## 例外と NSError

Objective-C 自身が報告する失敗です。LispWorks の `OBJC` はこれをコンディションにしません。呼び出しの中で送出された例外は、最も内側の `objc:invoke`、C 関数またはブロックの呼び出しから `objc:objc-exception` (`error` のサブタイプ) としてシグナルされます。`objc:invoke-with-error` は、`NSError **` で失敗を報告したメソッドについて `objc:ns-error` をシグナルします。

| 関数 | 例 | 結果 |
|------|-----|------|
| [`objc:invoke-with-error`](objc-invoke-with-error.md) | `(objc:invoke-with-error fm "removeItemAtPath:error:" path)` | メソッドの値 (失敗したら `objc:ns-error`) |
| [`objc:objc-exception-name`](objc-objc-exception-name.md) | `(objc:objc-exception-name e)` | `"NSRangeException"` |
| [`objc:objc-exception-reason`](objc-objc-exception-reason.md) | `(objc:objc-exception-reason e)` | 理由 (なければ `nil`) |
| [`objc:objc-exception-object`](objc-objc-exception-object.md) | `(objc:objc-exception-object e)` | 送出されたオブジェクト |
| [`objc:ns-error-domain`](objc-ns-error-domain.md) | `(objc:ns-error-domain e)` | `"NSCocoaErrorDomain"` |
| [`objc:ns-error-code`](objc-ns-error-code.md) | `(objc:ns-error-code e)` | コード (整数) |
| [`objc:ns-error-description`](objc-ns-error-description.md) | `(objc:ns-error-description e)` | ローカライズされた説明 |
| [`objc:ns-error-object`](objc-ns-error-object.md) | `(objc:ns-error-object e)` | `NSError` |

## このパッケージ独自の関数

LispWorks の `OBJC` にない関数です。Lisp をメインスレッドで実行する関数、メモリブロックを受け渡す関数、Objective-C のオブジェクトをほかの値と見分ける関数があります。

| 関数 | 例 | 結果 |
|------|-----|------|
| [`objc:on-main`](objc-on-main.md) | `(objc:on-main (lambda () ...))` | メインスレッドで計算された関数の値 |
| [`objc:data`](objc-data.md) | `(objc:data buffer)` | バッファのバイト列を持つ `NSMutableData` |
| [`objc:bytes`](objc-bytes.md) | `(objc:bytes data)` | `NSData` の内容 (新しい `(unsigned-byte 8)` ベクタ) |
| [`objc:objectp`](objc-objectp.md) | `(objc:objectp x)` | オブジェクトポインタ (クラスを含む) なら `t` |
