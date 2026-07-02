# rontolisp:json-stringify

`(rontolisp:json-stringify value)`

Lisp の値を JSON ドキュメント文字列にシリアライズします(JavaScript の
`JSON.stringify` に相当)。[`rontolisp:json-parse`](rontolisp-json-parse.md)
が生成する 2 つのオブジェクト表現 — キーワードをキーとする属性リスト
(plist)とハッシュテーブル(キーは文字列・シンボル・キーワード・数値) —
はどちらも JSON オブジェクトに戻ります。

```lisp
(rontolisp:json-stringify (list :name "rontolisp" :ok t :ver 1.5))   ; => "{\"name\":\"rontolisp\",\"ok\":true,\"ver\":1.5}"
(rontolisp:json-stringify (list 1 (list 2 3) nil))   ; => "[1,[2,3],null]"
(let ((h (make-hash-table)))
  (setf (gethash "x" h) (list 1 2))
  (rontolisp:json-stringify h))   ; => "{"x":[1,2]}"
```

## 値の対応

| Lisp | JSON |
|------|------|
| `nil` | `null` |
| `t` | `true` |
| 整数、浮動小数点数 | 数値 |
| 比 | 数値(`float` で変換) |
| 文字列 | 文字列(引用符・バックスラッシュ・制御文字はエスケープ) |
| キーワード、シンボル、文字 | 文字列(キーワードは先頭のコロンを除去) |
| キーワード/値が交互に並ぶ非空リスト | オブジェクト |
| その他のリスト | 配列 |
| ハッシュテーブル | オブジェクト(反復順序は未規定) |

それ以外(関数、ストリーム、プロミス、配列)はエラーを通知します。

```lisp
(rontolisp:json-stringify :key)   ; => "\"key\""
(rontolisp:json-stringify 3/2)   ; => "1.5"
(rontolisp:json-stringify "a\"b")   ; => ""a\"b""
```

JSON からパースした値は構造的にラウンドトリップします:

```lisp
(rontolisp:json-stringify
 (rontolisp:json-parse "{\"deep\": {\"list\": [{\"k\": \"v\"}, 2.5, true]}}"))   ; => "{"deep":{"list":[{"k":"v"},2.5,true]}}"
```

## 制限事項

- リストがオブジェクトとしてシリアライズされるのは、キーワード属性リスト
  (`(:a 1 :b 2)`)の形をしている場合だけです。キーワードと値が交互に並ぶ
  本物の配列は区別できず、オブジェクトになります。
- `nil` は常に `null` になります — 空の配列 `[]` や(plist からの)空の
  オブジェクト `{}` を出力する方法はありません。後者は空のハッシュテーブルで
  表現できます。
- 出力中のハッシュテーブルのキー順序はバックエンド依存(未規定)です
  (`maphash` と同様)。
- 非 ASCII 文字はそのまま出力されます(`\uXXXX` エスケープはしません)。
  これは正しい JSON です。
- WASM バックエンドでは絶対値が 2³¹ 以上の浮動小数点数はシリアライズ
  できません(浮動小数点数フォーマッタがトラップします)。
  [WASM ガイド](../../compiling/wasm.md) を参照してください。

## バックエンドサポート

[`rontolisp:json-parse`](rontolisp-json-parse.md) と同じく、すべての
バックエンド・すべての WASM モード(Preview 1 含む)で動作します:
シリアライザは rontolisp 自身で書かれており、使用時にプログラムへ
組み込まれてコンパイルされます。
