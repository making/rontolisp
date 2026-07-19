# rontolisp:json-stringify

`(rontolisp:json-stringify value)`

Lisp の値を JSON ドキュメント文字列にシリアライズします。挙動は
[`com.inuoe.jzon`](../../guides/asdf-systems.md) ライブラリのデフォルトに
従い、[`rontolisp:json-parse`](rontolisp-json-parse.md) の逆になります:
ハッシュテーブルはオブジェクトに、ベクタまたはリストは配列になり、
`nil`・`t`・シンボル `null` はそれぞれ `false`・`true`・`null` になります。
jzon の軽量なサブセットなので、形を変えずに jzon へ切り替えられます。

サブセットの範囲を超えたら `com.inuoe.jzon` に切り替えてください: より高機能が
必要なとき(プリティ出力、ストリーミングライタ、`:replacer`、カスタム
シリアライズ)や、JSON のコードを他の Common Lisp 処理系に移植可能に
したいときです — `com.inuoe.jzon` は標準的なライブラリで、`rontolisp:json-*` は
rontolisp 上でのみ動作します。

```lisp
(rontolisp:json-stringify (vector 1 2 3))   ; => "[1,2,3]"
(rontolisp:json-stringify (list 1 (list 2 3) nil))   ; => "[1,[2,3],false]"
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "name" h) "rontolisp")
  (rontolisp:json-stringify h))   ; => "{"name":"rontolisp"}"
```

## 値の対応

| Lisp | JSON |
|------|------|
| `nil` | `false` |
| `t` | `true` |
| シンボル `null` | `null` |
| 整数、浮動小数点数 | number |
| 比 | number(`float` で変換) |
| string | 文字列(引用符・バックスラッシュ・制御文字はエスケープ) |
| ベクタ、リスト | array |
| ハッシュテーブル | オブジェクト(シンボルのキーは、小文字を含まない限り小文字化されます) |
| CLOS インスタンス(`standard-object`) | オブジェクト(各スロット名 → その値、定義順) |
| キーワード、シンボル、文字 | 文字列 |

それ以外(関数、ストリーム、多次元配列)はエラーを通知します。

ハッシュテーブルと CLOS インスタンスはどちらもオブジェクトとして
シリアライズされるため、オブジェクトを構築する方法は 2 通りあります —
動的なキーにはハッシュテーブル(多くは
[`rontolisp:plist-hash-table`](rontolisp-plist-hash-table.md) 経由)、
固定された形にはクラスです。スロット自体がハッシュテーブル(ネストした
オブジェクト)、リストやベクタ(配列)、あるいは別のインスタンスを保持して
いても構いません:

```lisp
(defclass response () ((status :initarg :status) (body :initarg :body)))
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "content-type" h) "text/plain")
  (rontolisp:json-stringify (make-instance 'response :status 200 :body h)))   ; => "{"status":200,"body":{"content-type":"text/plain"}}"
```

```lisp
(rontolisp:json-stringify :key)   ; => ""key""
(rontolisp:json-stringify 3/2)   ; => "1.5"
(rontolisp:json-stringify "a\"b")   ; => ""a\"b""
```

JSON からパースした値は構造的にラウンドトリップします:

```lisp
(rontolisp:json-stringify
 (rontolisp:json-parse "{\"deep\": {\"list\": [{\"k\": \"v\"}, 2.5, true]}}"))   ; => "{"deep":{"list":[{"k":"v"},2.5,true]}}"
```

## 制限事項

- `nil` は `false` にシリアライズされ、空リストは `nil` なので、空の配列には
  `#()`(空のベクタ)を、空のオブジェクト `{}` には空のハッシュテーブルを
  使ってください。
- リストは常に配列になります — JSON オブジェクトにはハッシュテーブルを
  構築してください(jzon は alist/plist の判定を廃止しており、このサブセットも
  同様です)。[`rontolisp:plist-hash-table`](rontolisp-plist-hash-table.md) は
  キーワードのプロパティリストを、
  [`rontolisp:alist-hash-table`](rontolisp-alist-hash-table.md) は連想リストを、
  そのハッシュテーブルに変換します。
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
