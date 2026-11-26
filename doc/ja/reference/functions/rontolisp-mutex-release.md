# rontolisp:mutex-release

`(rontolisp:mutex-release mutex)`

`mutex` の獲得を 1 回分解放し、その mutex を返します。呼び出しスレッドが保持していない
mutex を解放することは、インタプリタと JVM バックエンドではエラーです(WASM では
プリミティブが no-op なので何も起きません)。ロックは再入可能なので、2 回獲得したスレッドは
別のスレッドが獲得できるようになる前に 2 回解放する必要があります。

[`rontolisp:with-mutex`](../macros/rontolisp-with-mutex.md) は非局所脱出も含めて獲得と
解放を対にしてくれます。素のプリミティブを使うのは、その 2 つを 1 つのレキシカルブロックに
収められない場合だけにしてください。

```lisp
(let ((m (rontolisp:make-mutex)))
  (eq (rontolisp:mutex-release (rontolisp:mutex-acquire m)) m))  ; => T
```

## 制限

- mutex ハンドルでない値はエラーです。
- WASM バックエンドでは何も検査されません。保持していないロックの解放は黙って受理されます。
