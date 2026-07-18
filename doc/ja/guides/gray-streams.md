# Gray ストリーム (ユーザー定義出力ストリーム)

rontolisp は実装ネイティブの Gray サポートを公開する実際の処理系にならい、独自の小さな
Gray ストリーム拡張を同梱しています: ユーザークラスが基底クラス
`rontolisp:fundamental-character-output-stream` を継承し、総称関数
`rontolisp:stream-write-string` (と `rontolisp:stream-write-char`)
にメソッドを定義すると、[`write-string`](../reference/functions/write-string.md)
/ [`write-char`](../reference/functions/write-char.md)
組み込みはストリームハンドルの代わりにそのインスタンスを渡されたときにこれらのメソッドへディスパッチします。すべてのバックエンド
(インタープリタ、JVM、両 WASM) で動作します。

```lisp
(defclass upcase-stream (rontolisp:fundamental-character-output-stream)
  ((acc :initform "")))
(defmethod rontolisp:stream-write-string ((s upcase-stream) str)
  (setf (slot-value s 'acc)
        (concatenate 'string (slot-value s 'acc) (string-upcase str)))
  str)
(let ((s (make-instance 'upcase-stream)))
  (write-string "hello" s)
  (write-char #\! s)
  (slot-value s 'acc)) ; => "HELLO!"
```

## trivial-gray-streams シム

ポータブルなライブラリは処理系独自のプロトコルではなく
[trivial-gray-streams](https://github.com/trivial-gray-streams/trivial-gray-streams)
に対して書かれています。rontolisp はポータブル API を上のプロトコルへ適合させる組み込みの
`trivial-gray-streams` ASDF システムを同梱しています
([システム](asdf-systems.md#built-in-shim-systems)を参照):
`trivial-gray-streams:fundamental-character-output-stream` を継承し
`trivial-gray-streams:stream-write-char`/`-string`
にメソッドを定義したクラスは、組み込みの書き込みをそのまま受け取ります — jzon の
`:stream` ライタ API はこの仕組みで動いています。

## 制限

- 出力側のみ: `stream-write-char` と `stream-write-string` が存在します。完全な
  Gray ストリームの入力側総称関数 (`stream-read-char` など) はありません。
- `write-char` は 1 文字の `write-string` に脱糖されるため、
  `rontolisp:stream-write-string` の実装だけで十分です
  (シムの委譲メソッドは両方をルーティングします)。
- `format` ファミリは Gray インスタンスへディスパッチしません —
  レンダリング済みの文字列を `write-string` で書いてください。
- 境界キーワード付きの `(write-string s instance :start ... :end ...)`
  は境界をインスタンスへディスパッチしません。
