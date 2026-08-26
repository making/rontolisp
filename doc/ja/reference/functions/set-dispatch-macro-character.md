# set-dispatch-macro-character

`(set-dispatch-macro-character disp-char sub-char function &optional readtable)`

ライト版スタブ: 受け付けますが無視し、`t` を返します。ユーザーのディスパッチマクロで Java 側リーダーを拡張することはできません。

ライブラリがこの方法で定義するディスパッチ構文のうち 2 つはリーダーに組み込まれているため、それらのライブラリはそのまま動作します: `#N@(...)`(ironclad の S ボックスリテラル。指定した要素幅のベクタとして読まれます)と `#L(...)`(iterate の番号付き引数ラムダ。`#L(list !2 !3)` は `#'(lambda (!1 !2 !3) (list !2 !3))` として読まれ、引数の個数は `#nL` で明示しない限り本体が言及する最大の `!n` になります)。それ以外のディスパッチ文字は未対応のままで、そのリテラルは通常のリーダーに渡ります。

```lisp
(set-dispatch-macro-character #\# #\7 (lambda (s c n) nil)) ; => T
```
