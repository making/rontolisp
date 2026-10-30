# SWANK: cross references over loaded code, and the inspector's remaining pages

Difficulty: Medium

`rontolisp --swank` (`.kb/swank.md`, "Definitions and cross references", "Inspector")
records definitions and uses only for what the editor compiles (`C-c C-c`, `C-c C-k`).

1. Code loaded otherwise -- `C-c C-l`, a program's `load`, an ASDF system -- has `M-.`
   only for a `defun`, `defmacro` or `defgeneric` (`LispEvaluator.Definition`'s
   `LocatedCons` line), and no `xref` at all; a function typed at the REPL is not listed
   as a caller (SBCL lists one with an `:error` place). Feed `SwankSources.record` from
   the evaluator's top-level loop while a SWANK server runs, without a cost when none
   does.
2. The fancy inspector's pages for a class, a generic function and its methods, a slot
   definition, a package and a pathname (`swank-fancy-inspector.lisp`) fall to the generic
   page or the instance page here.

Measure against SWANK 2.32 on SBCL with the `nav` steps of the driver in
`.todo/artefacts/h18-common-lisp-swank-server/`, and extend `cli/SwankServerTest`.
