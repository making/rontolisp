package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The handle-side pushback of {@code unread-char} on the compile paths
 * ({@code unread-char.lisp}): the character-reading built-ins have no per-stream pushback
 * in any runtime -- a WASI fd, a socket and a string input stream can all be read but not
 * un-read -- so a program that uses {@code unread-char} gets ONE Lisp-level cell and has
 * its {@code read-char} / {@code read-char-no-hang} / {@code peek-char} /
 * {@code read-line} / {@code unread-char} call sites rewritten onto the defuns that
 * consult it. The compiled runtimes themselves know nothing; the pushback is ordinary
 * Lisp, so both compile backends answer identically by construction.
 *
 * <p>
 * A program that never names {@code unread-char} is returned unchanged, so nothing that
 * compiled before this pass existed moves a byte. The interpreter answers the same
 * contract in Java ({@code Environment}), because its built-ins are function values
 * rather than call sites a pre-pass could rewrite.
 *
 * <p>
 * Runs AFTER {@link GrayStreamsLibrary#process(List)}: a Gray dispatch helper's FALLBACK
 * branch is the handle arm, so its {@code (read-char stream ...)} /
 * {@code (unread-char character stream)} calls are exactly the call sites that must reach
 * the cell.
 */
public final class UnreadCharLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private UnreadCharLibrary() {
	}

	/** The pushback defun a rewritten {@code unread-char} call site names. */
	static final String PUSH = LispNames.UNREAD_CHAR_PUSH_INTERNAL;

	/** The pushback defun a rewritten {@code read-char} call site names. */
	static final String READ_CHAR = "%UNREAD-READ-CHAR";

	/** The pushback defun a rewritten {@code peek-char} call site names. */
	static final String PEEK_CHAR = "%UNREAD-PEEK-CHAR";

	/** The pushback defun a rewritten {@code read-line} call site names. */
	static final String READ_LINE = "%UNREAD-READ-LINE";

	/**
	 * The library's own defuns, whose bodies call the very built-ins the rewrite targets:
	 * rewriting those into the pushback defuns again would recurse forever.
	 */
	private static final Set<String> LIBRARY_DEFUNS = Set.of(PUSH, READ_CHAR, PEEK_CHAR, READ_LINE, "%UNREAD-KEY",
			"%UNREAD-CHAR-TAKE", "%UNREAD-PEEK-STOPS-P");

	/**
	 * Returns the parsed pushback definitions. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (UnreadCharLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readSource(), Features.INTERPRETER);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = UnreadCharLibrary.class.getResourceAsStream("unread-char.lisp")) {
			if (in == null) {
				throw new IllegalStateException("unread-char.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Splices the pushback definitions and rewrites the character-read call sites when
	 * the program uses {@code unread-char}; returns the program unchanged otherwise.
	 * @param program the top-level forms (after the Gray-dispatch rewrite)
	 * @return the program with the pushback spliced and call sites rewritten
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (!usesUnreadChar(program)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		for (LispVal form : program) {
			out.add(rewrite(form));
		}
		return out;
	}

	/**
	 * Whether the program names {@code unread-char} at all -- the one trigger. Quoted
	 * data counts: over-triggering costs a splice the program does not use, while missing
	 * a call site costs the character.
	 * @param program the top-level forms
	 * @return whether the pushback must be spliced
	 */
	public static boolean usesUnreadChar(List<LispVal> program) {
		for (LispVal form : program) {
			if (names(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean names(LispVal form) {
		if (form instanceof LispSymbol sym) {
			return LispNames.UNREAD_CHAR.equals(member(sym.name()));
		}
		if (form instanceof LispCons cons) {
			return names(cons.car()) || names(cons.cdr());
		}
		return false;
	}

	private static LispVal rewrite(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol op) {
			String opName = member(op.name());
			// Quoted data is data, and the library's own defuns must keep calling the
			// built-ins their bodies name.
			if (LispNames.QUOTE.equals(opName) || isLibraryDefun(cons, opName)) {
				return form;
			}
			LispVal call = rewriteCall(cons, opName);
			if (call != null) {
				return call;
			}
			LispVal structural = rewriteStructuralForm(cons, opName);
			if (structural != null) {
				return structural;
			}
		}
		return rewriteElements(cons);
	}

	private static boolean isLibraryDefun(LispCons cons, String opName) {
		return LispNames.DEFUN.equals(opName) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name && LIBRARY_DEFUNS.contains(member(name.name()));
	}

	/**
	 * The pushback rewrite of one call, or null when the form is not one. Absent optional
	 * arguments become their defaults LITERALLY -- {@code t}/{@code nil} evaluate to
	 * themselves, so no evaluation order changes -- and {@code read-line}'s eof-error-p
	 * defaults to nil, the built-in's lite convention.
	 */
	@Nullable private static LispVal rewriteCall(LispCons cons, String opName) {
		List<LispVal> parts = cons.toList();
		int args = parts.size() - 1;
		switch (opName) {
			case LispNames.UNREAD_CHAR -> {
				if (args == 1 || args == 2) {
					return listOf(defunSymbol(PUSH), rewrite(parts.get(1)),
							args >= 2 ? rewrite(parts.get(2)) : LispNil.INSTANCE);
				}
			}
			// read-char-no-hang IS read-char here (no source rontolisp can open reports
			// "would block" separately), so the two share the pushback defun.
			case LispNames.READ_CHAR, LispNames.READ_CHAR_NO_HANG -> {
				if (args <= 3) {
					return listOf(defunSymbol(READ_CHAR), arg(parts, 1, LispNil.INSTANCE),
							arg(parts, 2, LispTrue.INSTANCE), arg(parts, 3, LispNil.INSTANCE));
				}
			}
			case LispNames.READ_LINE -> {
				if (args <= 3) {
					return listOf(defunSymbol(READ_LINE), arg(parts, 1, LispNil.INSTANCE),
							arg(parts, 2, LispNil.INSTANCE), arg(parts, 3, LispNil.INSTANCE));
				}
			}
			case LispNames.PEEK_CHAR -> {
				if (args <= 4) {
					return listOf(defunSymbol(PEEK_CHAR), arg(parts, 1, LispNil.INSTANCE),
							arg(parts, 2, LispNil.INSTANCE), arg(parts, 3, LispTrue.INSTANCE),
							arg(parts, 4, LispNil.INSTANCE));
				}
			}
			default -> {
				return null;
			}
		}
		return null;
	}

	private static LispVal arg(List<LispVal> parts, int index, LispVal missing) {
		return index < parts.size() ? rewrite(parts.get(index)) : missing;
	}

	/**
	 * The forms whose leading elements are STRUCTURE rather than calls -- a lambda list,
	 * a binding variable, a class name and its slot specs. Rewriting those would turn a
	 * parameter named after one of the read built-ins into a call; only the trailing body
	 * / value positions are rewritten. Returns null when the head is not one of them.
	 */
	@Nullable private static LispVal rewriteStructuralForm(LispCons cons, String opName) {
		List<LispVal> parts = cons.toList();
		switch (opName) {
			case LispNames.LAMBDA, LispNames.DESTRUCTURING_BIND -> {
				return keepThenRewrite(parts, 2);
			}
			case LispNames.DEFUN, LispNames.DEFMACRO -> {
				return keepThenRewrite(parts, 3);
			}
			case LispNames.DEFMETHOD -> {
				// (defmethod name [qualifier] lambda-list body...): the lambda list is
				// the first list-valued element after the name.
				int body = 2;
				while (body < parts.size() && !(parts.get(body) instanceof LispCons)
						&& !(parts.get(body) instanceof LispNil)) {
					body++;
				}
				return keepThenRewrite(parts, Math.min(body + 1, parts.size()));
			}
			case LispNames.LET, LispNames.LET_STAR -> {
				List<LispVal> out = new ArrayList<>();
				out.add(parts.get(0));
				out.add(parts.size() > 1 ? rewriteBindings(parts.get(1)) : LispNil.INSTANCE);
				for (int i = 2; i < parts.size(); i++) {
					out.add(rewrite(parts.get(i)));
				}
				return listOf(out.toArray(new LispVal[0]));
			}
			case LispNames.FLET, LispNames.LABELS, LispNames.MACROLET -> {
				List<LispVal> out = new ArrayList<>();
				out.add(parts.get(0));
				out.add(parts.size() > 1 ? rewriteLocalFunctions(parts.get(1)) : LispNil.INSTANCE);
				for (int i = 2; i < parts.size(); i++) {
					out.add(rewrite(parts.get(i)));
				}
				return listOf(out.toArray(new LispVal[0]));
			}
			case LispNames.DEFCLASS, LispNames.DEFINE_CONDITION -> {
				// name, superclasses and the slot list stay verbatim; the class options
				// after them are ordinary code (a :report lambda reads and prints).
				return keepThenRewrite(parts, 4);
			}
			case LispNames.DEFSTRUCT -> {
				return listOf(parts.toArray(new LispVal[0]));
			}
			default -> {
				return null;
			}
		}
	}

	private static LispVal keepThenRewrite(List<LispVal> parts, int firstRewritten) {
		List<LispVal> out = new ArrayList<>();
		for (int i = 0; i < parts.size(); i++) {
			out.add(i < firstRewritten ? parts.get(i) : rewrite(parts.get(i)));
		}
		return listOf(out.toArray(new LispVal[0]));
	}

	// ((var init) ...): the variable half is a name, the init half is code.
	private static LispVal rewriteBindings(LispVal bindings) {
		if (!(bindings instanceof LispCons)) {
			return bindings;
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal binding : ((LispCons) bindings).toList()) {
			if (binding instanceof LispCons pair) {
				out.add(keepThenRewrite(pair.toList(), 1));
			}
			else {
				out.add(binding);
			}
		}
		return listOf(out.toArray(new LispVal[0]));
	}

	// ((name lambda-list body...) ...): only the body is code.
	private static LispVal rewriteLocalFunctions(LispVal functions) {
		if (!(functions instanceof LispCons)) {
			return functions;
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal fn : ((LispCons) functions).toList()) {
			out.add(fn instanceof LispCons local ? keepThenRewrite(local.toList(), 2) : fn);
		}
		return listOf(out.toArray(new LispVal[0]));
	}

	// The generic recursion rewrites list ELEMENTS; a dotted tail is data, never a call.
	private static LispVal rewriteElements(LispCons cons) {
		LispVal car = rewrite(cons.car());
		LispVal cdr = cons.cdr() instanceof LispCons tail ? rewriteElements(tail) : cons.cdr();
		return new LispCons(car, cdr);
	}

	private static LispSymbol defunSymbol(String name) {
		return new LispSymbol(LispNames.RONTOLISP_PKG + "::" + name);
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	private static LispVal listOf(LispVal... items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			result = new LispCons(items[i], result);
		}
		return result;
	}

}
