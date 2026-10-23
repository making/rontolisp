# Pathnames: a distinct VALUE carrying its namestring

**Invariant**: a pathname is an instance of the fixed `LispLayout.PATHNAME` layout (kind
`PATHNAME`, tag `%PATHNAME`, one slot = the namestring); a string is NOT one; the two
spellings are interchangeable as ARGUMENTS to every path-taking operator. `pathnamep` /
`(typep x 'pathname)` answer `T` exactly for the value; `namestring` unwraps it.

## Value model
- The layout is a CONSTANT seeded into `ClosRegistry.layoutsByTag` as a LAYOUT ONLY: no
  `typep` tag table, no `structure-object`/`standard-object` enumeration, no
  `%class-slot-defs`; `(typep #P"x" 'structure-object)` is `NIL`, as in SBCL.
  `LispLayout.SYNONYM_STREAM` is the second value built this way
  (`.kb/read-load-streams.md`) — copy this pattern. Tag is UPPER case.
- Printing is a per-kind arm in the three instance printers (`LispInstance.render`, JVM
  `_instToString`/`_instToDisplayString`, WASM `emitPrintInstance`), NOT a `print-object`
  method: `prin1` writes `#P` + escaped namestring, `princ` the bare namestring (CLHS
  22.1.3.11), and nested elements follow — `(princ (list #P"a b"))` is `(a b)`, which the
  print-object seam cannot give.
- `equal`/`equalp` compare the namestring; `(equal #P"a" "a")` is `NIL`. Compile-path
  runtime `typep` uses one `%typep-tag-table%` entry (`(PATHNAME) %PATHNAME`); `type-of`
  answers `PATHNAME` via a string-compare clause in the prelude defun.

## `#P"..."` and the instance gate
`Token.PathnameOpen` for `#P"`/`#p"`; `LispReader` builds the `LispInstance` directly
(self-evaluating like a folded `#S(...)`). `#PFOO` stays a symbol. Both EMITTED readers
have a `#P` arm (JVM `Object[]{layout, ns}` off the interned `_ly$` field; WASM
`struct.new` over the baked layout address). A runtime `read` can CONSTRUCT an instance, so
`constructsInstance` answers true for `read`/`read-from-string`/`load` heads and a
read-using program is instance-gated; gate off, `%obj-is` compiles to constant nil
(`pathnamep` stays correct) and the readers' `#P` arm signals. A `#P` literal is what
`mayCreateInstances` detects, so it flips the gate itself.

## Producers wrap, consumers coerce, internals stay strings
Prelude pattern (`LispPreludeLibrary`): every PUBLIC path function coerces through
`%path-ns` (LENIENT) or the strict `namestring`, computes on namestrings, wraps its answer
with `(pathname ...)`.
- String-typed internals: `%pathname-split`, `%dir-namestring`, `%wild-match`,
  `%wild-captures`, `%wild-component-p`, `%wild-inferiors-at`,
  `%pathname-directory-component`, `%path-dir-parts`, `%wild-dirs`, `%directory-subdirs`,
  `%directory-in`, `%temp-file-name`, `%probe-file`, `%probe-truename`, `%read-link`, `%real-path`, `%list-directory`, `%delete-file`,
  `%make-directories`, `%rename-file`.
- Consumers unwrap: interpreter built-ins via `PathnameOps.designatorNamestring`; the
  compile paths wrap `open`/`load`/`file-write-date`'s path argument in
  `LispMacroExpander.coercePathArg`, ONLY when the instance gate is on, so an
  instance-free program keeps its bytes.
- Deliberately still namestrings: `asdf:system-source-directory` / `component-pathname` /
  `system-relative-pathname` answers (`.kb/asdf.md`), `ensure-directories-exist`'s value,
  `uiop:with-temporary-file`'s `:pathname` binding. `uiop:native-namestring` =
  `namestring`. `probe-file`'s `BuiltinFunctionWrappers` entry is GONE; the
  prelude defun serves `#'probe-file`.
- `CompileTimePathnameFolder` folds `make-pathname` / `uiop:merge-pathnames*` to a pathname
  literal and records them for the `defparameter` substitution; `LoadInliner` inlines
  `(load #P"x.lisp")`. A `typecase` `pathname` clause is an ordinary `%obj-is` test. The
  transport still REFUSES a pathname response body (`%http-body-string` /
  `LispEvaluator.responseBody`); `lack-app-file` is deferred.

## Wild components: `:wild` / `:wild-inferiors`
The flat model renders CL's directory keywords as the text CL prints: `:up`/`:back` ->
`..`, `:wild` -> `*`, `:wild-inferiors` -> `**` (`%pathname-directory-string`, Java twin
`PathnameOps.formatDirectory`); `:wild` is also `:name`/`:type`'s `*`.
- **The round trip is the namestring, and it is total** — there is no structured pathname
  at all. Decomposition is the exact INVERSE of construction; only an EXACT `*` is a
  keyword (`"a*"` stays a string, as SBCL).
- **`**/` is ONE matcher token, separator included** — `%wild-inferiors-at` is the single
  spelling, read by `%wild-match` and `%wild-captures` — and through them, per component,
  by `translate-pathname` — so the three cannot disagree. The trailing `/` belongs to the
  token because it matches ZERO levels as well as many; it contributes ONE capture (`""`
  when none).
- Building a wild pathname is filesystem-INDEPENDENT; only `directory`'s WALK touches
  `%list-directory` (`.kb/directory-listing.md`).

## Deliberate lite edges
`(eql #P"a" #P"a")` is T on the interpreter, NIL on the compile paths — the pre-existing
instance `eql` split; **do not pin `eql`**. No PATHNAME metaobject: `class-of` falls back,
`find-class 'pathname` is absent, `sxhash` is 0. Logical pathnames cannot exist:
`logical-pathname` always signals, `translate-logical-pathname` is the identity.
`pathname-host`/`-device`/`-version` answer `nil` (SBCL answers `:newest` for VERSION).

## The algebra over the flat namestring
All prelude Lisp, so the four backends run ONE definition each.
- `wild-pathname-p` reads the same `%pathname-split` and wildcards `%wild-match` uses, so
  predicate and matcher cannot disagree. `enough-namestring` is the INVERSE of
  `merge-pathnames` and answers a STRING. `host-namestring` is `""`, written
  `(progn (namestring x) "")` so the designator is still validated.
- `translate-pathname` — a port of SBCL's `translate-component` / `translate-directories`
  over `%pathname-split`, `%path-dir-parts`, `%wild-component-p`, `%wild-match` and
  `%wild-captures` (the CAPTURING twin of `%wild-match`: `:no-match` is the failure answer;
  `*` is tried SHORTEST first). The three namestrings are split into directory list + name +
  type and matched/substituted COMPONENT by component: `*`/`?` never span a `/`, `**`
  consumes a RUN of source directory levels (captured with a trailing `/`, written back
  normalized), and a component's captures feed only that component's TO wildcards. Answers
  are pinned against SBCL 2.6.5. LITE edges (documented on the reference page, not
  signalled): a TO wildcard with no capture left substitutes `""`, adjacent wildcards in one
  component consume one capture apiece, and an unpaired `**` is not checked. The `:no-match`
  answer of `%wild-captures` is the error signal here — keep it.
- `rename-file` — prelude Lisp over `%rename-file`: real on all four backends (both
  WASM backends move through the `path_rename` import, `.todo/257`; a missing source
  answers nil, which the Lisp above raises the `file-error` from).
  Truename values 2 and 3 are not returned.
- `file-namestring` / `directory-namestring` — one split: `%pathname-split`'s first element
  is a literal PREFIX, so `file-namestring` is `(subseq ns (length it))`, exact
  complements. NOT built from name + `"."` + type: that loses a trailing dot and re-decides
  the dotfile rule.
- **`*default-pathname-defaults*` is a genuine dynamic variable on all four backends**,
  holding `#P""`: `Environment.createGlobal` (proclaimed special) / a `defvar` from
  `LispMacroExpander`'s `PRINTER_MODE_VARS` injection, which runs AFTER
  `mayCreateInstances`, so mentioning it flips the instance gate like a `#P` does.
  `uiop:get-pathname-defaults` reads this special (`.kb/uiop.md`).

- **`probe-file` answers the truename, as SBCL's does** (2026-10-23): `%probe-truename` is
  `(%probe-file (bare (%real-path ns)))` -- the trailing `/` dropped before the probe, so
  `%probe-file`'s kind answer decides the form: a directory in DIRECTORY form however it is
  spelled (`d`, `d/`, a link to `d` all `d/`), `f/` naming a file `f` (SBCL's answers) --
  and `truename` is `probe-file` plus the `file-error`. A link whose target is missing
  answers ITSELF, its directory resolved (SBCL's fallback when realpath fails); `directory`
  lists a dangling link through the same fallback. On macOS a
  `@TempDir` under `/var` answers `/private/var`, so tests comparing either with a temp path
  -- and `directory`'s answers -- use `toRealPath()`. `uiop:directory-exists-p` /
  `uiop:file-exists-p` split `probe-file`'s answer by its form. **On WASM the walk resolves only what the preopens let the module
  read** -- `%read-link` of a path no preopen covers (`/tmp` under `--dir /tmp`) answers nil,
  an absolute target is refused -- so the answer is always a path the module can open, and no
  mapping back through the preopen table is needed: under `--dir /tmp`,
  `/tmp/f` answers `/tmp/f`; under `--dir /`, `/private/tmp/f`. A relative target that climbs
  out of every preopen resolves to a path no preopen covers, so the probe answers nil -- the
  host would refuse that escape through the link anyway.
- **Callers asking only "is it there" use `%probe-file`, not `probe-file`**: the `open` /
  `load` existence guards (`LispMacroExpander.existenceProbe`, over `coercePathArg`'s unwrap,
  so a guard needs no prelude splice and walks no link) and `%directory-in`'s
  `:resolve-symlinks nil` arm. ci-spec
  `runtime-absolute-path-open-probe-and-load` pins the openable answer rather than its
  host-dependent spelling.
- **`directory` answers truenames, each once** (2026-10-23; `.kb/directory-listing.md`), and
  `:resolve-symlinks nil` keeps the matched spellings; the pathspec's `.`/`..` go by spelling in
  both modes, as SBCL's.
- **The truename family is ABSOLUTE, as SBCL's** (2026-10-23): `%absolute-path` merges with
  `*default-pathname-defaults*` (`merge-pathnames`' default now, CL's) and puts a still-relative
  path below `%host-getcwd`, before `probe-file` / `truename` / `directory` / `uiop:truenamize`
  resolve it. SBCL's own defaults ARE the cwd; ours stay `#P""` and the cwd is added after, so a
  user-bound RELATIVE defaults still ends up absolute (SBCL answers relative there under
  `:resolve-symlinks nil`, with a warning) -- not copied. `%host-getcwd` per backend: the
  interpreter's `SourceLoader.workingDirectory()` (`user.dir` for `fileSystem()`, NIL for an
  in-memory loader, whose paths have nothing to sit below -- `probeFileGoesThroughTheInstalledSourceLoader`),
  the JVM's `user.dir`, and on both WASM backends `_host_getcwd`: **fd 3's preopen name when it is
  absolute, else NIL**. fd 3 is what every relative path resolves against, so its absolute name
  is the one working directory a module can know: the `--native` runner stub names it
  `current_dir()` (`.kb/native-output.md`), and there all four answers equal SBCL's;
  `wasmtime run --dir .` names it `.`, and the answers stay relative (the cwd itself `./`). Not
  done: inventing an absolute path from a preopen that does not name the cwd -- it need not be
  openable. Not absolutized: `ensure-absolute-pathname` / `get-pathname-defaults` /
  `probe-file*`'s parsed answer (rove keys files by the path as given, `.kb/uiop.md`) and
  `file-error-pathname` (the designator as given). ci-spec cases print these answers through
  `(enough-namestring p (truename ""))`, so one expectation holds where they are absolute and
  where they are not, and `JvmDeadMethodEliminationCorpusTest`'s two run directories compare
  equal.
- **WASM `_probe_file` of a preopen root** (fixed 2026-10-23): `_path_dirfd` writes `.` over the
  staged path's last byte when the path names a preopened directory itself; the directory form
  was built from that staging, so `/r/abs54` answered `/r/abs5./`. The probe saves the byte
  before `path_open` and puts it back
  (`absoluteRuntimePathResolvesAgainstThePreopenThatCoversIt`).

## Tests
`LispReaderTest#readPathname*`; `LispEvaluatorTest#pathname*` plus its wild-pathname,
`enoughNamestring`, `namestringHalves`, `translatePathname`, `renameFile` and
`directoryFamilyAnswersPathnames` cases; `LispPreludeLibraryTest` (PathnameOps agreement
pins); `Jvm/WasmLispCompilerTest`'s `pathnameAlgebraOverTheFlatNamestring`,
`wildPathnameComponentsBuildMatchTranslateAndWalk`,
`namestringHalvesNstringCaseAndEnvironmentEnquiry` and twins; the truename/link tree
`truenameResolvesEverySymbolicLinkInThePath` in all three suites (interpreter, JVM, both
WASM; SBCL-checked directory forms, dangling link, `directory` with and without
`:resolve-symlinks`, a `..` after the link `de` -> `d/e` read by spelling in `directory` and
through the link in `truename`, `*default-pathname-defaults*` merged; the interpreter and JVM
twins also pin `(truename "")` = the real `user.dir`), `relativePathClimbsAboveANamedCurrentDirectory`
(WASM under the native stub's preopens: `uiop:getcwd` and absolute truenames); ci-spec
`truename-resolves-symbolic-links` (the same over the staged `e95-links/`),
`pathname-algebra-over-the-flat-namestring`, `pathname-family-and-broadcast-streams`,
`lite-builtins-residue`, `probe-file-existing-and-missing`,
`directory-listing-and-uiop-walkers`,
`namestring-halves-nstring-case-and-environment-enquiry`, `wild-pathnames`.
