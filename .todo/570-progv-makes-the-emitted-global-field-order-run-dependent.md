# 570. A `progv` in the program makes the emitted global-field order run-dependent

Difficulty: Low (the cause is one `Set.of` iterated into an ordered set; the work
is the fix plus a pinning test that compiles twice and compares)

`.kb/emitted-output-determinism.md` says a 2026-07-26 sweep of all 313
`Map.of`/`Set.of` sites found exactly one whose ITERATION order reached emitted
bytes, and that it was fixed. That is no longer true. Measured 2026-08-29 while
checking whether todo-562 shifted any emitted bytes: compiling the concatenated
ci-spec corpus twice with ONE unmodified jar produced two classes differing in
**211 bytes** -- the static fields `_g$*ERROR-OUTPUT*` and `_g$*STANDARD-INPUT*`
swap places in the constant pool, and every later index shifts with them.

## The cause

`macro/SpecialVarCollector`:

```java
out.addAll(collectDynamicallyBound(List.of(form),
        Set.of(LispNames.STANDARD_OUTPUT_VAR, LispNames.STANDARD_INPUT_VAR, LispNames.ERROR_OUTPUT_VAR)));
```

`collectDynamicallyBound` walks the program and normally adds only the specials
it actually saw bound, in walk order. But a program containing `progv` cannot be
walked statically, so it takes the over-collection arm:

```java
if (bound.size() < specials.size()) {
    for (LispVal expr : topLevelExprs) {
        if (usesProgv(expr)) {
            bound.addAll(specials);   // <- iterates the Set.of
            break;
        }
    }
}
```

`Set.of` scrambles its iteration order per JVM process
(`ImmutableCollections.SALT` from `System.nanoTime()`), so the order those three
names enter the returned `LinkedHashSet` -- and therefore the order
`GlobalVarCollector` mints their JVM static fields -- differs between runs. The
ci-spec corpus reaches it because it has a `progv` case.

`*STANDARD-OUTPUT*` does not move, because something adds it earlier; only the
other two permute.

## The fix

Give the call site an ordered set (`LinkedHashSet` over a `List.of`, or take a
`List<String>` and let the parameter's own order be the contract), and say in
`collectDynamicallyBound`'s javadoc that the parameter's iteration order reaches
emitted output. Then re-check the other `Set`-typed parameters that end up in an
emission-ordered collection the same way -- `WitImportDirective.defpackageForm`
is already flagged in the `.kb` file as the same shape.

## Acceptance

- Compiling one program with a `progv` twice in separate JVMs produces
  byte-identical output. A test can do it in one JVM only if it forces the two
  compiles through different `ImmutableCollections` orders, so the honest pin is
  a unit test on `SpecialVarCollector` asserting the returned order for a
  `progv` program, plus a line in `.kb/emitted-output-determinism.md`.
- Update that `.kb` file's "found that one and no other" sweep claim with this
  second site and the date.
