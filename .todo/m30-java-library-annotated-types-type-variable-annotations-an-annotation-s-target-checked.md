# m30. Java library: annotated types, a type variable's annotations, an annotation's target checked

Difficulty: Medium

What l66 left (`.kb/java-frontend.md`, "Generic types, records and the annotations of members",
its "Not here"): the generic types, a record's components and the annotations of declarations
answer as a JDK's; the annotations written on TYPES do not.

- **Annotated types**: `java.lang.reflect.AnnotatedType` and its four kinds
  (`AnnotatedParameterizedType`, `AnnotatedTypeVariable`, `AnnotatedWildcardType`,
  `AnnotatedArrayType`); `getAnnotatedType`, `getAnnotatedReturnType`,
  `getAnnotatedParameterTypes`, `getAnnotatedExceptionTypes`, `getAnnotatedReceiverType`,
  `getAnnotatedSuperclass`, `getAnnotatedInterfaces`, `getAnnotatedBounds`. The parser reads a
  type annotation and drops it (`JavaParser.annotations`); JSpecify's `@Nullable` is one, and a
  framework reading nullness by reflection asks for them. Refused by name now
  (`JavaLoweringTest#aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition`).
- **A type variable's annotations** (`ElementType.TYPE_PARAMETER`): `TypeVariable` is no
  `AnnotatedElement` here, so a call is a compile error where javac compiles it.
- **`@Target` checked where an annotation is written**: javac refuses `annotation interface not
  applicable to this kind of declaration`; the subset compiles the program and carries none
  (`JavaClasses.appliesTo` decides what is carried, nothing refuses).

Done means: the members above answer as JDK 25 does on the four targets, the refusal is javac's,
and a program naming none of it is byte-identical.
