# fli:dereference

`(fli:dereference pointer &key index type copy-foreign-object)`

`pointer` が指すオブジェクト (配列なら `index` 番目、既定値 0) を、ポインタの型、または `type` を与えればその型として読んで返します。数値、ブール型なら `t` / `nil`、オブジェクトなら `objc:objc-object-pointer`、ポインタなら外部ポインタです。`setf` で書き込めます。構造体と配列はここでは Lisp の値になりません。`copy-foreign-object` が `nil` ならそれを指す外部ポインタを、`t` なら新しく確保したメモリへのコピーを返し、既定値の `:error` ではシグナルします。ヌルポインタと、`type` のない `:void` へのポインタはシグナルします。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (fli:with-dynamic-foreign-objects ((n :int :initial-element 41))
           (setf (fli:dereference n) (+ 1 (fli:dereference n)))
           (fli:dereference n))
42
```
