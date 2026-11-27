# n10. Java: a class object's test of a class the program makes no instance of calls an undefined function

Difficulty: Medium

A compile prints `warning: the function java.io.FileDescriptor? is undefined; compiled as
a call-time error` (also `java.nio.channels.FileChannel?`, `ThreadGroup?`,
`AbstractStringBuilder?`) for a program whose class objects include a class of the
library's Java source that it makes no instance of: the class object's `isInstance`
lambda calls the structure's predicate (`(LAMBDA (|%o|) (|ThreadGroup?| |%o|))` in the
`Class.new(..)` of `ThreadGroup%class`), and the structure is not defined.

Measured 2026-11-27 (before and after m83, so not that item's): a run of tests with
`assertThat(x).asInstanceOf(InstanceOfAssertFactories.STRING)` AND a property read by name
(`extracting("name")`) prints the first three on every compiled leg; with a constructor
found by reflection (`getDeclaredConstructor(..).newInstance(..)`) the fourth too. Either
alone prints none. The run's output is right on four backends: nothing asks those class
objects for an instance.

- Reproduce: the corpus case `soft-assertions-collect-what-fails-and-the-chain-goes-on`
  of `java-spec.yaml` compiled with `-o x.wasm` prints the four.
- Find where the class objects of member types are made (`JavaLowering.classReflection`,
  the `%class` objects of types only a member's signature names) and answer `NIL` for a
  class with no structure, as a class nobody has is answered
  (`JavaLowering.instanceTest`: "has no instance").
- Pin by a `warnings:` of a `standalone:` case (`JavaSpecE2eTest.assertEnded` compares
  what the compile printed).
