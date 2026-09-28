# objc:define-objc-class

`(objc:define-objc-class name (superclass*) (slot-spec*) class-option*)`

`name` を、Objective-C クラスを実装する CLOS クラスとして定義します。`defclass` のオプションに加えて、作る Objective-C クラスの名前 `(:objc-class-name "Name")`、Lisp のスーパークラスが Objective-C クラスを実装していないときのスーパークラス `(:objc-superclass-name "Name")` (既定は `NSObject`)、`objc:objc-object-var-value` で読むインスタンス変数 `(:objc-instance-vars ("name" type)...)`、採用するプロトコル `(:objc-protocols "Name"...)` を受け取ります。`superclass` を書かなければ `objc:standard-objc-object` を継承します。Objective-C クラスを名付けず継承もしないクラスはミックスインで、そのメソッドは Objective-C クラスを名付けるサブクラスそれぞれに入ります。同じプロセスで以前に定義した同名のクラス (評価し直した定義) はメソッドを置き換えて再利用し、Lisp が定義していないクラスは拒否します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。定義にランタイムは要りません。Objective-C 側はランタイムを開いたとき (`objc:ensure-objc-initialized` か最初の送信) に作られます。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

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
