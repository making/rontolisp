# usocket:host-to-hostname usocket:get-host-by-name

`(usocket:host-to-hostname host)` -- `(usocket:get-host-by-name name)`

usocket のホスト指定子を扱う 2 つの関数です。`host-to-hostname` は上流が受け付ける
指定子をすべてホスト名・ドット区切り文字列として描画します: `nil` はワイルドカード
ホスト `"0.0.0.0"`、文字列はそのまま、4 要素のベクタ（またはオクテット 4 個のリスト）
とホストバイトオーダの 32 ビット整数はドット区切り表記になります。

```lisp
(list (usocket:host-to-hostname nil)
      (usocket:host-to-hostname "example.com")
      (usocket:host-to-hostname #(192 168 0 1))
      (usocket:host-to-hostname 2130706433)) ; => ("0.0.0.0" "example.com" "192.168.0.1" "127.0.0.1")
```

`get-host-by-name` は **ライト版** です: rontolisp にはどのバックエンドにも名前解決の
プリミティブがないため、上流のようにベクタ 4 要素へ解決するのではなく、引数を
`host-to-hostname` で描画して返します。これによりライブラリが使う「正規化してから
渡す」という連鎖 -- `(usocket:host-to-hostname (usocket:get-host-by-name address))` --
は与えられたアドレスに対する恒等写像となり、そのアドレスが最終的に届く
[`usocket:socket-connect`](usocket-socket-connect.md) /
[`usocket:socket-listen`](usocket-socket-listen.md) の呼び出しが実際の解決を行います
（インタプリタと JVM ではネイティブに、WASM では IPv4 リテラルのみ）。

```lisp
(usocket:host-to-hostname (usocket:get-host-by-name "127.0.0.1")) ; => "127.0.0.1"
```

## バックエンドサポート

4 つすべてのバックエンドで動作し、どこでも同じ答えを返します: どちらもシムの中の
純粋な Lisp でソケットを開かないため、usocket API の他の部分とは異なり WASM
Preview 1 でも利用できます。
