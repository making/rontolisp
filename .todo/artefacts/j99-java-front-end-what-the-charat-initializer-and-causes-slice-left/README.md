# j99: the probes

What a compiler says of checked exceptions, asked of javac 25 one program at a time
(`compare.sh File.java`: javac's first error against the front end's, by line and
message; `JAR` is the exec jar). Written here; javac's output is not kept. What the
answers showed is in `.kb/java-frontend.md`, "Exceptions" ("Checked exceptions are
checked").

| Probes | What they ask | Programs | Differs from javac |
|---|---|---|---|
| `refused/T1.java`, `T2.java` | every kind of error in one class, in the order javac reports them | 2 | only the first error is reported |
| `refused/V1` .. `V22` | constructors, initializers, anonymous classes, enum constants, lambdas, method references | 22 | nothing |
| `refused/V31` .. `V64` | overrides, a throws clause of no throwable, type parameters in a clause, resources, a catch parameter thrown again, `finally` | 34 | `V36` (an access javac refuses, not checked here), `V58` / `V59` (`Cloneable` and `Object.clone()` are no members here) |
| `refused/V71` .. `V95` | the order of the kinds, the clauses, inference from a lambda's body | 25 | `V92`: a local class's flow is checked where it is lowered |
| `accepted/W1` .. `W40` | programs a compiler ACCEPTS (33) and their refused neighbours (7) | 40 | nothing |
| `accepted/W51` .. `W67` | a catch parameter thrown from a lambda and from an anonymous class, inference through a conditional and from two lambdas, wildcards and type variables thrown | 13 | nothing |
| `order/Order.java`, `Order2.java` | the kinds' order in a class, a class with an error of typing after one with a flow error | 2 | |
| `order/Warn.java` | the catch clauses javac warns of | 1 | |

`Checked.java`: what `checked` of the corpus case
`try-catch-finally-over-one-condition-holding-the-thrown-object` is, as a file of its
own. `cleanup-exit.lisp`: the cleanup that leaves by an exit of its own
(`.kb/error-handling.md`, Phase 1); SBCL, the interpreter, the JVM and wasm print the
same since j99.

`init/`: what marking a lazy class unusable after its initialization failed would cost
a program that throws nothing, spelled in Java (wasm / class bytes): `Lazy` as it is
lowered today 18,744 / 34,421; `Marked` (a `finally` setting a flag) 18,828 / 34,732;
`Unusable` (a later use throwing `NoClassDefFoundError`) 50,092 / 77,358.
