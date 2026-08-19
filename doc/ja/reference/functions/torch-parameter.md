# torch:parameter

`(torch:parameter x &key element-type)`

`:requires-grad t` を持つ葉テンソルを返します。値をモジュールの**学習可能なパラメータ**として印づける綴りです。`(torch:tensor x :requires-grad t)` と同一ですが、名前が別にあることでモジュールのフィールドが一目で読めます。`requires-grad` を持た*ない*テンソルのフィールドはバッファ扱いで、[`torch:parameters`](torch-parameters.md) は飛ばします。

```lisp
(torch:requires-grad-p (torch:parameter '(1.0 2.0)))  ; => T
(torch:data (torch:parameter '(1.0 2.0)))             ; => #d(1.0 2.0)
```
