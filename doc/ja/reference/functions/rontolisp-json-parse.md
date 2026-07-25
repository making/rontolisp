# rontolisp:json-parse

`(rontolisp:json-parse string)`

JSON ドキュメント文字列を Lisp の値にパースします。挙動は
[`com.inuoe.jzon`](../../guides/asdf-systems.md) ライブラリのデフォルトに
従います: JSON オブジェクトは文字列をキーとするハッシュテーブルに、配列は
ベクタになり、`true`/`false`/`null` はそれぞれ `t`・`nil`・シンボル `null`
になります。`rontolisp:json-parse` は jzon の軽量なサブセットなので、まずは
ここから始めて、後から形を変えずに jzon へ切り替えられます — ただし 1 点だけ
意図的な例外があり、それが後述の桁数の大きい整数のルールです。

サブセットの範囲を超えたら `com.inuoe.jzon` に切り替えてください: より高機能が
必要なとき(プリティ出力、ストリーミングライタ、`:replacer`、カスタム
シリアライズ)や、JSON のコードを他の Common Lisp 処理系に移植可能に
したいときです — `com.inuoe.jzon` は標準的なライブラリで、`rontolisp:json-*` は
rontolisp 上でのみ動作します。

```lisp
(gethash "name" (rontolisp:json-parse "{\"name\": \"rontolisp\", \"n\": 2}"))   ; => "rontolisp"
(gethash "b" (gethash "a" (rontolisp:json-parse "{\"a\": {\"b\": [1, true, null]}}")))   ; => #(1 T NULL)
```

## 値の対応

| JSON | Lisp |
|------|------|
| object | 文字列をキーとするハッシュテーブル(`equal` テスト) |
| array | ベクタ |
| string | 文字列(`\uXXXX` エスケープとサロゲートペアはデコードされます) |
| number | 整数。小数部・指数があるか 18 桁を超える場合は浮動小数点数 |
| `true` | `t` |
| `false` | `nil` |
| `null` | シンボル `null` |

```lisp
(rontolisp:json-parse "[1, 2.5, \"x\", false, null]")   ; => #(1 2.5 "x" nil null)
(rontolisp:json-parse "1e3")   ; => 1000.0
(rontolisp:json-parse "\"a\\u3042b\"")   ; => "aあb"
```

### jzon との唯一の非互換点

18 桁を超える整数はどのバックエンドでも浮動小数点数になります — ライブラリ
共通の規則で、パース結果は全バックエンドで同一になります。jzon は任意の桁数を正確な整数のまま保持するため、
ここが `rontolisp:json-parse` と `jzon:parse` が食い違う唯一の点です — 13 桁の
ミリ秒タイムスタンプは両者とも正確にパースされますが、19 桁の整数はここでは
浮動小数点数に、jzon では正確な整数にパースされます。それ以外はすべて同一に
ラウンドトリップします。

```lisp
(rontolisp:json-parse "1234567890123")   ; => 1234567890123
(floatp (rontolisp:json-parse "1234567890123456789"))   ; => T
```

## エラー

不正な JSON、および値の後に余分な文字が続く場合は、`json-parse` の
呼び出し時にエラーを通知します:

```console
> (rontolisp:json-parse "{\"a\": ")
Error: json-parse: unexpected end of input
> (rontolisp:json-parse "1 2")
Error: json-parse: unexpected trailing characters
```

## 制限事項

- JSON オブジェクトは常にハッシュテーブルにパースされるため、`{}`(空の
  ハッシュテーブル)は `false`/`nil`、空配列 `#()`、シンボル `null` の
  いずれとも区別されます — JavaScript とは異なり、これら 4 つが混同される
  ことはありません。
- WASM バックエンドでは絶対値が 2³¹ 以上の浮動小数点数はパースは正しく
  行われますが、*印字* できません(`print`/`princ-to-string` がトラップ
  します)。[WASM ガイド](../../compiling/wasm.md) を参照してください。

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 含む)で動作します:
パーサは rontolisp 自身で書かれており、使用時にプログラムへ組み込まれて
コンパイルされます。典型的な用途は [`rontolisp:fetch`](rontolisp-fetch.md) の
レスポンスボディのパースです:

```console
(print (gethash "url"
                (rontolisp:json-parse
                 (getf (rontolisp:await (rontolisp:fetch "https://httpbin.ik.am/get")) :body))))   ; "https://httpbin.ik.am/get"
```

逆の操作は [`rontolisp:json-stringify`](rontolisp-json-stringify.md) です。
