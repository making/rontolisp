# uiop/filesystem

`uiop/filesystem` probes the file system, walks directory trees and mutates
them. **All 32 exports are implemented**, and the read side runs identically on
all four backends — over the one existence probe (`probe-file`) and the one
directory listing (`directory`) every backend already carries.

Every name is reachable through either spelling: `uiop:probe-file*` and
`uiop/filesystem:probe-file*` are the same function
([The uiop Package](../uiop.md#sub-packages)).

## Probing and walking

| Function | What it answers |
|----------|-----------------|
| `uiop:file-exists-p` | the pathname when the file exists, `nil` otherwise — the same contract as `probe-file`, which it lowers onto on every backend |
| `uiop:directory-exists-p` | the pathname (with a trailing `/`) when the DIRECTORY exists, `nil` otherwise — the directory twin of `file-exists-p`, and what tells an empty directory from a missing one |
| `uiop:probe-file*` | parse the designator and probe: the parsed pathname when something is there (`:truename t` answers its truename instead), `nil` otherwise — including for a designator `ensure-pathname` rejects, such as a wildcard |
| `uiop:truename*` | the nil-tolerant `truename`: the truename when the file exists (trying the directory form too, where a missing trailing separator defeats some implementations' `truename`), `nil` otherwise — and `nil` for `nil` |
| `uiop:directory*` | `directory` — upstream's per-implementation symlink keys are accepted and dropped: `directory` answers each entry as named, never resolved, which is what upstream asks for with them |
| `uiop:directory-files` | the non-directory entries of a directory — `(directory "db/*.*")` with the subdirectories dropped. UIOP's optional second argument, the namestring of a name-and-type wildcard, filters them exactly as `directory` matches; omitting it lists everything, and a pattern carrying a directory component is an error |
| `uiop:subdirectories` | the subdirectories of a directory, each with its trailing `/` |
| `uiop:collect-sub*directories` | walk a directory tree: `collectp` decides what reaches `collector`, `recursep` what is descended into. Every directory handed over is in directory form, root included |
| `uiop:filter-logical-directory-results` | its entries unchanged — logical pathnames cannot exist here (`logical-pathname-p` is `nil` on every backend), so there is nothing to filter |
| `uiop:safe-file-write-date` | `file-write-date` with the missing-file `file-error` swallowed |
| `uiop:native-namestring` | `"/tmp/x"` — the host-OS spelling of a pathname, which here IS the namestring, so this is `namestring` |
| `uiop:parse-native-namestring` | `parse-unix-namestring` plus the `ensure-pathname` constraints — `os-unix-p` is `t` outright, so the native spelling IS the Unix one |
| `uiop:get-pathname-defaults` | the defaults relative names resolve against — `*default-pathname-defaults*` (initially `#P""`, the pathname designating the host working directory) unless an absolute defaults argument is given |

```lisp
(print (uiop:probe-file* "definitely-missing.txt"))
(print (uiop:truename* nil))
(print (uiop:parse-native-namestring "/tmp/x" :ensure-directory t))
(print (string (uiop:inter-directory-separator)))
```

```
NIL
NIL
#P"/tmp/x/"
":"
```

## Environment pathnames

| Function | What it answers |
|----------|-----------------|
| `uiop:inter-directory-separator` | `#\:` — the Unix separator, on every backend |
| `uiop:split-native-pathnames-string` | split a `:`-separated native string and parse each piece; an empty piece denotes `nil` |
| `uiop:getenv-pathname` | the environment variable's value parsed as a native pathname, checked against the `ensure-pathname` constraints |
| `uiop:getenv-pathnames` | the variable's `:`-separated values parsed the same way; empty entries answer `nil` |
| `uiop:getenv-absolute-directory` | `getenv-pathname` with `:want-absolute t :ensure-directory t` |
| `uiop:getenv-absolute-directories` | `getenv-pathnames` with `:want-absolute t :ensure-directory t` |

```lisp
(progn (setf (uiop:getenv "UIOP_FS_DEMO") "/tmp:/var")
       (print (uiop:getenv-pathnames "UIOP_FS_DEMO"))
       (print (uiop:getenv-pathname "UIOP_FS_UNSET")))
```

```
(#P"/tmp" #P"/var")
NIL
```

## Symlinks and the implementation directory

Every backend resolves symbolic links the way [`truename`](../functions/truename.md)
does, so `uiop:*resolve-symlinks*` defaults to `t` as upstream's does:

| Function | What it answers |
|----------|-----------------|
| `uiop:*resolve-symlinks*` | `t` |
| `uiop:resolve-symlinks` / `uiop:truenamize` | the pathname with every symbolic link among the parts that exist resolved, the rest kept as spelled (a relative path stays relative) |
| `uiop:resolve-symlinks*` | `resolve-symlinks` when the flag is true, the argument untouched when it is not |
| `uiop:lisp-implementation-directory` | `nil` — there is no install directory to name: no `compile-file`, no fasl cache |
| `uiop:lisp-implementation-pathname-p` | `nil` — nothing is under a directory that does not exist |

## The working directory

| Function | What it answers |
|----------|-----------------|
| `uiop:call-with-current-directory` | run the thunk with `*default-pathname-defaults*` bound to the directory and the process working directory changed to it. Inherits `chdir`'s decision, so a non-`nil` directory signals `not-implemented-error` on every backend and a `nil` one just runs the thunk |
| `uiop:with-current-directory` | the macro over it: `(uiop:with-current-directory (dir) body...)` runs the body under `call-with-current-directory`. An absent directory is `nil`, which just runs the body |

## Mutating the tree

| Function | What it answers |
|----------|-----------------|
| `uiop:ensure-all-directories-exist` | create every pathname's parent directories (`ensure-directories-exist` over each) |
| `uiop:rename-file-overwriting-target` | `rename-file` — the move already replaces the target, so the overwrite is the primitive's own |
| `uiop:delete-file-if-exists` | delete a file, answering `nil` instead of signalling when it is not there — the whole reason UIOP exports it |
| `uiop:delete-empty-directory` | delete an empty directory, over the same file primitive (it removes an empty directory as well as a file) |
| `uiop:delete-directory-tree` | `rm -rf` as a portable recursive walk: the directory must pass the `:validate` predicate (a missing one is a `parameter-error`, and so is a failing one), a missing directory signals unless `:if-does-not-exist` is `:ignore` |

The four mutating operations are real on all four backends, where their primitives
are: `ensure-all-directories-exist` over `%make-directories`, `rename-file-overwriting-target`
over `%rename-file` and `delete-file-if-exists` over `%delete-file` run everywhere, pinned
by the ci-spec `filesystem-write-create-rename-delete-and-probe` case on every backend.
`delete-empty-directory` (and with it `delete-directory-tree`) removes an empty directory on
every backend too; a non-empty one signals the `file-error`.
