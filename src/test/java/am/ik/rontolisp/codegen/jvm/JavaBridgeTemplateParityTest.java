package am.ik.rontolisp.codegen.jvm;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaOverloads;
import am.ik.rontolisp.compiler.ReflectiveJavaClasses;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

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
