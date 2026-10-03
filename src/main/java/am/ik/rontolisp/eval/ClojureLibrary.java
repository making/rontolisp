package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.ClojureArms;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The run-time half of the EXPERIMENTAL Clojure front end ({@code clojure.lisp} on the
 * classpath): the printer behind {@code println}/{@code print}/{@code pr}/{@code prn},
 * the string builders behind {@code str}/{@code pr-str}, and the REPL echo. It is Common
 * Lisp source like every other shipped library, so no backend learns a Clojure name
 * ({@code .kb/clojure-frontend.md}).
 *
 * <p>
 * Consumers, the {@link UrlLibrary} shape:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time one of the {@code rontolisp::%clojure-} functions is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path calls {@link #process(List)} inside
 * {@code CompileFrontend.expand}: a program that references one of the functions -- only
 * a lowered Clojure program does -- gets the definitions prepended, and
 * {@link LibraryDefunPruner} drops the ones it does not reach.</li>
 * </ul>
 *
 * <p>
 * A program that builds no sorted collection has the sorted-collection arms of its own
 * forms and of the library stripped first ({@link ClojureArms}), so it is spliced and
 * compiled exactly as before sorted collections existed; likewise the unbound-root arms
 * of a program that makes no unbound var. The interpreter keeps them: its library loads
 * once for whatever the session reads next.
 */
public final class ClojureLibrary {

	private static final Map<String, List<LispVal>> FORMS = new ConcurrentHashMap<>();

	/**
	 * The {@code java:}-free bodies {@link #formsWithoutHostArms()} splices in place of
	 * the library's host arms, one {@code defun} per arm, each defining the same name and
	 * answering what the host arm answers for a value that is no host object:
	 * {@code %clojure-host-class} ({@code class} of a value of no Clojure kind) refuses,
	 * {@code %clojure-host-class-name} (the printer), {@code %clojure-host-string}
	 * ({@code str}) and {@code %clojure-host-instance-p} ({@code inst?}, {@code uuid?},
	 * {@code uri?}, {@code class?}) answer NIL.
	 */
	private static final String HOST_ARMS_WITHOUT_JAVA = """
			(defun rontolisp::%clojure-host-class (x)
			  (declare (ignore x))
			  (error "class needs a value of a known kind"))
			(defun rontolisp::%clojure-host-class-name (x)
			  (declare (ignore x))
			  nil)
			(defun rontolisp::%clojure-host-string (x)
			  (declare (ignore x))
			  nil)
			(defun rontolisp::%clojure-host-instance-p (x class-name)
			  (declare (ignore x class-name))
			  nil)
			""";

	@Nullable private static volatile Set<String> functionNames;

	private ClojureLibrary() {
	}

	/**
	 * Returns the library definitions as the interpreter reads them.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		return FORMS.computeIfAbsent("default", ignored -> List.copyOf(LispReader.readAllFromString(readSource())));
	}

	private static String readSource() {
		try (InputStream in = ClojureLibrary.class.getResourceAsStream("clojure.lisp")) {
			if (in == null) {
				throw new IllegalStateException("clojure.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the name is one of the functions {@code clojure.lisp} defines.
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code true} when the library defines the function
	 */
	public static boolean isClojureFunction(String symbolName) {
		Set<String> names = functionNames;
		if (names == null) {
			names = new HashSet<>();
			for (LispVal form : forms()) {
				if (form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
						&& rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
			functionNames = names;
		}
		return names.contains(symbolName);
	}

	/**
	 * The compile-path pre-pass: prepends the library definitions when the program
	 * references one of its functions. A program that does not is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (!referencesAny(program)) {
			return program;
		}
		// an arm names the library too, so the reference is asked again once they go
		List<LispVal> body = program;
		Set<ClojureArms.Family> made = EnumSet.noneOf(ClojureArms.Family.class);
		for (ClojureArms.Family family : ClojureArms.Family.values()) {
			ClojureArms.Scan scan = ClojureArms.scan(body, family);
			if (scan.builds()) {
				made.add(family);
			}
			else if (scan.strips()) {
				body = ClojureArms.strip(body, family);
			}
		}
		if (body != program && !referencesAny(body)) {
			return body;
		}
		List<LispVal> out = new ArrayList<>(library(usesJava(body), made));
		out.addAll(body);
		return out;
	}

	private static boolean referencesAny(List<LispVal> program) {
		for (LispVal form : program) {
			if (references(form)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The library a program splices: with or without the host arms, and with the arms of
	 * only the families whose values the program makes (one that builds no sorted
	 * collection takes no sorted-collection arm).
	 */
	private static List<LispVal> library(boolean java, Set<ClojureArms.Family> made) {
		List<LispVal> library = java ? forms() : formsWithoutHostArms();
		StringBuilder key = new StringBuilder(java ? "default" : "without-host-arms");
		List<ClojureArms.Family> stripped = new ArrayList<>();
		for (ClojureArms.Family family : ClojureArms.Family.values()) {
			if (!made.contains(family)) {
				key.append(" without-").append(family.name());
				stripped.add(family);
			}
		}
		if (stripped.isEmpty()) {
			return library;
		}
		return FORMS.computeIfAbsent(key.toString(), ignored -> {
			List<LispVal> out = library;
			for (ClojureArms.Family family : stripped) {
				out = ClojureArms.strip(out, family);
			}
			return List.copyOf(out);
		});
	}

	/**
	 * The library for a program with no {@code java:} operator: the host arms replaced by
	 * their {@code java:}-free bodies ({@link #HOST_ARMS_WITHOUT_JAVA}). No host object
	 * can exist there, so the answer is the same, while the {@code java:} reference would
	 * change the JVM output (the bridge, the host guards on every accessor) and is a
	 * call-time error on wasm. The interpreter keeps {@link #forms()}: its {@code java:}
	 * costs nothing.
	 */
	private static List<LispVal> formsWithoutHostArms() {
		// read first: a nested computeIfAbsent on one ConcurrentHashMap is refused
		List<LispVal> library = forms();
		return FORMS.computeIfAbsent("without-host-arms", ignored -> {
			Map<String, LispVal> standIns = new HashMap<>();
			for (LispVal standIn : LispReader.readAllFromString(HOST_ARMS_WITHOUT_JAVA)) {
				standIns.put(definedName(standIn), standIn);
			}
			List<LispVal> out = new ArrayList<>();
			for (LispVal form : library) {
				LispVal standIn = standIns.remove(definedName(form));
				out.add(standIn == null ? form : standIn);
			}
			if (!standIns.isEmpty()) {
				throw new IllegalStateException("clojure.lisp defines no host arm " + standIns.keySet());
			}
			return List.copyOf(out);
		});
	}

	private static String definedName(LispVal form) {
		return form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name ? name.name() : "";
	}

	private static boolean usesJava(List<LispVal> program) {
		for (LispVal form : program) {
			if (mentions(form, LispNames.JAVA_OPERATORS_QUALIFIED)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentions(LispVal form, List<String> names) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (mentions(cons.car(), names)) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && names.contains(symbol.name());
	}

	private static boolean references(LispVal form) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (references(cons.car())) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && isClojureFunction(symbol.name());
	}

}
