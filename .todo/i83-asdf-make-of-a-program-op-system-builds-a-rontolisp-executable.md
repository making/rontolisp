# i83. `asdf:make` of a program-op system builds a rontolisp executable

Difficulty: High

`asdf:make` with a `:build-operation` of the program-op family is refused (measured 2026-11-06,
interpreter, deploy 20260101 loaded):

    (defsystem "hello" :defsystem-depends-on (:deploy) :build-operation "deploy-op"
      :build-pathname "hello-bin" :entry-point "hello:main" :components ((:file "main")))
    (asdf:make "hello")
    => ASDF:MAKE: deploy-op is not supported: a system is loaded from source with LOAD-OP and tested with TEST-OP

`asdf:program-op` (and `deploy:deploy-op`, `image-op`) is how a CL project says "this system is an
executable": `:entry-point`, `:build-pathname`, `uiop:*image-entry-point*`. rontolisp already
builds executables (`-o out.jar`, `--native -o out`, `.wasm`), so the system declaration can drive
that output instead of refusing: `asdf:make` / `rontolisp build SYSTEM` compiling the system with
its entry point as the program, `:build-pathname` naming the artifact. deploy's own hooks
(`define-hook :boot/:deploy`) and `deploy:data-directory` need a decision (run at build vs. at start).

What works today: `deploy` loads, `deploy-op` is a real class over `asdf:program-op`, and
`component-build-operation` / `component-entry-point` read back the options.

Done when: a decision is recorded in `.kb/asdf.md` and either `asdf:make` produces a runnable
artifact on the JVM and native targets for the system above, or the refusal names the rontolisp
command that does.
