package am.ik.rontolisp.macro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code compile} runtime of a compiled program ({@code compile-runtime.lisp} on the
 * classpath, canonical shape, read with the real {@link LispReader} like
 * {@link MopProtocol}): a definition that defines methods -- postmodern's
 * {@code build-dao-methods} {@code %eval} idiom, whose method construction already ran at
 * definition time and was spliced into the program (see {@link MopEvalCapture}) -- is a
 * deliberate no-op; a definition the macro-time evaluator compiled answers its
 * precompiled factory's function (see {@link CompiledTemplates}); everything else is
 * evaluated, because a compiled program has no runtime compiler. Injected by
 * {@code LispMacroExpander.expandTopLevelDefinitions}, gated on the program referencing
 * {@code compile} without defining it, or registering a precompiled definition. The
 * interpreter (and the compile paths' macro-time evaluator) instead carries the real
 * evaluator-backed {@code compile} built-in and never evaluates these forms.
 */
public final class CompileRuntime {

	@Nullable private static volatile List<LispVal> forms;

	@Nullable private static volatile List<LispVal> globals;

	private CompileRuntime() {
	}

	/**
	 * The runtime's function definitions, in definition order: position-independent.
	 * Parsed once and cached; the list is immutable, so callers may splice it into a
	 * program directly.
	 * @return the runtime defuns
	 */
	public static List<LispVal> forms() {
		load();
		return Objects.requireNonNull(forms);
	}

	/**
	 * The runtime's global variable definitions, which run before any top-level form (the
	 * registrations of precompiled definitions run at their own positions).
	 * @return the runtime defvars
	 */
	public static List<LispVal> globals() {
		load();
		return Objects.requireNonNull(globals);
	}

	private static void load() {
		if (forms == null) {
			synchronized (CompileRuntime.class) {
				if (forms == null) {
					List<LispVal> defuns = new ArrayList<>();
					List<LispVal> defvars = new ArrayList<>();
					for (LispVal form : LispReader.readAllFromString(readSource())) {
						boolean defvar = form instanceof LispCons cons && cons.car() instanceof LispSymbol head
								&& LispNames.DEFVAR.equals(head.name());
						(defvar ? defvars : defuns).add(form);
					}
					globals = List.copyOf(defvars);
					forms = List.copyOf(defuns);
				}
			}
		}
	}

	private static String readSource() {
		try (InputStream in = CompileRuntime.class.getResourceAsStream("compile-runtime.lisp")) {
			if (in == null) {
				throw new IllegalStateException("compile-runtime.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
