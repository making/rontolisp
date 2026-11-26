# Build an Executable from an ASDF System

A system says it is a program the way ASDF says it: `:build-operation
"program-op"`, the function to run as `:entry-point`, and where the executable
goes as `:build-pathname`. `rontolisp build SYSTEM`, or `asdf:make` in a program
rontolisp runs, builds that executable:

```console
$ cat app.asd
(defsystem "app"
  :build-operation "program-op"
  :build-pathname "bin/app"
  :entry-point "app:main"
  :components ((:file "main")))
$ cat main.lisp
(defpackage #:app (:use #:cl) (:export #:main))
(in-package #:app)
(defun main ()
  (format t "hello ~{~a~^ ~}~%" (uiop:command-line-arguments))
  t)
$ rontolisp build app
$ ./bin/app from the executable
hello from the executable
```

## The artifact

What the build writes follows the name, `:build-pathname` below the system's
directory or the `-o` given to `rontolisp build`:

| Name | Artifact | Runs as |
|------|----------|---------|
| no type (`bin/app`) | a [native executable](native.md) for this machine | `./bin/app` |
| `app.jar` | a [JVM](jvm.md) program jar | `java -jar app.jar` |
| `app.class` | a JVM class named after the file | `java -cp . app` |
| `app.wasm` | a [WASI](wasm.md) module (`--component` for a component) | `wasmtime run app.wasm` |

```bash
rontolisp build app -o app.jar
rontolisp build app -o app.wasm
rontolisp build app --native-target linux-aarch64 --optimize=size
```

Every compiler flag reaches the build as it reaches `-o`. `rontolisp build app.asd`
builds the system the `.asd` is named after; `rontolisp build` of a system whose
`:build-operation` is not `program-op` (or a subclass) fails, since `asdf:make`
would only load it.

## How it is built

rontolisp has no heap image to save, so the build is a compile. The system loads
on the interpreter -- the image the build runs in, where a library's build-time
work runs -- and `uiop:dump-image` then compiles that image: each system the
program loaded, read again for the target, followed by `uiop:restore-image` of
the entry point. The executable therefore starts by loading its systems, runs
the restore hooks, then calls the entry point, and its first value is the exit
status: 0 when it is true, 1 when it is `nil` (`uiop:*lisp-interaction*` is
`nil` here; SBCL's image keeps upstream's `t` and exits 0 whatever it returns).
`uiop:*image-dumped-p*` is `:executable` in it.

The entry point can be a function name or a string naming one, a `lambda`
form, or a `(function argument...)` list. Only what loading the systems makes
is in the executable: a definition the build script evaluated outside a system,
or a value set after the systems loaded, is not. A closure as the entry point is
refused by name.

A compiled program cannot build -- it has no compiler -- so `asdf:make` of a
program-op system signals there, naming `rontolisp build`.

## From a build script

A Makefile's `sbcl --eval '(asdf:make :app)'` is a rontolisp program:

```console
$ cat build.lisp
(asdf:make "app")
$ rontolisp build.lisp
```

## deploy

[deploy](https://shinmera.com/docs/deploy/) applications build too: a system with
`:defsystem-depends-on (:deploy)` and `:build-operation "deploy-op"`. deploy's
build-time hooks (`:load`, `:deploy`, `:build`) run on the interpreter while it
builds, so a `:deploy` hook writes its files next to the executable then; its
`:boot` and `:quit` hooks run when the executable starts and ends.
`deploy:data-directory` is the executable's directory -- `bin/` below the
system, where deploy writes the executable, or the directory of the jar.
`deploy:deployed-p` is true there. A `.wasm` build loads deploy without cffi,
since a module has no foreign libraries. Hooks of type `:pre-load` do not run.
