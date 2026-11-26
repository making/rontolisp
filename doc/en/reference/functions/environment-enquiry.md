# lisp-implementation-type lisp-implementation-version software-type software-version machine-type machine-version machine-instance short-site-name long-site-name

`(lisp-implementation-type)` -- `(lisp-implementation-version)` -- `(software-type)` -- `(software-version)` -- `(machine-type)` -- `(machine-version)` -- `(machine-instance)` -- `(short-site-name)` -- `(long-site-name)`

The environment enquiry functions. Each answers a string or `nil`, and every answer here is a constant: nothing is read from the host, so a User-Agent or a banner a library composes out of them is the same string on every backend except for `machine-type`.

| function | answer |
| --- | --- |
| `lisp-implementation-type` | `"rontolisp"` |
| `lisp-implementation-version` | the project version -- the same string `rontolisp --version` and `(getf (rontolisp:version) :version)` report |
| `software-type` | `"Unix"` -- the same claim `uiop:os-unix-p` and `uiop:operating-system` make: every backend presents the POSIX-shaped file model |
| `software-version` | `nil` |
| `machine-type` | the ABI the running artifact targets: `"JVM"` on the interpreter and the JVM backend, `"WASM32"` on both WASM backends |
| `machine-version` | `nil` |
| `machine-instance` | `nil` |
| `short-site-name` | `nil` |
| `long-site-name` | `nil` |

`machine-type` deliberately names the ABI rather than the host processor: a class file and a wasm module are both CPU-independent, which is why [`uiop:architecture`](../uiop/os.md) answers `:jvm` / `:wasm32` the same way. Everything rontolisp cannot know answers `nil`, which is what Common Lisp prescribes when no appropriate and relevant result can be supplied -- a fabricated host name or version would be an answer rather than the absence of one.

```lisp
(list (lisp-implementation-type) (software-type) (machine-type) (machine-version))
; => ("rontolisp" "Unix" "JVM" NIL)
```

The version is the build's own, so it is shown rather than asserted:

```lisp
(format nil "dexador/1.0 (~A ~A); ~A"
        (lisp-implementation-type) (lisp-implementation-version) (software-type))
```

## Backend support

All four backends -- one definition in rontolisp source, spliced into the program when it is referenced. Only `machine-type` differs between backends.
