# rontolisp:json-parse

`(rontolisp:json-parse string &optional representation)`

JSON ドキュメント文字列を Lisp の値にパースします(JavaScript の
`JSON.parse` に相当)。デフォルトでは JSON オブジェクトはキーワードをキーとする
属性リスト(plist)になり、[`rontolisp:fetch`](rontolisp-fetch.md) の結果と
同じく `getf` で読み出せます。第 2 引数に `:hash-table` を渡すとハッシュ
テーブルになり、オブジェクトのキーは文字列のまま保持されます(この表現は
ネストしたオブジェクトにも再帰的に適用されます)。

```lisp
(rontolisp:json-parse "{\"name\": \"rontolisp\", \"n\": 2}")   ; => (:name "rontolisp" :n 2)
(getf (rontolisp:json-parse "{\"a\": {\"b\": [1, true, null]}}") :a)   ; => (:b (1 t nil))
(let ((h (rontolisp:json-parse "{\"content-type\": \"text/html\"}" :hash-table)))
  (gethash "content-type" h))   ; => "text/html"
```

## 値の対応

| JSON | Lisp |
|------|------|
| オブジェクト | キーワードをキーとする plist(デフォルト)、または文字列をキーとするハッシュテーブル(`:hash-table`) |
| 配列 | リスト |
| 文字列 | 文字列(`\uXXXX` エスケープとサロゲートペアはデコードされます) |
| 数値 | 整数。小数部・指数があるか 10 桁以上の場合は浮動小数点数 |
| `true` | `t` |
| `false` | `nil` |
| `null` | `nil` |

```lisp
(rontolisp:json-parse "[1, 2.5, \"x\", false, null]")   ; => (1 2.5 "x" nil nil)
(rontolisp:json-parse "1e3")   ; => 1000.0
(rontolisp:json-parse "\"a\\u3042b\"")   ; => "aあb"
```

10 桁以上の整数はどのバックエンドでも浮動小数点数になり、WASM バックエンドの
`i31` 整数範囲に収まります(13 桁のミリ秒タイムスタンプはどこでも
`1.234567890123E12` としてパースされます):

```lisp
(floatp (rontolisp:json-parse "1234567890123"))   ; => t
```

## エラー

不正な JSON、値の後の余分な文字、未知の表現引数は `json-parse` の呼び出し時に
エラーを通知します:

```console
> (rontolisp:json-parse "{\"a\": ")
Error: json-parse: unexpected end of input
> (rontolisp:json-parse "1" :alist)
Error: json-parse: the object representation must be :plist or :hash-table
```

plist 表現ではオブジェクトのキーが単一のキーワードとして読み戻せる必要が
あります。空白などシンボルを区切る文字を含むキーは `:hash-table` を促す
エラーになります(`:hash-table` では任意のキー文字列が使えます):

```lisp
(let ((h (rontolisp:json-parse "{\"a b\": 1}" :hash-table)))
  (gethash "a b" h))   ; => 1
```

## 制限事項

- plist 表現では `{}`・`false`・`null` はいずれも `nil` にパースされます
  (空の JSON 配列 `[]` は空リスト、すなわち `nil` です)。空オブジェクトを
  区別する必要がある場合は `:hash-table` を使ってください。
- `nil`・`t`・キーワードは plist の値としても有効なので、パース結果の plist が
  一意に解釈できるのはドキュメントの形が分かっている場合に限られます —
  JavaScript のオブジェクトと同じく、JSON のラウンドトリップは型ではなく形で
  成立します。
- WASM バックエンドでは絶対値が 2³¹ 以上の浮動小数点数はパースは正しく
  行われますが、*印字* できません(`print`/`princ-to-string` がトラップ
  します)。[WASM ガイド](../../compiling/wasm.md) を参照してください。

## バックエンドサポート

すべてのバックエンド・すべての WASM モード(Preview 1 含む)で動作します:
パーサは rontolisp 自身で書かれており、使用時にプログラムへ組み込まれて
コンパイルされます。典型的な用途は [`rontolisp:fetch`](rontolisp-fetch.md) の
レスポンスボディのパースです:

```console
(print (getf (rontolisp:json-parse
              (getf (rontolisp:await (rontolisp:fetch "https://httpbin.org/get")) :body))
             :url))   ; "https://httpbin.org/get"
```

逆の操作は [`rontolisp:json-stringify`](rontolisp-json-stringify.md) です。
