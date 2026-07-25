# encode-universal-time

`(encode-universal-time second minute hour date month year &optional time-zone)`

分解された時刻要素が表すユニバーサルタイム (Common Lisp のエポックである 1900-01-01 00:00:00 GMT からの経過秒数) を返します。`time-zone` は GMT より西へのオフセット (時間単位) です。**省略時 (または nil の場合) はローカルタイムゾーンではなく GMT を意味します**。バックエンド間で移植可能なローカルゾーンの取得手段が存在しないためです (WASM ターゲットはタイムゾーンを一切公開していません)。暦の計算は整数上の紀元 (era) ベースの先発グレゴリオ暦アルゴリズムで行われるため、任意の年で正確であり、すべてのバックエンドが同じ値を返します。

```lisp
(encode-universal-time 0 0 0 1 1 1970 0) ; => 2208988800
```

逆変換は [`decode-universal-time`](decode-universal-time.md)、同じ単位で現在時刻を読むのは [`get-universal-time`](get-universal-time.md) です。
