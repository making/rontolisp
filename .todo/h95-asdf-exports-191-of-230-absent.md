# h95. The `asdf` package exports 39 of real ASDF's 230 symbols

Difficulty: Medium

Measured 2026-11-04: every external of SBCL 2.2.9's ASDF 3.3.1 looked up with `find-symbol` on the
interpreter. **39 external, 191 absent.** An absent name is not a run-time problem: reading
`asdf:NAME` stops the whole file, `The symbol X is not external in the ASDF package`, even when the
line is never reached. UIOP, by contrast, misses 9 of 427 (`*OPTIMIZATION-SETTINGS*`,
`*PREVIOUS-OPTIMIZATION-SETTINGS*`, `DEFGENERIC*`, `DEFUN*`, `GET-OPTIMIZATION-SETTINGS`, `LOOP*`,
`PROCLAIM-OPTIMIZATION-SETTINGS`, `SB-GROVEL-UNKNOWN-CONSTANT-CONDITION`,
`WITH-OPTIMIZATION-SETTINGS`), because it exports every member and stubs what it lacks.

Measured stops: `asdf:load-source-op` (prove, in a function never called while loading),
`asdf:*central-registry*` (cl-project, so caveman2), `asdf/component:default-component-class`
(hu.dwim.asdf).

Absent names by how many of the 232 fetched releases spell them (`asdf:` or `asdf::`):
`oos` 17, `clear-system` 15, `*central-registry*` 7, `apply-output-translations` 5,
`system-source-file` 4, `module-components` 4, `asdf-version` 4 (`.todo/h78`), `find-component` 3,
`version-satisfies` 2 (`.todo/h78`), `system-homepage` 2, `registered-system` 2,
`precompiled-system` 2, `load-asd` 2, `initialize-source-registry` 2, `component-depends-on` 2,
`compile-system` 2, `coerce-name` 2, `already-loaded-systems` 2, then the rest of the
`system-<metadata>` readers (`description`, `author`, `license`, `maintainer` ...) and
`load-source-op`, `map-systems`, `make`, `primary-system-name`, `program-op` at 1.

Plan:
1. Export all 230, as UIOP does: a real definition, or a signalling stub or empty class naming the
   gap. The bundle and program ops stay refusals.
2. Implement what the list shows programs do: `oos`, `clear-system`, `find-component`,
   `load-source-op` (rontolisp's load-op already loads source), `load-asd`, `map-systems`,
   `system-source-file`, `module-components`, `component-depends-on`, `coerce-name`,
   `primary-system-name`, `already-loaded-systems`, `registered-system`, the metadata readers (the
   options are parsed and dropped today: keep them), and the condition classes programs handle
   (`missing-dependency`, `system-definition-error`, `load-system-definition-error`,
   `compile-error`).
3. `*central-registry*` as a variable the search actually reads: a pushed directory has to find
   systems on the interpreter. A compile path reads only a literal push, as `load-system` does.
4. `slot-value` of a component's real slot names: cxml reads
   `(slot-value (asdf:find-system :cxml) 'asdf::relative-pathname)` while loading
   (`The slot RELATIVE-PATHNAME is missing`, `xml/catalog.lisp:230`).
