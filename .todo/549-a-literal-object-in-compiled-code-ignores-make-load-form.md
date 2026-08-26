# 549. A literal object in compiled code ignores `make-load-form`

Difficulty: High

Found by `.todo/542`'s cl-sqlite run. A `cffi:defcfun` whose argument or return type is
a `cffi:defcenum` -- which is most of a real binding's entry points; cl-sqlite's every
one -- expands into a form containing the foreign-type OBJECT itself: cffi's
`expand-to-foreign` / `expand-from-foreign` splice `,type` straight into the expansion.
Upstream makes that legal the way CLHS 3.2.4.4 says to, with

```lisp
(defmethod make-load-form ((type foreign-type) &optional env)
  `(parse-type ',(unparse-type type)))
```

in `src/early-types.lisp`. Every compiling implementation dumps the object through that
method.

**rontolisp does not.** The interpreter is fine (the object is live), and so is the
native binary. A compile path quotes a literal by STRUCTURE -- `JvmQuoteCompiler` /
`WasmQuoteCompiler` -- so it reaches the enum object's slots, finds a hash table, and
dies:

```
error: while compiling defun C-ABS: Cannot quote: #<HASH-TABLE :TEST EQUAL :COUNT 2>
```

Smallest reproduction (`-o Prog.class` fails, the interpreter answers `:BAD`):

```lisp
(ql:quickload "cffi")
(cffi:defcenum status (:ok 0) (:bad 1))
(cffi:defcfun ("abs" c-abs) status (n :int))
(print (c-abs 1))
```

## What it is

`make-load-form` is the ONE protocol CL gives for "this object appears as a literal in
code that will be compiled; here is a form that reconstructs it". It is not a cffi
feature -- `defstruct`'s `:make-load-form-fun`, cl-ppcre's compiled scanners and any
library that memoizes a CLOS object into a macro expansion want the same thing. Today
rontolisp has no `make-load-form` at all (no generic function, no default methods, no
consumer), and the literal-instance path is `LispInstance` -> `compileQuotedInstance`,
which serialises slots and gives up on anything it cannot spell.

## Shape of the work

- `make-load-form` as a real generic function with CLHS's two default methods
  (`standard-object` / `structure-object` signal; `structure-object` may instead answer
  the constructor form), plus `make-load-form-saving-slots`.
- The compile path's quoting of a `LispInstance` consults it: the macro-time evaluator
  (`UserMacroExpander`) is where a spliced object appears, so the substitution should
  happen there -- replace the object with its creation form BEFORE the quote compiler
  ever sees it -- rather than inside `JvmQuoteCompiler`, which has no evaluator.
- Both backends, one behavior, pinned together; and a `.kb` file, because "a literal
  object is dumped by its own method" is exactly the kind of invariant that gets
  re-broken.

## Why it is worth doing

It is the last thing between the ecosystem's C bindings and a compiled `.class`: with it
`examples/jvm/cffi-sqlite.lisp` gains its `jvm` leg, and `.kb/cffi.md`'s consumer table
stops carrying an asterisk.
