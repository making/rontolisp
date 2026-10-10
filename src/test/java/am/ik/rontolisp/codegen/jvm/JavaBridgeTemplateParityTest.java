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

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispJavaObject;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
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

	private static Arg bignum(BigInteger v) {
		return new Arg(JavaKind.Lisp.BIGNUM, v);
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

	private static final Arg FALSE = new Arg(JavaKind.Lisp.FALSE, LispNames.JAVA_FALSE);

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
				new Object[] { String.class, "valueOf", List.of(FALSE) },
				new Object[] { String.class, "valueOf", List.of(NIL) },
				new Object[] { Boolean.class, "toString", List.of(FALSE) },
				new Object[] { java.util.Objects.class, "equals", List.of(FALSE, NIL) },
				new Object[] { java.util.List.class, "of", List.of(FALSE, T) },
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
				new Object[] { StringBuilder.class, "append", List.of(FALSE) },
				new Object[] { StringBuilder.class, "append", List.of(NIL) },
				new Object[] { StringBuilder.class, "append", List.of(host(builder)) },
				new Object[] { StringBuilder.class, "append(CharSequence)", List.of(string("xy")) },
				new Object[] { StringBuilder.class, "insert", List.of(integer(0), string("x")) },
				new Object[] { java.util.ArrayList.class, "remove", List.of(integer(1)) },
				new Object[] { java.util.Collection.class, "remove", List.of(integer(1)) },
				new Object[] { java.util.ArrayList.class, "add", List.of(integer(0), integer(42)) },
				new Object[] { String.class, "valueOf", List.of(bignum(BigInteger.TWO.pow(100))) },
				new Object[] { java.util.Objects.class, "equals",
						List.of(bignum(BigInteger.TWO.pow(100)), integer(1)) },
				new Object[] { java.util.List.class, "of", List.of(bignum(BigInteger.TWO.pow(64)), integer(1)) },
				new Object[] { BigInteger.class, "add", List.of(integer(5)) },
				new Object[] { BigInteger.class, "add", List.of(bignum(BigInteger.TWO.pow(64))) },
				new Object[] { BigInteger.class, "pow", List.of(integer(5)) },
				new Object[] { BigDecimal.class, "valueOf", List.of(integer(5)) },
				new Object[] { Math.class, "sqrt", List.of(bignum(BigInteger.TWO.pow(64))) },
				new Object[] { Math.class, "max", List.of(bignum(BigInteger.TWO.pow(64)), integer(1)) },
				new Object[] { new HashMap<>().entrySet().iterator().getClass(), "hasNext", List.of() },
				new Object[] { new HashMap<>().keySet().iterator().getClass(), "next", List.of() });
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
		assertThat(checked).isGreaterThan(44);
	}

	// A function costs less for a functional interface than for another one, in both
	// copies of the cost table: TreeSet(Comparator) over TreeSet(Collection), as a Java
	// lambda and the oracle's fn choose.
	@Test
	void aFunctionCostsLessForAFunctionalInterface() throws Exception {
		for (Class<?> target : List.of(java.util.Comparator.class, Runnable.class, java.util.function.Function.class,
				Iterable.class, java.util.Collection.class, java.util.SortedSet.class,
				java.awt.event.MouseListener.class, java.io.Serializable.class, Object.class)) {
			int shared = JavaOverloads.kindCost(JavaKind.Lisp.FUNCTION, ReflectiveJavaClasses.of(target),
					ReflectiveJavaClasses.instance());
			assertThat(invoke("kindCost", new Class<?>[] { Object.class, Class.class }, "function", target))
				.as(target.getName())
				.isEqualTo(shared);
		}
		assertThat(JavaOverloads.kindCost(JavaKind.Lisp.FUNCTION, ReflectiveJavaClasses.of(java.util.Comparator.class),
				ReflectiveJavaClasses.instance()))
			.isEqualTo(JavaOverloads.COST_PROXY);
		assertThat(JavaOverloads.kindCost(JavaKind.Lisp.FUNCTION, ReflectiveJavaClasses.of(java.util.Collection.class),
				ReflectiveJavaClasses.instance()))
			.isEqualTo(JavaOverloads.COST_PROXY_NOT_FUNCTIONAL);
		assertThat(constant("COST_PROXY_NOT_FUNCTIONAL")).isEqualTo(JavaOverloads.COST_PROXY_NOT_FUNCTIONAL);
	}

	// A non-public receiver class whose method is declared on a non-public superclass
	// (HashMap's entry iterator, hasNext on HashMap$HashIterator) is called through the
	// receiver's own interface (Iterator.hasNext) in both copies.
	@Test
	void aNonPublicReceiverReachesItsOwnInterface() throws Exception {
		Class<?> iterator = new HashMap<>().entrySet().iterator().getClass();
		Object[] template = templateChoice(iterator, "hasNext", List.of());
		assertThat(Objects.requireNonNull(template)[0]).isEqualTo(java.util.Iterator.class.getMethod("hasNext"));
		JavaOverloads.Overload shared = sharedChoice(iterator, "hasNext", List.of());
		assertThat(((ReflectiveJavaClasses.Member) Objects.requireNonNull(shared).executable()).executable())
			.isEqualTo(java.util.Iterator.class.getMethod("hasNext"));
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
				template = String.valueOf(new java.util.TreeMap<>(
						(java.util.Map<?, ?>) invoke("reifySlots", new Class<?>[] { Class[].class, String[].class },
								new Class<?>[] { iface }, designators.toArray(String[]::new))));
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				template = "error: " + Objects.requireNonNull(ex.getCause()).getMessage();
			}
			assertThat(template).as("%s", row).isEqualTo(shared);
		}
		// A java:reify of several interfaces too: the most specific of them, a designator
		// naming a method of any, the messages naming the interface declaring it.
		for (List<List<String>> row : List.of(
				List.of(List.of("java.lang.Runnable", "java.util.function.Supplier"), List.of("run", "get")),
				List.of(List.of("java.lang.Runnable", "java.util.function.Supplier"), List.of("nope")),
				List.of(List.of("java.lang.Runnable", "java.util.function.Supplier"), List.of("get", "get()")),
				List.of(List.of("java.lang.Iterable", "java.util.List", "java.util.Collection"), List.of("size")),
				List.of(List.of("java.util.List", "java.util.Set"), List.of("size", "toString")),
				List.of(List.of("java.util.function.Consumer", "java.util.function.IntConsumer"),
						List.of("accept(int)", "accept(Object)")))) {
			List<Class<?>> listed = new java.util.ArrayList<>();
			List<am.ik.rontolisp.compiler.JavaType> types = new java.util.ArrayList<>();
			for (String name : row.get(0)) {
				listed.add(Class.forName(name));
				types.add(ReflectiveJavaClasses.of(Class.forName(name)));
			}
			String shared;
			try {
				shared = slots(am.ik.rontolisp.compiler.JavaImplementations.reify(types, row.get(1),
						ReflectiveJavaClasses.instance()));
			}
			catch (IllegalArgumentException ex) {
				shared = "error: " + ex.getMessage();
			}
			Class<?>[] specific = (Class<?>[]) Objects
				.requireNonNull(invoke("mostSpecific", new Class<?>[] { List.class }, listed));
			String template;
			try {
				template = String.valueOf(new java.util.TreeMap<>(
						(java.util.Map<?, ?>) invoke("reifySlots", new Class<?>[] { Class[].class, String[].class },
								specific, row.get(1).toArray(String[]::new))));
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				template = "error: " + Objects.requireNonNull(ex.getCause()).getMessage();
			}
			assertThat(template).as("%s", row).isEqualTo(shared);
		}
		// A java:proxy of several interfaces too: a name two declare is one slot per
		// parameter list and return type, whichever interface declares it.
		for (List<String> names : List.of(List.of("java.util.Comparator"), List.of("java.util.function.Function"),
				List.of("java.lang.CharSequence"), List.of("java.util.List"),
				List.of("java.util.function.Consumer", "java.util.function.IntConsumer"),
				List.of("java.awt.event.ActionListener", "java.awt.event.KeyListener"),
				List.of("java.util.function.Supplier", "java.util.concurrent.Future", "java.lang.Runnable"),
				List.of("java.util.Collection", "java.util.List"))) {
			Class<?>[] interfaces = new Class<?>[names.size()];
			List<am.ik.rontolisp.compiler.JavaType> types = new java.util.ArrayList<>();
			for (int i = 0; i < interfaces.length; i++) {
				interfaces[i] = Class.forName(names.get(i));
				types.add(ReflectiveJavaClasses.of(interfaces[i]));
			}
			assertThat(String.valueOf(new java.util.TreeMap<>(
					(java.util.Map<?, ?>) invoke("proxySlots", new Class<?>[] { Class[].class }, (Object) interfaces))))
				.as("%s", names)
				.isEqualTo(slots(
						am.ik.rontolisp.compiler.JavaImplementations.proxy(types, ReflectiveJavaClasses.instance())));
		}
	}

	// A function passed at a :functional site the bridge converts when it runs declares
	// the slots the shared rule chooses (compiler/JavaImplementations.functional).
	@Test
	void theTemplateImplementsAFunctionalArgumentAsTheSharedRuleDoes() throws Exception {
		for (String name : List.of("java.util.Comparator", "java.util.function.Function", "java.lang.Runnable",
				"java.util.Iterator", "java.lang.CharSequence", "java.util.List", "java.awt.event.KeyListener",
				"am.ik.rontolisp.compiler.JavaImplementationsTest$Narrowed")) {
			Class<?> iface = Class.forName(name);
			assertThat(String.valueOf(new java.util.TreeMap<>(
					(java.util.Map<?, ?>) invoke("functionalSlots", new Class<?>[] { Class.class }, iface))))
				.as("%s", name)
				.isEqualTo(slots(am.ik.rontolisp.compiler.JavaImplementations
					.functional(ReflectiveJavaClasses.of(iface), ReflectiveJavaClasses.instance())));
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
	// ArrayList / LinkedHashMap a Lisp array / hash table also is among them, and
	// subclasses of them whatever their overrides answer -- the two answer alike, and as
	// intended.
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
		// A subclass whose overrides lie about its contents: the class decides, its
		// methods are never asked.
		ArrayList<Object> notEmpty = new ArrayList<>() {
			@Override
			public boolean isEmpty() {
				return false;
			}
		};
		ArrayList<Object> headerLike = new ArrayList<>(lispArray) {
		};
		LinkedHashMap<Object, Object> ordered = new LinkedHashMap<>() {
			@Override
			public Object get(Object key) {
				return new ArrayList<>();
			}
		};
		List<Object> host = List.of(new StringBuilder(), new ArrayList<>(), hostList, new LinkedHashMap<>(), hostMap,
				new HashMap<>(), Optional.empty(), BigDecimal.ONE, notEmpty, headerLike, ordered);
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

	// The object a java:call on a Lisp value is made on -- and the one equal hands a host
	// object's equals -- is one rule with four copies: the shared
	// JavaOverloads.isReceiverKind / receiverClassName, the interpreter's
	// LispJavaObject.receiverObject, the bridge's receiverObject and the _jrecv a
	// resolved site and _equal call. Over every kind, the three run-time copies answer
	// the same object, of the class the shared rule names (a fixnum's narrowest box,
	// which it leaves to the value), and none for nil, a function or a value of no kind.
	@Test
	void theBridgeAndADirectSiteCallALispValueAsTheSharedRuleSays(@TempDir Path dir) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("ReceiverTest");
		byte[] bytes = compiler.compile(LispReader.readAllFromString("""
				(print (java:call "abc" "length"))
				"""));
		Files.write(dir.resolve("ReceiverTest.class"), bytes);
		for (Map.Entry<String, byte[]> file : compiler.runtimeClassFiles().entrySet()) {
			Path target = dir.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		Object function = new Object[] { 3, "car" };
		List<Arg> values = List.of(NIL, T, FALSE, integer(5), integer(-7), integer(1L << 40),
				bignum(BigInteger.TEN.pow(30)), real(1.5), string("s"), string("str"), character('a'),
				character(128512), new Arg(JavaKind.Lisp.FUNCTION, function));
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Method jrecv = loader.loadClass("ReceiverTest")
				.getDeclaredMethod(JvmJavaDirectSites.RECEIVER, Object.class);
			jrecv.setAccessible(true);
			for (Arg value : values) {
				JavaKind.Lisp kind = (JavaKind.Lisp) value.kind();
				Object direct = jrecv.invoke(null, value.compiled());
				Object bridge = invoke("receiverObject", new Class<?>[] { Object.class }, value.compiled());
				assertThat(direct).as("_jrecv %s", kind).isEqualTo(bridge);
				assertThat(LispJavaObject.receiverObject(interpreted(value))).as("interpreter %s", kind)
					.isEqualTo(bridge);
				if (!JavaOverloads.isReceiverKind(kind)) {
					assertThat(bridge).as("bridge %s", kind).isNull();
					continue;
				}
				assertThat(bridge).as("bridge %s", kind).isNotNull();
				String expected = JavaOverloads.receiverClassName(kind);
				if (expected == null) {
					long fixnum = (Long) Objects.requireNonNull(value.compiled());
					expected = fixnum == (int) fixnum ? "java.lang.Integer" : "java.lang.Long";
				}
				assertThat(Objects.requireNonNull(bridge).getClass().getName()).as("bridge %s", kind)
					.isEqualTo(expected);
			}
			for (Object none : List.of("FOO", new BigInteger[] { BigInteger.ONE, BigInteger.TWO },
					new Object[] { 1L, null })) {
				assertThat(jrecv.invoke(null, none)).as("_jrecv %s", none).isNull();
				assertThat(invoke("receiverObject", new Class<?>[] { Object.class }, none)).as("bridge %s", none)
					.isNull();
			}
			for (LispVal none : List.of(new LispSymbol("FOO"), new LispRatio(BigInteger.ONE, BigInteger.TWO),
					new LispCons(new LispInteger(1), LispNil.INSTANCE))) {
				assertThat(LispJavaObject.receiverObject(none)).as("interpreter %s", none.print()).isNull();
			}
		}
	}

	// The interpreter's value of an argument's compiled representation, for the kinds the
	// receiver rule is pinned over.
	private static LispVal interpreted(Arg value) {
		Object compiled = value.compiled();
		return switch ((JavaKind.Lisp) value.kind()) {
			case NIL -> LispNil.INSTANCE;
			case T -> LispTrue.INSTANCE;
			case FALSE -> new LispSymbol(LispNames.JAVA_FALSE);
			case INTEGER -> new LispInteger((Long) Objects.requireNonNull(compiled));
			case BIGNUM -> new LispBigInteger((BigInteger) Objects.requireNonNull(compiled));
			case FLOAT -> new LispDouble((Double) Objects.requireNonNull(compiled));
			case STRING, STRING_1 -> {
				String framed = (String) Objects.requireNonNull(compiled);
				yield new LispString(framed.substring(1, framed.length() - 1));
			}
			case CHAR, SUPPLEMENTARY_CHAR -> new LispChar(((int[]) Objects.requireNonNull(compiled))[0]);
			case FUNCTION -> new LispFunction("car", args -> LispNil.INSTANCE);
		};
	}

	// A specialized vector reaches a site as the same elements whichever copy reads it:
	// the bridge's packedElements and the _jseq a dispatched site calls, over every
	// packed shape -- the rank-1 ones as aref reads them, a rank-2 array and a quantized
	// matrix (a byte[] whose slot 0 is its format code) as no sequence.
	@Test
	void theBridgeAndADirectSiteReadASpecializedVectorAlike(@TempDir Path dir) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("PackedTest");
		byte[] bytes = compiler.compile(LispReader.readAllFromString("""
				(defvar *c* "java.util.Arrays")
				(defun ts (x) (java:static "java.util.Arrays" "toString" x))
				(defun ts* (x) (java:static *c* "toString" x))
				(print (ts (make-array 1 :element-type 'bfloat16)))
				(print (ts* (make-array 1 :element-type '(unsigned-byte 8))))
				"""));
		Files.write(dir.resolve("PackedTest.class"), bytes);
		for (Map.Entry<String, byte[]> file : compiler.runtimeClassFiles().entrySet()) {
			Path target = dir.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		short oneAndAHalf = (short) (Float.floatToRawIntBits(1.5f) >>> 16);
		short nan = (short) 0x7f81;
		List<Object> values = List.of(new double[] { 1, 2, 1.5, -2 }, new float[] { 1, 1, 6 },
				new short[] { 1, 0, 2, oneAndAHalf, nan }, new long[] { 16, 7, 65535 },
				new byte[] { 8, 1, (byte) 200, (byte) 255 }, new double[] { 2, 1, 1, 0 }, new byte[] { 1, 0, 0 });
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> program = loader.loadClass("PackedTest");
			Method jseq = program.getDeclaredMethod(JvmJavaDirectSites.SEQUENCE, Object.class);
			jseq.setAccessible(true);
			invoke("bind", new Class<?>[] { Class.class }, program);
			try {
				List<@Nullable List<?>> direct = new ArrayList<>();
				List<@Nullable Object> bridge = new ArrayList<>();
				for (Object value : values) {
					Object[] elements = (Object[]) jseq.invoke(null, value);
					direct.add(elements == null ? null : Arrays.asList(elements));
					bridge.add(invoke("packedElements", new Class<?>[] { Object.class }, value));
				}
				assertThat(bridge).isEqualTo(direct);
				assertThat(direct).containsExactly(List.of(1.5, -2.0), List.of(6.0),
						List.of(1.5, am.ik.rontolisp.BFloat16.value(nan)), List.of(7L, 65535L), List.of(1L, 200L, 255L),
						null, null);
				assertThat(Double.doubleToRawLongBits((Double) Objects.requireNonNull(direct.get(2)).get(1)))
					.isEqualTo(Double.doubleToRawLongBits(am.ik.rontolisp.BFloat16.value(nan)));
			}
			finally {
				// The template class is this JVM's: leave it unbound for the other tests.
				for (String field : List.of("applyMethod", "strvMethod", "lispToStringMethod", "bf16ValueMethod",
						"hashValuesMethod", "signalMethod", "failMethod")) {
					Field f = JavaBridgeTemplate.class.getDeclaredField(field);
					f.setAccessible(true);
					f.set(null, null);
				}
			}
		}
	}

	// A hash table reaches a site as the same entries whichever copy reads it: the
	// bridge's tableEntries and the _jtab a dispatched site calls, both through the
	// program's _hashValues -- live entries in insertion order, keys and values
	// alternating, an equalp table's key as first stored -- and anything else as no
	// table.
	@Test
	void theBridgeAndADirectSiteReadAHashTableAlike(@TempDir Path dir) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("TableTest");
		byte[] bytes = compiler.compile(LispReader.readAllFromString("""
				(defvar *c* "java.util.Objects")
				(defun ts (x) (java:static "java.util.Objects" "toString" x))
				(defun ts* (x) (java:static *c* "toString" x))
				(let ((h (make-hash-table :test 'equalp)))
				  (setf (gethash "a" h) 1)
				  (remhash "a" h)
				  (print (ts h))
				  (print (ts* h)))
				"""));
		Files.write(dir.resolve("TableTest.class"), bytes);
		for (Map.Entry<String, byte[]> file : compiler.runtimeClassFiles().entrySet()) {
			Path target = dir.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> program = loader.loadClass("TableTest");
			Method jtab = declared(program, JvmJavaDirectSites.TABLE, Object.class);
			Method make = declared(program, JvmHashRuntimeBuilder.MAKE);
			Method makeEqualp = declared(program, JvmHashRuntimeBuilder.MAKE_EQUALP);
			Method put = declared(program, JvmHashRuntimeBuilder.PUT, Object.class, Object.class, Object.class);
			Method remove = declared(program, JvmHashRuntimeBuilder.REM, Object.class, Object.class);
			Object table = make.invoke(null);
			put.invoke(null, "\"b\"", table, 2L);
			put.invoke(null, "\"gone\"", table, 0L);
			put.invoke(null, "\"a\"", table, null);
			remove.invoke(null, "\"gone\"", table);
			Object folded = makeEqualp.invoke(null);
			put.invoke(null, "\"Key\"", folded, 1L);
			put.invoke(null, "\"KEY\"", folded, 2L);
			invoke("bind", new Class<?>[] { Class.class }, program);
			try {
				assertThat(Arrays.asList((Object[]) jtab.invoke(null, table))).containsExactly("\"b\"", 2L, "\"a\"",
						null);
				assertThat(Arrays.asList((Object[]) jtab.invoke(null, folded))).containsExactly("\"Key\"", 2L);
				for (Object value : List.of(table, folded)) {
					assertThat(invoke("tableEntries", new Class<?>[] { Object.class }, value)).as("bridge %s", value)
						.isEqualTo(Arrays.asList((Object[]) jtab.invoke(null, value)));
				}
				assertThat(invoke("tableEntries", new Class<?>[] { Object.class }, new LinkedHashMap<>())).isNull();
			}
			finally {
				// The template class is this JVM's: leave it unbound for the other tests.
				for (String field : List.of("applyMethod", "strvMethod", "lispToStringMethod", "bf16ValueMethod",
						"hashValuesMethod", "signalMethod", "failMethod")) {
					Field f = JavaBridgeTemplate.class.getDeclaredField(field);
					f.setAccessible(true);
					f.set(null, null);
				}
			}
		}
	}

	private static Method declared(Class<?> program, String name, Class<?>... parameterTypes) throws Exception {
		Method method = program.getDeclaredMethod(name, parameterTypes);
		method.setAccessible(true);
		return method;
	}

	// A Java value comes back the same whichever copy reads it: the bridge's unmarshal
	// and
	// the _junm a direct site calls, and at a call ending in :java-false the bridge's
	// unmarshal of Java's false as |false| and _junf -- an array's elements, a nested
	// array's, a boolean[]'s alike. A Comparator's answer is read alike by the bridge's
	// comparison and _jcmp (the false arm, which calls the function again, is pinned by
	// JavaImplementationPrograms.JAVA_FALSE on both paths).
	@Test
	void theBridgeAndADirectSiteAnswerJavasFalseAlike(@TempDir Path dir) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("FalseTest");
		byte[] bytes = compiler.compile(LispReader.readAllFromString("""
				(defun get0 (l)
				  (declare (type (java:object "java.util.List") l))
				  (list (java:call l "get" 0) (java:call l "get" 0 :java-false)))
				(let ((l (java:new "java.util.ArrayList")))
				  (java:call l "add" 1)
				  (java:static "java.util.Collections" "sort" l (lambda (a b) t) :functional :java-false)
				  (print (get0 l)))
				"""));
		Files.write(dir.resolve("FalseTest.class"), bytes);
		for (Map.Entry<String, byte[]> file : compiler.runtimeClassFiles().entrySet()) {
			Path target = dir.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		List<@Nullable Object> values = Arrays.asList(null, Boolean.TRUE, Boolean.FALSE, 5, "x", 'c',
				new Object[] { Boolean.FALSE, Boolean.TRUE, null }, new boolean[] { false, true },
				new Object[] { new boolean[] { false }, new Object[] { Boolean.FALSE } }, new StringBuilder("host"));
		List<@Nullable Object> answers = Arrays.asList("T", 5L, -3L, 4294967297L, 1.5, -2.7, Double.NaN, 1e20,
				BigInteger.TWO.pow(40).add(BigInteger.ONE), BigInteger.TWO.pow(40).negate(),
				new BigInteger[] { new BigInteger("499999999999999999"), new BigInteger("100000000000000000") },
				new BigInteger[] { BigInteger.valueOf(-7), BigInteger.TWO }, "\"x\"", "FOO", null);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> program = loader.loadClass("FalseTest");
			Method junm = declared(program, JvmJavaDirectSites.UNMARSHAL, Object.class);
			Method junf = declared(program, JvmJavaDirectSites.UNMARSHAL_FALSE, Object.class);
			Method jcmp = declared(program, JvmJavaDirectSites.COMPARISON, Object.class, Object.class, Object.class);
			for (Object value : values) {
				String direct = Arrays.deepToString(new Object[] { junm.invoke(null, value) });
				String directFalse = Arrays.deepToString(new Object[] { junf.invoke(null, value) });
				assertThat(Arrays.deepToString(new Object[] {
						invoke("unmarshal", new Class<?>[] { Object.class, boolean.class }, value, false) }))
					.as("bridge %s", value)
					.isEqualTo(direct);
				assertThat(Arrays.deepToString(new Object[] {
						invoke("unmarshal", new Class<?>[] { Object.class, boolean.class }, value, true) }))
					.as("bridge :java-false %s", value)
					.isEqualTo(directFalse);
			}
			assertThat(junf.invoke(null, Boolean.FALSE)).isEqualTo(LispNames.JAVA_FALSE);
			assertThat(junm.invoke(null, Boolean.FALSE)).isNull();
			List<@Nullable Object> direct = new ArrayList<>();
			List<@Nullable Object> bridge = new ArrayList<>();
			for (Object answer : answers) {
				direct.add(compared(jcmp.invoke(null, null, null, answer)));
				bridge.add(compared(invoke("comparison", new Class<?>[] { Object.class, Object[].class, Object.class },
						null, new Object[] { 1L, new Object[] { 2L, null } }, answer)));
			}
			assertThat(bridge).isEqualTo(direct);
			// the oracle's AFunction.compare: Long/BigInt.intValue (low bits), (int) of a
			// double, a ratio's DECIMAL64 quotient truncated; nil its
			// NullPointerException,
			// anything else its ClassCastException -- a string's naming String, a
			// symbol's
			// its printed spelling (without the program's printer, the bridge's own text)
			assertThat(direct).containsExactly(-1, 5, -3, 1, 1, -2, 0, Integer.MAX_VALUE, 1, 0, 5, -3,
					"java.lang.ClassCastException: class java.lang.String cannot be cast to class java.lang.Number",
					"java.lang.ClassCastException: class FOO cannot be cast to class java.lang.Number",
					"java.lang.NullPointerException: " + am.ik.rontolisp.compiler.JavaImplementation.COMPARISON_OF_NIL);
		}
	}

	// A byte[] comes back the same whichever copy reads it at a call ending in :octets:
	// the bridge's unmarshal and the _juno / _jufo a direct site calls -- an octet vector
	// (the width 8, then the octets), empty, an Object[]'s element, a byte[][]'s -- while
	// the unmarked _junm keeps the list of signed bytes.
	@Test
	void theBridgeAndADirectSiteAnswerOctetsAlike(@TempDir Path dir) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("OctetsTest");
		byte[] bytes = compiler.compile(LispReader.readAllFromString("""
				(defun get0 (l)
				  (declare (type (java:object "java.util.List") l))
				  (list (java:call l "get" 0) (java:call l "get" 0 :octets) (java:call l "get" 0 :java-false :octets)))
				(let ((l (java:new "java.util.ArrayList")))
				  (java:call l "add" 1)
				  (print (get0 l)))
				"""));
		Files.write(dir.resolve("OctetsTest.class"), bytes);
		for (Map.Entry<String, byte[]> file : compiler.runtimeClassFiles().entrySet()) {
			Path target = dir.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		List<@Nullable Object> values = Arrays.asList(new byte[] { 1, -1, 0 }, new byte[0],
				new Object[] { new byte[] { 7 }, "x", Boolean.FALSE }, new byte[][] { { 2, 3 }, {} },
				new boolean[] { false }, new int[] { 9 }, null, "s");
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> program = loader.loadClass("OctetsTest");
			Method junm = declared(program, JvmJavaDirectSites.UNMARSHAL, Object.class);
			Method juno = declared(program, JvmJavaDirectSites.UNMARSHAL_OCTETS, Object.class);
			Method jufo = declared(program, JvmJavaDirectSites.UNMARSHAL_FALSE_OCTETS, Object.class);
			Class<?>[] flags = { Object.class, boolean.class, boolean.class };
			for (Object value : values) {
				assertThat(Arrays.deepToString(new Object[] { invoke("unmarshal", flags, value, false, true) }))
					.as("bridge :octets %s", value)
					.isEqualTo(Arrays.deepToString(new Object[] { juno.invoke(null, value) }));
				assertThat(Arrays.deepToString(new Object[] { invoke("unmarshal", flags, value, true, true) }))
					.as("bridge :java-false :octets %s", value)
					.isEqualTo(Arrays.deepToString(new Object[] { jufo.invoke(null, value) }));
				assertThat(Arrays.deepToString(new Object[] { invoke("unmarshal", flags, value, false, false) }))
					.as("bridge %s", value)
					.isEqualTo(Arrays.deepToString(new Object[] { junm.invoke(null, value) }));
			}
			assertThat((byte[]) juno.invoke(null, (Object) new byte[] { 1, -1 })).containsExactly(8, 1, -1);
		}
	}

	// What Java hands a function made at :octets comes out the same whichever copy makes
	// it: the bridge's callbackArguments and the _jcbo / _jcbf a generated callback calls
	// -- a byte[] an octet vector, one handed twice one vector, an Object[]'s byte[] its
	// element's -- and what the function stores goes back alike (writeBackOctets, _jwbo)
	// into the array, once for one handed twice; a function that never ran writes
	// nothing.
	@Test
	void theBridgeAndAGeneratedCallbackHandAFunctionOctetsAlike(@TempDir Path dir) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("CallbackOctetsTest");
		byte[] bytes = compiler.compile(LispReader.readAllFromString("""
				(let ((l (java:new "java.util.ArrayList")))
				  (java:call l "forEach" (java:reify "java.util.function.Consumer" "accept" (lambda (x) x) :octets))
				  (java:call l "forEach" (java:reify "java.util.function.Consumer" "accept" (lambda (x) x)
				                           :java-false :octets)))
				"""));
		Files.write(dir.resolve("CallbackOctetsTest.class"), bytes);
		for (Map.Entry<String, byte[]> file : compiler.runtimeClassFiles().entrySet()) {
			Path target = dir.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		byte[] shared = { 1, -1 };
		Object[] handed = { shared, "x", shared, new Object[] { new byte[] { 3 } }, Boolean.FALSE, null, new byte[0] };
		Class<?>[] arguments = { Object[].class, boolean.class };
		Class<?>[] writeBack = { Object[].class, Object[].class };
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> program = loader.loadClass("CallbackOctetsTest");
			Method jcbo = declared(program, JvmJavaDirectSites.CALLBACK_OCTETS, Object[].class);
			Method jcbf = declared(program, JvmJavaDirectSites.CALLBACK_FALSE_OCTETS, Object[].class);
			Method jwbo = declared(program, JvmJavaDirectSites.WRITE_BACK_OCTETS, Object[].class, Object[].class);
			for (boolean javaFalse : new boolean[] { false, true }) {
				Object[] direct = (Object[]) (javaFalse ? jcbf : jcbo).invoke(null, (Object) handed);
				Object[] bridge = (Object[]) Objects
					.requireNonNull(invoke("callbackArguments", arguments, handed, javaFalse));
				assertThat(Arrays.deepToString(bridge)).as("java-false %s", javaFalse)
					.isEqualTo(Arrays.deepToString(direct));
				assertThat(direct[2]).isSameAs(direct[0]);
				assertThat(bridge[2]).isSameAs(bridge[0]);
			}
			assertThat((byte[]) ((Object[]) jcbo.invoke(null, (Object) handed))[0]).containsExactly(8, 1, -1);
			for (boolean direct : new boolean[] { true, false }) {
				byte[] stored = { 1, 2 };
				byte[] kept = { 5 };
				Object[] java = { stored, kept, stored };
				Object[] values = direct ? (Object[]) jcbo.invoke(null, (Object) java)
						: (Object[]) Objects.requireNonNull(invoke("callbackArguments", arguments, java, false));
				((byte[]) values[2])[2] = 9;
				if (direct) {
					jwbo.invoke(null, java, values);
					jwbo.invoke(null, java, null);
				}
				else {
					invoke("writeBackOctets", writeBack, java, values);
				}
				assertThat(stored).as("direct %s", direct).containsExactly(1, 9);
				assertThat(kept).as("direct %s", direct).containsExactly(5);
			}
		}
	}

	// What _jcmp or the bridge's comparison answered: an Integer, or the refusal's class
	// and message.
	private static @Nullable Object compared(@Nullable Object answer) {
		return answer instanceof Throwable refusal ? refusal.toString() : answer;
	}

	// A java:handle or java:view comes back as the value it stands for whichever copy
	// reads it: the bridge's unmarshal, through the runtime/RontoJavaValue it binds, and
	// the _junm / _junf a direct site calls -- by itself and as an array's element; both
	// count one a host object.
	@Test
	void theBridgeAndADirectSiteAnswerAHandlesValueAlike(@TempDir Path dir) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("HandleTest");
		byte[] bytes = compiler.compile(LispReader.readAllFromString("""
				(defun get0 (l)
				  (declare (type (java:object "java.util.List") l))
				  (list (java:call l "get" 0) (java:call l "get" 0 :java-false)))
				(let ((l (java:new "java.util.ArrayList")))
				  (java:call l "add" (java:handle 'apple "apple"))
				  (java:call l "add" (java:view '(v) '(1 2) :list))
				  (java:call l "forEach" (lambda (x) (print x)) :functional)
				  (print (get0 l)))
				"""));
		Files.write(dir.resolve("HandleTest.class"), bytes);
		for (Map.Entry<String, byte[]> file : compiler.runtimeClassFiles().entrySet()) {
			Path target = dir.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> program = loader.loadClass("HandleTest");
			Method junm = declared(program, JvmJavaDirectSites.UNMARSHAL, Object.class);
			Method junf = declared(program, JvmJavaDirectSites.UNMARSHAL_FALSE, Object.class);
			Method handle = declared(program, JvmJavaDirectSites.HANDLE, Object.class, Object.class, Object.class,
					Object.class, Object.class, int.class);
			Method view = declared(program, JvmJavaDirectSites.VIEW, Object.class, Object.class, Object.class,
					Object.class, Object.class, Object.class, int.class);
			Method jhost = declared(program, JvmJavaDirectSites.HOST, Object.class);
			Object apple = handle.invoke(null, "APPLE", "\"apple\"", null, null, null, 2);
			Object pair = handle.invoke(null, new Object[] { 1L, null }, "\"(1)\"", 7L, "\"b\"", null, 4);
			Object other = handle.invoke(null, "X", "\"(2)\"", BigInteger.TWO.pow(40).add(BigInteger.valueOf(9)),
					"\"c\"", null, 4);
			Object list = view.invoke(null, "V", new Object[] { 1L, new Object[] { "\"s\"", null } }, ":LIST", null,
					null, null, 3);
			// runtime/RontoJavaHandle, the interpreter's class too: the text its toString
			// and
			// equality, the hash's low 32 bits its hashCode (the text's by default), the
			// order text (the text by default) its compareTo
			assertThat(apple).hasToString("apple");
			assertThat(List.of(apple.hashCode(), pair.hashCode(), other.hashCode())).containsExactly("apple".hashCode(),
					7, 9);
			assertThat(pair).isNotEqualTo(other).isEqualTo(handle.invoke(null, "Y", "\"(1)\"", 7L, null, null, 3));
			@SuppressWarnings("unchecked")
			Comparable<Object> ordered = (Comparable<Object>) pair;
			assertThat(ordered.compareTo(other)).isNegative();
			assertThat(ordered.compareTo(apple)).isPositive();
			// a view of a list: a read-only List of the items as Objects
			assertThat(list).isEqualTo(List.of(1, "s")).hasToString("[1, s]");
			invoke("bind", new Class<?>[] { Class.class }, program);
			try {
				for (Object value : List.of(apple, pair, list)) {
					assertThat(invoke("isJavaObject", new Class<?>[] { Object.class }, value)).as("bridge %s", value)
						.isEqualTo(jhost.invoke(null, value))
						.isEqualTo(true);
				}
				for (Object value : Arrays.asList(apple, pair, list, new Object[] { apple, Boolean.FALSE, pair })) {
					String direct = Arrays.deepToString(new Object[] { junm.invoke(null, value) });
					String directFalse = Arrays.deepToString(new Object[] { junf.invoke(null, value) });
					assertThat(Arrays.deepToString(new Object[] {
							invoke("unmarshal", new Class<?>[] { Object.class, boolean.class }, value, false) }))
						.as("bridge %s", value)
						.isEqualTo(direct);
					assertThat(Arrays.deepToString(new Object[] {
							invoke("unmarshal", new Class<?>[] { Object.class, boolean.class }, value, true) }))
						.as("bridge :java-false %s", value)
						.isEqualTo(directFalse);
				}
				assertThat(junm.invoke(null, apple)).isEqualTo("APPLE");
				assertThat(junm.invoke(null, list)).isEqualTo("V");
			}
			finally {
				// The template class is this JVM's: leave it unbound for the other tests.
				for (String field : List.of("applyMethod", "strvMethod", "lispToStringMethod", "bf16ValueMethod",
						"hashValuesMethod", "signalMethod", "failMethod", "javaValueClass", "javaValueMethod",
						"javaListViewClass", "javaListViewItems")) {
					Field f = JavaBridgeTemplate.class.getDeclaredField(field);
					f.setAccessible(true);
					f.set(null, null);
				}
			}
		}
	}

	// The bridge may import nothing of rontolisp's, so it spells the hash table's order
	// key, the runtime package, Java's false, the markers, Comparator's compare, the
	// class a handle or view is and a comparison's refusals itself.
	@Test
	void theBridgeSpellsTheRepresentationAsTheRuntimeDoes() throws Exception {
		assertThat(constant("HASH_TABLE_ORDER_KEY")).isEqualTo(RontoHashTable.ORDER_KEY);
		assertThat(constant("RUNTIME_PACKAGE_PREFIX")).isEqualTo(JvmJavaDirectSites.RUNTIME_PACKAGE_PREFIX);
		assertThat(constant("JAVA_FALSE")).isEqualTo(LispNames.JAVA_FALSE);
		assertThat(constant("FUNCTIONAL_MARKER")).isEqualTo(LispNames.JAVA_FUNCTIONAL_MARKER);
		assertThat(constant("JAVA_FALSE_MARKER")).isEqualTo(LispNames.JAVA_FALSE_MARKER);
		assertThat(constant("OCTETS_MARKER")).isEqualTo(LispNames.JAVA_OCTETS_MARKER);
		assertThat(constant("OCTET_TAG")).isEqualTo(JvmIntArrayRuntimeBuilder.OCTET_TAG);
		assertThat(constant("VALUE_OPTION")).isEqualTo(LispNames.JAVA_VALUE_OPTION);
		assertThat(constant("CLASS_OPTION")).isEqualTo(LispNames.JAVA_CLASS_OPTION);
		assertThat(constant("REIFY_USAGE")).isEqualTo(am.ik.rontolisp.compiler.JavaImplementations.REIFY_USAGE);
		assertThat(constant("COMPARATOR_COMPARE"))
			.isEqualTo(am.ik.rontolisp.compiler.JavaImplementation.COMPARATOR_COMPARE);
		assertThat(constant("JAVA_VALUE_CLASS")).isEqualTo(am.ik.rontolisp.runtime.RontoJavaValue.class.getName());
		assertThat(constant("JAVA_LIST_VIEW_CLASS"))
			.isEqualTo(am.ik.rontolisp.runtime.RontoJavaListView.class.getName());
		assertThat(constant("JAVA_BYTES_VIEW_CLASS"))
			.isEqualTo(am.ik.rontolisp.runtime.RontoJavaBytesView.class.getName());
		assertThat(constant("COST_VIEW_ARRAY")).isEqualTo(JavaOverloads.COST_VIEW_ARRAY);
		assertThat(constant("COMPARISON_OF_NIL"))
			.isEqualTo(am.ik.rontolisp.compiler.JavaImplementation.COMPARISON_OF_NIL);
		assertThat(constant("COMPARISON_CAST_PREFIX"))
			.isEqualTo(am.ik.rontolisp.compiler.JavaImplementation.COMPARISON_CAST_PREFIX);
		assertThat(constant("COMPARISON_CAST_SUFFIX"))
			.isEqualTo(am.ik.rontolisp.compiler.JavaImplementation.COMPARISON_CAST_SUFFIX);
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
