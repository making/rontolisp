package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one rule {@code java:reify} and {@code java:proxy} implement an interface by, which
 * the interpreter's {@code Proxy} handler and a compiled program's generated class both
 * follow: the methods the implementing class declares and which function each calls.
 */
class JavaImplementationsTest {

	private static final ReflectiveJavaClasses CLASSES = ReflectiveJavaClasses.instance();

	private static JavaType type(String name) {
		return Objects.requireNonNull(CLASSES.find(name), name);
	}

	private static List<String> slots(JavaImplementation implementation) {
		List<String> slots = new ArrayList<>();
		for (JavaImplementation.Slot slot : implementation.slots()) {
			slots.add(slot.dispatchKey() + "=" + slot.implementation());
		}
		return slots;
	}

	private static JavaImplementation reify(String iface, String... designators) {
		return JavaImplementations.reify(type(iface), List.of(designators), CLASSES);
	}

	private static JavaImplementation resolve(String form) {
		return JavaImplementations.resolve((LispCons) LispReader.readAllFromString(form).get(0), CLASSES);
	}

	// The designated method calls its function; an abstract method none names throws;
	// a default method keeps its body; Object implements a redeclared equals.
	@Test
	void eachDesignatorImplementsTheOneMethodItNames() {
		assertThat(slots(reify("java.util.Comparator", "compare")))
			.containsExactly("compare(java.lang.Object,java.lang.Object)int=0");
		assertThat(slots(reify("java.util.Iterator", "hasNext"))).containsExactly("hasNext()boolean=0",
				"next()java.lang.Object=-1");
		assertThat(slots(reify("java.util.function.Function", "apply", "andThen"))).containsExactly(
				"andThen(java.util.function.Function)java.util.function.Function=1",
				"apply(java.lang.Object)java.lang.Object=0");
	}

	@Test
	void objectsEqualsHashCodeAndToStringMayBeImplemented() {
		JavaImplementation implementation = reify("java.lang.Runnable", "run", "toString", "hashCode");
		assertThat(slots(implementation)).containsExactly("run()void=0", "hashCode()int=2",
				"toString()java.lang.String=1");
		assertThat(implementation.declaresToString()).isTrue();
		assertThat(reify("java.lang.Runnable", "run").declaresToString()).isFalse();
		assertThat(reify("java.lang.Runnable", "run").defaultToString()).isEqualTo("#<java-reify java.lang.Runnable>");
		// Comparator redeclares equals: implementing it overrides Object's.
		assertThat(slots(reify("java.util.Comparator", "compare", "equals")))
			.containsExactly("compare(java.lang.Object,java.lang.Object)int=0", "equals(java.lang.Object)boolean=1");
	}

	// A name several parameter lists share must be tagged, in java:call's tag syntax.
	@Test
	void aTagPicksAnOverload() {
		assertThat(slots(reify("java.lang.Appendable", "append(char)"))).containsExactly(
				"append(char)java.lang.Appendable=0", "append(java.lang.CharSequence)java.lang.Appendable=-1",
				"append(java.lang.CharSequence,int,int)java.lang.Appendable=-1");
		// _ matches any type; the tag must still leave one method.
		assertThat(slots(
				reify("java.lang.Appendable", "append(CharSequence,_,_)", "append(char)", "append(CharSequence)")))
			.containsExactly("append(char)java.lang.Appendable=1",
					"append(java.lang.CharSequence)java.lang.Appendable=2",
					"append(java.lang.CharSequence,int,int)java.lang.Appendable=0");
		assertThatThrownBy(() -> reify("java.lang.Appendable", "append(_)"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("append(_) names more than one method");
	}

	@Test
	void aDesignatorThatNamesNoMethodOrSeveralIsAnError() {
		assertThatThrownBy(() -> reify("java.util.Comparator", "nope")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("java:reify: interface java.util.Comparator has no method nope");
		assertThatThrownBy(() -> reify("java.lang.Appendable", "append")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("java:reify: append names more than one method of java.lang.Appendable: append(char),"
					+ " append(java.lang.CharSequence), append(java.lang.CharSequence,int,int)");
		assertThatThrownBy(() -> reify("java.util.Iterator", "next", "next()"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("java:reify: java.util.Iterator.next() is implemented twice");
		assertThatThrownBy(() -> reify("java.util.Iterator", "next(")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("malformed parameter tag");
	}

	// A covariant variant is a method of its own the designator's function implements.
	@Test
	void everyReturnTypeVariantOfTheMethodIsImplemented() {
		assertThat(slots(reify(Narrowed.class.getName(), "get"))).containsExactly("get()java.lang.Object=0",
				"get()java.lang.String=0");
	}

	/** A redeclaration narrowing a generic method's erased return type. */
	public interface Narrowed extends java.util.function.Supplier<String> {

		@Override
		String get();

	}

	// java:proxy routes every method -- default ones too -- to its one callable; only
	// Object's three keep their identity behavior.
	@Test
	void aProxyRoutesEveryMethodButObjectsThree() {
		assertThat(slots(JavaImplementations.proxy(type("java.util.function.Function"), CLASSES))).containsExactly(
				"andThen(java.util.function.Function)java.util.function.Function=0",
				"apply(java.lang.Object)java.lang.Object=0",
				"compose(java.util.function.Function)java.util.function.Function=0");
		assertThat(slots(JavaImplementations.proxy(type("java.util.Comparator"), CLASSES)))
			.contains("compare(java.lang.Object,java.lang.Object)int=0", "reversed()java.util.Comparator=0")
			.noneMatch(slot -> slot.startsWith("equals("));
	}

	// A function passed at a :functional site implements every abstract method (each
	// return-type variant of it) by its arguments; a default method and Object's three --
	// a redeclared equals too -- are no slot.
	@Test
	void aFunctionalImplementationRoutesEveryAbstractMethodAndNoOther() {
		assertThat(slots(JavaImplementations.functional(type("java.util.function.Function"), CLASSES)))
			.containsExactly("apply(java.lang.Object)java.lang.Object=0");
		assertThat(slots(JavaImplementations.functional(type("java.util.Comparator"), CLASSES)))
			.containsExactly("compare(java.lang.Object,java.lang.Object)int=0");
		assertThat(slots(JavaImplementations.functional(type("java.util.Iterator"), CLASSES)))
			.containsExactly("hasNext()boolean=0", "next()java.lang.Object=0");
		assertThat(slots(JavaImplementations.functional(type(Narrowed.class.getName()), CLASSES)))
			.containsExactly("get()java.lang.Object=0", "get()java.lang.String=0");
		JavaImplementation runnable = JavaImplementations.functional(type("java.lang.Runnable"), CLASSES);
		assertThat(runnable.proxy()).isFalse();
		assertThat(runnable.defaultToString()).isEqualTo("#<java-reify java.lang.Runnable>");
	}

	// The markers a form ends in are no part of it -- after a java:reify's interface, a
	// java:proxy's callable, a java:subclass's callable -- and the implementation carries
	// them: :java-false hands its functions Java's false as |false|. A Comparator's
	// compare reads a boolean answer only for a function implementing it by its
	// arguments at :java-false (Clojure's AFunction.compare), never a proxy's callable.
	@Test
	void theMarkersAFormEndsInAreCarriedByItsImplementation() {
		JavaImplementation proxy = resolve("(java:proxy \"java.util.function.Consumer\" f :java-false)");
		assertThat(proxy.resolved()).isTrue();
		assertThat(proxy.javaFalse()).isTrue();
		assertThat(resolve("(java:proxy \"java.util.function.Consumer\" f)").javaFalse()).isFalse();
		JavaImplementation reify = resolve(
				"(java:reify \"java.util.Comparator\" \"compare\" f :functional :java-false)");
		assertThat(slots(reify)).containsExactly("compare(java.lang.Object,java.lang.Object)int=0");
		assertThat(reify.readsComparison(reify.slots().get(0))).isTrue();
		JavaImplementation unmarked = resolve("(java:reify \"java.util.Comparator\" \"compare\" f :java-false)");
		assertThat(unmarked.readsComparison(unmarked.slots().get(0))).isFalse();
		JavaImplementation subclass = resolve(
				"(java:subclass \"java.lang.Thread\" '() '(\"run\") (lambda (this m) nil) :java-false :functional)");
		assertThat(subclass.resolved()).isTrue();
		assertThat(subclass.markers()).isEqualTo(new JavaMarkers(true, true));
		JavaImplementation compare = JavaImplementations.functional(type("java.util.Comparator"), CLASSES,
				new JavaMarkers(true, true));
		assertThat(compare.readsComparison(compare.slots().get(0))).isTrue();
		JavaImplementation plain = JavaImplementations.functional(type("java.util.Comparator"), CLASSES);
		assertThat(plain.readsComparison(plain.slots().get(0))).isFalse();
		JavaImplementation function = JavaImplementations.functional(type("java.util.function.ToIntBiFunction"),
				CLASSES, new JavaMarkers(true, true));
		assertThat(function.readsComparison(function.slots().get(0))).isFalse();
		JavaImplementation proxied = JavaImplementations.proxy(type("java.util.Comparator"), CLASSES)
			.withMarkers(new JavaMarkers(true, true));
		assertThat(proxied.slots()).allMatch(slot -> !proxied.readsComparison(slot));
	}

	// A form resolves before it runs only when a compiled program can implement it:
	// literal names, a public interface found, every return type public.
	@Test
	void aFormResolvesWhenACompiledProgramCanImplementIt() {
		JavaImplementation resolved = resolve("(java:reify \"java.lang.Runnable\" \"run\" f)");
		assertThat(resolved.resolved()).isTrue();
		assertThat(resolved.proxy()).isFalse();
		assertThat(resolve("(java:proxy \"java.lang.Runnable\" f)").proxy()).isTrue();
		assertThat(resolve("(java:reify iface \"run\" f)").reason())
			.isEqualTo("the interface names are not a literal string or a quoted list of them");
		assertThat(resolve("(java:reify \"java.lang.Runnable\" name f)").reason())
			.isEqualTo("method name 1 is not a literal string");
		assertThat(resolve("(java:reify \"java.lang.Runnable\" \"run\")").reason()).isEqualTo("the form is malformed");
		assertThat(resolve("(java:reify \"no.such.Iface\" \"run\" f)").reason())
			.isEqualTo("class no.such.Iface is not found");
		assertThat(resolve("(java:reify \"java.lang.String\" \"length\" f)").reason())
			.isEqualTo("java:reify expects an interface, got java.lang.String");
		assertThat(resolve("(java:proxy \"java.lang.String\" f)").reason())
			.isEqualTo("java:proxy expects an interface, got java.lang.String");
		assertThat(resolve("(java:reify \"" + Hidden.class.getName() + "\" \"run\" f)").reason())
			.isEqualTo("interface " + Hidden.class.getName() + " is not public");
		// The error the form raises when it runs is why it is not resolved before.
		assertThat(resolve("(java:reify \"java.util.Comparator\" \"nope\" f)").reason())
			.isEqualTo("java:reify: interface java.util.Comparator has no method nope");
	}

	/** An interface a compiled program cannot name. */
	interface Hidden {

		void run();

	}

	// java:reify of several interfaces: one object implementing the most specific of
	// them -- a superinterface of another listed one is implied, so a default method it
	// and the subinterface both declare is none a class must implement, while two
	// unrelated defaults of one method are --, a designator naming a method of any of
	// them, the messages naming the interface that declares it.
	@Test
	void aReifyOfSeveralInterfacesImplementsTheMostSpecificOfThem() {
		JavaImplementation both = JavaImplementations.reify(
				List.of(type("java.lang.Runnable"), type("java.util.function.Supplier")), List.of("run", "get"),
				CLASSES);
		assertThat(slots(both)).containsExactly("get()java.lang.Object=1", "run()void=0");
		assertThat(both.interfaceNames()).isEqualTo("java.lang.Runnable java.util.function.Supplier");
		assertThat(both.declaringName("get()")).isEqualTo("java.util.function.Supplier");
		assertThat(both.defaultToString()).isEqualTo("#<java-reify java.lang.Runnable java.util.function.Supplier>");
		JavaImplementation list = JavaImplementations.reify(
				List.of(type("java.lang.Iterable"), type("java.util.List"), type("java.util.Collection")),
				List.of("size"), CLASSES);
		assertThat(list.interfaceNames()).isEqualTo("java.util.List");
		assertThat(slots(list)).contains("size()int=0", "get(int)java.lang.Object=-1")
			.noneMatch(slot -> slot.startsWith("spliterator()") || slot.startsWith("stream()"));
		assertThat(slots(JavaImplementations.reify(List.of(type("java.util.List"), type("java.util.Set")),
				List.of("size"), CLASSES)))
			.contains("spliterator()java.util.Spliterator=-1")
			.noneMatch(slot -> slot.startsWith("stream()"));
		assertThatThrownBy(() -> JavaImplementations
			.reify(List.of(type("java.lang.Runnable"), type("java.util.function.Supplier")), List.of("nope"), CLASSES))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("java:reify: interfaces java.lang.Runnable java.util.function.Supplier have no method nope");
		assertThatThrownBy(() -> JavaImplementations.reify(
				List.of(type("java.lang.Runnable"), type("java.util.function.Supplier")), List.of("get", "get()"),
				CLASSES))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("java:reify: java.util.function.Supplier.get() is implemented twice");
		JavaImplementation resolved = resolve(
				"(java:reify '(\"java.lang.Runnable\" \"java.util.function.Supplier\") \"run\" f \"get\" g)");
		assertThat(resolved.resolved()).isTrue();
		assertThat(resolved.interfaceNames()).isEqualTo("java.lang.Runnable java.util.function.Supplier");
		assertThat(resolve("(java:reify '(\"java.lang.Runnable\" \"java.lang.Runnable\") \"run\" f)").reason())
			.isEqualTo("java:reify names interface java.lang.Runnable twice");
		assertThat(resolve("(java:reify '() \"run\" f)").reason()).isEqualTo("the form is malformed");
		assertThat(JavaImplementations.describe((LispCons) LispReader
			.readAllFromString("(java:reify '(\"java.lang.Runnable\" \"java.util.function.Supplier\") \"run\" f)")
			.get(0))).isEqualTo("java:reify \"java.lang.Runnable\" \"java.util.function.Supplier\"");
		// one object of the interfaces' implementation type, as a java:proxy of them
		JavaStaticType type = new JavaSiteResolver(CLASSES).typeOf(LispReader
			.readAllFromString("(java:reify '(\"java.lang.Runnable\" \"java.util.function.Supplier\") \"run\" f)")
			.get(0));
		assertThat(type).isEqualTo(new JavaStaticType.Kinds(java.util.Set
			.of(CLASSES.implementationOf(List.of(type("java.lang.Runnable"), type("java.util.function.Supplier"))))));
	}

	// :value stands the object for a value: the form resolves like any java:reify, its
	// :class literal carried, the options in either order and a value that is a
	// marker's keyword the value; :class without :value, an option twice, and an
	// interface declaring value() or className() -- runtime/RontoJavaValue's own -- are
	// refused. Its class is read when it runs.
	@Test
	void aReifyGivenAValueStandsForIt() {
		JavaImplementation standing = resolve(
				"(java:reify '(\"java.lang.Runnable\" \"java.lang.Comparable\") :value x :class \"my.Task\" \"run\" f"
						+ " :java-false)");
		assertThat(standing.resolved()).isTrue();
		assertThat(standing.standIn()).isEqualTo(new JavaImplementation.StandIn("my.Task"));
		assertThat(standing.javaFalse()).isTrue();
		assertThat(slots(standing)).containsExactly("compareTo(java.lang.Object)int=-1", "run()void=0");
		assertThat(resolve("(java:reify \"java.lang.Runnable\" :class \"c\" :value x \"run\" f)").standIn())
			.isEqualTo(new JavaImplementation.StandIn("c"));
		assertThat(resolve("(java:reify \"java.lang.Runnable\" :value x \"run\" f)").standIn())
			.isEqualTo(new JavaImplementation.StandIn(null));
		assertThat(resolve("(java:reify \"java.lang.Runnable\" \"run\" f)").standsFor()).isFalse();
		JavaImplementation keyword = resolve("(java:reify \"java.lang.Runnable\" :value :java-false \"run\" f)");
		assertThat(keyword.standsFor()).isTrue();
		assertThat(keyword.javaFalse()).isFalse();
		assertThat(resolve("(java:reify \"java.lang.Runnable\" :class \"c\" \"run\" f)").reason())
			.isEqualTo("the form is malformed");
		assertThat(resolve("(java:reify \"java.lang.Runnable\" :value x :value y \"run\" f)").reason())
			.isEqualTo("the form is malformed");
		assertThat(resolve("(java:reify \"java.lang.Runnable\" :value x :class c \"run\" f)").reason())
			.isEqualTo("the class name is not a literal string");
		assertThat(resolve("(java:reify \"" + Valued.class.getName() + "\" :value x \"value\" f)").reason())
			.isEqualTo("java:reify: :value conflicts with " + Valued.class.getName() + ".value()");
		assertThat(new JavaSiteResolver(CLASSES)
			.typeOf(LispReader.readAllFromString("(java:reify \"java.lang.Runnable\" :value x \"run\" f)").get(0)))
			.isEqualTo(JavaStaticType.UNKNOWN);
	}

	/**
	 * An interface declaring a method an object standing for a value implements itself.
	 */
	public interface Valued {

		Object value();

	}

	// The object a resolved form makes is of the interface's implementation type: a
	// receiver resolves against the interface, an argument by that type's cost -- so a
	// call passing one resolves before it runs.
	@Test
	void theObjectHasTheImplementationTypeOfItsInterface() {
		JavaSiteResolver resolver = new JavaSiteResolver(CLASSES);
		String reify = "(java:reify \"java.util.Comparator\" \"compare\" (lambda (a b) (- a b)))";
		JavaStaticType type = resolver.typeOf(LispReader.readAllFromString(reify).get(0));
		JavaImplementationType implementation = CLASSES.implementationOf(type("java.util.Comparator"));
		assertThat(type).isEqualTo(new JavaStaticType.Kinds(java.util.Set.of(implementation)));
		assertThat(type.receiverClass()).isSameAs(type("java.util.Comparator"));
		// No object's class is exactly an interface: (java:object "I" :exact) spells the
		// implementation type, so a let-bound reify keeps it.
		am.ik.rontolisp.LispVal spec = Objects.requireNonNull(resolver.specOf(type));
		assertThat(spec.print()).isEqualTo("(JAVA:OBJECT \"java.util.Comparator\" :EXACT)");
		assertThat(resolver.typeOfSpec(spec)).isEqualTo(type);
		JavaSite sort = resolver.resolve((LispCons) LispReader
			.readAllFromString(
					"(java:static \"java.util.Collections\" \"sort\" (java:new \"java.util.ArrayList\") " + reify + ")")
			.get(0));
		assertThat(sort.designator()).isEqualTo("sort(java.util.List,java.util.Comparator)");
		assertThat(sort.arguments().get(1).kinds()).containsExactly(implementation);
		assertThat(sort.arguments().get(1).expected()).isEqualTo("an implementation of java.util.Comparator");
		JavaSite call = resolver
			.resolve((LispCons) LispReader.readAllFromString("(java:call " + reify + " \"compare\" 1 2)").get(0));
		assertThat(call.staticClass() + " " + call.designator())
			.isEqualTo("java.util.Comparator compare(java.lang.Object,java.lang.Object)");
		assertThat(resolver
			.typeOf(LispReader.readAllFromString("(java:reify \"java.util.Comparator\" \"nope\" f)").get(0)))
			.isEqualTo(JavaStaticType.UNKNOWN);
	}

	// A Proxy class's supertypes less Proxy itself: Object, Serializable, the interface
	// and its superinterfaces.
	@Test
	void theImplementationTypeIsAssignableToWhatAProxyObjectIs() {
		JavaImplementationType operator = CLASSES.implementationOf(type("java.util.function.UnaryOperator"));
		for (String name : List.of("java.lang.Object", "java.io.Serializable", "java.util.function.UnaryOperator",
				"java.util.function.Function")) {
			assertThat(type(name).isAssignableFrom(operator)).as(name).isTrue();
		}
		for (String name : List.of("java.lang.reflect.Proxy", "java.lang.Runnable", "java.lang.String")) {
			assertThat(type(name).isAssignableFrom(operator)).as(name).isFalse();
		}
		assertThat(CLASSES.implementationOf(type("java.util.function.UnaryOperator"))).isSameAs(operator);
		assertThat(JavaOverloads.kindCost(operator, type("java.util.function.Function"), CLASSES))
			.isEqualTo(JavaOverloads.COST_WIDEN);
	}

	// A java:proxy of several interfaces declares every method of each: a name two
	// interfaces declare with other parameters is a slot of its own, the same key is one
	// slot; all call the one callable.
	@Test
	void aProxyOfSeveralInterfacesDeclaresEachMethodOfEach() {
		JavaImplementation implementation = JavaImplementations
			.proxy(List.of(type("java.util.function.Consumer"), type("java.util.function.IntConsumer")), CLASSES);
		assertThat(slots(implementation)).containsExactly("accept(int)void=0", "accept(java.lang.Object)void=0",
				"andThen(java.util.function.Consumer)java.util.function.Consumer=0",
				"andThen(java.util.function.IntConsumer)java.util.function.IntConsumer=0");
		assertThat(implementation.defaultToString())
			.isEqualTo("#<java-proxy java.util.function.Consumer java.util.function.IntConsumer>");
		assertThat(slots(
				JavaImplementations.proxy(List.of(type("java.util.Collection"), type("java.util.List")), CLASSES)))
			.contains("size()int=0")
			.doesNotHaveDuplicates();
	}

	@Test
	void aProxyFormOfSeveralInterfacesResolvesWhenEachDoes() {
		JavaImplementation resolved = resolve("(java:proxy \"java.lang.Runnable\" \"java.util.function.Supplier\" f)");
		assertThat(resolved.resolved()).isTrue();
		assertThat(resolved.interfaceNames()).isEqualTo("java.lang.Runnable java.util.function.Supplier");
		assertThat(resolve("(java:proxy \"java.lang.Runnable\" iface f)").reason())
			.isEqualTo("interface name 2 is not a literal string");
		assertThat(resolve("(java:proxy \"java.lang.Runnable\" \"java.lang.String\" f)").reason())
			.isEqualTo("java:proxy expects an interface, got java.lang.String");
		assertThat(resolve("(java:proxy \"java.lang.Runnable\" \"java.lang.Runnable\" f)").reason())
			.isEqualTo("java:proxy names interface java.lang.Runnable twice");
		assertThat(resolve("(java:proxy f)").reason()).isEqualTo("the form is malformed");
		assertThat(JavaImplementations.describe((LispCons) LispReader
			.readAllFromString("(java:proxy \"java.lang.Runnable\" \"java.util.function.Supplier\" f)")
			.get(0))).isEqualTo("java:proxy \"java.lang.Runnable\" \"java.util.function.Supplier\"");
		assertThat(JavaImplementations
			.describe((LispCons) LispReader.readAllFromString("(java:reify \"java.lang.Runnable\" \"run\" f)").get(0)))
			.isEqualTo("java:reify \"java.lang.Runnable\"");
	}

	// The object is assignable to each interface; a call on it resolves against its one
	// interface only -- with several, by its class when the call runs -- and no
	// specifier spells it.
	@Test
	void theObjectOfSeveralInterfacesIsAssignableToEach() {
		JavaSiteResolver resolver = new JavaSiteResolver(CLASSES);
		JavaStaticType type = resolver.typeOf(
				LispReader.readAllFromString("(java:proxy \"java.lang.Runnable\" \"java.util.function.Supplier\" f)")
					.get(0));
		JavaImplementationType implementation = CLASSES
			.implementationOf(List.of(type("java.lang.Runnable"), type("java.util.function.Supplier")));
		assertThat(type).isEqualTo(new JavaStaticType.Kinds(java.util.Set.of(implementation)));
		assertThat(CLASSES.implementationOf(List.of(type("java.lang.Runnable"), type("java.util.function.Supplier"))))
			.isSameAs(implementation);
		assertThat(implementation).isNotSameAs(CLASSES.implementationOf(type("java.lang.Runnable")));
		for (String name : List.of("java.lang.Object", "java.io.Serializable", "java.lang.Runnable",
				"java.util.function.Supplier")) {
			assertThat(type(name).isAssignableFrom(implementation)).as(name).isTrue();
		}
		assertThat(type("java.util.function.Function").isAssignableFrom(implementation)).isFalse();
		assertThat(type.receiverClass()).isNull();
		assertThat(resolver.specOf(type)).isNull();
	}

	// A named method calls the callable; an unnamed concrete class method is
	// inherited (no slot); an unnamed abstract one throws; Object's three override
	// like any other class method.
	@Test
	void subclassSlotsNameInheritAndThrow() {
		JavaImplementation file = JavaImplementations.subclass(type("java.io.File"), List.of(),
				List.of("lastModified"));
		assertThat(file.isSubclass()).isTrue();
		assertThat(file.superclass()).isSameAs(type("java.io.File"));
		assertThat(slots(file)).contains("lastModified()long=0");
		assertThat(slots(file).stream().noneMatch(s -> s.startsWith("getName("))).as("inherited").isTrue();
		assertThat(slots(file).stream().noneMatch(s -> s.startsWith("toString("))).as("inherited").isTrue();
		JavaImplementation objects = JavaImplementations.subclass(type("java.io.File"), List.of(),
				List.of("toString", "equals", "hashCode"));
		assertThat(slots(objects)).contains("toString()java.lang.String=0", "equals(java.lang.Object)boolean=0",
				"hashCode()int=0");
		JavaImplementation abstractList = JavaImplementations.subclass(type("java.util.AbstractList"), List.of(),
				List.of("size"));
		assertThat(slots(abstractList)).contains("size()int=0", "get(int)java.lang.Object=-1");
		// An extra interface's abstract method is a slot too.
		JavaImplementation runnable = JavaImplementations.subclass(type("java.io.File"),
				List.of(type("java.lang.Runnable")), List.of("run"));
		assertThat(slots(runnable)).contains("run()void=0");
		assertThat(runnable.interfaceNames()).isEqualTo("java.lang.Runnable");
		assertThatThrownBy(() -> JavaImplementations.subclass(type("java.io.File"), List.of(), List.of("nope")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("java:subclass: java.io.File has no method nope");
		assertThatThrownBy(() -> JavaImplementations.subclass(type("java.io.File"), List.of(), List.of("getClass")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("java:subclass: java.io.File.getClass cannot be overridden");
	}

	// A java:subclass form resolves to its superclass and slots; anything else is
	// the reason it is left to run time.
	@Test
	void subclassFormsResolveToTheirSuperclass() {
		JavaImplementation resolved = resolve(
				"(java:subclass \"java.io.File\" '(\"java.lang.Runnable\") '(\"run\" \"toString\") \"x\" f)");
		assertThat(resolved.resolved()).isTrue();
		assertThat(resolved.isSubclass()).isTrue();
		assertThat(resolved.superclass()).isSameAs(type("java.io.File"));
		assertThat(resolved.interfaceNames()).isEqualTo("java.lang.Runnable");
		assertThat(slots(resolved)).contains("run()void=0", "toString()java.lang.String=0");
		assertThat(resolve("(java:subclass \"java.io.File\" '() '(\"toString\") \"x\" f)").reason()).isNull();
		assertThat(resolve("(java:subclass \"java.util.function.Supplier\" '() '() f)").reason())
			.isEqualTo("java:subclass expects a class, got java.util.function.Supplier");
		assertThat(resolve("(java:subclass \"java.lang.String\" '() '() \"x\" f)").reason())
			.isEqualTo("java:subclass: class java.lang.String is final and cannot be extended");
		assertThat(resolve("(java:subclass \"no.such.Class\" '() '() f)").reason())
			.isEqualTo("class no.such.Class is not found");
		assertThat(resolve("(java:subclass \"java.io.File\" '(\"java.lang.String\") '() \"x\" f)").reason())
			.isEqualTo("java:subclass expects an interface, got java.lang.String");
		assertThat(resolve("(java:subclass \"java.io.File\" '() '(\"nope\") \"x\" f)").reason())
			.isEqualTo("java:subclass: java.io.File has no method nope");
		assertThat(resolve("(java:subclass \"java.io.File\" '() '() 1 2 3 4 5 f)").reason())
			.isEqualTo("No matching constructor for java.io.File with 5 argument(s)");
		assertThat(resolve("(java:subclass \"java.io.File\" '() '(\"toString\"))").reason())
			.isEqualTo("the form is malformed");
		assertThat(JavaImplementations.describe(
				(LispCons) LispReader.readAllFromString("(java:subclass \"java.io.File\" '() '() \"x\" f)").get(0)))
			.isEqualTo("java:subclass \"java.io.File\"");
	}

	// A proxy-super reaches the most derived concrete declaration, else an
	// interface default, else nothing; the accessor is one name per (name, arity).
	@Test
	void superAccessorsReachTheSuperclassImplementation() {
		assertThat(JavaImplementations.superAccessor("paintComponent", 1)).isEqualTo("super$paintComponent$1");
		JavaImplementations.SuperTarget fileToString = JavaImplementations.superTarget(type("java.io.File"), List.of(),
				"toString", List.of());
		assertThat(fileToString).isNotNull();
		assertThat(fileToString.owner().name()).isEqualTo("java.io.File");
		assertThat(
				JavaImplementations.superTarget(type("java.util.AbstractList"), List.of(), "get", List.of(type("int"))))
			.isNull();
		assertThat(JavaImplementations.superTarget(type("java.util.AbstractList"), List.of(type("java.util.List")),
				"get", List.of(type("int"))))
			.isNull();
	}

	// The object is assignable to its superclass, its chain and the extra
	// interfaces; a call on it is resolved by its class when it runs, and no
	// specifier spells it.
	@Test
	void theObjectOfASuperclassIsAssignableToItsChain() {
		JavaSiteResolver resolver = new JavaSiteResolver(CLASSES);
		JavaStaticType type = resolver.typeOf(LispReader
			.readAllFromString("(java:subclass \"java.io.File\" '(\"java.lang.Runnable\") '(\"run\") \"x\" f)")
			.get(0));
		JavaImplementationType implementation = CLASSES.subclassOf(type("java.io.File"),
				List.of(type("java.lang.Runnable")));
		assertThat(type).isEqualTo(new JavaStaticType.Kinds(java.util.Set.of(implementation)));
		assertThat(CLASSES.subclassOf(type("java.io.File"), List.of(type("java.lang.Runnable"))))
			.isSameAs(implementation);
		assertThat(implementation).isNotSameAs(CLASSES.implementationOf(List.of(type("java.lang.Runnable"))));
		assertThat(implementation.superclass()).isSameAs(type("java.io.File"));
		assertThat(implementation.single()).isNull();
		for (String name : List.of("java.lang.Object", "java.io.Serializable", "java.io.File", "java.lang.Comparable",
				"java.lang.Runnable")) {
			assertThat(type(name).isAssignableFrom(implementation)).as(name).isTrue();
		}
		assertThat(type("java.util.function.Function").isAssignableFrom(implementation)).isFalse();
		assertThat(type.receiverClass()).isNull();
		assertThat(resolver.specOf(type)).isNull();
	}

}
