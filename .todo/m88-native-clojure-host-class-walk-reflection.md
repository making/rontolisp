# Native binary: a Clojure program's host class walk calls unregistered reflection

Difficulty: Medium

On the native binary's interpreter a class literal of a JDK class is still its `forName` object
(the lowering keeps it where the host is), while every `clojure.lang` class value is a designator
(`%clojure-host-classes-p` is false: `.kb/clojure-frontend.md`, "Class values"). A program naming
a `java:` operator takes the host class walk (`clojure.lisp`, "Host classes"), whose arms call
`Class#isAssignableFrom`, `getSuperclass` and `getInterfaces` -- none registered for reflection,
so the binary dies on a `MissingReflectionRegistrationError`, an `Error` no `handler-case` takes:

```clojure
(println (.getName String) (isa? (class "a") CharSequence))
```

`(isa? (class f) clojure.lang.IFn)` of a fn answers false there (true on the JVM's interpreter):
the walk meets a designator child and a designator parent and reads neither as its class keyword
(`%clojure-class-designator-key` is the designator walk's alone). `supers`/`bases` of a host class
object already answer from `%clojure-class-bases-text` where the host makes no class value
(`%clojure-host-class-bases`).

Done when the program above prints the JVM interpreter's line on the native binary, and a host
class walk there answers `isa?`/`parents`/`ancestors` of a JDK class literal and a fn's class like
the JVM's interpreter. Either register the reflection the arms call, or read a host class object as
its name's designator wherever `%clojure-host-classes-p` is false.
