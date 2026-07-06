# 76: Load cl-who (parent) -- (X)HTML generation macros

Goal: load Edi Weitz's **cl-who** (`asdf:load-system "cl-who"`, BSD, 4 files /
779 lines) verbatim on all four backends and render HTML, the same standard we
held for split-sequence (`.todo/54` Phase 3). cl-who is the target HTML library
(see the 2026-07-06 candidate discussion: chosen over Spinneret/flute/Djula
because it is macro-first and does not require CLOS/conditions at its core).

This is the parent tracking file. Work is split into session-sized units
(76 = this file; the units are `.todo/77`..`.todo/81`).

## Investigation (2026-07-06, native jar, source vendored under scratchpad)

cl-who's core (`with-html-output` / `with-html-output-to-string`) is a macro
whose expansion runs a chain of ordinary defuns AT MACRO-EXPANSION TIME
(`tree-to-commands` -> `tree-to-template` -> `process-tag` ->
`convert-tag-to-string-list` -> `convert-attributes`, plus `maybe-downcase`
etc.). The load therefore hinges on the macro expander being able to call
user defuns -- **verified working on both the interpreter and the compile
path**. `macrolet` (which `with-html-output` uses internally to provide the
local `htm`/`str`/`esc`/`fmt` macros) is **also already working**. Those two
facts make a verbatim load realistic.

### Already works (no work needed) -- verified by probe

`macrolet`, `defconstant`, `format` `~x`/`~C`/`~d`/`~A`, `case` with character
keys, `string-downcase`/`string-equal`/`string` (symbol coerce),
`alpha-char-p`, `loop for ... on ... by #'cddr`, `loop ... nconc/collect ...
into ... finally`, `&key ((:indent var) default)` explicit keyword-var binding,
`&rest r &key k` combined, `position-if`, `member :test`, `every`, nested
backquote, and (critically) a `defmacro` body calling a user `defun` at
expansion time on interpreter + compiled JVM.

### Missing / broken -> the unit work below

| Gap | Unit |
| --- | --- |
| `make-string` (`:initial-element`/`:element-type`), `replace` (seq, `:start1`), `write-sequence` on strings (`:start`/`:end`), `constantp`, `lower-case-p`, `upper-case-p`, `streamp` + `stream` type-specifier in `check-type` | `.todo/77` **DONE** (all 4 backends + native E2E) |
| `defgeneric` + `defmethod` (CLOS-lite: single default method, optional eql-specializer) -- cl-who's `convert-tag-to-string-list` | `.todo/78` |
| `defun (setf html-mode)` -- setf-function definitions | `.todo/79` |
| `loop for s being the {external-\|present-}symbols of PACKAGE` (hyperdoc block; lite/empty iteration is acceptable) | `.todo/80` |
| Vendor + 4-backend load + fix residue + ci-spec/e2e | `.todo/81` (integration/close) |

### Deferred -- dynamic special variables (NOT a blocker for the first green load)

cl-who's ~10 specials (`*html-mode*`, `*indent*`, `*escape-char-p*`,
`*attribute-quote-char*`, ...) are today plain globals with lexical `let`
(no dynamic binding). Consequences for the first cut, to be DOCUMENTED as
lite limitations rather than fixed here:

- **Default (no-indent) rendering is correct**: with `*indent*` = nil the
  expansion-time chain reads the defaults and emits well-formed un-indented
  HTML; `*html-mode*` = :xml default is honored.
- **`:indent t` is broken** (indentation reads the global, not the `let`
  rebinding) and **`(let ((*html-mode* :sgml)) ...)` rebinding is ignored**.
- **The supported way to switch mode is `(setf (html-mode) :html5)`** -- that
  mutates the global and works once `.todo/79` lands.

True fix = dynamic/special variable binding, which is the "Dynamic/special
variable binding" item in `.todo/54` Phase 4 (a deep evaluator+compiler
change, its own multi-session effort). Track it there; revisit cl-who
indentation once it exists.

`n-spaces` (`make-array :displaced-to +spaces+ :element-type 'base-char`) is
only exercised when indenting, so it is deferred together with the above.

## Order & acceptance

Recommended order: `77` (leaf primitives, no deps) -> `78` -> `79` -> `80`
-> `81` (integration). Each unit is one self-contained session (implement on
all four backends where applicable + tests + docs + native `CiSpecE2eTest`).

Definition of done for the parent: `asdf:load-system "cl-who"` loads and
`(cl-who:with-html-output-to-string (s) (:html (:body (:p "Hi"))))` returns
the expected string on interpreter / JVM / WASM Preview 1 / WASM component,
with the dynamic-variable limitation documented. Vendor cl-who under
`src/test/resources/` (BSD license retained) as with split-sequence.
