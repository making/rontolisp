# `uiop/configuration`: XDG paths and the configuration-file search

Difficulty: Medium

Depends on `.todo/353`, `.todo/354`, `.todo/356` (`getenv*`), `.todo/357`
(pathname algebra) and `.todo/358` (`getenv-pathname*`, `probe-file*`). Take it
after those; on top of them it is mostly transcription.

38 externals, **none** present:

```
XDG-CACHE-HOME XDG-CONFIG-DIRS XDG-CONFIG-HOME XDG-CONFIG-PATHNAME
XDG-CONFIG-PATHNAMES XDG-DATA-DIRS XDG-DATA-HOME XDG-DATA-PATHNAME
XDG-DATA-PATHNAMES XDG-RUNTIME-DIR GET-FOLDER-PATH *USER-CACHE*
USER-CONFIGURATION-DIRECTORIES SYSTEM-CONFIGURATION-DIRECTORIES
SYSTEM-CONFIG-PATHNAMES IN-USER-CONFIGURATION-DIRECTORY
IN-SYSTEM-CONFIGURATION-DIRECTORY IN-FIRST-DIRECTORY FIND-PREFERRED-FILE
FILTER-PATHNAME-SET *HERE-DIRECTORY* UIOP-DIRECTORY
RESOLVE-LOCATION RESOLVE-ABSOLUTE-LOCATION RESOLVE-RELATIVE-LOCATION
LOCATION-DESIGNATOR-P LOCATION-FUNCTION-P
VALIDATE-CONFIGURATION-DIRECTORY VALIDATE-CONFIGURATION-FILE
VALIDATE-CONFIGURATION-FORM CONFIGURATION-INHERITANCE-DIRECTIVE-P
INVALID-CONFIGURATION REPORT-INVALID-FORM *IGNORED-CONFIGURATION-FORM*
CLEAR-CONFIGURATION *CLEAR-CONFIGURATION-HOOK* REGISTER-CLEAR-CONFIGURATION-HOOK
UPGRADE-CONFIGURATION
```

## Two halves

**The XDG half is genuinely useful and fully implementable** -- it is
`getenv` + pathname algebra + `probe-file`, nothing else. Ten `xdg-*` functions
plus `user-configuration-directories` / `system-configuration-directories` /
`find-preferred-file` / `in-first-directory` / `filter-pathname-set` /
`*user-cache*`. A rontolisp program that wants "where do I put my config"
has no answer today, so this half stands on its own merits, independent of ASDF.

`get-folder-path` is the Windows/macOS special-folder lookup; on unix upstream
derives it from XDG, which is the answer here too.

**The configuration-DSL half is ASDF's own** -- `resolve-location`,
`validate-configuration-form` and the inheritance directives interpret the
`source-registry.conf` / `asdf-output-translations` sexp language. rontolisp's
ASDF subset (`.kb/asdf.md`) does not read those files. Implement the pure
interpreters anyway (`resolve-location` and friends are total functions over a
sexp; `location-designator-p`, `location-function-p`,
`configuration-inheritance-directive-p` are predicates), because they are
portable and cost nothing, and reserve `not-implemented-error` for the ones that
must READ a config file that this ASDF subset does not consult. Say which is
which in `.kb/uiop.md`.

`clear-configuration` / `*clear-configuration-hook*` /
`register-clear-configuration-hook` / `upgrade-configuration` are hook lists --
real, like `.todo/362`'s image hooks, since only the dumping is impossible.

**`*here-directory*` and `uiop-directory`** name where uiop's own source lives.
uiop is built in here; answer with the source-loader's notion of the library
root rather than inventing a path that does not exist.

## Gate

`UiopCoverageTest` reports `uiop/configuration 38/38`. `LispEvaluatorTest` pins
the XDG table with `HOME` / `XDG_CONFIG_HOME` / `XDG_CONFIG_DIRS` set and unset,
and one `ci-spec.yaml` case prints `(uiop:xdg-config-home)` on all four backends
-- `getenv` already differs per backend in how it reaches the host environment,
so this is where that shows.
