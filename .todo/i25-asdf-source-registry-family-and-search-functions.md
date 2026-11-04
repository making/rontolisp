# i25. The source-registry family and `*system-definition-search-functions*` do nothing

Difficulty: Medium

`asdf:*central-registry*` is searched (`.kb/asdf.md`). The other ways a program widens the search are
exported and empty:

- `initialize-source-registry` and the rest of the family signal `uiop:not-implemented-error`.
  Measured: cl-json's `test-cl-json.lisp` and `compile-cl-json.lisp` say
  `(:source-registry (:directory :here) :inherit-configuration)`, fiveam's `build.lisp` and `test.lisp`
  say `(:tree :here)`. Each is a script's first line, so nothing behind it runs.
- A function pushed on `*system-definition-search-functions*` is never called, silently. That variable is
  how Quicklisp and any local search hook reach ASDF. The value should be real ASDF's three names
  (measured: `sysdef-package-inferred-system-search`, `sysdef-central-registry-search`,
  `sysdef-source-registry-search`), with a search walking the list.

Needed on the interpreter: `:directory`, `:tree` (a recursive `%list-directory`), `:here`,
`:inherit-configuration`, `:ignore-inherited-configuration`, a string parameter, and
`clear-source-registry`. A compile path reads a literal call, as it reads a literal push on the central
registry (`LoadInliner`, `AsdfSystems.centralRegistryPush`).
