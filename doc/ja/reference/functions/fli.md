# fli パッケージの関数

`fli` パッケージは、LispWorks 8.1 の外部言語インターフェースのうち、`objc` と並んで Objective-C マニュアルの例が使う部分です。メソッドが参照渡しで埋める外部オブジェクト、Objective-C が返すポインタ、[`fli:define-foreign-function`](../macros/fli-define-foreign-function.md) を持ちます。**macOS 専用**で、**Common Lisp の一部ではありません**。外部ポインタはアドレスとそれが指す FLI 型の組で、メモリはどのターゲットでもプロセスのヒープです。マクロ [`fli:with-dynamic-foreign-objects`](../macros/fli-with-dynamic-foreign-objects.md) は本体の実行中だけ外部オブジェクトを束縛します。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

| 関数 | 例 | 結果 |
|------|-----|------|
| [`fli:allocate-foreign-object`](fli-allocate-foreign-object.md) | `(fli:allocate-foreign-object :type :int)` | ゼロで初期化した `int` を指す外部ポインタ |
| [`fli:free-foreign-object`](fli-free-foreign-object.md) | `(fli:free-foreign-object p)` | `nil` (メモリを解放する) |
| [`fli:dereference`](fli-dereference.md) | `(fli:dereference p)` | `p` が指すオブジェクト |
| [`fli:foreign-slot-value`](fli-foreign-slot-value.md) | `(fli:foreign-slot-value rect '(:size :width))` | 構造体のスロット |
| [`fli:size-of`](fli-size-of.md) | `(fli:size-of 'cocoa:ns-rect)` | `32` |
| [`fli:pointerp`](fli-pointerp.md) | `(fli:pointerp p)` | 外部ポインタなら `t` |
| [`fli:pointer-address`](fli-pointer-address.md) | `(fli:pointer-address p)` | アドレス (整数) |
| [`fli:make-pointer`](fli-make-pointer.md) | `(fli:make-pointer :address 4096 :type :int)` | そのアドレスを指す外部ポインタ |
| [`fli:null-pointer-p`](fli-null-pointer-p.md) | `(fli:null-pointer-p p)` | アドレスが 0 なら `t` |
| [`fli:pointer-eq`](fli-pointer-eq.md) | `(fli:pointer-eq p q)` | 同じアドレスなら `t` |
