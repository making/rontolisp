# Gray ストリーム (ユーザー定義ストリーム)

rontolisp は実装ネイティブの Gray サポートを公開する実際の処理系にならい、独自の小さな
Gray ストリーム拡張を同梱しています: ユーザークラスが `rontolisp:fundamental-*-stream`
基底クラスのいずれかを継承し `rontolisp:stream-*` 総称関数にメソッドを定義すると、
ストリームを取る組み込みはストリームハンドルの代わりにそのインスタンスを渡されたときに
これらのメソッドへディスパッチします。すべてのバックエンド
(インタープリタ、JVM、両 WASM) で動作します。

基底クラスは CL と同じ形の階層を成します: ルートに `fundamental-stream`、その下に
`fundamental-input-stream` / `fundamental-output-stream`、リーフとして
`fundamental-character-input-stream` / `fundamental-character-output-stream` /
`fundamental-binary-input-stream` / `fundamental-binary-output-stream`
(すべて `rontolisp` パッケージ)。

| 組み込み | ディスパッチ先 |
| --- | --- |
| `write-string`, `write-char`, `format` (ストリーム宛先) | `rontolisp:stream-write-string` (`write-char` は 1 文字の書き込みに脱糖) |
| `write-byte` | `rontolisp:stream-write-byte` |
| `read-byte` | `rontolisp:stream-read-byte` |
| `read-char` | `rontolisp:stream-read-char` |
| `read-line` | `rontolisp:stream-read-line` (デフォルトメソッドは `stream-read-char` をループ) |
| `listen` | `rontolisp:stream-listen` (デフォルトメソッドは `nil` を返す) |
| `read-sequence` / `write-sequence` | `rontolisp:stream-read-sequence` / `-write-sequence` (デフォルトメソッドは要素総称関数をループ) |
| `file-position` | `rontolisp:stream-file-position`。2 引数形式は `(setf rontolisp:stream-file-position)` ライタ総称関数を呼ぶ |

読み取り側のメソッドはストリーム終端でキーワード `:eof` を返します。組み込みはそれを通常の
`eof-error-p` / `eof-value` 契約に翻訳します。`stream-read-line`
は末尾の部分行をその行として返します — `:eof` は「文字がまったく残っていない」ことを意味します。

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

`file-position` プロトコル付きのバイナリ入力ストリーム:

```lisp
(defclass byte-source (rontolisp:fundamental-binary-input-stream)
  ((items :initarg :items) (pos :initform 0)))
(defmethod rontolisp:stream-read-byte ((s byte-source))
  (let ((items (slot-value s 'items)) (pos (slot-value s 'pos)))
    (if (>= pos (length items))
        :eof
        (progn (setf (slot-value s 'pos) (+ pos 1)) (nth pos items)))))
(defmethod rontolisp:stream-file-position ((s byte-source)) (slot-value s 'pos))
(defmethod (setf rontolisp:stream-file-position) (position (s byte-source))
  (setf (slot-value s 'pos) position))
(let ((in (make-instance 'byte-source :items (list 10 20 30))))
  (read-byte in)                          ; 10
  (file-position in)                      ; 1
  (file-position in 0)
  (list (read-byte in) (read-byte in nil :done))) ; => (10 20)
```

## trivial-gray-streams シム

ポータブルなライブラリは処理系独自のプロトコルではなく
[trivial-gray-streams](https://github.com/trivial-gray-streams/trivial-gray-streams)
に対して書かれています。rontolisp はポータブル API を上のプロトコルへ適合させる組み込みの
`trivial-gray-streams` ASDF システムを同梱しています
([システム](asdf-systems.md#built-in-shim-systems)を参照):
`trivial-gray-streams` パッケージはすべての基底クラス
(`trivial-gray-stream-mixin` も含む) とすべての総称関数をミラーします。
`stream-read-sequence` / `stream-write-sequence` `(stream sequence start end
&key)` や `stream-file-position` とその `(setf ...)` ライタも含まれます — jzon の
`:stream` ライタ API はこの仕組みで動いており、fast-io や circular-streams
の定義するクラス形状もそのままロードできます。

```lisp
(asdf:load-system "trivial-gray-streams")

(defclass upcase-stream (trivial-gray-streams:fundamental-character-output-stream)
  ((acc :initform "")))
(defmethod trivial-gray-streams:stream-write-string
    ((s upcase-stream) str &optional start end)
  (declare (ignore start end))
  (setf (slot-value s 'acc)
        (concatenate 'string (slot-value s 'acc) (string-upcase str)))
  str)
(defmethod trivial-gray-streams:stream-write-char ((s upcase-stream) c)
  (trivial-gray-streams:stream-write-string s (string c))
  c)
(let ((s (make-instance 'upcase-stream)))
  (write-string "hello" s)
  (write-char #\! s)
  (slot-value s 'acc)) ; => "HELLO!"
```

## 制限

- `rontolisp:stream-unread-char` はプロトコル総称関数として存在しますが、
  どの組み込みもディスパッチしません (`unread-char` は組み込みにありません)。
  `peek-char` も Gray インスタンスへディスパッチしません。
- 読み取り総称関数はプライマリ値のみを返します: `stream-read-line` に
  `(values line missing-newline-p)` のペアはなく、`:eof` が EOF の唯一のシグナルです。
- Gray インスタンスへの `listen` はインタープリタと JVM で動作します。Preview 1 WASM
  バックエンドはあらゆる `listen` 呼び出しをコンパイル時に拒否します
  (Gray とは無関係の既存プラットフォーム制限)。
- 境界キーワード付きの `(write-string s instance :start ... :end ...)`
  は境界をインスタンスへディスパッチしません。
- ディスパッチは組み込みの呼び出しサイトで起きます: 第一級値経由の
  `(funcall #'read-byte instance)` はコンパイルバックエンドではディスパッチしません。
