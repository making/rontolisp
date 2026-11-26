# make-pathname

`(make-pathname &key directory name type defaults)`

Builds a pathname from its components: `:directory` (Common Lisp's list -- `:absolute` or
`:relative` followed by one component per level -- or a directory namestring),
`:name` (the file name without its type) and `:type` (the extension without its
dot).

A directory component is a string or one of the keywords Common Lisp names the
special levels by: `:up` / `:back` (`..`), `:wild` (`*`, one level) and
`:wild-inferiors` (`**`, any number of levels). `:wild` is also what `:name` and
`:type` take for their `*`:

```lisp
(namestring (make-pathname :directory (list :absolute "a" :wild-inferiors)
                           :name :wild :type "lisp"))   ; => "/a/**/*.lisp"
```

That is the pathspec [`directory`](directory.md) walks a whole subtree with and
the from-wildcard [`translate-pathname`](translate-pathname.md) rewrites against.
 `:host`, `:device`, `:version` and `:case` are accepted and dropped, as is
any other keyword: a namestring models no such component, and a portability
layer's call still works.

`:defaults` supplies every component the call did NOT, **component-wise -- this
is not a merge**. A supplied component REPLACES the defaults' one instead of
combining with it, and an explicitly supplied `nil` means "no component" rather
than "take the default". That is Common Lisp's rule, so a supplied `:directory`
does not nest under the defaults' directory:

| Call | Result |
|------|--------|
| `(make-pathname :name "b" :defaults "d/a.sql")` | `#P"d/b.sql"` |
| `(make-pathname :name "b" :type nil :defaults "d/a.sql")` | `#P"d/b"` |
| `(make-pathname :type "txt" :defaults "d/a.sql")` | `#P"d/a.txt"` |
| `(make-pathname :directory (list :relative "m") :name "b" :defaults "d/a.sql")` | `#P"m/b.sql"` |
| `(make-pathname :directory (list :absolute "u" "s") :name "b" :type "c")` | `#P"/u/s/b.c"` |

Naming a sibling file is what this is for: [`pathname-name`](pathname-name.md)
and [`pathname-type`](pathname-type.md) take a namestring apart by the same rule
this puts one together by. To combine two paths instead of replacing components,
use [`merge-pathnames`](merge-pathnames.md).

```lisp
(make-pathname :name "20260101.down" :defaults "db/20260101.up.sql")   ; => #P"db/20260101.down.sql"
```

## Backend support

All four backends, as a real run-time function -- one definition in rontolisp
source. On the compiled backends a call whose keywords and values are all
literals is additionally folded to a literal pathname while the program is
being built (which is what lets an [`asdf:system-relative-pathname`](asdf-system-relative-pathname.md)
result become a constant in the artifact); every other call -- a computed
`:defaults` or `:name`, say -- runs the function. Both renderings implement the
same rule.
