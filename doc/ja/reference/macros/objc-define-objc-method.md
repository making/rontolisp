# objc:define-objc-method

`(objc:define-objc-method (name result-type [result-style]) ((object-var class-name [pointer-var]) (arg-var arg-type [arg-style])*) form*)`

`objc:define-objc-class` で定義したクラスのインスタンスメソッド `name` (コロンを含むセレクタ) を定義します。`form` が本体です。`object-var` にはレシーバの `objc:standard-objc-object` (なければそのポインタ)、`pointer-var` にはポインタが束縛されます。引数と結果は宣言した FLI 型で変換され、形の制限はありません。整数、`:float` / `:double`、`t` / `nil` になる `objc:objc-bool` / `:boolean`、`objc:objc-object-pointer`、`objc:sel`、`objc:objc-class`、そして構造体 (`objc:invoke` が使う Lisp の値) を受け取れます。オブジェクト引数の `arg-style` が `string` なら `NSString` を文字列に、`array` なら `NSArray` をベクタに、`(array style)` なら要素も変換し、`:foreign` なら生の値を渡します。オブジェクトとして返した文字列やベクタは `NSString` / `NSArray` になります。キーワードでない `result-style` は、本体が埋める新しい構造体に束縛する変数の名前です (`cocoa:set-ns-rect*` で埋めます)。本体の中では `(objc:current-super)` でスーパークラスに送れます。本体のエラーは表示され、メソッドはゼロ値を返します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。定義にランタイムは要りません。Objective-C 側はランタイムを開いたとき (`objc:ensure-objc-initialized` か最初の送信) に作られます。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (define-objc-class my-object ()
          ((slot1 :initarg :slot1 :initform nil))
          (:objc-class-name "MyObject"))
MY-OBJECT
MY-APP> (define-objc-method ("areaOfWidth:height:" (:unsigned :int))
            ((self my-object)
             (width (:unsigned :int))
             (height (:unsigned :int)))
          (* width height))
"areaOfWidth:height:"
MY-APP> (invoke (alloc-init-object "MyObject") "areaOfWidth:height:" 6 7)
42
```
