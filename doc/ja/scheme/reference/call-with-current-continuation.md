# call-with-current-continuation

`(call-with-current-continuation proc)`

`call/cc` の長い名前です。現在の継続を渡して `proc` を呼び出します。継続は脱出専用で、呼び出しの実行中に 1 回だけ呼べ、再突入はできません。典型的な用途はループからの早期脱出です。

```scheme
(call-with-current-continuation (lambda (return) (for-each (lambda (x) (if (negative? x) (return x))) '(1 -2 3)) 'none)) ; => -2
```
