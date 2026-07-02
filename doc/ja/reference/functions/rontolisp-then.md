# rontolisp:then

`(rontolisp:then value fn)`

`value` (通常はプロミス) とコールバックから新しいプロミスを導出します。
JavaScript の `Promise.prototype.then` がモデルです: 導出されたプロミスを
await すると、`value` を await した結果に `fn` (1 引数の関数) を適用します。
`then` は常にプロミスを返すのでチェーンでき、コールバックがプロミスを返した
場合は JavaScript と同様に平坦化されます。プロミスでない `value` も渡せて、
その場合は値がそのままコールバックに渡ります。

```lisp
(rontolisp:await
 (rontolisp:then (rontolisp:then 10 (lambda (x) (+ x 1)))
                 (lambda (x) (* x 3))))   ; => 33
```

典型的な使い方は [`rontolisp:fetch`](rontolisp-fetch.md) のレスポンスの変換です。

```console
(print (rontolisp:await
        (rontolisp:then (rontolisp:fetch "https://httpbin.org/get")
                        (lambda (r) (getf r :status)))))   ; 200
```

## 実行タイミング

コールバックは遅延実行されます: 導出されたプロミスが最初に
[await](rontolisp-await.md) されたときに走り、結果はメモ化されるため、何度
await してもコールバックは高々 1 回しか実行されません。この「await 時に実行」
というタイミングは 3 バックエンドすべてで同一です (WASM バックエンドには確定
時にコールバックを走らせるイベントループがありません)。一度も await されない
チェーンのコールバックは実行されません。

```lisp
(setq cnt 0)
(let ((p (rontolisp:then 1 (lambda (x) (setq cnt (+ cnt 1)) x))))
  (rontolisp:await p)
  (rontolisp:await p)
  cnt)   ; => 1
```

## エラー

失敗したベースのプロミス (例えば接続拒否) はコールバックをスキップします:
インタプリタと JVM では `await` から失敗がシグナルされ、WASM では失敗した
fetch が `nil` に解決されてコールバックに渡ります
([`rontolisp:await`](rontolisp-await.md) のエラーの節を参照)。エラー用
コールバック (`onRejected`) 引数はありません。

## バックエンドのサポート

[`rontolisp:await`](rontolisp-await.md) や
[`rontolisp:promisep`](rontolisp-promisep.md) と同様に、すべてのバックエンド・
すべての WASM モード (Preview 1 を含む) で動作します。
