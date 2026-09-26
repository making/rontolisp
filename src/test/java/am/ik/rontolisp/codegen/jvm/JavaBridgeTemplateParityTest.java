package am.ik.rontolisp.codegen.jvm;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaOverloads;
import am.ik.rontolisp.compiler.ReflectiveJavaClasses;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.runtime.RontoComplex;
import am.ik.rontolisp.runtime.RontoHashTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compiled program's bridge carries a hand copy of the overload rule -- it must stand
 * alone in the output -- so this pins it to the one the interpreter and the site resolver
 * share ({@link JavaOverloads}): over a corpus of calls, the template's run-time
 * resolution of the compiled values chooses the same member, packed the same way, as the
 * shared rule chooses over the same arguments' kinds. Tags, varargs packing, the
 * one-character-string and supplementary-character rows and the covariant-return
 * tie-break are all in the corpus.
 */
class JavaBridgeTemplateParityTest {

	/**
	 * An argument: its kind for the shared rule, its compiled representation for the
	 * template.
	 */
	private record Arg(JavaKind kind, @Nullable Object compiled) {
	}

	private static Arg integer(long v) {
		return new Arg(JavaKind.Lisp.INTEGER, v);
	}

	private static Arg real(double v) {
		return new Arg(JavaKind.Lisp.FLOAT, v);
	}

	private static Arg string(String s) {
		return new Arg(s.length() == 1 ? JavaKind.Lisp.STRING_1 : JavaKind.Lisp.STRING, "\"" + s + "\"");
	}

	private static Arg character(int codePoint) {
		return new Arg(Character.isBmpCodePoint(codePoint) ? JavaKind.Lisp.CHAR : JavaKind.Lisp.SUPPLEMENTARY_CHAR,
				new int[] { codePoint });
	}

	private static Arg host(Object object) {
		return new Arg(ReflectiveJavaClasses.of(object.getClass()), object);
	}

	private static final Arg T = new Arg(JavaKind.Lisp.T, "T");

	private static final Arg NIL = new Arg(JavaKind.Lisp.NIL, null);

	@Test
	void theTemplateChoosesWhatTheSharedRuleChooses() throws Exception {
		Object builder = new StringBuilder();
		List<Object[]> corpus = List.of(new Object[] { Math.class, "max", List.of(integer(3), integer(7)) },
				new Object[] { Math.class, "max", List.of(integer(3), real(7.5)) },
				new Object[] { Math.class, "max", List.of(real(3.5), real(7.5)) },
				new Object[] { Math.class, "max(long,_)", List.of(integer(3), integer(7)) },
				new Object[] { Math.class, "abs", List.of(real(-5.5)) },
				new Object[] { Math.class, "sqrt", List.of(integer(16)) },
				new Object[] { String.class, "valueOf", List.of(integer(5)) },
				new Object[] { String.class, "valueOf", List.of(real(5.0)) },
				new Object[] { String.class, "valueOf", List.of(character('a')) },
				new Object[] { String.class, "valueOf", List.of(T) },
				new Object[] { String.class, "valueOf", List.of(NIL) },
				new Object[] { String.class, "valueOf", List.of(string("x")) },
				new Object[] { String.class, "valueOf", List.of(host(builder)) },
				new Object[] { String.class, "valueOf(Object)", List.of(integer(5)) },
				new Object[] { String.class, "format", List.of(string("%s-%s"), integer(1), string("x")) },
				new Object[] { String.class, "join", List.of(string("-"), string("a"), string("b")) },
				new Object[] { List.class, "of", List.of() },
				new Object[] { List.class, "of", List.of(integer(1), integer(2)) },
				new Object[] { List.class, "of",
						List.of(integer(1), integer(2), integer(3), integer(4), integer(5), integer(6), integer(7),
								integer(8), integer(9), integer(10), integer(11)) },
				new Object[] { java.util.Arrays.class, "asList", List.of(integer(1), string("b")) },
				new Object[] { Character.class, "toString", List.of(character('a')) },
				new Object[] { Character.class, "toString", List.of(character(128512)) },
				new Object[] { Character.class, "charCount", List.of(character('a')) },
				new Object[] { Integer.class, "parseInt", List.of(string("100")) },
				new Object[] { java.util.Objects.class, "equals", List.of(string("a"), NIL) },
				new Object[] { java.util.Objects.class, "equals", List.of(NIL, NIL) },
				new Object[] { StringBuilder.class, "append", List.of(string("x")) },
				new Object[] { StringBuilder.class, "append", List.of(string("xy")) },
				new Object[] { StringBuilder.class, "append", List.of(integer(42)) },
				new Object[] { StringBuilder.class, "append", List.of(real(4.2)) },
				new Object[] { StringBuilder.class, "append", List.of(character('a')) },
				new Object[] { StringBuilder.class, "append", List.of(T) },
				new Object[] { StringBuilder.class, "append", List.of(NIL) },
				new Object[] { StringBuilder.class, "append", List.of(host(builder)) },
				new Object[] { StringBuilder.class, "append(CharSequence)", List.of(string("xy")) },
				new Object[] { StringBuilder.class, "insert", List.of(integer(0), string("x")) },
				new Object[] { java.util.ArrayList.class, "remove", List.of(integer(1)) },
				new Object[] { java.util.Collection.class, "remove", List.of(integer(1)) },
				new Object[] { java.util.ArrayList.class, "add", List.of(integer(0), integer(42)) });
		int checked = 0;
		for (Object[] row : corpus) {
			Class<?> type = (Class<?>) row[0];
			String designator = (String) row[1];
			@SuppressWarnings("unchecked")
			List<Arg> args = (List<Arg>) row[2];
			String what = type.getName() + "." + designator + args;
			JavaOverloads.Overload shared = sharedChoice(type, designator, args);
			Object[] template = templateChoice(type, designator, args);
			assertThat(template == null).as("%s resolves in both", what).isEqualTo(shared == null);
			if (shared == null || template == null) {
				continue;
			}
			Executable sharedExecutable = ((ReflectiveJavaClasses.Member) shared.executable()).executable();
			assertThat(template[0]).as(what).isEqualTo(sharedExecutable);
			assertThat(template[2]).as("%s packs the same way", what).isEqualTo(shared.packed());
			checked++;
		}
		assertThat(checked).isGreaterThan(35);
	}

	// A covariant variant is never chosen over the method it overrides: both copies pick
	// the StringBuilder-returning append, not the AbstractStringBuilder bridge.
	@Test
	void theCovariantOverrideWinsInBoth() throws Exception {
		Object[] template = templateChoice(StringBuilder.class, "append", List.of(string("xy")));
		assertThat(((Method) Objects.requireNonNull(template)[0]).getReturnType()).isEqualTo(StringBuilder.class);
		JavaOverloads.Overload shared = sharedChoice(StringBuilder.class, "append", List.of(string("xy")));
		assertThat(Objects.requireNonNull(shared).executable().returnType().name())
			.isEqualTo("java.lang.StringBuilder");
	}

	// A java:reify / java:proxy the bridge implements when it runs declares the slots
	// the shared rule chooses (compiler/JavaImplementations) -- the ones the
	// interpreter and a compiled program's generated class declare -- and refuses a
	// designator with the same text.
	@Test
	void theTemplateImplementsAnInterfaceAsTheSharedRuleDoes() throws Exception {
		List<List<String>> corpus = List.of(List.of("java.util.Comparator", "compare"),
				List.of("java.util.Comparator", "compare", "equals"), List.of("java.util.Iterator", "hasNext"),
				List.of("java.util.Iterator", "hasNext", "next", "remove"), List.of("java.lang.Runnable", "run"),
				List.of("java.lang.Runnable", "run", "toString", "hashCode"),
				List.of("java.lang.Appendable", "append(char)"),
				List.of("java.lang.Appendable", "append(CharSequence,_,_)", "append(char)", "append(CharSequence)"),
				List.of("java.lang.CharSequence", "length", "charAt"),
				List.of("java.util.function.Function", "apply", "andThen"),
				List.of("am.ik.rontolisp.compiler.JavaImplementationsTest$Narrowed", "get"),
				List.of("java.util.Comparator", "nope"), List.of("java.lang.Appendable", "append"),
				List.of("java.util.Iterator", "next", "next()"), List.of("java.util.Iterator", "next("));
		for (List<String> row : corpus) {
			Class<?> iface = Class.forName(row.get(0));
			List<String> designators = row.subList(1, row.size());
			String shared;
			try {
				shared = slots(am.ik.rontolisp.compiler.JavaImplementations.reify(ReflectiveJavaClasses.of(iface),
						designators, ReflectiveJavaClasses.instance()));
			}
			catch (IllegalArgumentException ex) {
				shared = "error: " + ex.getMessage();
			}
			String template;
			try {
				template = String.valueOf(new java.util.TreeMap<>((java.util.Map<?, ?>) invoke("reifySlots",
						new Class<?>[] { Class.class, String[].class }, iface, designators.toArray(String[]::new))));
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				template = "error: " + Objects.requireNonNull(ex.getCause()).getMessage();
			}
			assertThat(template).as("%s", row).isEqualTo(shared);
		}
		for (String name : List.of("java.util.Comparator", "java.util.function.Function", "java.lang.CharSequence",
				"java.util.List")) {
			Class<?> iface = Class.forName(name);
			assertThat(String.valueOf(new java.util.TreeMap<>(
					(java.util.Map<?, ?>) invoke("proxySlots", new Class<?>[] { Class.class }, iface))))
				.as(name)
				.isEqualTo(slots(am.ik.rontolisp.compiler.JavaImplementations.proxy(ReflectiveJavaClasses.of(iface),
						ReflectiveJavaClasses.instance())));
		}
	}

	// The shared rule's slots in the template's shape: dispatch key -> implementation.
	private static String slots(am.ik.rontolisp.compiler.JavaImplementation implementation) {
		java.util.TreeMap<String, Integer> slots = new java.util.TreeMap<>();
		for (am.ik.rontolisp.compiler.JavaImplementation.Slot slot : implementation.slots()) {
			slots.put(slot.dispatchKey(), slot.implementation());
		}
		return slots.toString();
	}

	// What a compiled program counts as a host object is one rule with two copies: the
	// bridge's isJavaObject and the _jhost a resolved site calls. Over the compiled
	// representation of every kind of Lisp value and a spread of host objects -- the
	// ArrayList / LinkedHashMap a Lisp array / hash table also is among them -- the two
	// answer alike, and as intended.
	@Test
	void theBridgeAndADirectSiteCountTheSameHostObjects(@TempDir Path dir) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("HostTest");
		byte[] bytes = compiler.compile(LispReader.readAllFromString("""
				(defun size (x)
				  (declare (type (java:object "java.util.Collection") x))
				  (java:call x "size"))
				(print (size (java:new "java.util.ArrayList")))
				"""));
		Files.write(dir.resolve("HostTest.class"), bytes);
		for (Map.Entry<String, byte[]> file : compiler.runtimeClassFiles().entrySet()) {
			Path target = dir.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		ArrayList<Object> lispArray = new ArrayList<>();
		lispArray.add(new Object[] { new Object[] { 1L }, null, null });
		lispArray.add(7L);
		LinkedHashMap<Object, Object> lispTable = new LinkedHashMap<>();
		lispTable.put(RontoHashTable.ORDER_KEY, new ArrayList<>());
		ArrayList<Object> hostList = new ArrayList<>(List.of(1L));
		LinkedHashMap<Object, Object> hostMap = new LinkedHashMap<>(Map.of("#order", "not a list"));
		List<@Nullable Object> lisp = Arrays.asList(null, 5L, 1.5, BigInteger.TEN.pow(30), "\"s\"", "FOO", "T",
				new int[] { 97 }, new BigInteger[] { BigInteger.ONE, BigInteger.TWO }, new Object[] { 1L, null },
				new Object[] { 3, "car" }, new double[] { 2, 1, 0 }, new byte[] { 8, 1 }, lispArray, lispTable,
				new RontoComplex(1L, 2L));
		List<Object> host = List.of(new StringBuilder(), new ArrayList<>(), hostList, new LinkedHashMap<>(), hostMap,
				new HashMap<>(), Optional.empty(), BigDecimal.ONE);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Method jhost = loader.loadClass("HostTest").getDeclaredMethod(JvmJavaDirectSites.HOST, Object.class);
			jhost.setAccessible(true);
			for (Object value : lisp) {
				assertThat(jhost.invoke(null, value)).as("_jhost %s", value).isEqualTo(false);
				assertThat(invoke("isJavaObject", new Class<?>[] { Object.class }, value)).as("bridge %s", value)
					.isEqualTo(false);
			}
			for (Object value : host) {
				assertThat(jhost.invoke(null, value)).as("_jhost %s", value).isEqualTo(true);
				assertThat(invoke("isJavaObject", new Class<?>[] { Object.class }, value)).as("bridge %s", value)
					.isEqualTo(true);
			}
		}
	}

	// The bridge may import nothing of rontolisp's, so it spells the hash table's order
	// key and the runtime package itself.
	@Test
	void theBridgeSpellsTheRepresentationAsTheRuntimeDoes() throws Exception {
		assertThat(constant("HASH_TABLE_ORDER_KEY")).isEqualTo(RontoHashTable.ORDER_KEY);
		assertThat(constant("RUNTIME_PACKAGE_PREFIX")).isEqualTo(JvmJavaDirectSites.RUNTIME_PACKAGE_PREFIX);
	}

	private static @Nullable Object constant(String name) throws Exception {
		Field field = JavaBridgeTemplate.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	private static JavaOverloads.@Nullable Overload sharedChoice(Class<?> type, String designator, List<Arg> args) {
		JavaOverloads.Member member = JavaOverloads.parseMember(designator);
		ReflectiveJavaClasses.Type reflected = ReflectiveJavaClasses.of(type);
		List<? extends JavaExecutable> candidates = JavaOverloads.filterByTag(reflected.methods(member.name()),
				member.tag());
		return JavaOverloads.select(candidates, args.size(),
				(i, target) -> JavaOverloads.kindCost(args.get(i).kind(), target, ReflectiveJavaClasses.instance()));
	}

	// The template's private resolve(Class, String, Supplier, Object[]) over the compiled
	// values, its candidates from its own methods() and tag filter.
	private static Object @Nullable [] templateChoice(Class<?> type, String designator, List<Arg> args)
			throws Exception {
		Object[] member = (Object[]) Objects
			.requireNonNull(invoke("member", new Class<?>[] { String.class }, designator));
		String name = (String) member[0];
		String[] tag = (String[]) member[1];
		Supplier<List<?>> candidates = () -> {
			try {
				List<?> methods = (List<?>) invoke("methods", new Class<?>[] { Class.class, String.class }, type, name);
				return (List<?>) invoke("filterByTag", new Class<?>[] { List.class, String[].class }, methods, tag);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		};
		List<@Nullable Object> values = new ArrayList<>();
		for (Arg arg : args) {
			values.add(arg.compiled());
		}
		return (Object[]) invoke("resolve",
				new Class<?>[] { Class.class, String.class, Supplier.class, Object[].class }, type,
				designator + "#parity", candidates, values.toArray());
	}

	private static @Nullable Object invoke(String name, Class<?>[] parameterTypes, @Nullable Object... args)
			throws Exception {
		Method method = JavaBridgeTemplate.class.getDeclaredMethod(name, parameterTypes);
		method.setAccessible(true);
		return method.invoke(null, args);
	}

}
