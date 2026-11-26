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
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.net.URI}'s reading of a URI reference as the front ends that have one share
 * it ({@code uri.lisp} on the classpath): the cut into components, the characters each
 * component takes and the resolution of a reference against a base -- what the Clojure
 * HTTP client follows a redirect by and what the Java subset's {@code java.net.URI} and
 * {@code java.net.http} are written over. It is Common Lisp source like every other
 * shipped library, written here from RFC 3986 and a JDK's answers
 * ({@code .kb/java-frontend-provenance.md}).
 *
 * <p>
 * The names reach a program through the run-time libraries of the front ends
 * ({@code clojure.lisp}) and through a lowered Java program's own forms, so the
 * compile-path splice runs after theirs:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time one of the {@code rontolisp::%uri-} functions is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path calls {@link #process(List)} inside
 * {@code CompileFrontend.expand}: a program that references one of the functions gets the
 * definitions prepended, and {@link LibraryDefunPruner} drops the ones it does not
 * reach.</li>
 * </ul>
 */
public final class UriLibrary {

	private static final String NAME_PREFIX = "%URI-";

	@Nullable private static volatile List<LispVal> forms;

	@Nullable private static volatile Set<String> functionNames;

	private UriLibrary() {
	}

	/**
	 * Returns the parsed library definitions. The source is written in canonical shape
	 * (internal double-colon {@code rontolisp::} helpers, bare {@code cl} names), so it
	 * needs no package resolution. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (UriLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource(), Features.INTERPRETER));
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = UriLibrary.class.getResourceAsStream("uri.lisp")) {
			if (in == null) {
				throw new IllegalStateException("uri.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the name is one of the functions the library defines.
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code true} when the library defines the function
	 */
	public static boolean isUriFunction(String symbolName) {
		return mayDefine(symbolName) && functionNames().contains(symbolName);
	}

	/**
	 * Whether the name can be one the library defines, answered from its spelling alone:
	 * every definition is a {@code rontolisp} {@code %uri-} name. The interpreter asks
	 * {@link #isUriFunction} for every function name it resolves for the first time and
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
			for (LispVal form : forms()) {
				if (form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
						&& rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
			for (String name : names) {
				if (!mayDefine(name)) {
					throw new IllegalStateException("uri.lisp defines " + name + ", which mayDefine refuses");
				}
			}
			functionNames = names;
		}
		return names;
	}

	/**
	 * The compile-path pre-pass: prepends the library definitions when the program
	 * references one of its functions. A program that does not, or one that holds them
	 * already, is returned unchanged.
	 * @param program the top-level forms, the front ends' run-time libraries spliced
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		boolean referenced = false;
		for (LispVal form : program) {
			if (defines(form)) {
				return program;
			}
			referenced = referenced || references(form);
		}
		if (!referenced) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
	}

	private static boolean defines(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name && isUriFunction(name.name());
	}

	private static boolean references(LispVal form) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (references(cons.car())) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && isUriFunction(symbol.name());
	}

}
