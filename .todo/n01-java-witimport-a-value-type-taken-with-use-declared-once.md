# n01. Java @WitImport: a value type an interface takes with `use`, declared once

Difficulty: Medium

Left by m89 (2026-11-27; `.kb/java-frontend.md`, "A resource of another interface"). A
RESOURCE an interface takes from another with `use` is the member interface of the
`@WitImport` interface standing for the interface defining it. A record, a variant, an enum
or flags taken the same way is still a member type of the USING interface: `wasi:http/client`
declares `error-code` again, 39 cases and three records, beside `wasi:http/types`' own
(`JavaWitImportTest`'s `HTTP_ERROR_CODE` is spliced into both), and the two Java types do not
convert into each other.

- Let the type written decide, as for a resource (`JavaWitImports.Checker.matches`): a member
  type of the `@WitImport` interface standing for the defining interface is accepted, the
  using interface's own member type stays accepted (programs written so keep compiling).
- Where no type is written (an error arm thrown as a `WitException`): the using interface's
  member if it declares one, else the defining interface's.
- `JavaWitTypes.Converters.memberClass` and the static `javaText` / `memberText` name the
  class by the scope's owner alone: they need the class the checker settled on.
  `Description.used` lists resources only; it needs every type a `use` takes.
- `@WitExport` keeps its rule (a used type is a member type of the using scope's class): a
  world's export has no interface standing for the other one.

Pin: drop `Client`'s copy of `ErrorCode` in `JavaWitImportTest`'s HTTP client and catch
`Types.ErrorCode`. `doc/{en,ja}/java/wit.md`: the paragraph on `use` and the types table.
