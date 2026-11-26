# ffi パッケージの関数

`ffi` パッケージは JVM の外部関数 API を通して素の C を呼び出す。JNI も同梱の
ネイティブライブラリもリフレクションも使わないため、`java -jar` やコンパイル済みの
`.class` / `.jar` だけでなく**ネイティブバイナリ**でも動作する。どちらの WASM
バックエンドにも外部関数 API は無いので、これに触れるプログラムはコンパイルを
拒否される。**Common Lisp の一部ではない**ので、関数は `ffi:` 修飾子付きで参照する。
これらはプリミティブであり、想定する利用者は 1 つだけ --- バインディングはこれらの
動詞ではなく `cffi:defcfun` に対して書くこと。[C ライブラリガイド](../../guides/cffi.md)
を参照。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `ffi:open` | `(ffi:open "libsqlite3.so.0")` | ライブラリのハンドル (整数。引数なしならプロセス自身) |
| `ffi:symbol` | `(ffi:symbol (ffi:open) "strlen")` | シンボルのアドレス (ポインタ。無ければ `nil`) |
| `ffi:call` | `(ffi:call addr :long '(:string) "hello")` | C 関数の戻り値を戻り値型でマーシャリングしたもの |
| `ffi:callback` | `(ffi:callback fn :int '(:int :int))` | Lisp 関数を呼び出す C 関数ポインタ |
| `ffi:alloc` | `(ffi:alloc 8)` | 指定バイト数の領域へのポインタ (`malloc`) |
| `ffi:free` | `(ffi:free p)` | `nil` (領域を解放する) |
| `ffi:peek` | `(ffi:peek p :int 8)` | アドレス+オフセット位置にあるその型の値 |
| `ffi:poke` | `(ffi:poke p :double 1.5)` | 書き込んだ値 |
| `ffi:size` | `(ffi:size :pointer)` | その型のサイズ (バイト) |
| `ffi:align` | `(ffi:align :double)` | その型のアラインメント (バイト) |
| `ffi:pointerp` | `(ffi:pointerp 42)` | 外部ポインタなら `t` |
| `ffi:address` | `(ffi:address 4096)` | 整数ならポインタ、ポインタなら整数 |
| `ffi:errno` | `(ffi:errno)` | 呼び出しスレッドの直前の呼び出しが残した `errno` |

