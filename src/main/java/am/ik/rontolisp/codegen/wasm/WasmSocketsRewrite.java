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
	private static final Map<String, String> SYNC_DISPATCH = Map.of(LispNames.READ_LINE, "%io-read-line",
			LispNames.READ_CHAR, "%io-read-char", LispNames.READ_BYTE, "%io-read-byte", LispNames.WRITE_LINE,
			"%io-write-line", LispNames.WRITE_BYTE, "%io-write-byte", LispNames.WRITE_STRING, "%io-write-string",
			LispNames.CLOSE, "%io-close");

	// Async-context read promotions: native name -> the future-returning async internal.
	private static final Map<String, String> ASYNC_FUTURES = Map.of(LispNames.READ_LINE, "%read-line-future",
			LispNames.READ_CHAR, "%read-char-future", LispNames.READ_BYTE, "%read-byte-future");

	// Async-context tcp promotions: canonical qualified name -> the async internal.
	private static final Map<String, String> TCP_FUTURES = Map.of(
			PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_CONNECT), "%tcp-connect-f",
			PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_ACCEPT), "%tcp-accept-f");

	// Accepted argument-count ranges per target: a call outside its range is left
	// UNREWRITTEN so it resolves against the native built-in / public defun and errors
	// under its public name ("tcp-connect expects 2 arguments"), not an internal one.
	private static final Map<String, int[]> ARITIES = Map.of(LispNames.READ_LINE, new int[] { 0, 1 },
			LispNames.READ_CHAR, new int[] { 0, 1 }, LispNames.READ_BYTE, new int[] { 0, 1 }, LispNames.WRITE_LINE,
			new int[] { 1, 2 }, LispNames.WRITE_BYTE, new int[] { 2, 2 }, LispNames.WRITE_STRING, new int[] { 1, 2 },
			LispNames.CLOSE, new int[] { 1, 1 },
			PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_CONNECT), new int[] { 2, 2 },
			PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_ACCEPT), new int[] { 1, 1 });

	private static final String IO_MARKER = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, "%io-read-line");

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
		return Set.copyOf(SYNC_DISPATCH.values());
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
					return rebuildFrom(parts, 3, true);
				}
				case LispNames.ASYNC_LAMBDA_QUALIFIED -> {
					return rebuildFrom(parts, 2, true);
				}
				case LispNames.ASYNC_RUN_QUALIFIED -> {
					// the thunk handed to the async primitive IS the asynchronous body
					List<LispVal> out = new java.util.ArrayList<>(parts.size());
					out.add(parts.get(0));
					for (int i = 1; i < parts.size(); i++) {
						LispVal arg = parts.get(i);
						if (arg instanceof LispCons argCons && argCons.isProperList()
								&& argCons.car() instanceof LispSymbol head && LispNames.LAMBDA.equals(head.name())) {
							out.add(rebuildFrom(argCons.toList(), 2, true));
						}
						else {
							out.add(rewriteForm(arg, true));
						}
					}
					return properList(out);
				}
				case LispNames.DEFUN, LispNames.DEFMETHOD -> {
					return rebuildFrom(parts, 3, false);
				}
				case LispNames.LAMBDA -> {
					return rebuildFrom(parts, 2, false);
				}
				case LispNames.FLET, LispNames.LABELS -> {
					List<LispVal> out = new java.util.ArrayList<>(parts.size());
					out.add(parts.get(0));
					if (parts.size() > 1 && parts.get(1) instanceof LispCons defs && defs.isProperList()) {
						List<LispVal> newDefs = new java.util.ArrayList<>();
						for (LispVal def : defs.toList()) {
							newDefs.add(def instanceof LispCons defCons && defCons.isProperList()
									? rebuildFrom(defCons.toList(), 2, false) : def);
						}
						out.add(properList(newDefs));
					}
					else if (parts.size() > 1) {
						out.add(parts.get(1));
					}
					for (int i = 2; i < parts.size(); i++) {
						out.add(rewriteForm(parts.get(i), asyncContext));
					}
					return properList(out);
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
		return properList(out);
	}

	// Rebuild parts keeping [0, from) verbatim (head, name, lambda list) and rewriting
	// the rest in the given context.
	private static LispVal rebuildFrom(List<LispVal> parts, int from, boolean asyncContext) {
		List<LispVal> out = new java.util.ArrayList<>(parts.size());
		for (int i = 0; i < parts.size(); i++) {
			out.add(i < from ? parts.get(i) : rewriteForm(parts.get(i), asyncContext));
		}
		return properList(out);
	}

	// The call substitution, or null when the head is not a target.
	private static @org.jspecify.annotations.Nullable LispVal substitute(String head, List<LispVal> parts,
			boolean asyncContext) {
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
