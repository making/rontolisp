# A `.asd` that defines its own component class cannot be loaded

Difficulty: Medium

`AsdfSystems.parseAsdSource` tolerates exactly two kinds of top-level
definition in a `.asd`: a doc-file `defclass`, and a `(defmethod perform ...)`
hook. A `.asd` that subclasses `cl-source-file` to change how its files are
compiled -- the standard ASDF extension idiom, and the one every pre-2010
system reaches for -- is a hard error before a single component is read.

Walking portableaserve (quicklisp's `aserve`) turns up the whole chain, each
error only reachable after the previous one is patched out:

```
aserve/aserve.asd:  only a doc-file component class is tolerated as a top-level
                    DEFCLASS in a .asd file:
                    (DEFCLASS LEGACY-ACL-SOURCE-FILE (CL-SOURCE-FILE.CL) NIL ...)
aserve/aserve.asd:  unsupported option :DEFAULT-COMPONENT-CLASS
aserve/aserve.asd:  unsupported component type :CL-SOURCE-FILE
                    (supported: :file :module :static-file and declared doc-file classes)
aserve/htmlgen/htmlgen.asd:
                    only a (DEFMETHOD PERFORM ...) hook is tolerated as a
                    top-level method in a .asd file:
                    (DEFMETHOD SOURCE-FILE-TYPE ((C ACL-FILE) (S MODULE)) "cl")
```

Everything in that chain is about ONE thing -- which file on disk a component
names, and whether its warnings are fatal. We do not have compile-file
warnings at all, so the second half is already a no-op here; what is load-bearing
is only the file EXTENSION (`source-file-type`, and `cl-source-file.cl` = `.cl`
rather than `.lisp`) and the fact that a user-declared subclass of
`cl-source-file` should behave as `:file` does.

Proposed shape, smallest first:

1. Accept a top-level `(defclass NAME (cl-source-file...) ...)` and record NAME
   as an alias for `:file`, exactly as the doc-file classes are recorded today;
   a subclass of `cl-source-file.cl` additionally defaults its type to `cl`.
2. Accept `:default-component-class` and apply it to components with no
   explicit class.
3. Accept the built-in `:cl-source-file` / `:cl-source-file.cl` component
   types.
4. Accept `(defmethod source-file-type ((c CLASS) (s module)) "ext")` and let
   it set that class's extension. Ignore any other top-level `defmethod` the
   way `perform` hooks are already ignored, rather than erroring -- an unknown
   method on a system we are about to load is a warning at worst.

`.kb/asdf.md` owns the tolerated-forms list and must move with the code.

## What it unblocks

`.todo/620`: PCL chapters 26 (`url-function`), 28 (`shoutcast`) and 29
(`mp3-browser`) all `(:use :net.aserve)`, and SBCL loads portableaserve from
quicklisp unchanged, so the reference answers exist. **Loading the `.asd` is
only the first gate** -- `acl-compat` beneath it is a portability layer of
`#+sbcl` socket / process / gray-stream branches, which lands on exactly the
implementation-identity question `.todo/620` describes. Do not size this item
as "three more PCL chapters"; size it as "the .asd extension surface", which is
worth having on its own -- it is the reason a `.asd` from before ASDF 3 stops
us dead.
