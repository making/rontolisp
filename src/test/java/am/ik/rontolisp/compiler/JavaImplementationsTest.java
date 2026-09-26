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

	// A form resolves before it runs only when a compiled program can implement it:
	// literal names, a public interface found, every return type public.
	@Test
	void aFormResolvesWhenACompiledProgramCanImplementIt() {
		JavaImplementation resolved = resolve("(java:reify \"java.lang.Runnable\" \"run\" f)");
		assertThat(resolved.resolved()).isTrue();
		assertThat(resolved.proxy()).isFalse();
		assertThat(resolve("(java:proxy \"java.lang.Runnable\" f)").proxy()).isTrue();
		assertThat(resolve("(java:reify iface \"run\" f)").reason())
			.isEqualTo("the interface name is not a literal string");
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

}
