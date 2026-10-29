# Clojure on the JVM: a host's compiled forms reach what a run-time call defines

Difficulty: Medium

Carried out of h00, which made a JVM class lower its Clojure program's lowering verbs as it
runs (`.kb/jvm-run-time-lowering.md`). Two gaps it left:

1. **Late binding from the host.** A name the program only declares and a later `eval` or
   `load-string` defines stays undefined at the host's own call sites: the host's forms were
   compiled before the definition existed, and the open shape of a declared-only call,
   `(%clojure-call (if (fboundp 'f) #'f f) ...)`, asks the host's own registry. The
   interpreter resolves the name when the call runs. Plan: in a linked class an undefined
   call (and a literal `fboundp` / `#'`) goes through the symbol designator, and the
   dispatchers' `_lookup` miss asks the host's router for the session's table
   (`JvmLinkage.Function` gains the function's id; every program defun of a linked class
   dispatchable, so its class has the case). Pin: a clojure-spec case
   `(declare f) (defn g [] (f)) (eval '(defn f [] 1)) (g)`, interpreter and JVM legs.

2. **The native binary carries no compiler.** `cli/RunTimeCompilerFiles` reads the build's
   code source, which a native image does not have, so its `-o App.class` of such a program
   only warns and the class needs the rontolisp jar on its class path. Options: resolve the
   rontolisp jar of the binary's own version through `am.ik.maven` (`~/.m2`, then the
   repository) and carry its entries, or ship the jar as a resource of the image. Measure the
   binary's size against the first before choosing.
