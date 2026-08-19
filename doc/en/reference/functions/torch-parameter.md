# torch:parameter

`(torch:parameter x &key element-type)`

Returns a leaf tensor with `:requires-grad t` -- the spelling that marks a value as a **trainable parameter** of a module. Identical to `(torch:tensor x :requires-grad t)`; the separate name is what makes a module's fields readable at a glance. A field holding a tensor *without* `requires-grad` is a buffer instead, and [`torch:parameters`](torch-parameters.md) skips it.

```lisp
(torch:requires-grad-p (torch:parameter '(1.0 2.0)))  ; => T
(torch:data (torch:parameter '(1.0 2.0)))             ; => #d(1.0 2.0)
```
