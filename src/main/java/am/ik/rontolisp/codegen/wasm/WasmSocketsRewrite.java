package am.ik.rontolisp.codegen.wasm;

import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * The {@code --component} socket-I/O rewrite: when sockets.lisp is spliced (the program
 * defines {@code rontolisp::%io-read-line}), the stream built-ins that can meet a socket
 * handle are redirected onto the library's dispatch defuns, because a socket read/write
 * no longer has an {@code fd_read}/{@code fd_write} adapter behind it -- the socket IS a
 * Lisp-side entry over wit-imported {@code wasi:sockets} streams.
 *
 * <ul>
 * <li><strong>Synchronous context</strong> (plain defun/lambda bodies):
 * {@code (read-line s)} -&gt; {@code (rontolisp::%io-read-line s)} (and read-char /
 * read-byte / write-line / write-byte / close alike). The dispatch defun forces the
 * library's async read internals through {@code rontolisp::%future-force} (the blocking
 * {@code _sched_loop} drive) for a socket handle and falls back to the native built-in
 * (the {@code %...-raw} alias names) otherwise.</li>
 * <li><strong>Asynchronous context</strong> (async-defun/async-lambda bodies and the top
 * level -- the {@code LispAsync} placement rule): a read is PROMOTED instead --
 * {@code (read-line s)} -&gt; {@code (rontolisp:await (rontolisp::%read-line-future s))}
 * -- so a pending socket read suspends the task and other tasks keep running.
 * {@code rontolisp:tcp-connect}/{@code tcp-accept} promote the same way onto their
 * {@code %tcp-connect-f}/{@code %tcp-accept-f} async internals (which carry the
 * nil-on-failure convention, so both surfaces behave identically). The inserted
 * {@code rontolisp:await} is counted by {@code WasmAwaitAnalysis} like any user await,
 * which is why this runs BEFORE compilation, right after the async-sugar rewrite.</li>
 * </ul>
 *
 * <p>
 * Writes and {@code close} are never promoted (the write built-ins keep the blocking
 * park; drops are synchronous). {@code #'read-line} keeps the native wrapper -- a
 * first-class read applied to a socket is out of contract, like {@code eval}.
 */
final class WasmSocketsRewrite {

	// Sync-context substitutions: native name -> the sockets.lisp dispatch defun.
	private static final Map<String, String> SYNC_DISPATCH = Map.of(LispNames.READ_LINE, "%IO-READ-LINE",
			LispNames.READ_CHAR, "%IO-READ-CHAR", LispNames.READ_BYTE, "%IO-READ-BYTE", LispNames.WRITE_LINE,
			"%IO-WRITE-LINE", LispNames.WRITE_BYTE, "%IO-WRITE-BYTE", LispNames.WRITE_STRING, "%IO-WRITE-STRING",
			LispNames.CLOSE, "%IO-CLOSE", LispNames.LISTEN, "%IO-LISTEN", LispNames.OPEN_STREAM_P, "%IO-OPEN-STREAM-P");

	// Async-context read promotions: native name -> the future-returning async internal.
	private static final Map<String, String> ASYNC_FUTURES = Map.of(LispNames.READ_LINE, "%READ-LINE-FUTURE",
			LispNames.READ_CHAR, "%READ-CHAR-FUTURE", LispNames.READ_BYTE, "%READ-BYTE-FUTURE");

	// Async-context tcp promotions: canonical qualified name -> the async internal.
	private static final Map<String, String> TCP_FUTURES = Map.of(
			PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_CONNECT), "%TCP-CONNECT-F",
			PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_ACCEPT), "%TCP-ACCEPT-F");

	// Accepted argument-count ranges per target: a call outside its range is left
	// UNREWRITTEN so it resolves against the native built-in / public defun and errors
	// under its public name ("tcp-connect expects 2 arguments"), not an internal one.
	private static final Map<String, int[]> ARITIES = Map.ofEntries(Map.entry(LispNames.READ_LINE, new int[] { 0, 1 }),
			Map.entry(LispNames.READ_CHAR, new int[] { 0, 1 }), Map.entry(LispNames.READ_BYTE, new int[] { 0, 1 }),
			Map.entry(LispNames.WRITE_LINE, new int[] { 1, 2 }), Map.entry(LispNames.WRITE_BYTE, new int[] { 2, 2 }),
			Map.entry(LispNames.WRITE_STRING, new int[] { 1, 2 }), Map.entry(LispNames.CLOSE, new int[] { 1, 1 }),
			Map.entry(LispNames.LISTEN, new int[] { 0, 1 }), Map.entry(LispNames.OPEN_STREAM_P, new int[] { 1, 1 }),
			Map.entry(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_CONNECT), new int[] { 2, 2 }),
			Map.entry(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_ACCEPT), new int[] { 1, 1 }));

	// The sequence ops and the eof-tolerant read-byte, dispatched by arity (they need
	// per-shape targets, so they stay out of the 1:1 maps above): cl-postgres' socket
	// layer reads/writes byte arrays with (read-sequence result socket) /
	// (write-sequence bytes socket) and probes with (read-byte socket nil 0), none of
	// which the plain maps cover -- unrewritten they compile to the NATIVE stream
	// built-ins, whose fd_read/fd_write on a socket fd (>= 200) walks off the preview1
	// adapter's 64-slot fd table. A non-socket designator still reaches the native
	// expansion through the %...-raw aliases inside the dispatch defuns.
	private static final String IO_READ_BYTE_EOF = "%IO-READ-BYTE-EOF";

	private static final String READ_BYTE_EOF_FUTURE = "%READ-BYTE-EOF-FUTURE";

	private static final String IO_READ_SEQUENCE = "%IO-READ-SEQUENCE";

	private static final String READ_SEQUENCE_FUTURE = "%READ-SEQUENCE-FUTURE";

	private static final String IO_WRITE_SEQUENCE = "%IO-WRITE-SEQUENCE";

	private static final String IO_MARKER = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, "%IO-READ-LINE");

	private WasmSocketsRewrite() {
	}

	/**
	 * The dispatch defuns this rewrite substitutes for the stream built-ins, by member
	 * name. They are ORDINARY defuns -- every argument is a value position -- so an await
	 * among their arguments hoists like any other strict call's, even though the
	 * {@code %} prefix marks the rest of the internal forms as structurally special
	 * ({@link WasmAwaitNormalizer#isStrictCallHead}). Without this, an async
	 * {@code (write-line (read-line s))} -- the write redirected here, the read promoted
	 * to an await -- would leave the await in an unhoisted argument and be rejected.
	 * @return the {@code %io-*} member names
	 */
	static Set<String> strictDispatchMembers() {
		Set<String> members = new java.util.HashSet<>(SYNC_DISPATCH.values());
		members.add(IO_READ_BYTE_EOF);
		members.add(IO_READ_SEQUENCE);
		members.add(IO_WRITE_SEQUENCE);
		return Set.copyOf(members);
	}

	/**
	 * The {@code ShadowedBuiltins} composition seam: when this rewrite has fired on the
	 * program, every {@code close}/{@code listen}/... call site already spells the
	 * {@code rontolisp::%io-*} dispatch defun, so a user method on such a built-in name
	 * must intercept THOSE heads -- and its dispatcher's fall-through must call the
	 * {@code %io-*} defun (which keeps the socket table honest and falls back to the
	 * {@code %...-raw} native aliases itself), never the raw built-in. Returns the
	 * canonical qualified dispatch name -&gt; native built-in name map when the rewrite
	 * fired, an empty map otherwise.
	 * @param program the top-level forms, after {@link #rewrite}
	 * @return qualified {@code %io-*} name -&gt; native name, or empty
	 */
	static Map<String, String> builtinDispatchAliases(List<LispVal> program) {
		if (!spliced(program)) {
			return Map.of();
		}
		Map<String, String> aliases = new java.util.HashMap<>();
		for (Map.Entry<String, String> entry : SYNC_DISPATCH.entrySet()) {
			aliases.put(PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, entry.getValue()), entry.getKey());
		}
		return Map.copyOf(aliases);
	}

	/**
	 * Rewrites the program when sockets.lisp is spliced; returns it untouched otherwise.
	 * @param program the resolved, async-sugar-rewritten top-level forms
	 * @return the program with socket I/O redirected
	 */
	static List<LispVal> rewrite(List<LispVal> program) {
		if (!spliced(program)) {
			return program;
		}
		List<LispVal> out = new java.util.ArrayList<>(program.size());
		for (LispVal form : program) {
			// The top level is an asynchronous context (LispAsync.checkTopLevel).
			out.add(rewriteForm(form, true));
		}
		return out;
	}

	// The splice marker: a top-level (defun rontolisp::%io-read-line ...).
	private static boolean spliced(List<LispVal> program) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& (LispNames.DEFUN.equals(head.name()) || LispNames.ASYNC_DEFUN_QUALIFIED.equals(head.name()))
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& IO_MARKER.equals(name.name())) {
				return true;
			}
		}
		return false;
	}

	// Mirrors LispAsync.check's context rules: async-defun/async-lambda bodies (and the
	// %async-run thunk) are async, plain defun/lambda/defmethod and flet/labels
	// definitions reset to sync, quote/defmacro/macrolet are left alone.
	private static LispVal rewriteForm(LispVal form, boolean asyncContext) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return form;
		}
		List<LispVal> parts = cons.toList();
		if (cons.car() instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.QUOTE, LispNames.DEFMACRO, LispNames.MACROLET -> {
					return form;
				}
				case LispNames.ASYNC_DEFUN_QUALIFIED -> {
					return rebuildFrom(cons, parts, 3, true);
				}
				case LispNames.ASYNC_LAMBDA_QUALIFIED -> {
					return rebuildFrom(cons, parts, 2, true);
				}
				case LispNames.ASYNC_RUN_QUALIFIED -> {
					// the thunk handed to the async primitive IS the asynchronous body
					List<LispVal> out = new java.util.ArrayList<>(parts.size());
					out.add(parts.get(0));
					for (int i = 1; i < parts.size(); i++) {
						LispVal arg = parts.get(i);
						if (arg instanceof LispCons argCons && argCons.isProperList()
								&& argCons.car() instanceof LispSymbol head && LispNames.LAMBDA.equals(head.name())) {
							out.add(rebuildFrom(argCons, argCons.toList(), 2, true));
						}
						else {
							out.add(rewriteForm(arg, true));
						}
					}
					return LispCons.rebuiltList(cons, out);
				}
				case LispNames.DEFUN, LispNames.DEFMETHOD -> {
					return rebuildFrom(cons, parts, 3, false);
				}
				case LispNames.LAMBDA -> {
					return rebuildFrom(cons, parts, 2, false);
				}
				case LispNames.FLET, LispNames.LABELS -> {
					List<LispVal> out = new java.util.ArrayList<>(parts.size());
					out.add(parts.get(0));
					if (parts.size() > 1 && parts.get(1) instanceof LispCons defs && defs.isProperList()) {
						List<LispVal> newDefs = new java.util.ArrayList<>();
						for (LispVal def : defs.toList()) {
							newDefs.add(def instanceof LispCons defCons && defCons.isProperList()
									? rebuildFrom(defCons, defCons.toList(), 2, false) : def);
						}
						out.add(LispCons.rebuiltList(defs, newDefs));
					}
					else if (parts.size() > 1) {
						out.add(parts.get(1));
					}
					for (int i = 2; i < parts.size(); i++) {
						out.add(rewriteForm(parts.get(i), asyncContext));
					}
					return LispCons.rebuiltList(cons, out);
				}
				default -> {
					// fall through to the generic call rewrite
				}
			}
			LispVal substituted = substitute(sym.name(), parts, asyncContext);
			if (substituted != null) {
				return substituted;
			}
		}
		List<LispVal> out = new java.util.ArrayList<>(parts.size());
		for (LispVal part : parts) {
			out.add(rewriteForm(part, asyncContext));
		}
		return LispCons.rebuiltList(cons, out);
	}

	// Rebuild parts keeping [0, from) verbatim (head, name, lambda list) and rewriting
	// the rest in the given context.
	private static LispVal rebuildFrom(LispCons original, List<LispVal> parts, int from, boolean asyncContext) {
		List<LispVal> out = new java.util.ArrayList<>(parts.size());
		for (int i = 0; i < parts.size(); i++) {
			out.add(i < from ? parts.get(i) : rewriteForm(parts.get(i), asyncContext));
		}
		return LispCons.rebuiltList(original, out);
	}

	// The call substitution, or null when the head is not a target.
	private static @org.jspecify.annotations.Nullable LispVal substitute(String head, List<LispVal> parts,
			boolean asyncContext) {
		// (close stream :abort expr) normalizes to (close stream) BEFORE the arity
		// check, so an aborting close on a socket still reaches %io-close.
		if (LispNames.CLOSE.equals(head) && parts.size() == 4 && parts.get(2) instanceof LispSymbol kw
				&& ":ABORT".equals(kw.name())) {
			parts = List.of(parts.get(0), parts.get(1));
		}
		// The eof-tolerant read-byte (2-3 args) and the 2-arg sequence ops take their
		// own dispatch targets; reads promote in async context like the 1-arg reads,
		// writes never do.
		if (LispNames.READ_BYTE.equals(head) && (parts.size() == 3 || parts.size() == 4)) {
			return asyncContext ? awaitOf(replaceHead(parts, READ_BYTE_EOF_FUTURE, true))
					: replaceHead(parts, IO_READ_BYTE_EOF, false);
		}
		if (LispNames.READ_SEQUENCE.equals(head) && parts.size() == 3) {
			return asyncContext ? awaitOf(replaceHead(parts, READ_SEQUENCE_FUTURE, true))
					: replaceHead(parts, IO_READ_SEQUENCE, false);
		}
		if (LispNames.WRITE_SEQUENCE.equals(head) && parts.size() == 3) {
			return replaceHead(parts, IO_WRITE_SEQUENCE, asyncContext);
		}
		int[] arity = ARITIES.get(head);
		if (arity != null && (parts.size() - 1 < arity[0] || parts.size() - 1 > arity[1])) {
			return null;
		}
		if (asyncContext) {
			String future = ASYNC_FUTURES.get(head);
			if (future == null) {
				future = TCP_FUTURES.get(head);
			}
			if (future != null) {
				return awaitOf(replaceHead(parts, future, asyncContext));
			}
		}
		String tcpFuture = TCP_FUTURES.get(head);
		if (tcpFuture != null) {
			// Sync context: the public tcp defun already IS the forcing wrapper; leave
			// the call alone (it resolves to the spliced defun).
			return null;
		}
		String dispatch = SYNC_DISPATCH.get(head);
		if (dispatch != null) {
			// A stream-argument-less write keeps following the current
			// *standard-output* through the dispatch defun: the redirect
			// (.kb/standard-output-redirect.md) resolves at the ORIGINAL call site,
			// one read instead of the one %write-string-raw's own designator
			// resolution would do inside the dispatch defun. A non-redirecting program
			// compiles the bare symbol to the constant t (= stdout), so this is always
			// safe. An EXPLICIT nil needs nothing here: it flows through the dispatch
			// defun's non-socket arm into %write-string-raw / %write-line-raw, whose
			// compilers apply the same designator rule (StreamDesignators).
			if ((LispNames.WRITE_STRING.equals(head) || LispNames.WRITE_LINE.equals(head)) && parts.size() == 2) {
				List<LispVal> withDefault = new java.util.ArrayList<>(parts);
				withDefault.add(new LispSymbol(LispNames.STANDARD_OUTPUT_VAR));
				parts = withDefault;
			}
			return replaceHead(parts, dispatch, asyncContext);
		}
		return null;
	}

	private static LispVal replaceHead(List<LispVal> parts, String internalName, boolean asyncContext) {
		List<LispVal> out = new java.util.ArrayList<>(parts.size());
		out.add(new LispSymbol(PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, internalName)));
		for (int i = 1; i < parts.size(); i++) {
			out.add(rewriteForm(parts.get(i), asyncContext));
		}
		return properList(out);
	}

	private static LispVal awaitOf(LispVal call) {
		return properList(
				List.of(new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.AWAIT)), call));
	}

	private static LispVal properList(List<LispVal> parts) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = parts.size() - 1; i >= 0; i--) {
			tail = new LispCons(parts.get(i), tail);
		}
		return tail;
	}

}
