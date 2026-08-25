# objc:define-class

`(objc:define-class "Name" "Superclass" methods &optional protocols)`

メソッドが Lisp 関数であるクラスを登録します。`methods` は `("selector:" function)` のペアのリストで、各関数は最初に receiver、続いてメソッド自身の引数を受け取ります。メソッドの型はスーパークラスがそのセレクタを宣言していればそこから、そうでなければ採用したプロトコル (`protocols`、名前のリスト) から取られ、どちらにもなければ target/action の形 (結果なし、コロンごとに 1 つのオブジェクト引数) がデフォルトです。再定義するとメソッドが束縛し直されます。クラスを返します。macOS 専用の `objc` パッケージの一部です。`java -jar` のインタプリタと `rontolisp` ネイティブバイナリで動作し、コンパイル済み `.class` や `.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (defvar *cls*
    (objc:define-class "MyTarget" "NSObject"
      (list (list "invoke:" (lambda (self sender) (print sender) nil)))))
> (defvar *target* (objc:send (objc:send *cls* "alloc") "init"))
> (objc:send button "setTarget:" *target*)
> (objc:send button "setAction:" "invoke:")
```

コールバックの形は閉じた集合です (引数なし、オブジェクト引数 1 つまたは 2 つ、オブジェクト引数 1 つで `BOOL`・オブジェクト・整数を返す)。コールバック内のエラーはシグナルされず表示されます。
