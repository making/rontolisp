# k06: the probes

`order/`: which flow error javac 25 reports first, by kind, of a top-level class, its
member classes and the local and anonymous classes in its bodies (41 programs, `L*`, `M*`,
`N*`, `S*`). Compared one at a time with the j99 script:
`JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar bash .todo/artefacts/j99-java-front-end-what-the-charat-initializer-and-causes-slice-left/compare.sh order/L1.java`.
All 41 and the j99 probes but `V36`, `V58`, `V59` answer as javac since k06. What they
showed is in `.kb/java-frontend.md`, "The flow". javac's output is not kept.

| Probes | What they ask |
|---|---|
| `L1`..`L9`, `L19`..`L25` | a local or anonymous class against the methods and initializers around it |
| `L10`..`L17`, `M1`, `M2` | member classes against their class, each kind |
| `L18`, `L5` | an error of typing after a flow error in the same top-level class |
| `N1`..`N7` | captures as written; an unassigned local a local class reads against its own |
| `S1`..`S5` | static and instance initializers against the methods, each kind |
| `L27` | the `unreachable catch clause` warnings' order |

`clone/`: what `Object.clone()` and `finalize()` would have to do (`.todo` item that
carries them). `K1` runs under a JDK: a shallow copy of a `Cloneable` class (`3 4 9 true
true`), a subclass copied as itself, `CloneNotSupportedException: K1$Q` (the binary
name), a record, `Enum.clone()` throwing one with no message, `finalize()` overridden and
called. `K2`..`K9` are refused or accepted by javac: `clone() has protected access in
Object` for a receiver of `Object`, of another class, of a type variable (`K2`, `K3`,
`K5`, `StringBuilder` in `K9`), none for the class around a nested one (`K6`), `cannot
find symbol` through an interface (`K4`), `CloneNotSupportedException` and `Throwable`
unreported (`K7`, `K8`), `ArrayList.clone()` public (`K9`). javac also prints `[removal]
finalize() in Object has been deprecated and marked for removal` by default.
