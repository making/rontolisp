# k16. Java front end: `Object.clone()` and `finalize()` as members

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated.

Left by k06. `Object.clone()` and `finalize()` are no members here, so a program calling
`super.clone()` is refused (`Object.clone() is not supported ...`) and the checked
exceptions of their clauses (`CloneNotSupportedException`, `Throwable`) are met by no
program (the j99 probes `refused/V58`, `V59`; `Cloneable` is `cannot find symbol`).
What a JDK 25 and javac answer: `.todo/artefacts/k06-java-front-end-what-the-checked-exceptions-slice-left/clone/`
(README there).

## What it takes

- **`Cloneable`** (library source, an empty interface) and **`Object.clone()`**: protected,
  `throws CloneNotSupportedException`; a shallow copy of the structure for an instance of
  a `Cloneable` class (`copy-structure` exists on four backends; the identity number and
  the monitor are side tables keyed by identity, so a copy is a new identity), else
  `CloneNotSupportedException` of the binary name (`K1$Q`). The run time's text could be a
  `native` member of `rontolisp.internal.Support` (`JavaIntrinsics.natives`). The array arm
  exists (`JavaLowering`, `clone` on an `ArrayType`).
- **Dispatch**: a call resolved to `Object.clone()` through a class that does not override
  it must still reach a subclass's override at run time. `toString`/`equals`/`hashCode`
  do this through `objectMethod` and generated dispatchers; an intrinsic member does not.
  Decide: a dispatcher like theirs, or `clone`/`finalize` declared in Java source.
- **Access (JLS 6.6.2.1)**: javac refuses `o.clone()` through `Object`, another class, a
  type variable (`clone() has protected access in Object`), accepts it through the class
  around a nested one; through an interface it is `cannot find symbol`. `checkAccess`
  skips intrinsic and library owners today (`deviations.md`: protected is reached from
  every class under its class), so without the rule `list.clone()` would run
  `Object.clone()` on the library's `ArrayList`.
- **The library's public `clone()`**: of the library's classes a JDK's `ArrayList`,
  `ArrayDeque`, `HashMap`, `HashSet`, `Hashtable`, `Vector` (and `Stack`), `Locale`,
  `CopyOnWriteArrayList` are `Cloneable` with a public `clone()` (asked by reflection);
  since k47 and k55 also `TreeMap`, `TreeSet`, `LinkedList`, `EnumSet`, `EnumMap`,
  `ConcurrentSkipListMap` / `Set` (each declaring one) and `LinkedHashMap` /
  `LinkedHashSet` (`HashMap`'s and `HashSet`'s; asked of JDK 25 by reflection,
  2026-11-20). Each needs its own copy of what it holds (a shallow copy of the structure
  would share the backing array, table or tree). `Enum.clone()` is final and throws with no message.
- **`finalize()`**: protected, `throws Throwable`, does nothing; nothing calls it (a JDK
  need not). javac's `[removal]` warning is one of those not printed (`deviations.md`).
- Probes: the j99 `compare.sh` over `clone/K2`..`K9`; `K1` as a java-spec case on four
  backends. Docs: the overview's `Object` row, `deviations.md`.

## A Servlet program's cookies wait for it (k97, 2026-11-21)

`jakarta.servlet.http.Cookie` (the API's sources jar, not ours to edit) is
`implements Cloneable, Serializable` and its public `clone()` calls `super.clone()` and
catches `CloneNotSupportedException`. So every Servlet program naming a cookie -- `new
Cookie(..)`, `request.getCookies()` (the container makes them) -- is refused at the API's
`Cookie.java:60` with `cannot find symbol: class Cloneable` (a JDK class the library lacks
is `cannot find symbol` when its simple name is `java.lang`'s, not "is not supported":
worth fixing with this item). What `Cookie` needs is the `super.clone()` of a class whose
superclass is `Object`; what a whole `Cloneable` needs is above. The k97 corpus program
(`java-http-server-spec.yaml`, `servlet`) names no cookie; add a cookie request there once
this lands (`.kb/servlet-httpserver.md`).
