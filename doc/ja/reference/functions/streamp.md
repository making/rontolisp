# streamp

`(streamp object)`

`object` がストリームであれば `t` を、そうでなければ `nil` を返します。ストリームは自己記述的な「値」です。`open`、`make-string-input-stream`、`make-string-output-stream`、ソケット構築子はいずれもこの値を返します。したがって整数はストリームではなく、「ファイルディスクリプタか Lisp ストリームか」で分岐するライブラリ (`(etypecase s (integer ...) (stream ...))`) に渡しても正しい側が選ばれます。標準出力の指定子 `t` (`*standard-output*` の束縛値) もストリームとして扱われ、[Gray ストリーム](../../guides/gray-streams.md)のインスタンス、すなわち `rontolisp:fundamental-stream` を継承するクラスのインスタンス、およびシノニムストリームも同様です。`check-type`/`typecase` が使う `stream` 型指定子も同じ判定に基づいています。

値が生成時の種別を保持しているため、部分型名も正確です: `file-stream` は `open` が返したストリーム、`string-stream` は 2 つの文字列ストリーム構築子が返したストリーム、`synonym-stream` は `make-synonym-stream` の値に対して真になります。`readtable` は `*readtable*` が保持する不透明な `nil` トークンの型です。ストリームは `#<STREAM :HANDLE n :KIND :FILE>` の形で表示され、ハンドル番号はバックエンドごとの値です。`--no-gc` を除くすべてのバックエンドで利用できます。

```lisp
(with-output-to-string (s) (princ (streamp s) s)) ; => "T"
```
