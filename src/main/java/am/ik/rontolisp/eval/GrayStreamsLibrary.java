package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * rontolisp's own Gray-stream extension ({@code gray.lisp}): the base classes and generic
 * functions a user-defined character output stream implements, mirroring how real
 * implementations expose their native Gray support. The
 * {@code write-char}/{@code write-string} built-ins dispatch to
 * {@code rontolisp:stream-write-string} when handed a CLOS instance instead of a stream
 * handle; the {@code trivial-gray-streams} shim system adapts the portable protocol onto
 * this one, so no third-party name is known to the core.
 */
public final class GrayStreamsLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private GrayStreamsLibrary() {
	}

	/**
	 * Returns the parsed protocol definitions (canonical shape, no package resolution
	 * needed). Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (GrayStreamsLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readSource(), Features.INTERNAL);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = GrayStreamsLibrary.class.getResourceAsStream("gray.lisp")) {
			if (in == null) {
				throw new IllegalStateException("gray.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** The protocol names whose presence activates the compile-path pre-pass. */
	private static final java.util.Set<String> PROTOCOL_NAMES = java.util.Set.of(LispNames.GRAY_STREAM_WRITE_CHAR,
			LispNames.GRAY_STREAM_WRITE_STRING, "fundamental-character-output-stream",
			"fundamental-character-input-stream");

	private static final String WRITE_STRING_DISPATCH = "%gray-write-string-dispatch";

	private static final String WRITE_CHAR_DISPATCH = "%gray-write-char-dispatch";

	/**
	 * The compile-path pre-pass (the usocket {@code process()} pattern): when the program
	 * uses the Gray protocol, splices {@code gray.lisp} unless a load already did, and
	 * rewrites every {@code (write-string s stream)} / {@code (write-char c
	 * stream)} call with an explicit non-literal stream onto the
	 * {@code rontolisp::%gray-write-*-dispatch} helpers, so a CLOS instance stream
	 * reaches the Gray generics in compiled programs like it does on the interpreter. A
	 * program that never mentions the protocol is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the Gray dispatch spliced and call sites rewritten
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (program.stream().noneMatch(GrayStreamsLibrary::referencesProtocol)) {
			return program;
		}
		java.util.List<LispVal> out = new java.util.ArrayList<>();
		if (program.stream().noneMatch(GrayStreamsLibrary::definesProtocol)) {
			out.addAll(forms());
		}
		for (LispVal form : program) {
			out.add(rewrite(form));
		}
		return out;
	}

	private static boolean referencesProtocol(LispVal form) {
		return switch (form) {
			case am.ik.rontolisp.LispSymbol sym -> PROTOCOL_NAMES.contains(member(sym.name()));
			case am.ik.rontolisp.LispCons cons -> referencesProtocol(cons.car()) || referencesProtocol(cons.cdr());
			default -> false;
		};
	}

	/** Whether the form is gray.lisp's own base-class defclass (already spliced). */
	private static boolean definesProtocol(LispVal form) {
		if (!(form instanceof am.ik.rontolisp.LispCons cons) || !(cons.car() instanceof am.ik.rontolisp.LispSymbol op)
				|| !LispNames.DEFCLASS.equals(member(op.name()))
				|| !(cons.cdr() instanceof am.ik.rontolisp.LispCons rest)
				|| !(rest.car() instanceof am.ik.rontolisp.LispSymbol name)) {
			return false;
		}
		am.ik.rontolisp.PackageRegistry.QualifiedName qn = am.ik.rontolisp.PackageRegistry.splitQualified(name.name());
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg())
				&& "fundamental-character-output-stream".equals(qn.member());
	}

	private static LispVal rewrite(LispVal form) {
		if (!(form instanceof am.ik.rontolisp.LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof am.ik.rontolisp.LispSymbol op) {
			String opName = member(op.name());
			// Quoted data is data; the dispatch defuns' own fallback calls must not
			// rewrite into themselves.
			if (LispNames.QUOTE.equals(opName)) {
				return form;
			}
			if (LispNames.DEFUN.equals(opName) && cons.cdr() instanceof am.ik.rontolisp.LispCons rest
					&& rest.car() instanceof am.ik.rontolisp.LispSymbol defName
					&& (WRITE_STRING_DISPATCH.equals(member(defName.name()))
							|| WRITE_CHAR_DISPATCH.equals(member(defName.name())))) {
				return form;
			}
			List<LispVal> parts = cons.toList();
			if (parts.size() == 3 && (LispNames.WRITE_STRING.equals(opName) || LispNames.WRITE_CHAR.equals(opName))
					&& streamArgMayBeInstance(parts.get(2))) {
				String helper = LispNames.RONTOLISP_PKG + "::"
						+ (LispNames.WRITE_STRING.equals(opName) ? WRITE_STRING_DISPATCH : WRITE_CHAR_DISPATCH);
				return new am.ik.rontolisp.LispCons(new am.ik.rontolisp.LispSymbol(helper),
						new am.ik.rontolisp.LispCons(rewrite(parts.get(1)),
								new am.ik.rontolisp.LispCons(rewrite(parts.get(2)), am.ik.rontolisp.LispNil.INSTANCE)));
			}
			if (LispNames.FORMAT.equals(opName) && parts.size() >= 3 && streamArgMayBeInstance(parts.get(1))) {
				// (format STREAM ctrl args...) with a possibly-CLOS destination: render
				// with (format nil ...) and route the string through the write-string
				// dispatch (whose fallback handles handles and the t designator). The
				// destination is bound first, preserving CL's evaluation order; the form
				// yields nil like format-to-stream.
				List<LispVal> fmtNil = new java.util.ArrayList<>();
				fmtNil.add(new am.ik.rontolisp.LispSymbol(LispNames.FORMAT));
				fmtNil.add(am.ik.rontolisp.LispNil.INSTANCE);
				for (int i = 2; i < parts.size(); i++) {
					fmtNil.add(rewrite(parts.get(i)));
				}
				am.ik.rontolisp.LispSymbol temp = new am.ik.rontolisp.LispSymbol("__gray_fmt_stream");
				LispVal dispatch = listOf(
						new am.ik.rontolisp.LispSymbol(LispNames.RONTOLISP_PKG + "::" + WRITE_STRING_DISPATCH),
						listOf(fmtNil.toArray(LispVal[]::new)), temp);
				return listOf(new am.ik.rontolisp.LispSymbol(LispNames.LET),
						listOf(listOf(temp, rewrite(parts.get(1)))), dispatch, am.ik.rontolisp.LispNil.INSTANCE);
			}
		}
		// Generic: rewrite the operator/elements individually. The tail is walked
		// element-wise (NOT re-checked as a call) so an interior argument sequence
		// starting with an operator-named symbol -- (error 'ty :format-control format
		// :format-arguments args) -- is never misread as a nested call.
		LispVal car = rewrite(cons.car());
		LispVal cdr = rewriteTail(cons.cdr());
		if (car == cons.car() && cdr == cons.cdr()) {
			return form;
		}
		return new am.ik.rontolisp.LispCons(car, cdr);
	}

	private static LispVal rewriteTail(LispVal tail) {
		if (!(tail instanceof am.ik.rontolisp.LispCons cons)) {
			return tail;
		}
		LispVal car = rewrite(cons.car());
		LispVal cdr = rewriteTail(cons.cdr());
		if (car == cons.car() && cdr == cons.cdr()) {
			return tail;
		}
		return new am.ik.rontolisp.LispCons(car, cdr);
	}

	/**
	 * A literal t/nil/string stream argument can never be a CLOS instance. A lambda-list
	 * keyword ({@code &rest} and friends) is not a stream argument at all: the walker has
	 * no position awareness, so a parameter named like an operator (jzon's {@code (defun
	 * %raise (type pos format &rest args) ...)}) would otherwise read as a call.
	 */
	private static boolean streamArgMayBeInstance(LispVal streamArg) {
		if (streamArg instanceof am.ik.rontolisp.LispSymbol sym && sym.name().startsWith("&")) {
			return false;
		}
		return !(streamArg instanceof am.ik.rontolisp.LispTrue) && !(streamArg instanceof am.ik.rontolisp.LispNil)
				&& !(streamArg instanceof am.ik.rontolisp.LispString);
	}

	private static String member(String name) {
		am.ik.rontolisp.PackageRegistry.QualifiedName qn = am.ik.rontolisp.PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	private static LispVal listOf(LispVal... items) {
		LispVal result = am.ik.rontolisp.LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			result = new am.ik.rontolisp.LispCons(items[i], result);
		}
		return result;
	}

}
