# n25. Java WIT scaffold: a value type taken with `use`, declared once

Difficulty: Medium

`@WitImport` accepts a record, a variant, an enum or flags an interface takes with `use` as a
member type of the interface standing for the defining one (`.kb/java-frontend.md`, "A type
of another interface"). The skeleton `JavaWitScaffold` writes still declares it again in the
using interface: a world importing `wasi:http/client` gets `error-code` (39 cases, three
records) twice.

- Where the skeleton also declares the defining interface, name the type there
  (`Scope.named`, as a used resource is) and declare it once in that scope.
- `Description.used` lists resources only, and the scaffold follows it to the interfaces it
  declares: decide whether a value type alone pulls its interface in (it needs no import of
  its own) or stays a copy when that interface is not declared.

Pin: `WitScaffolderTest.aJavaSkeletonPassesTheCheckAndCompilesToTheWorld` on a world whose
interface takes a value type with `use`.
