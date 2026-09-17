# JVM backend: a hash table used as a key of an `eq`/`eql` table may hash by its contents

Difficulty: Medium

Found while working `.todo/835` (2026-09-17), NOT yet reproduced. That item found that the
JVM backend hashed a VECTOR key of an identity table by its contents: a vector containing
itself overflowed the stack, and a vector mutated after `setf gethash` lost its entry. It
switched vectors to identity hashing (`codegen/jvm/JvmHashRuntimeBuilder`) and left the
other mutable aggregates alone. A hash table as a key is the suspected next case.

## To do

1. Reproduce first, on all four backends: a hash table stored as a key of an `eq`/`eql`
   table, then mutated (`setf gethash` on the key table), then looked up; and a table that
   contains itself as a value used as a key.
2. Audit every mutable aggregate `_hash` handles on the JVM (hash tables, structures,
   strings with fill pointers, arrays) against `.kb/hash-tables.md`'s rule that aggregates
   hash by identity in identity tables. Fix the ones that diverge from the interpreter.
3. A failing test per case (a JVM integration test and a `ci-spec.yaml` case if cross-backend).
4. If the premise does not reproduce, record that in `.kb/hash-tables.md` and close.
