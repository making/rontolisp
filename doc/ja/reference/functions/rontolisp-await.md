# rontolisp:await

`(rontolisp:await value)`

プロミスを解決します: プロミスが確定するまでブロックし、その値を返します。
プロミスでない値はそのまま返されます — JavaScript でプロミス以外を `await`
した場合と同じで、「プロミスかもしれない値」に一律に `await` を適用できます。

```lisp
(rontolisp:await 42)                                          ; => 42
(rontolisp:await (rontolisp:then 20 (lambda (x) (+ x 1))))    ; => 21
```

[`rontolisp:fetch`](rontolisp-fetch.md) のプロミスの場合、確定値はレスポンスの
プロパティリスト
`(:status <integer> :body <string> :headers <alist>)` です。`:headers` は
レスポンスヘッダの `(name . value)` ペアの連想リストです。

```console
(let ((p (rontolisp:fetch "https://httpbin.org/get")))
  (getf (rontolisp:await p) :status))   ; 200
```

確定済みのプロミスは何度でも await できます (結果はメモ化されるため、
[`rontolisp:then`](rontolisp-then.md) のコールバックは高々 1 回しか実行されません)。
複数のプロミスを任意の順序で await でき、各 `await` はそのプロミスに対応する
リクエストの結果を返します。

```console
(let ((p1 (rontolisp:fetch "http://example.com/a"))
      (p2 (rontolisp:fetch "http://example.com/b")))
  (print (getf (rontolisp:await p2) :status))
  (print (getf (rontolisp:await p1) :status)))
```

## エラー

リクエストの失敗 (例えば接続拒否) はここで顕在化します — `fetch` の時点ではなく、
JavaScript の `await` の reject と同じタイミングです。失敗したプロミスにチェーン
された [`rontolisp:then`](rontolisp-then.md) のコールバックはスキップされます。

- **インタプリタ / JVM**: `await` が失敗内容を示すエラーを発生させます。
- **WASM**: `await` は `nil` を返します (このバックエンドの nil-on-failure 規約)。
  `nil` のプロミス (開始できなかった fetch) を await した場合も `nil` になります。
  チェーンされたコールバックにはこの `nil` が渡されます (このバックエンドでは
  失敗と `nil` 値を区別できません)。

## バックエンドのサポート

`await` 自体は汎用のプロミス操作で、すべてのバックエンド・すべての WASM モード
(Preview 1 を含む) で動作します — `--component` モード限定なのは
[`rontolisp:fetch`](rontolisp-fetch.md) だけです。ブラウザ プレイグラウンドでは、
`await` はブラウザがレスポンスを届けるまでインタプリタのワーカーをブロックします。
