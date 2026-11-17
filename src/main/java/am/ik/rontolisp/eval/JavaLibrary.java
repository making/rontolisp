package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The run-time half of the EXPERIMENTAL front end for a subset of the Java language
 * ({@code java.lisp} on the classpath): the condition a thrown object travels in, the
 * text of a number and of a value, {@code Object}'s methods over the values no class of
 * the program answers for, and the collection helpers. It is Common Lisp source like
 * every other shipped library, so no backend learns a Java name
 * ({@code .kb/java-frontend.md}).
 *
 * <p>
 * Consumers, the {@link StarlarkLibrary} shape:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time one of the {@code rontolisp::%java-} functions is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path calls {@link #process(List, boolean)} inside
 * {@code CompileFrontend.expand}: a program that references one of the functions -- only
 * a lowered Java program does -- gets the definitions prepended, and
 * {@link LibraryDefunPruner} drops the ones it does not reach.</li>
 * </ul>
 */
public final class JavaLibrary {

	private static final String NAME_PREFIX = "%JAVA-";

	@Nullable private static volatile List<LispVal> forms;

	@Nullable private static volatile List<LispVal> oneStackForms;

	@Nullable private static volatile Set<String> functionNames;

	private JavaLibrary() {
	}

	/**
	 * Returns the parsed library definitions for a target that has threads: the
	 * interpreter and the JVM. The source is written in canonical shape (internal
	 * double-colon {@code rontolisp::} helpers, bare {@code cl} names), so it needs no
	 * package resolution. Parsed once and cached.
	 * @return the library forms
	 * @see #forms(boolean)
	 */
	public static List<LispVal> forms() {
		return forms(false);
	}

	/**
	 * Returns the parsed library definitions for a target. The two variants differ in the
	 * thread functions alone, under the same names: a thread is the host's where the
	 * target has threads, and runs on the stack of the thread that waits for it where the
	 * target has one stack ({@code .kb/java-frontend.md}, "Threads").
	 * @param oneStack whether the target runs a program on one stack: the wasm backends
	 * @return the library forms
	 */
	public static List<LispVal> forms(boolean oneStack) {
		List<LispVal> cached = oneStack ? oneStackForms : forms;
		if (cached == null) {
			synchronized (JavaLibrary.class) {
				cached = oneStack ? oneStackForms : forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource(),
							oneStack ? Features.WASM : Features.INTERPRETER));
					if (oneStack) {
						oneStackForms = cached;
					}
					else {
						forms = cached;
					}
				}
			}
		}
		return cached;
	}

	/**
	 * The definitions of both variants: every name the library can define.
	 * @return the forms of the two variants, one after the other
	 */
	public static List<LispVal> everyVariantForms() {
		List<LispVal> all = new ArrayList<>(forms(false));
		all.addAll(forms(true));
		return all;
	}

	private static String readSource() {
		try (InputStream in = JavaLibrary.class.getResourceAsStream("java.lisp")) {
			if (in == null) {
				throw new IllegalStateException("java.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the name is one of the functions {@code java.lisp} defines.
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code true} when the library defines the function
	 */
	public static boolean isJavaFunction(String symbolName) {
		return mayDefine(symbolName) && functionNames().contains(symbolName);
	}

	/**
	 * Whether the name can be one the library defines, answered from its spelling alone:
	 * every definition is a {@code rontolisp} {@code %java-} name. The interpreter asks
	 * {@link #isJavaFunction} for every function name it resolves for the first time and
	 * the compile path for every symbol of a program, so any other name must not read the
	 * library ({@code .kb/source-language.md}, "The run-time libraries' name gate").
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code false} when the library cannot define the name
	 */
	static boolean mayDefine(String symbolName) {
		return symbolName.contains(NAME_PREFIX) && LispSymbol.memberName(symbolName).startsWith(NAME_PREFIX);
	}

	private static Set<String> functionNames() {
		Set<String> names = functionNames;
		if (names == null) {
			names = new HashSet<>();
			for (LispVal form : everyVariantForms()) {
				if (form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
						&& rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
			for (String name : names) {
				if (!mayDefine(name)) {
					throw new IllegalStateException("java.lisp defines " + name + ", which mayDefine refuses");
				}
			}
			functionNames = names;
		}
		return names;
	}

	/**
	 * The compile-path pre-pass: prepends the library definitions when the program
	 * references one of its functions. A program that does not is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @param oneStack whether the target runs a program on one stack: the wasm backends
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program, boolean oneStack) {
		for (LispVal form : program) {
			if (references(form)) {
				List<LispVal> out = new ArrayList<>(forms(oneStack));
				out.addAll(program);
				return out;
			}
		}
		return program;
	}

	/**
	 * The functions a lowered program calls whose one-stack definitions call
	 * {@code sleep}.
	 */
	private static final Set<String> SLEEPING = Set.of("RONTOLISP::%JAVA-THREAD-SLEEP",
			"RONTOLISP::%JAVA-MONITOR-WAIT");

	/**
	 * Whether a lowered program can sleep through the library: a thread's sleep and a
	 * timed wait pass the time by {@code sleep} where the target has one stack. The
	 * library is spliced after the pass that binds {@code sleep} for a component
	 * ({@link WaitForLibrary}), so that pass asks here.
	 * @param program the top-level forms, before the library is spliced
	 * @return {@code true} when the program names one of those functions
	 */
	static boolean sleeps(List<LispVal> program) {
		for (LispVal form : program) {
			if (namesSleeping(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean namesSleeping(LispVal form) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (namesSleeping(cons.car())) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && SLEEPING.contains(symbol.name());
	}

	private static boolean references(LispVal form) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (references(cons.car())) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && isJavaFunction(symbol.name());
	}

}
