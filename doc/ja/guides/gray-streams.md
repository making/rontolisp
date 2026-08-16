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
| `write-char` | `rontolisp:stream-write-char` |
| `write-string`, `format` (ストリーム宛先) | `rontolisp:stream-write-string` |
| `princ`, `prin1`, `print` | 描画したテキストの `rontolisp:stream-write-string` (`print` はその後 `stream-terpri`) |
| `terpri` | `rontolisp:stream-terpri` (デフォルトメソッドは `stream-write-char` で改行を書く) |
| `fresh-line` | `rontolisp:stream-fresh-line` (デフォルトメソッド: `stream-start-line-p` でなければ `stream-terpri`) |
| `write-line` | `rontolisp:stream-write-string` のあと `rontolisp:stream-terpri` |
| `force-output` / `finish-output` / `clear-output` | `rontolisp:stream-force-output` / `-finish-output` / `-clear-output` (デフォルトメソッドは `nil` を返す) |
| `close` | `t` を返す — 後述 |
| `write-byte` | `rontolisp:stream-write-byte` |
| `read-byte` | `rontolisp:stream-read-byte` |
| `read-char` | `rontolisp:stream-read-char` |
| `read-char-no-hang` | `rontolisp:stream-read-char-no-hang` (デフォルトメソッドは `stream-read-char` そのもの) |
| `peek-char` | `rontolisp:stream-peek-char` (デフォルトメソッドは 1 文字読んで `stream-unread-char` で押し戻す)。`peek-type` の読み飛ばし形式はこれをループします |
| `unread-char` | `rontolisp:stream-unread-char` (デフォルトメソッドはプロトコルが持つ 1 文字ぶんの押し戻しスロットに保管) |
| `read-line` | `rontolisp:stream-read-line` (デフォルトメソッドは `stream-read-char` をループ) |
| `listen` | `rontolisp:stream-listen` (デフォルトメソッドは `nil` を返す) |
| `open-stream-p` | `t` を返します -- `close` と同じく、プログラムが所有できる名前です |
| `stream-element-type` | `character`、バイナリ基底クラスなら `(unsigned-byte 8)` -- プログラムが所有できる名前です |
| `read-sequence` / `write-sequence` | `rontolisp:stream-read-sequence` / `-write-sequence` (デフォルトメソッドは要素総称関数をループ) |
| `file-position` | `rontolisp:stream-file-position`。2 引数形式は `(setf rontolisp:stream-file-position)` ライタ総称関数を呼ぶ |

文字出力ストリームは **`stream-write-char` か `stream-write-string` のどちらか一方を
定義すれば十分です**。それぞれ他方を使ったデフォルトメソッドを持つため、書いたほうから
残りの出力プロトコルが組み上がります (どちらも定義しないのが唯一の壊れた形で、2 つの
デフォルトが互いを呼び合います)。

さらに 2 つ、対応する組み込みは持たないものの行単位の演算子が参照する総称関数があります:
`rontolisp:stream-line-column` はストリームの現在の桁位置を返し、桁位置を追跡しない
ストリームでは `nil` (デフォルト) を返します。`rontolisp:stream-start-line-p` はそこから
答えます。桁位置のないストリームは行頭かどうかを判断できないため、`fresh-line` は常に
改行を書き込みます。`rontolisp:stream-advance-to-column` は直接呼ぶプログラムのために
プロトコルを補完します。

Gray ストリームを閉じると `t` を返し、他には何もしません — 解放するものがないからです。
解放するものが**ある**ストリームは CL 本来の綴り、すなわち `close` 自身へのメソッドを
書きます:

```lisp
(defclass closing-stream (rontolisp:fundamental-character-output-stream)
  ((acc :initform "") (openp :initform t)))
(defmethod rontolisp:stream-write-char ((s closing-stream) c)
  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) (string c)))
  c)
(defmethod close ((s closing-stream) &key abort)
  (declare (ignore abort))
  (setf (slot-value s 'openp) nil)
  t)
(let ((s (make-instance 'closing-stream)))
  (write-string "bye" s)
  (list (close s) (slot-value s 'openp))) ; => (T NIL)
```

このメソッドはすべてのバックエンドでディスパッチします。これを定義したプログラムが
`close` を完全に所有し、Gray のデフォルトは道を譲ります。

文字**入力**ストリームが定義すべきなのは **`stream-read-char` ただ 1 つ**です
(バイナリなら `stream-read-byte`)。読み取り側の残りはすべてその上に書かれています:
`stream-read-line` と `stream-read-sequence` はそれをループし、
`stream-read-char-no-hang` はそれ自体で、`stream-peek-char` は 1 文字読んでから
`stream-unread-char` で押し戻します。`stream-unread-char` の既定メソッドは、その文字を
プロトコルが持つ 1 文字ぶんの押し戻しスロットに保管します。自前でソースを巻き戻せる
クラスは `stream-unread-char` を定義して押し戻しを自分で所有します — そのときスロットは
一度も書かれません。

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

`stream-write-char` だけを定義し、桁位置を追跡することで `fresh-line` が改行の要否を
判断できるストリーム:

```lisp
(defclass column-stream (rontolisp:fundamental-character-output-stream)
  ((acc :initform "") (col :initform 0)))
(defmethod rontolisp:stream-write-char ((s column-stream) c)
  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) (string c)))
  (setf (slot-value s 'col) (if (char= c #\Newline) 0 (+ (slot-value s 'col) 1)))
  c)
(defmethod rontolisp:stream-line-column ((s column-stream)) (slot-value s 'col))
(let ((s (make-instance 'column-stream)))
  (princ "one" s)
  (fresh-line s)      ; 桁位置 3 -> 改行を書く
  (fresh-line s)      ; 桁位置 0 -> 何も書かない
  (write-line "two" s)
  ;; 答えを 1 行に収めるため改行を / で表示
  (substitute #\/ #\Newline (slot-value s 'acc))) ; => "one/two/"
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

`stream-read-char` だけを定義した文字入力ストリームを、読み取りプロトコルの残りで
駆動する例です:

```lisp
(defclass text-source (rontolisp:fundamental-character-input-stream)
  ((text :initarg :text) (pos :initform 0)))
(defmethod rontolisp:stream-read-char ((s text-source))
  (let ((text (slot-value s 'text)) (pos (slot-value s 'pos)))
    (if (>= pos (length text))
        :eof
        (progn (setf (slot-value s 'pos) (+ pos 1)) (char text pos)))))
(let ((in (make-instance 'text-source :text "ab  cd")))
  (list (peek-char nil in)                ; 消費せずに覗く
        (read-char in)
        (progn (unread-char #\a in) (read-char in))
        (read-char-no-hang in)
        (peek-char t in)                  ; 空白を読み飛ばす
        (read-line in)
        (open-stream-p in)
        (stream-element-type in))) ; => (#\a #\a #\a #\b #\c "cd" T CHARACTER)
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
&key)`、`stream-file-position` とその `(setf ...)` ライタ、そして出力系の
`stream-line-column` / `stream-start-line-p` / `stream-terpri` /
`stream-fresh-line` / `stream-advance-to-column` / `stream-force-output` /
`stream-finish-output` / `stream-clear-output` も含まれます — jzon の
`:stream` ライタ API はこの仕組みで動いており、fast-io や circular-streams
の定義するクラス形状もそのままロードできます。デフォルトは rontolisp プロトコルと同じ
ものなので、`trivial-gray-streams:stream-write-char` だけを定義したポータブルなクラスでも
上のすべての演算子に応答します。

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

- `rontolisp:stream-advance-to-column` はプロトコル総称関数として存在しますが、
  どの組み込みもディスパッチしません (`format` の `~T` は桁位置を参照しません)。
- プロトコルの押し戻しは 1 ストリームにつき 1 文字だけを保持します。これは CL が
  `unread-char` に約束している範囲そのものです。プロトコル自身のデフォルトを通る読み取りは
  すべてこれを消費しますが、`stream-read-line` や `stream-read-sequence` を丸ごと
  オーバーライドしたクラスは押し戻しを読み飛ばすので、そうしたクラスは
  `stream-unread-char` も定義してください。
- `input-stream-p` / `output-stream-p` はディスパッチしません。Gray インスタンスは
  どちらにも `nil` を返します。
- ストリーム**ハンドル**への `unread-char` は通知します — ハンドル経由の読み取りが
  消費できる押し戻しは、どのバックエンドにもありません。
- 読み取り総称関数はプライマリ値のみを返します: `stream-read-line` に
  `(values line missing-newline-p)` のペアはなく、`:eof` が EOF の唯一のシグナルです。
- Gray インスタンスへの `listen` はインタープリタと JVM で動作します。Preview 1 WASM
  バックエンドはあらゆる `listen` 呼び出しをコンパイル時に拒否します
  (Gray とは無関係の既存プラットフォーム制限)。
- 境界キーワード付きの `(write-string s instance :start ... :end ...)`
  は境界をインスタンスへディスパッチしません。
- ディスパッチは組み込みの呼び出しサイトで起きます: 第一級値経由の
  `(funcall #'read-byte instance)` はコンパイルバックエンドではディスパッチしません。
