# Language incidentals on the postmodern path (format, features, floats, streams)

Grab-bag of small-to-medium gates hit by postmodern proper, each too small for
its own file but each a real blocker at a named line. Probed 2026-07-28 unless
marked "verify". Blocks `.todo/202-postmodern-non-mop-milestone.md`.

## FORMAT (extends `.todo/001-advanced-format-directives.md`)

- `~^` inside `~{...~}` -- BROKEN (see `.todo/195-s-sql-support.md`, which
  owns it; postmodern re-hits it in `deftable.lisp` and `execute-file.lisp`).
- `"\\~C~V,V,'0R"` (`json-encoder.lisp` unicode escape writer): `~V`
  parameter-from-argument (twice), `'0` character prefix parameter, and `~R`
  RADIX mode with min-width/padchar. Radix-mode `~r` exists; the `~V,V,'0`
  parameter forms are the gap (verify each).
- `"deallocate ~:@(~S~)"` (`prepare.lisp`) -- `~:@(` confirmed working; make
  sure `~S` inside case conversion stays correct.
- `deftable.lisp` `\!foreign`/`\!unique`: `~@[...~]` NESTED with
  `~:[~;...~:[~;~]~]` and `~{~^~}` in one control string -- the current
  limitation "conditional/iteration bodies cannot nest another such body"
  is a direct blocker; nesting support is the actual work item here.

## `*features*` pushed by `eval-when` must be visible to the reader mid-file

`postmodern/json-encoder.lisp:500`: `(eval-when (:compile-toplevel) ...)`
probes the float lattice with `subtypep` and pushes
`:cl-json-only-one-float-type` etc., consumed by `#-`/`#+` conditionals 15
lines later in the SAME file. This is exactly
`.todo/181-features-pushes-are-invisible-to-the-reader.md` -- postmodern is
its first hard consumer. (The `postmodern.asd` eval-when feature pushes are
handled differently -- the replacement `.asd` takes those two decisions
statically instead; see the postmodern section of `.kb/asdf.md`.) If 181 stays
open, a
Tier-4 form rewrite of json-encoder.lisp is the fallback; prefer fixing 181.

## `subtypep` float lattice is dishonest

`(subtypep 'single-float 'double-float)` returns `T` (should be NIL,T). The
json-encoder eval-when above branches on exactly these probes, so a wrong
lattice silently selects the wrong float-encoding path. Fix the float
relationships in the `%subtypep-runtime` tables (`.kb/*` type-lattice notes,
`.todo/180-bit-type-is-dead-in-the-type-lattice.md` is adjacent).

## Streams / io

- `with-output-to-string` binding `*standard-output*` -- BROKEN, owned by
  `.todo/195-s-sql-support.md`; postmodern re-hits it via `execute-file.lisp`
  (`make-string-output-stream` in a defstruct `:initform`, `get-output-
  stream-string` -- verify those two exist and work as slot defaults).
- `read-char`/`peek-char` with `end-of-file` `handler-case`
  (`execute-file.lisp` lexer) -- verify `end-of-file` is a catchable typed
  condition on string streams on all backends.

## Misc verifications (cheap, do them in one probe file)

- `getf` with a default (`(getf args :on-delete :restrict)`).
- `#.` read-time eval INSIDE a source file (`json-encoder.lisp:488`
  `'#.(rassoc-if ...)`) -- the reader supports `#.` in `.asd` context; verify
  in normal source, plus `rassoc-if` itself.
- Character literals `#\Backspace`, `#\Return`, `#\Tab`, and a literal
  form-feed inside `#\` (json-encoder).
- `case` over characters incl. `#\\`; `(declare (type character ...))`.
- `mapc #'funcall` over hook lists; `notany`; `string-trim`;
  `cl-ppcre:create-scanner` with the `:whitespace-char-class` keyword
  (cl-ppcre is loaded real, so this is a "does our cl-ppcre slice cover it"
  check).
- `eval-when` around a defun needed AT MACROEXPANSION time
  (`transaction.lisp:39` `isolation-level-p` called while expanding
  `with-transaction`) -- eval-when is progn + compile-path flattening, and
  macro expansion happens interleaved; verify the defun is visible to
  `UserMacroExpander` when the macro call site expands.
- Toplevel `(let ...)` / `(labels ...)` wrapping `defun`s
  (`prepare.lisp` `next-statement-id`, `deftable.lisp` `\!index`) --
  interpreter confirmed working; verify the COMPILE path hoists these
  correctly on JVM + both WASM.
