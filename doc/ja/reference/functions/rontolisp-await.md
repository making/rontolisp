# rontolisp:await

`(rontolisp:await promise)`

[`rontolisp:fetch`](rontolisp-fetch.md) が返したプロミスが確定するまでブロックし、
レスポンスのプロパティリスト
`(:status <integer> :body <string> :headers <alist>)` を返します。`:headers` は
レスポンスヘッダの `(name . value)` ペアの連想リストです。

```lisp
(let ((p (rontolisp:fetch "https://httpbin.org/get")))
  (getf (rontolisp:await p) :status))   ; => 200
```

確定済みのプロミスは何度でも await でき、複数のプロミスを任意の順序で await
できます。各 `await` は、そのプロミスに対応するリクエストの結果を返します。

```console
(let ((p1 (rontolisp:fetch "http://example.com/a"))
      (p2 (rontolisp:fetch "http://example.com/b")))
  (print (getf (rontolisp:await p2) :status))
  (print (getf (rontolisp:await p1) :status)))
```

## エラー

リクエストの失敗 (例えば接続拒否) はここで顕在化します — `fetch` の時点ではなく、
JavaScript の `await` の reject と同じタイミングです。

- **インタプリタ / JVM**: `await` が失敗内容を示すエラーを発生させます。
- **WASM**: `await` は `nil` を返します (このバックエンドの nil-on-failure 規約)。
  `nil` のプロミス (開始できなかった fetch) を await した場合も `nil` になります。

プロミスでない値を渡すと、インタプリタではエラーになります。コンパイルされる
バックエンドはプロミスを不透明なハンドルとして扱い、チェックしません。

## バックエンドのサポート

[`rontolisp:fetch`](rontolisp-fetch.md) と同じです: インタプリタ、JVM、および
`--component` モードの WASM (非同期フラグに加えて `-S http=y` を付けて実行) で
動作し、WASM Preview 1 モードではコンパイルエラーです。ブラウザ プレイグラウンド
ではプロミスは `fetch` が返った時点で確定済みのため、`await` は単に値を取り出します。
