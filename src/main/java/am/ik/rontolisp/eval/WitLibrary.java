package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The runtime half of {@code rontolisp:wit-import} ({@code wit.lisp} on the classpath):
 * the provider registry {@code rontolisp::%wit-call} dispatches through, the
 * {@code rontolisp:wit-provide} binder, and the {@code rontolisp:wit-error} condition a
 * WIT {@code result}'s error arm signals.
 *
 * <p>
 * That is the provider MECHANISM, and it is deliberately the whole of it: this library
 * ships an implementation of NO concrete interface. rontolisp does not know what
 * wasi:keyvalue is -- hardcoding one third-party interface's id and member names into the
 * core would be version-pinned, name-pinned, and would privilege one spec, when the whole
 * bet of the IDL work is that a new host interface costs a {@code .wit} FILE and not core
 * code. Implementing a WIT interface is ordinary user code; see
 * {@code examples/wit/keyvalue} for an in-memory store, which is a plain Lisp file a
 * program loads. Calling a bound function with no provider behind it signals
 * {@code rontolisp:wit-error}.
 *
 * <p>
 * It is written in rontolisp itself (the {@code usocket}/{@code linalg} pattern) because
 * everything it does -- a hash table of callables, {@code apply}, a
 * {@code define-condition} -- is expressible in core primitives, so one implementation
 * serves both backends that need it and neither the JVM nor the WASM codegen gains a
 * case. The WASM backends never see it: there a {@code wit-import} lowers to
 * {@code rontolisp:wasm-import} and the host supplies the functions.
 *
 * <p>
 * Consumers, exactly like {@code UsocketLibrary}:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time it meets a {@code rontolisp:wit-import} directive or resolves one of the library's
 * own names (see {@code LispEvaluator});</li>
 * <li>the compile path ({@code RontoLispCli}, the web playground) calls {@link #process}
 * after {@code WitImportInliner} has lowered the directives, so the {@code %wit-call}
 * bodies it synthesized are what triggers the splice.</li>
 * </ul>
 */
public final class WitLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private WitLibrary() {
	}

	/**
	 * Returns the parsed library definitions. The source is written in canonical shape
	 * (external single-colon public names, internal double-colon helpers, bare {@code cl}
	 * names), so it needs no package resolution and re-resolving it is a no-op. Parsed
	 * once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (WitLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readSource());
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = WitLibrary.class.getResourceAsStream("wit.lisp")) {
			if (in == null) {
				throw new IllegalStateException("wit.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name is one the library defines: the internal
	 * {@code rontolisp::%wit-call} dispatch primitive every binding lowers to, the
	 * {@code rontolisp:wit-provide} binder, or the {@code rontolisp:wit-error} condition.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name belongs to the WIT runtime
	 */
	public static boolean isWitRuntimeName(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		if (qn == null || !LispNames.RONTOLISP_PKG.equals(qn.pkg())) {
			return false;
		}
		String member = qn.member();
		return LispNames.WIT_CALL.equals(member) || LispNames.WIT_PROVIDE.equals(member)
				|| LispNames.WIT_ERROR.equals(member) || LispNames.WIT_ERROR_PAYLOAD.equals(member)
				|| LispNames.WIT_RESULT.equals(member);
	}

	/**
	 * The compile-path pre-pass: when the program references the WIT runtime -- which it
	 * does exactly when {@code WitImportInliner} lowered a {@code wit-import} for the
	 * interpreter/JVM boundary, or the program binds a provider itself -- prepends the
	 * library definitions. A program that does not is returned unchanged, so its output
	 * is byte-identical to a build that never knew about the library.
	 * @param program the top-level forms (after {@code WitImportInliner})
	 * @return the program with the WIT runtime spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (!references(program) || definesWitCall(program)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
	}

	private static boolean references(List<LispVal> program) {
		for (LispVal form : program) {
			if (references(form)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a form references the WIT runtime anywhere inside it -- including as a
	 * QUOTED symbol, which is how {@code rontolisp:wit-error} appears when it is used as
	 * a condition class name rather than called as a function.
	 * <p>
	 * This is the predicate the compile-path pre-pass splices on. The interpreter needs
	 * the same one: its lazy load otherwise triggers only on resolving a runtime
	 * <em>function</em> name, which a class-name datum never is.
	 * @param form the form
	 * @return {@code true} if the form references one of the runtime's names
	 */
	public static boolean referencesWitRuntime(LispVal form) {
		return references(form);
	}

	private static boolean references(LispVal form) {
		return switch (form) {
			case LispSymbol sym -> isWitRuntimeName(sym.name());
			case LispCons cons -> references(cons.car()) || references(cons.cdr());
			default -> false;
		};
	}

	// The marker that the library is already present (the forms() splice defines it), so
	// process() cannot prepend a second copy.
	private static boolean definesWitCall(List<LispVal> program) {
		String qualified = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.WIT_CALL);
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.DEFUN.equals(op.name()) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name && qualified.equals(name.name())) {
				return true;
			}
		}
		return false;
	}

}
