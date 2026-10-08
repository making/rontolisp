# ring.adapter.rontolisp/run-server

`[ring.adapter.rontolisp :refer [run-server]]` での `(run-server handler options)`

Ring の `handler`（1 引数関数、またはそれを指す var）を、ターゲットの受信トランスポートで
提供します（[Ring アダプター](ring.md)）。オプションマップは次のとおりです。

- `:port` -- 待ち受けるポート。既定は（`ring.adapter.jetty` と同じく）80。
- `:host`（または `:address`）-- バインドするアドレス。既定はすべてのインタフェース。
- `:join?` -- サーバーが止まるまでブロックするか。既定は true。`false` ならすぐに
  サーバーを返し、プログラムは先へ進みます。

この 3 つが意味を持つのは、プログラムがソケットを持つインタプリタと JVM だけです。war、
コンポーネント、リアクターはこれらを無視してすぐに戻ります。`:async? true` は拒否します。
3 引数の（非同期）ハンドラに対応する respond/raise の仕組みがないためです。サーバーは
プロセスにひとつで、コンパイル済みバックエンドでは 2 度目の `run-server` が最初のハンドラを
置き換えます。値としては 2 引数関数です。

```console
$ cat app.clj
(ns app (:require [ring.adapter.rontolisp :refer [run-server]]))
(defn handler [req] {:status 200 :body (str "hello " (:uri req))})
(run-server handler {:port 3000})
$ rontolisp app.clj -o app.wasm --component && wasmtime serve -W gc=y -W exceptions=y app.wasm
```
