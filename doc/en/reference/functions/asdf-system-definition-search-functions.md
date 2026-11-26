# asdf:\*system-definition-search-functions\*

The functions a search for a system definition calls in order, each with the system's name as a
string, until one answers. A function answers a pathname, the `.asd` to load, or an `asdf:system`,
which is registered as it is; nil passes to the next, and anything else is a `type-error`. This is
how Quicklisp and any local search hook reach ASDF. It starts as real ASDF's three:

```lisp
(mapcar #'symbol-name asdf:*system-definition-search-functions*)
; => ("SYSDEF-PACKAGE-INFERRED-SYSTEM-SEARCH" "SYSDEF-CENTRAL-REGISTRY-SEARCH" "SYSDEF-SOURCE-REGISTRY-SEARCH")
```

```console
(defun vendor-search (name)
  (when (equal name "my-lib") #P"/opt/vendor/my-lib/my-lib.asd"))
(push 'vendor-search asdf:*system-definition-search-functions*)
(asdf:load-system "my-lib")
```

`asdf:search-for-system-definition` is the walk, and `asdf:locate-system` answers real ASDF's five
values over it. Without `sysdef-source-registry-search` on the list, the
[search path](../../guides/asdf-systems.md#the-system-search-path) is not searched either; a system
built into rontolisp is found whatever the list holds.

## Backend support

The interpreter walks the list at each search. A compile path found its systems while it spliced,
before the program runs and before a function of the program exists, so a literal `load-system`
does not call a pushed function. In a program naming the list, `asdf:find-system` of a name it did
not splice walks it at run time: a system a function answers is registered, and a pathname signals,
since a compiled program cannot load a file.
