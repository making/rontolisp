# ソースコードのフォーマット

`rontolisp format` はLispのソースファイルをその場で再インデントします。ファイルまたは
ディレクトリを指定すると、その配下のすべての `.lisp` / `.asd` ファイルが唯一の正規の
レイアウトに書き換えられます。インデントについて考えたりレビューしたりする必要が
なくなります。

```bash
rontolisp format app.lisp          # one file
rontolisp format src/              # every .lisp / .asd under src/
rontolisp format src/ tests/       # several paths
```

変わるのは空白だけです。トークンは書かれたとおりに再現されます（大文字小文字も
そのままなので `Foo` は `Foo` のままです）。文字列・文字リテラル・ブロックコメント・
`#+`/`#-` ガードもそのまま複写されます。フォーマット後のファイルはまったく同じ
プログラムとして読めます。マクロ展開・評価・ロードは一切行わないので、依存ライブラリ
が入っていなくてもフォーマットできます。

すでにフォーマット済みのファイルは書き換えもされないため、ツリー全体に対して何度でも
安全に実行できます。

## オプション

| オプション | 意味 |
| --- | --- |
| `--check` | 何も書き込まない。フォーマットされていないファイルを一覧し、1つでもあれば終了コード `1` を返す。 |
| `--stdout` | ファイルではなく標準出力に結果を書く（対象は1ファイルのみ）。 |
| `--width=N` | 折り返す右マージン。既定は `80`。 |
| `-h`, `--help` | このコマンドのヘルプを表示する。 |

パスの代わりに `-` を渡すと標準入力を標準出力にフォーマットします。エディタの
「バッファをフォーマット」コマンドが必要とする形です。

```bash
echo '(let ((a 1)(b 2))(+ a b))' | rontolisp format -
```

```
(let ((a 1) (b 2)) (+ a b))
```

`--check` は何も書かずにフォーマット漏れがあれば失敗するので、CIのゲートを1行で
書けます。

```bash
rontolisp format --check src/ || { echo "run: rontolisp format src/"; exit 1; }
```

## レイアウトの見え方

マージン内に収まるフォームは1行になります。収まらないフォームは、その演算子に応じて
改行されます。

定義フォームは名前とラムダリストを1行目に置き、本体を2桁インデントします。

```lisp
(defun fizzbuzz (n)
  (cond ((zerop (mod n 15)) "FizzBuzz")
        ((zerop (mod n 3)) "Fizz")
        ((zerop (mod n 5)) "Buzz")
        (t (write-to-string n))))
; => FIZZBUZZ
```

`cond` の節は最初の節に揃えられます。`if` は2つの分岐をテストの下に置くので、本体では
なく対になって読めます。

```lisp
(let ((threshold (* 10 10)) (small-label "small") (measured (list 1 2 3 4 5 6)))
  (if (< (length measured) threshold)
      (list small-label (length measured))
      (list "large" (length measured))))
; => ("small" 6)
```

`let` の束縛は、`let` 自身の行に収まらなくなった時点で最初の束縛に揃えられます。

```lisp
(let ((numbers (list 3 1 4 1 5 9 2 6))
      (sorted (sort (list 3 1 4 1 5) #'<))
      (total (+ 1 2 3 4 5)))
  (list (length numbers) sorted total))
; => (8 (1 1 3 4 5) 15)
```

関数呼び出しは引数を第1引数の下に揃え、`:keyword value` のオプションは対のまま
1行ずつに置きます。

```console
(with-open-file (out "report.txt"
                     :direction :output
                     :if-exists :supersede
                     :if-does-not-exist :create)
  (write-line "done" out))
```

`loop` は節ごとに1行を与え、最初の節に揃えます。

```lisp
(loop for i from 1 to 10
      when (evenp i)
      collect (* i i) into squares
      finally (return squares))
; => (4 16 36 64 100)
```

### 本体が2フォーム以上なら必ず複数行

2つ以上のフォームからなる本体は順番に実行される「文の列」なので、どれほど短くても
1行ずつになります。C系言語のフォーマッタが2つの文を1行に置かないのと同じ理由です。
ちょうど1フォームの本体はヘッダの行を共有できます。

```lisp
(defun double (x) (* 2 x))
; => DOUBLE
```

一方、2フォームの本体は収まっても共有しません。

```lisp
(defun report (x)
  (print x)
  (terpri))
; => REPORT
```

これは出力を安定させる仕組みでもあります。リネームで2文字短くなったからといって、
2フォームの本体が黙って1行にまとまることはありません。

### コメント

行頭から始まっていたコメントは行頭のまま、周囲のコードのインデントに置かれます。
コードの後ろに付いていたコメントはその行に残り、連続する行の行末コメントは列として
揃えられます。

```console
(setq width 80)      ; the right margin
(setq body-indent 2) ; body indentation
(setq tabs nil)      ; spaces only
```

### 空行

空行はLispのソースが持つ唯一の段落区切りなので、置いた場所にそのまま残ります
（トップレベルのフォーム間だけでなく本体の中でも同じです）。連続する空行は1行に
まとめられ、空行が追加されることはありません。

## 制限

行コメントと文字列リテラルは折り返されません。その中身は書いた人のものであって、
フォーマッタのものではないからです。したがって、どうしても分割できない行
（長い文字列、これ以上短くできない深い入れ子）はマージンを超えることがあります。

フォーマッタが知らないマクロは名前から推測されます。`with-...` と `do-...` は引数1つ
＋本体、`def...` は名前＋本体、それ以外は関数呼び出しとして扱われます。自分のマクロ
が別の形をしているなら、望むレイアウトで書いておけば、1行に収まる限りそのまま
保たれます。
