# h49. closer-mop shim: slot definitions are lists, not `slot-definition` metaobjects

Difficulty: Medium

Found 2026-10-30 probing `djula`. Its dependency `access` (`access.lisp:155-355`) does
`(typep slot 'closer-mop:direct-slot-definition)` and dispatches on
`(or symbol keyword string closer-mop:slot-definition)`, and reads with
`closer-mop:slot-definition-name`. The shim (`eval/closer-mop.lisp`) answers
`(name declared-type)` pairs and exports no `slot-definition` /
`direct-slot-definition` / `effective-slot-definition` class:
`The symbol DIRECT-SLOT-DEFINITION is not external in the CLOSER-MOP package`.
djula also names `closer-mop:funcallable-standard-class` and
`set-funcallable-instance-function`.

Back `class-slots`/`class-direct-slots` with real slot-definition instances (classes
`standard-direct-slot-definition`/`standard-effective-slot-definition` under
`slot-definition`) built from `ClosRegistry`. Keep every existing reader working on them
Second consumer: jzon's `coerced-fields`, which sees no fields today, so a CLOS instance
stringifies as `{}` (moved here from `.todo/147`).
