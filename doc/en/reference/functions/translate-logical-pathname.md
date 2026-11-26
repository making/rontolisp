# translate-logical-pathname

`(translate-logical-pathname pathname &key)`

Translates a logical pathname into the physical one its host's
[`logical-pathname-translations`](logical-pathname-translations.md) name. A
namestring is logical when its prefix before the first `:` is a host defined
there; any other pathname is physical and answers itself, as Common Lisp
prescribes.

The logical part is spelled physically -- downcased, `;` as `/`, the version
dropped -- and so is each rule's from-wildcard; the first rule that
[`pathname-match-p`](pathname-match-p.md) accepts translates it through
[`translate-pathname`](translate-pathname.md). No rule matching signals a
`file-error`.

```lisp
(setf (logical-pathname-translations "APP")
      '(("SRC;**;*.*.*" "/opt/app/src/**/*.*")))
(list (namestring (translate-logical-pathname "APP:SRC;A;FOO.LISP"))
      (namestring (translate-logical-pathname "d/a.txt")))
; => ("/opt/app/src/a/foo.lisp" "d/a.txt")
```

Lite: a logical pathname is not a value of its own, so `merge-pathnames` treats
its namestring like any other (a relative one is merged under the defaults'
directory), and a relative to-wildcard stays relative where SBCL answers an
absolute path.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
