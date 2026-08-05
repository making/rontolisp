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
					cached = LispReader.readAllFromString(readSource(), Features.INTERPRETER);
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

	/**
	 * The protocol names whose presence activates the compile-path pre-pass. All
	 * UPPERCASE: the reader upcases every unescaped symbol
	 * ({@code .kb/reader-case-upcase.md}), so a lowercase entry can never match (the
	 * pre-widening set carried two such dead lowercase class names).
	 */
	private static final java.util.Set<String> PROTOCOL_NAMES = java.util.Set.of(LispNames.GRAY_STREAM_WRITE_CHAR,
			LispNames.GRAY_STREAM_WRITE_STRING, LispNames.GRAY_STREAM_WRITE_BYTE, LispNames.GRAY_STREAM_READ_BYTE,
			LispNames.GRAY_STREAM_READ_CHAR, LispNames.GRAY_STREAM_UNREAD_CHAR, LispNames.GRAY_STREAM_READ_LINE,
			LispNames.GRAY_STREAM_LISTEN, LispNames.GRAY_STREAM_READ_SEQUENCE, LispNames.GRAY_STREAM_WRITE_SEQUENCE,
			LispNames.GRAY_STREAM_FILE_POSITION, LispNames.GRAY_CHAR_OUTPUT_STREAM, LispNames.GRAY_CHAR_INPUT_STREAM,
			LispNames.GRAY_FUNDAMENTAL_STREAM, LispNames.GRAY_INPUT_STREAM, LispNames.GRAY_OUTPUT_STREAM,
			LispNames.GRAY_BINARY_INPUT_STREAM, LispNames.GRAY_BINARY_OUTPUT_STREAM, LispNames.GRAY_STREAM_MIXIN);

	static final String WRITE_STRING_DISPATCH = "%GRAY-WRITE-STRING-DISPATCH";

	static final String WRITE_CHAR_DISPATCH = "%GRAY-WRITE-CHAR-DISPATCH";

	static final String WRITE_BYTE_DISPATCH = "%GRAY-WRITE-BYTE-DISPATCH";

	static final String READ_BYTE_DISPATCH = "%GRAY-READ-BYTE-DISPATCH";

	static final String READ_CHAR_DISPATCH = "%GRAY-READ-CHAR-DISPATCH";

	static final String READ_LINE_DISPATCH = "%GRAY-READ-LINE-DISPATCH";

	static final String LISTEN_DISPATCH = "%GRAY-LISTEN-DISPATCH";

	static final String READ_SEQUENCE_DISPATCH = "%GRAY-READ-SEQUENCE-DISPATCH";

	static final String WRITE_SEQUENCE_DISPATCH = "%GRAY-WRITE-SEQUENCE-DISPATCH";

	static final String FILE_POSITION_DISPATCH = "%GRAY-FILE-POSITION-DISPATCH";

	static final String FILE_POSITION_SET_DISPATCH = "%GRAY-FILE-POSITION-SET-DISPATCH";

	/**
	 * The gray.lisp defuns whose bodies the rewrite walker must skip: their fallback
	 * branches call the very built-ins the rewrite targets, and rewriting those into the
	 * dispatch again would recurse forever. The default element loops are included for
	 * symmetry (they only call generics today, but they are library internals, not user
	 * call sites).
	 */
	private static final java.util.Set<String> DISPATCH_DEFUNS = java.util.Set.of(WRITE_STRING_DISPATCH,
			WRITE_CHAR_DISPATCH, WRITE_BYTE_DISPATCH, READ_BYTE_DISPATCH, READ_CHAR_DISPATCH, READ_LINE_DISPATCH,
			LISTEN_DISPATCH, READ_SEQUENCE_DISPATCH, WRITE_SEQUENCE_DISPATCH, FILE_POSITION_DISPATCH,
			FILE_POSITION_SET_DISPATCH, "%GRAY-DEFAULT-READ-LINE", "%GRAY-DEFAULT-READ-SEQUENCE",
			"%GRAY-DEFAULT-WRITE-SEQUENCE");

	/**
	 * The dispatch defuns spliced only when a rewrite references them (see
	 * {@link #process}) -- exactly the {@link #DISPATCH_DEFUNS} minus the
	 * {@code %gray-default-*} element loops, which the protocol's own default methods
	 * (and the shim's) call and which therefore always travel with the protocol.
	 */
	private static final java.util.Set<String> SPLICE_ON_USE = java.util.Set.of(WRITE_STRING_DISPATCH,
			WRITE_CHAR_DISPATCH, WRITE_BYTE_DISPATCH, READ_BYTE_DISPATCH, READ_CHAR_DISPATCH, READ_LINE_DISPATCH,
			LISTEN_DISPATCH, READ_SEQUENCE_DISPATCH, WRITE_SEQUENCE_DISPATCH, FILE_POSITION_DISPATCH,
			FILE_POSITION_SET_DISPATCH);

	/**
	 * The compile-path pre-pass (the usocket {@code process()} pattern): when the program
	 * uses the Gray protocol, splices the gray.lisp protocol definitions unless a load
	 * already did, and rewrites every stream-taking built-in call with an explicit
	 * non-literal stream ({@code write-string}/{@code write-char}/{@code format},
	 * {@code write-byte}, {@code read-byte}/{@code read-char}/{@code read-line},
	 * {@code listen}, {@code read-sequence}/{@code write-sequence},
	 * {@code file-position}) onto the {@code rontolisp::%gray-*-dispatch} helpers, so a
	 * CLOS instance stream reaches the Gray generics in compiled programs like it does on
	 * the interpreter. Only the dispatch helpers a rewrite actually produced are spliced
	 * ({@code LibraryDefunPruner} does not cover this splice, and an unused helper is not
	 * just bloat: {@code %gray-listen-dispatch}'s fallback names the {@code listen}
	 * built-in, which the Preview 1 WASM backend rejects at compile time -- a Gray
	 * program that never calls {@code listen} must not inherit that rejection). A program
	 * that never mentions the protocol is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the Gray protocol spliced and call sites rewritten
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (program.stream().noneMatch(GrayStreamsLibrary::referencesProtocol)) {
			return program;
		}
		java.util.Set<String> usedHelpers = new java.util.LinkedHashSet<>();
		java.util.List<LispVal> rewritten = new java.util.ArrayList<>();
		for (LispVal form : program) {
			rewritten.add(rewrite(form, usedHelpers));
		}
		if (usedHelpers.contains(WRITE_CHAR_DISPATCH)) {
			// Its body delegates to the write-string helper.
			usedHelpers.add(WRITE_STRING_DISPATCH);
		}
		java.util.List<LispVal> out = new java.util.ArrayList<>();
		if (program.stream().noneMatch(GrayStreamsLibrary::definesProtocol)) {
			out.addAll(protocolForms());
		}
		for (String helper : usedHelpers) {
			out.add(dispatchDefun(helper));
		}
		out.addAll(rewritten);
		return out;
	}

	/**
	 * The gray.lisp forms minus the on-use dispatch defuns: the classes, generics,
	 * default methods and their shared element-loop defuns. This is what a load of the
	 * trivial-gray-streams shim splices ({@link ShimLibraries}); the dispatch defuns are
	 * added by {@link #process} exactly when a rewrite references them.
	 * @return the protocol definition forms
	 */
	public static List<LispVal> protocolForms() {
		java.util.List<LispVal> out = new java.util.ArrayList<>();
		for (LispVal form : forms()) {
			if (dispatchDefunName(form) == null) {
				out.add(form);
			}
		}
		return List.copyOf(out);
	}

	private static LispVal dispatchDefun(String helperName) {
		for (LispVal form : forms()) {
			if (helperName.equals(dispatchDefunName(form))) {
				return form;
			}
		}
		throw new IllegalStateException("gray.lisp does not define " + helperName);
	}

	/** The on-use dispatch helper this form defines, or {@code null}. */
	private static @Nullable String dispatchDefunName(LispVal form) {
		if (form instanceof am.ik.rontolisp.LispCons cons && cons.car() instanceof am.ik.rontolisp.LispSymbol op
				&& LispNames.DEFUN.equals(member(op.name())) && cons.cdr() instanceof am.ik.rontolisp.LispCons rest
				&& rest.car() instanceof am.ik.rontolisp.LispSymbol defName) {
			String memberName = member(defName.name());
			return SPLICE_ON_USE.contains(memberName) ? memberName : null;
		}
		return null;
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
				&& "FUNDAMENTAL-CHARACTER-OUTPUT-STREAM".equals(qn.member());
	}

	private static LispVal rewrite(LispVal form, java.util.Set<String> used) {
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
					&& DISPATCH_DEFUNS.contains(member(defName.name()))) {
				return form;
			}
			// A BINDING form's lambda list / binding list is not a call, and one of its
			// entries can look exactly like one: cl-postgres' messages.lisp has
			// (flet ((set-param (format size value) ...))), whose parameter list is a
			// three-element form headed by FORMAT. Rewriting it produced a `let` where a
			// parameter belonged and failed the compile with "Parameter must be a
			// symbol". The walk therefore skips the structural position and rewrites only
			// the BODY (and, for let/let*/flet/labels, each binding's value forms).
			LispVal bindingForm = rewriteBindingForm(cons, opName, used);
			if (bindingForm != null) {
				return bindingForm;
			}
			List<LispVal> parts = cons.toList();
			if (parts.size() == 3 && (LispNames.WRITE_STRING.equals(opName) || LispNames.WRITE_CHAR.equals(opName))
					&& streamArgMayBeInstance(parts.get(2))) {
				String helper = LispNames.WRITE_STRING.equals(opName) ? WRITE_STRING_DISPATCH : WRITE_CHAR_DISPATCH;
				return new am.ik.rontolisp.LispCons(dispatchSymbol(helper, used), new am.ik.rontolisp.LispCons(
						rewrite(parts.get(1), used),
						new am.ik.rontolisp.LispCons(rewrite(parts.get(2), used), am.ik.rontolisp.LispNil.INSTANCE)));
			}
			// The read family shares the (stream [eof-error-p [eof-value]]) shape; the
			// dispatch helpers take all three, so an absent argument becomes its
			// default LITERALLY (t/nil evaluate to themselves -- no evaluation-order
			// change). read-line's eof-error-p defaults to nil, the built-in's lite
			// convention.
			if ((LispNames.READ_BYTE.equals(opName) || LispNames.READ_CHAR.equals(opName)
					|| LispNames.READ_LINE.equals(opName)) && parts.size() >= 2 && parts.size() <= 4
					&& streamArgMayBeInstance(parts.get(1))) {
				String helper = LispNames.READ_BYTE.equals(opName) ? READ_BYTE_DISPATCH
						: LispNames.READ_CHAR.equals(opName) ? READ_CHAR_DISPATCH : READ_LINE_DISPATCH;
				LispVal eofDefault = LispNames.READ_LINE.equals(opName) ? am.ik.rontolisp.LispNil.INSTANCE
						: am.ik.rontolisp.LispTrue.INSTANCE;
				return listOf(dispatchSymbol(helper, used), rewrite(parts.get(1), used),
						parts.size() >= 3 ? rewrite(parts.get(2), used) : eofDefault,
						parts.size() >= 4 ? rewrite(parts.get(3), used) : am.ik.rontolisp.LispNil.INSTANCE);
			}
			if (LispNames.WRITE_BYTE.equals(opName) && parts.size() == 3 && streamArgMayBeInstance(parts.get(2))) {
				return listOf(dispatchSymbol(WRITE_BYTE_DISPATCH, used), rewrite(parts.get(1), used),
						rewrite(parts.get(2), used));
			}
			if (LispNames.LISTEN.equals(opName) && parts.size() == 2 && streamArgMayBeInstance(parts.get(1))) {
				return listOf(dispatchSymbol(LISTEN_DISPATCH, used), rewrite(parts.get(1), used));
			}
			if (LispNames.FILE_POSITION.equals(opName) && parts.size() == 2 && streamArgMayBeInstance(parts.get(1))) {
				return listOf(dispatchSymbol(FILE_POSITION_DISPATCH, used), rewrite(parts.get(1), used));
			}
			if (LispNames.FILE_POSITION.equals(opName) && parts.size() == 3 && streamArgMayBeInstance(parts.get(1))) {
				return listOf(dispatchSymbol(FILE_POSITION_SET_DISPATCH, used), rewrite(parts.get(1), used),
						rewrite(parts.get(2), used));
			}
			if ((LispNames.READ_SEQUENCE.equals(opName) || LispNames.WRITE_SEQUENCE.equals(opName)) && parts.size() >= 3
					&& streamArgMayBeInstance(parts.get(2))) {
				// (read-sequence seq stream [:start s] [:end e]) -- like the built-in
				// macro expansion, the keywords must be literal; anything else is left
				// for the expansion to reject. A missing :end stays nil and the
				// dispatch helper normalizes it to (length seq) at run time.
				LispVal start = new am.ik.rontolisp.LispInteger(0);
				LispVal end = am.ik.rontolisp.LispNil.INSTANCE;
				boolean literalKeywords = (parts.size() - 3) % 2 == 0;
				if (literalKeywords) {
					for (int i = 3; i + 1 < parts.size(); i += 2) {
						if (parts.get(i) instanceof am.ik.rontolisp.LispSymbol kw && ":START".equals(kw.name())) {
							start = rewrite(parts.get(i + 1), used);
						}
						else if (parts.get(i) instanceof am.ik.rontolisp.LispSymbol kw && ":END".equals(kw.name())) {
							end = rewrite(parts.get(i + 1), used);
						}
						else {
							literalKeywords = false;
						}
					}
				}
				if (literalKeywords) {
					String helper = LispNames.READ_SEQUENCE.equals(opName) ? READ_SEQUENCE_DISPATCH
							: WRITE_SEQUENCE_DISPATCH;
					return listOf(dispatchSymbol(helper, used), rewrite(parts.get(1), used),
							rewrite(parts.get(2), used), start, end);
				}
			}
			if (LispNames.FORMAT.equals(opName) && parts.size() >= 3 && streamArgMayBeInstance(parts.get(1))) {
				// (format STREAM ctrl args...) with a possibly-CLOS destination: render
				// with (format nil ...) and route the string through the write-string
				// dispatch (whose fallback handles stream handles and the t designator).
				// The destination is bound first, preserving CL's evaluation order.
				//
				// The destination is then tested at RUN time, exactly as the ordinary
				// format lowering does (LispMacroExpander.formatDestinationDispatch):
				// nil is not a stream but the "return the string" destination, so a
				// caller forwarding its own &optional stream must get a string back. A
				// nil destination reaching the dispatch instead printed to standard
				// output -- and this rewrite runs on the whole program whenever ANY of
				// it uses the Gray protocol, so that turned an unrelated
				// (format stream ...) into the wrong answer.
				List<LispVal> fmtNil = new java.util.ArrayList<>();
				fmtNil.add(new am.ik.rontolisp.LispSymbol(LispNames.FORMAT));
				fmtNil.add(am.ik.rontolisp.LispNil.INSTANCE);
				for (int i = 2; i < parts.size(); i++) {
					fmtNil.add(rewrite(parts.get(i), used));
				}
				am.ik.rontolisp.LispSymbol temp = new am.ik.rontolisp.LispSymbol("__gray_fmt_stream");
				am.ik.rontolisp.LispSymbol result = new am.ik.rontolisp.LispSymbol("__gray_fmt_result");
				LispVal dispatch = listOf(dispatchSymbol(WRITE_STRING_DISPATCH, used), result, temp);
				LispVal wrote = listOf(new am.ik.rontolisp.LispSymbol(LispNames.PROGN), dispatch,
						am.ik.rontolisp.LispNil.INSTANCE);
				LispVal tested = listOf(new am.ik.rontolisp.LispSymbol(LispNames.IF), temp, wrote, result);
				LispVal bindResult = listOf(new am.ik.rontolisp.LispSymbol(LispNames.LET),
						listOf(listOf(result, listOf(fmtNil.toArray(LispVal[]::new)))), tested);
				return listOf(new am.ik.rontolisp.LispSymbol(LispNames.LET),
						listOf(listOf(temp, rewrite(parts.get(1), used))), bindResult);
			}
		}
		// Generic: rewrite the operator/elements individually. The tail is walked
		// element-wise (NOT re-checked as a call) so an interior argument sequence
		// starting with an operator-named symbol -- (error 'ty :format-control format
		// :format-arguments args) -- is never misread as a nested call.
		LispVal car = rewrite(cons.car(), used);
		LispVal cdr = rewriteTail(cons.cdr(), used);
		if (car == cons.car() && cdr == cons.cdr()) {
			return form;
		}
		return new am.ik.rontolisp.LispCons(car, cdr);
	}

	/**
	 * Rewrites a binding form without treating its structural position -- the lambda
	 * list, or the binding list's variable names -- as a call. Returns null when
	 * {@code opName} is not a binding form.
	 *
	 * <ul>
	 * <li>{@code lambda} / {@code defun} / {@code defmethod} /
	 * {@code destructuring-bind}: the lambda list is left alone, everything after it is
	 * rewritten.</li>
	 * <li>{@code let} / {@code let*}: each binding's VALUE form is rewritten, the
	 * variable is not.</li>
	 * <li>{@code flet} / {@code labels} / {@code macrolet}: each local function's lambda
	 * list is left alone and its body rewritten.</li>
	 * </ul>
	 *
	 * <p>
	 * Identity-preserving: a binding form with nothing to rewrite -- which is nearly
	 * every {@code defun} of a program that merely touches the protocol somewhere -- is
	 * handed back as it came in, so its {@link am.ik.rontolisp.SourceProvenance} position
	 * (and every position below it) survives the pass. See
	 * {@link am.ik.rontolisp.LispCons#rebuilt}.
	 */
	@org.jspecify.annotations.Nullable
	private static LispVal rewriteBindingForm(am.ik.rontolisp.LispCons cons, String opName,
			java.util.Set<String> used) {
		List<LispVal> parts = cons.toList();
		int lambdaListAt = switch (opName) {
			case LispNames.LAMBDA, LispNames.DESTRUCTURING_BIND -> 1;
			case LispNames.DEFUN, LispNames.DEFMACRO, LispNames.DEFMETHOD -> 2;
			default -> -1;
		};
		if (lambdaListAt >= 0) {
			// A defmethod may carry a qualifier before its lambda list.
			if (LispNames.DEFMETHOD.equals(opName) && lambdaListAt < parts.size()
					&& !(parts.get(lambdaListAt) instanceof am.ik.rontolisp.LispCons)) {
				lambdaListAt++;
			}
			if (lambdaListAt >= parts.size()) {
				return null;
			}
			List<LispVal> out = new java.util.ArrayList<>(parts.subList(0, lambdaListAt + 1));
			for (int i = lambdaListAt + 1; i < parts.size(); i++) {
				out.add(rewrite(parts.get(i), used));
			}
			return am.ik.rontolisp.LispCons.rebuiltList(cons, out);
		}
		boolean valueBindings = LispNames.LET.equals(opName) || LispNames.LET_STAR.equals(opName);
		boolean functionBindings = LispNames.FLET.equals(opName) || LispNames.LABELS.equals(opName)
				|| LispNames.MACROLET.equals(opName);
		if ((!valueBindings && !functionBindings) || parts.size() < 2
				|| !(parts.get(1) instanceof am.ik.rontolisp.LispCons bindings) || !bindings.isProperList()) {
			return null;
		}
		List<LispVal> rewrittenBindings = new java.util.ArrayList<>();
		for (LispVal binding : bindings.toList()) {
			if (!(binding instanceof am.ik.rontolisp.LispCons bindingCons) || !bindingCons.isProperList()) {
				rewrittenBindings.add(binding);
				continue;
			}
			List<LispVal> bindingParts = bindingCons.toList();
			// (var value) for let/let*; (name (params...) body...) for flet/labels.
			int bodyFrom = valueBindings ? 1 : 2;
			List<LispVal> out = new java.util.ArrayList<>(
					bindingParts.subList(0, Math.min(bodyFrom, bindingParts.size())));
			for (int i = bodyFrom; i < bindingParts.size(); i++) {
				out.add(rewrite(bindingParts.get(i), used));
			}
			rewrittenBindings.add(am.ik.rontolisp.LispCons.rebuiltList(bindingCons, out));
		}
		List<LispVal> out = new java.util.ArrayList<>();
		out.add(parts.get(0));
		out.add(am.ik.rontolisp.LispCons.rebuiltList(bindings, rewrittenBindings));
		for (int i = 2; i < parts.size(); i++) {
			out.add(rewrite(parts.get(i), used));
		}
		return am.ik.rontolisp.LispCons.rebuiltList(cons, out);
	}

	private static LispVal rewriteTail(LispVal tail, java.util.Set<String> used) {
		if (!(tail instanceof am.ik.rontolisp.LispCons cons)) {
			return tail;
		}
		LispVal car = rewrite(cons.car(), used);
		LispVal cdr = rewriteTail(cons.cdr(), used);
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

	private static am.ik.rontolisp.LispSymbol dispatchSymbol(String helperName, java.util.Set<String> used) {
		used.add(helperName);
		return new am.ik.rontolisp.LispSymbol(LispNames.RONTOLISP_PKG + "::" + helperName);
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
