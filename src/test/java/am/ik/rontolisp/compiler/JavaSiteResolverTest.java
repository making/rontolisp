package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The static half of the one {@code java:} resolution model: which sites resolve before
 * they run, to which member, and why the others do not. The run-time half is the same
 * rule ({@link JavaOverloads}); a site resolves only when every kind its arguments can
 * have selects the same member, so resolving early never changes the member -- except
 * through a receiver typed by an upper bound, which resolves among the bound's methods.
 */
class JavaSiteResolverTest {

	private final JavaSiteResolver resolver = new JavaSiteResolver(ReflectiveJavaClasses.instance());

	private JavaSite resolve(String site) {
		return this.resolver.resolve((LispCons) LispReader.readAllFromString(site).get(0));
	}

	private String member(String site) {
		JavaSite resolution = resolve(site);
		assertThat(resolution.reason()).as(site).isNull();
		return resolution.staticClass() + " " + resolution.designator() + (resolution.packed() ? " packed" : "");
	}

	@Test
	void literalArgumentsSelectByTheirKinds() {
		assertThat(member("(java:static \"java.lang.Math\" \"max\" 3 7)")).isEqualTo("java.lang.Math max(int,int)");
		assertThat(member("(java:static \"java.lang.Math\" \"max\" 3 7.5)"))
			.isEqualTo("java.lang.Math max(double,double)");
		assertThat(member("(java:static \"java.lang.Math\" \"sqrt\" 16)")).isEqualTo("java.lang.Math sqrt(double)");
		assertThat(member("(java:static \"java.lang.Character\" \"toString\" #\\a)"))
			.isEqualTo("java.lang.Character toString(char)");
	}

	@Test
	void aVarargsTailIsPackedExactlyAsAtRunTime() {
		assertThat(member("(java:static \"java.lang.String\" \"format\" \"%s-%s\" 1 \"x\")"))
			.isEqualTo("java.lang.String format(java.lang.String,[Ljava.lang.Object;) packed");
		assertThat(member("(java:static \"java.util.List\" \"of\")")).isEqualTo("java.util.List of()");
	}

	@Test
	void aParameterTagNarrowsTheCandidates() {
		assertThat(member("(java:static \"java.lang.Math\" \"max(long,long)\" 3 7)"))
			.isEqualTo("java.lang.Math max(long,long)");
		assertThat(member("(java:static \"java.lang.Math\" \"max(long,_)\" 3 7)"))
			.isEqualTo("java.lang.Math max(long,long)");
		assertThat(member("(java:new \"java.lang.StringBuilder(int)\" 16)"))
			.isEqualTo("java.lang.StringBuilder java.lang.StringBuilder(int)");
		// An unqualified tag type is java.lang's; an array is written with [].
		assertThat(member("(java:static \"java.lang.String\" \"valueOf(Object)\" 5)"))
			.isEqualTo("java.lang.String valueOf(java.lang.Object)");
		assertThat(resolve("(java:static \"java.lang.Math\" \"max(String,_)\" 3 7)").reason())
			.contains("no public static method max matching max(java.lang.String,_)");
		assertThat(resolve("(java:static \"java.lang.Math\" \"max(long\" 3 7)").reason())
			.contains("malformed parameter tag");
	}

	@Test
	void theConstructedClassTypesTheReceiver() {
		assertThat(member("(java:call (java:new \"java.lang.StringBuilder\") \"append\" \"x\")"))
			.isEqualTo("java.lang.StringBuilder append(java.lang.String)");
		assertThat(member("(java:call (java:new \"java.lang.StringBuilder\") \"append\" 42)"))
			.isEqualTo("java.lang.StringBuilder append(int)");
		// The declared return type of a resolved member types the next receiver: the
		// covariant StringBuilder append, never the bridge returning its superclass.
		assertThat(
				member("(java:call (java:call (java:new \"java.lang.StringBuilder\") \"append\" \"x\") \"reverse\")"))
			.isEqualTo("java.lang.StringBuilder reverse()");
		assertThat(member("(java:call (java:call (java:new \"java.util.HashMap\") \"keySet\") \"size\")"))
			.isEqualTo("java.util.Set size()");
	}

	// The documented difference: a receiver typed by an upper bound resolves among the
	// bound's methods, so ArrayList's added remove(int) is not a candidate.
	@Test
	void anUpperBoundReceiverResolvesAmongTheBoundsMethods() {
		assertThat(member("(java:call (the (java:object \"java.util.Collection\") x) \"remove\" 1)"))
			.isEqualTo("java.util.Collection remove(java.lang.Object)");
		assertThat(member("(java:call (java:new \"java.util.ArrayList\") \"remove\" 1)"))
			.isEqualTo("java.util.ArrayList remove(int)");
	}

	@Test
	void anArgumentResolvesOnlyWhenEveryKindItCanHaveAgrees() {
		// A String result may be nil, and nil selects append(boolean): no single member,
		// a dispatch among the overloads one of its kinds selects.
		JavaSite site = resolve("(java:call (java:new \"java.lang.StringBuilder\") \"append\""
				+ " (java:call (java:new \"java.lang.StringBuilder\") \"toString\"))");
		assertThat(site.dispatched()).isTrue();
		assertThat(site.executable()).isNull();
		assertThat(overloads(site)).contains("append(java.lang.String)", "append(boolean)", "append(char)")
			.doesNotContain("append(int)", "append(long)", "append(double)");
		assertThat(site.arguments()).containsExactly(
				new JavaSite.Argument(List.of(JavaKind.Lisp.NIL, JavaKind.Lisp.STRING, JavaKind.Lisp.STRING_1), null));
		// An int result is always an integer.
		assertThat(member("(java:call (java:new \"java.lang.StringBuilder\") \"append\""
				+ " (java:call (java:new \"java.lang.StringBuilder\") \"length\"))"))
			.isEqualTo("java.lang.StringBuilder append(int)");
		// A CharSequence may be a Lisp string: nothing is known of the argument.
		assertThat(resolve("(java:call (java:new \"java.lang.StringBuilder\") \"append\""
				+ " (the (java:object \"java.lang.CharSequence\") x))")
			.arguments()).containsExactly(JavaSite.Argument.open(null, null));
	}

	private static List<String> overloads(JavaSite site) {
		return site.overloads()
			.stream()
			.map(o -> JavaOverloads.fullDesignator(o.executable(), o.executable().name()) + (o.packed() ? "*" : ""))
			.toList();
	}

	// A site whose class is known but whose argument kinds are not dispatches among that
	// class's overloads -- the static class's, never the run-time class's -- in the order
	// a cost tie is broken by. What it counts on for each argument is checked when it
	// runs: known kinds, or nil or an instance of a bound, or anything.
	@Test
	void aSiteOfUnknownArgumentKindsDispatchesAmongTheClasssOverloads() {
		JavaSite site = resolve("(java:static \"java.lang.Math\" \"max\" x y)");
		assertThat(site.resolved()).isTrue();
		assertThat(site.dispatched()).isTrue();
		assertThat(site.staticClass()).isEqualTo("java.lang.Math");
		assertThat(site.designator()).isEqualTo("max");
		assertThat(overloads(site)).containsExactly("max(double,double)", "max(float,float)", "max(int,int)",
				"max(long,long)");
		assertThat(site.arguments()).containsExactly(JavaSite.Argument.open(null, null),
				JavaSite.Argument.open(null, null));
		// The overloads answer different types: nothing is known of the value.
		assertThat(site.result()).isEqualTo(JavaStaticType.UNKNOWN);
		// The receiver's declared class decides the candidates.
		JavaSite remove = resolve("(java:call (the (java:object \"java.util.Collection\") c) \"remove\" x)");
		assertThat(overloads(remove)).containsExactly("remove(java.lang.Object)");
		assertThat(remove.result())
			.isEqualTo(new JavaStaticType.Kinds(java.util.Set.of(JavaKind.Lisp.T, JavaKind.Lisp.NIL)));
		// A bounded argument is nil or an object of its class.
		JavaSite bounded = resolve("(java:call (java:new \"java.util.ArrayList\") \"addAll\""
				+ " (the (java:object \"java.util.Collection\") c))");
		assertThat(overloads(bounded)).containsExactly("addAll(java.util.Collection)");
		assertThat(bounded.arguments())
			.containsExactly(JavaSite.Argument.open("java.util.Collection", "java.util.Collection"));
		assertThat(bounded.arguments().get(0).expected()).isEqualTo("a java.util.Collection");
		// A varargs method is a candidate packed too.
		assertThat(overloads(resolve("(java:static \"java.lang.String\" \"format\" \"%s\" x)"))).containsExactly(
				"format(java.lang.String,[Ljava.lang.Object;)", "format(java.lang.String,[Ljava.lang.Object;)*");
		// A constructor dispatches too.
		assertThat(overloads(resolve("(java:new \"java.lang.StringBuilder\" x)"))).containsExactly("<init>(int)",
				"<init>(java.lang.CharSequence)", "<init>(java.lang.String)");
		// An overload no value an argument can have is accepted by is no candidate; with
		// none left the site is resolved when it runs, from the run-time class.
		assertThat(resolve("(java:static \"java.lang.Math\" \"max\" \"a\" x)").reason())
			.isEqualTo("no method of java.lang.Math accepts arguments of these types");
	}

	// The dispatch chooses what the run-time rule chooses: over every candidate set of a
	// corpus of JDK classes and every combination of argument kinds, the first cheapest
	// of the ranked overloads is the overload select() picks.
	@Test
	void theRankedOrderChoosesWhatSelectChooses() {
		ReflectiveJavaClasses classes = ReflectiveJavaClasses.instance();
		List<JavaKind> kinds = new java.util.ArrayList<>(List.of(JavaKind.Lisp.values()));
		for (String host : List.of("java.lang.StringBuilder", "java.util.ArrayList", "java.lang.Object",
				"java.util.Locale")) {
			kinds.add(java.util.Objects.requireNonNull(classes.find(host)));
		}
		int checked = 0;
		for (String className : List.of("java.lang.Math", "java.lang.String", "java.lang.StringBuilder",
				"java.util.Arrays", "java.lang.Integer", "java.lang.Character", "java.util.Collections",
				"java.util.Objects", "java.util.ArrayList", "java.io.PrintStream")) {
			ReflectiveJavaClasses.Type type = java.util.Objects.requireNonNull(classes.find(className));
			java.util.Set<String> names = new java.util.TreeSet<>();
			for (java.lang.reflect.Method method : type.type().getMethods()) {
				names.add(method.getName());
			}
			for (String name : names) {
				List<? extends JavaExecutable> candidates = type.methods(name);
				for (int argc = 0; argc <= 2; argc++) {
					List<JavaOverloads.Overload> ranked = JavaOverloads.ranked(candidates, argc);
					int combinations = (int) Math.pow(kinds.size(), argc);
					for (int n = 0; n < combinations; n++) {
						JavaKind[] combination = new JavaKind[argc];
						for (int i = 0, rest = n; i < argc; i++, rest /= kinds.size()) {
							combination[i] = kinds.get(rest % kinds.size());
						}
						JavaOverloads.ArgumentCost cost = (i, target) -> JavaOverloads.kindCost(combination[i], target,
								classes);
						JavaOverloads.Overload selected = JavaOverloads.select(candidates, argc, cost);
						JavaOverloads.Overload dispatched = JavaOverloads.selectRanked(ranked, argc, cost);
						if (selected == null) {
							assertThat(dispatched).as(className + "." + name).isNull();
						}
						else {
							assertThat(dispatched).as(className + "." + name).isNotNull();
							assertThat(selected.sameAs(dispatched)).as(className + "." + name).isTrue();
							checked++;
						}
					}
				}
			}
		}
		assertThat(checked).isGreaterThan(1000);
	}

	@Test
	void whatIsNotKnownIsLeftToRunTime() {
		assertThat(resolve("(java:call x \"length\")").reason()).isEqualTo("the receiver's class is not known");
		assertThat(resolve("(java:static cls \"max\" 1 2)").reason())
			.isEqualTo("the class name is not a literal string");
		assertThat(resolve("(java:static \"no.such.Class\" \"m\")").reason())
			.isEqualTo("class no.such.Class is not found");
		assertThat(resolve("(java:static \"java.lang.Math\" \"noSuchMethod\" 1)").reason())
			.isEqualTo("class java.lang.Math has no public static method noSuchMethod");
		// A method whose declared type is Object answers any value: nothing is known.
		assertThat(resolve("(java:call (the (java:object \"java.util.List\") x) \"get\" 0)").result())
			.isEqualTo(JavaStaticType.UNKNOWN);
	}

	@Test
	void fieldsResolveToo() {
		assertThat(member("(java:field \"java.lang.Integer\" \"MAX_VALUE\")")).isEqualTo("java.lang.Integer MAX_VALUE");
		assertThat(member("(java:field (java:new \"java.awt.Point\" 3 4) \"y\")")).isEqualTo("java.awt.Point y");
		assertThat(resolve("(java:field \"java.lang.Integer\" \"NO_SUCH\")").reason())
			.isEqualTo("class java.lang.Integer has no public field NO_SUCH");
	}

	@Test
	void theStaticTypeOfAResolvedValue() {
		assertThat(resolve("(java:static \"java.lang.Math\" \"max\" 3 7)").result())
			.isEqualTo(new JavaStaticType.Kinds(java.util.Set.of(JavaKind.Lisp.INTEGER)));
		assertThat(resolve("(java:call (java:new \"java.util.ArrayList\") \"add\" 42)").result())
			.isEqualTo(new JavaStaticType.Kinds(java.util.Set.of(JavaKind.Lisp.T, JavaKind.Lisp.NIL)));
		assertThat(resolve("(java:new \"java.lang.String\" \"x\")").result())
			.isEqualTo(new JavaStaticType.Kinds(java.util.Set.of(JavaKind.Lisp.STRING, JavaKind.Lisp.STRING_1)));
	}

	// java:static calls a static method: an instance method of the name, which could
	// only fail without a receiver, is no candidate.
	@Test
	void aStaticCallChoosesAmongTheStaticMethods() {
		assertThat(resolve("(java:static \"java.lang.String\" \"length\")").reason())
			.isEqualTo("class java.lang.String has no public static method length");
		assertThat(member("(java:static \"java.lang.Integer\" \"toString\" 5)"))
			.isEqualTo("java.lang.Integer toString(int)");
		// java:call keeps both: a static method is called through an instance as Java
		// allows.
		assertThat(member("(java:call (java:new \"java.lang.StringBuilder\") \"length\")"))
			.isEqualTo("java.lang.StringBuilder length()");
	}

	// A site resolves only when a compiled program could call it directly: nothing is
	// constructed from an abstract class or an interface, a class name reads a static
	// field, and every class the call names must be public.
	@Test
	void aSiteResolvesOnlyWhenItCanBeCalledDirectly() {
		assertThat(resolve("(java:new \"java.io.InputStream\")").reason())
			.isEqualTo("class java.io.InputStream is abstract");
		assertThat(resolve("(java:new \"java.lang.Runnable\")").reason())
			.isEqualTo("class java.lang.Runnable is an interface");
		assertThat(resolve("(java:field \"java.awt.Point\" \"x\")").reason())
			.isEqualTo("field java.awt.Point.x is not static");
		assertThat(resolve("(java:new \"" + Hidden.class.getName() + "\")").reason())
			.isEqualTo("class " + Hidden.class.getName() + " is not public");
		assertThat(resolve("(java:static \"java.lang.AbstractStringBuilder\" \"m\")").reason())
			.isEqualTo("class java.lang.AbstractStringBuilder is not accessible");
		// A value of a class no compiled program can name has no kind to check.
		assertThat(this.resolver
			.typeOf(LispReader.readAllFromString("(the (java:object \"" + Hidden.class.getName() + "\") x)").get(0)))
			.isEqualTo(JavaStaticType.UNKNOWN);
	}

	// What a resolved site counted on for each argument: the kinds, in a fixed order,
	// and the class a declaration named -- what a value of another kind is reported
	// against.
	@Test
	void aResolvedSiteRecordsWhatItCountedOnForEachArgument() {
		JavaSite site = resolve(
				"(java:static \"java.lang.Integer\" \"parseInt\"" + " (the (java:object \"java.lang.String\") s) 16)");
		assertThat(site.designator()).isEqualTo("parseInt(java.lang.String,int)");
		assertThat(site.arguments()).containsExactly(
				new JavaSite.Argument(List.of(JavaKind.Lisp.NIL, JavaKind.Lisp.STRING, JavaKind.Lisp.STRING_1),
						"java.lang.String"),
				new JavaSite.Argument(List.of(JavaKind.Lisp.INTEGER), null));
		assertThat(site.arguments().get(0).expected()).isEqualTo("a java.lang.String");
		assertThat(site.arguments().get(1).expected()).isEqualTo("an integer");
		assertThat(new JavaSite.Argument(List.of(JavaKind.Lisp.NIL, JavaKind.Lisp.STRING, JavaKind.Lisp.STRING_1), null)
			.expected()).isEqualTo("nil or a string");
		// A primitive declaration reads as its kinds.
		assertThat(new JavaSite.Argument(List.of(JavaKind.Lisp.INTEGER), "int").expected()).isEqualTo("an integer");
		assertThat(JavaSiteResolver.declaredClass(LispReader
			.readAllFromString("(the fixnum (the (java:object \"java.lang.Integer\") (the (java:object \"C\") x)))")
			.get(0))).isEqualTo("java.lang.Integer");
	}

	/** A class-path class that is not public: reflection can call it, bytecode cannot. */
	static final class Hidden {

		public Hidden() {
		}

	}

	@Test
	void tagTypesAreSpelledAsClassGetNameSpellsThem() {
		assertThat(JavaOverloads.parseMember("f(int,long[],String...,java.util.Map$Entry,_,[D)").tag())
			.isEqualTo(List.of("int", "[J", "[Ljava.lang.String;", "java.util.Map$Entry", "_", "[D"));
		assertThat(JavaOverloads.parseMember("f()").tag()).isEqualTo(List.of());
		assertThat(JavaOverloads.parseMember("f").tag()).isNull();
		assertThatThrownBy(() -> JavaOverloads.parseMember("f(int,)")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JavaOverloads.parseMember("f)")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sitesAreFoundOutsideQuotedData() {
		List<LispCons> sites = JavaSiteResolver.sitesIn(
				LispReader.readAllFromString("(defun f (x) (java:call (java:new \"C\") \"m\") '(java:call x \"m\"))")
					.get(0));
		assertThat(sites).extracting(LispCons::print)
			.containsExactly("(JAVA:CALL (JAVA:NEW \"C\") \"m\")", "(JAVA:NEW \"C\")");
	}

	// What JavaDeclarations writes for a variable's inferred type reads back as that
	// type, or as the narrowest declared type covering it -- never a narrower one.
	@Test
	void aStaticTypeIsSpelledAsASpecifierThatReadsItBack() {
		for (String form : List.of("3", "2.5", "\"abc\"", "\"a\"", "#\\a", "nil", "t",
				"(java:new \"java.lang.StringBuilder\")", "(java:new \"java.util.ArrayList\")",
				"(java:call (java:new \"java.lang.StringBuilder\") \"reverse\")",
				"(java:call (java:new \"java.lang.StringBuilder\") \"length\")",
				"(java:call (java:new \"java.lang.StringBuilder\") \"toString\")",
				"(java:static \"java.lang.Integer\" \"valueOf\" 3)",
				"(the (java:object \"java.util.Collection\") x)")) {
			JavaStaticType type = this.resolver.typeOf(LispReader.readAllFromString(form).get(0));
			am.ik.rontolisp.LispVal spec = this.resolver.specOf(type);
			assertThat(spec).as(form).isNotNull();
			JavaStaticType read = this.resolver.typeOfSpec(spec);
			if (type instanceof JavaStaticType.Kinds kinds) {
				assertThat(read).as(form)
					.isInstanceOfSatisfying(JavaStaticType.Kinds.class,
							r -> assertThat(r.kinds()).containsAll(kinds.kinds()));
			}
			else {
				assertThat(read).as(form).isEqualTo(type);
			}
		}
		assertThat(spelled("(java:new \"java.lang.StringBuilder\")"))
			.isEqualTo("(JAVA:OBJECT \"java.lang.StringBuilder\" :EXACT)");
		assertThat(spelled("3")).isEqualTo("(JAVA:OBJECT \"int\")");
		assertThat(spelled("(java:static \"java.lang.Integer\" \"valueOf\" 3)"))
			.isEqualTo("(JAVA:OBJECT \"java.lang.Long\")");
		// A function value has no java:object spelling: the variable stays untyped.
		assertThat(spelled("(lambda () 1)")).isNull();
	}

	private @org.jspecify.annotations.Nullable String spelled(String form) {
		am.ik.rontolisp.LispVal spec = this.resolver
			.specOf(this.resolver.typeOf(LispReader.readAllFromString(form).get(0)));
		return spec == null ? null : spec.print();
	}

}
