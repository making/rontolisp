# cerror

`(cerror continue-format-control datum arg...)`

[`error`](error.md) と同じ条件指定子 (condition designator) の形でエラーを通知します: `datum` はフォーマット制御文字列(`arg...` はフォーマット引数)か、コンディションクラス名(`arg...` は initarg)です。Common Lisp では `cerror` は `continue-format-control` で説明される `continue` リスタートを確立しますが、rontolisp にはリスタート機構がないため、エラーは**継続できず**、continue フォーマット制御は受理された上で捨てられます — `(cerror "Ignore it." "boom ~a" 1)` は `(error "boom ~a" 1)` とまったく同じ動作です。

キャッチされない `cerror` は実行を中断するため、実行可能な例ではなく静的な例として示します:

```console
(cerror "Ignore the error." "bad value: ~a" 42)
(cerror "Skip this character." 'bad-input :position 7)
```
