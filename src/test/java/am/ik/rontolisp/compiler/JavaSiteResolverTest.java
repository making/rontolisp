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
			.contains("no public method max matching max(java.lang.String,_)");
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
		// A String result may be nil, and nil selects append(boolean): no single member.
		JavaSite site = resolve("(java:call (java:new \"java.lang.StringBuilder\") \"append\""
				+ " (java:call (java:new \"java.lang.StringBuilder\") \"toString\"))");
		assertThat(site.resolved()).isFalse();
		assertThat(site.reason()).startsWith("the member depends on the argument values");
		// An int result is always an integer.
		assertThat(member("(java:call (java:new \"java.lang.StringBuilder\") \"append\""
				+ " (java:call (java:new \"java.lang.StringBuilder\") \"length\"))"))
			.isEqualTo("java.lang.StringBuilder append(int)");
		// An upper-bound argument has no known kind at all.
		assertThat(resolve("(java:call (java:new \"java.lang.StringBuilder\") \"append\""
				+ " (the (java:object \"java.lang.CharSequence\") x))")
			.reason()).isEqualTo("the type of argument 1 is not known");
	}

	@Test
	void whatIsNotKnownIsLeftToRunTime() {
		assertThat(resolve("(java:call x \"length\")").reason()).isEqualTo("the receiver's class is not known");
		assertThat(resolve("(java:call (java:new \"java.lang.StringBuilder\") \"append\" x)").reason())
			.isEqualTo("the type of argument 1 is not known");
		assertThat(resolve("(java:static cls \"max\" 1 2)").reason())
			.isEqualTo("the class name is not a literal string");
		assertThat(resolve("(java:static \"no.such.Class\" \"m\")").reason())
			.isEqualTo("class no.such.Class is not found");
		assertThat(resolve("(java:static \"java.lang.Math\" \"noSuchMethod\" 1)").reason())
			.isEqualTo("class java.lang.Math has no public method noSuchMethod");
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
