package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.ConcatenateForms;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles Lisp expressions to WASM instructions. Serves as the entry point and
 * dispatcher, delegating to specialized compiler classes for each built-in function and
 * special form. All values on the WASM stack are (ref eq); integers use i31ref, nil uses
 * ref.null eq.
 */
final class WasmExprCompiler {

	private WasmExprCompiler() {
	}

	static void compileExpr(LispVal expr, WasmLispCompiler.Ctx ctx) {
		if (ctx.asyncResume != null) {
			// Consume the spine marker set by the async-aware form compilers: it
			// applies to THIS form only (WasmAsyncEmit).
			ctx.asyncSpineCurrent = ctx.asyncSpine;
			ctx.asyncSpine = false;
		}
		switch (expr) {
			case LispInteger i -> WasmEmitHelper.compileIntegerLiteral(i.value(), ctx);
			case am.ik.rontolisp.LispBigInteger bi -> WasmEmitHelper.compileBigIntegerLiteral(bi.value(), ctx);
			case LispNil ignored -> {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			case LispTrue ignored -> WasmEmitHelper.emitTrue(ctx);
			case am.ik.rontolisp.LispRatio r -> {
				// The literal is already normalized; components are i31-range i32.
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(r.numerator().intValue());
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(r.denominator().intValue());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_RATIO);
			}
			case LispDouble d -> {
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(d.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			}
			case LispString s -> WasmEmitHelper.compileStringLiteral(s.print(), ctx);
			case am.ik.rontolisp.LispChar c -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(c.codePoint());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CHAR);
			}
			case LispSymbol sym -> {
				if (sym.isKeyword()) {
					WasmEmitHelper.compileStringLiteral(sym.name(), ctx);
				}
				else {
					compileSymbolRef(sym, ctx);
				}
			}
			case LispCons cons -> compileCons(cons, ctx);
			case am.ik.rontolisp.LispArray array -> WasmQuoteCompiler.compileLiteralArray(array, ctx);
			// An instance is self-evaluating (CLHS 3.1.2.1.3: neither a symbol nor a
			// cons), so a #S(...) literal in code position builds the same TYPE_INSTANCE
			// struct %obj-new does.
			case am.ik.rontolisp.LispInstance inst -> WasmQuoteCompiler.compileLiteralInstance(inst, ctx);
			case am.ik.rontolisp.LispDoubleFloatArray fa -> WasmQuoteCompiler.compilePackedLiteral(fa, ctx);
			case am.ik.rontolisp.LispSingleFloatArray fa -> WasmQuoteCompiler.compileSinglePackedLiteral(fa, ctx);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	static void compileSymbolRef(LispSymbol sym, WasmLispCompiler.Ctx ctx) {
		String name = sym.name();
		// DYNAMIC-FIRST read of a dual-bound special (see WasmLetCompiler): in the
		// binding function the lexical slot exists only so nested lambdas can capture
		// it -- reads go to the module global, so a called function's dynamic
		// rebinding or setq is visible. Inside a closure, the CAPTURE wins: the
		// closure may run after the extent ended and restored the global.
		if (ctx.specialVars.contains(name) && !ctx.captures.containsKey(name) && ctx.locals.containsKey(name)
				&& ctx.globalIndices.containsKey(name)) {
			ctx.writer.write(Instruction.GET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(java.util.Objects.requireNonNull(ctx.globalIndices.get(name)));
			return;
		}
		// Check local variables
		Integer slot = ctx.locals.get(name);
		if (slot != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			if (ctx.boxedVars.contains(name)) {
				// Unbox from cell
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
				ctx.writer.writeSignedLeb128(0);
			}
			return;
		}
		// Check captured variables
		Integer captureIdx = ctx.captures.get(name);
		if (captureIdx != null) {
			WasmEmitHelper.emitLoadCapture(ctx, captureIdx);
			return;
		}
		// A top-level global variable: read from its module-level wasm global. Works from
		// any function body, so a defun/lambda can reference a defvar/defparameter
		// global.
		Integer globalIndex = ctx.globalIndices.get(name);
		if (globalIndex != null) {
			ctx.writer.write(Instruction.GET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(globalIndex);
			return;
		}
		if (ctx.dynamic) {
			WasmDynamicCallCompiler.compileVarRef(name, ctx);
			return;
		}
		if (LispNames.STANDARD_OUTPUT_VAR.equals(name) || LispNames.ERROR_OUTPUT_VAR.equals(name)) {
			// The standard stream variables hold the designator t (the interpreter's
			// permanent value; print-family redirection through them does not exist).
			compileExpr(LispTrue.INSTANCE, ctx);
			return;
		}
		// Lisp-2: a bare symbol is a variable reference only; functions must be
		// referenced via (function name) / #'name.
		throw new UnsupportedOperationException("Cannot compile symbol: " + name);
	}

	private static void compileCons(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal head = cons.car();
		// A dotted tail is only meaningful as data (inside quote); in call position it
		// would otherwise be silently dropped by the toList() walks below.
		if (!(head instanceof LispSymbol qhead && LispNames.QUOTE.equals(qhead.name())) && !cons.isProperList()) {
			throw new UnsupportedOperationException("Improper list in call position: " + cons.print());
		}
		if (ctx.asyncResume != null) {
			// A strict call with an await somewhere in its arguments hoists them into
			// a let* so the await lands at a spine position (WasmAwaitNormalizer).
			LispVal hoisted = WasmAwaitNormalizer.hoistCallArgs(cons, ctx);
			if (hoisted != null) {
				ctx.asyncSpine = ctx.asyncSpineCurrent;
				compileExpr(hoisted, ctx);
				return;
			}
		}
		if (head instanceof LispSymbol sym) {
			// --simd: the vectorizable vec: kernels are routed to the emitted v128
			// runtime
			// helpers instead of the scalar vec.lisp defun of the same name.
			if (ctx.simd && WasmVecSimdCompiler.handles(sym.name())) {
				WasmVecSimdCompiler.compile(sym.name(), cons, ctx);
				return;
			}
			// --simd: the fifteen accelerated linalg: kernels. Unlike vec:, each call is
			// guarded -- a kernel that declines the operands (a general array, a mixed
			// width, a shape error) returns null and the emitted call site runs the
			// scalar linalg.lisp defun over the same locals.
			if (ctx.simd && WasmLinalgSimdCompiler.handles(sym.name())) {
				WasmLinalgSimdCompiler.compile(sym.name(), cons, ctx);
				return;
			}
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			if (qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg())) {
				if (LispNames.VERSION.equals(qn.member())) {
					WasmVersionCompiler.compile(cons, ctx);
					return;
				}
				if (LispNames.LIST_FUNCTIONS.equals(qn.member()) || LispNames.LIST_MACROS.equals(qn.member())
						|| LispNames.LIST_SPECIAL_FORMS.equals(qn.member())) {
					WasmIntrospectionCompiler.compile(qn.member(), cons, ctx);
					return;
				}
				if (LispNames.FETCH.equals(qn.member())) {
					if (ctx.component) {
						// Under --component, fetch is the spliced http.lisp defun (over
						// wit-imported wasi:http) on BOTH serve and non-serve -- a served
						// handler that fetches reaches http.lisp's fetch half too.
						// Run
						// the compile-time checks a defun cannot -- arity and a literal
						// unsupported :method -- then fall through to the ordinary call
						// path,
						// which resolves the defun. Preview 1 keeps the special form (the
						// component-only compile error).
						WasmFetchCompiler.validate(cons);
					}
					else {
						WasmFetchCompiler.reject();
					}
				}
				if (LispNames.HTTP_HANDLER.equals(qn.member())) {
					// In component mode the HttpHandlerInliner rewrites http-handler into
					// a
					// %http-dispatch wasm-export wrapper before compilation, so it never
					// reaches here; reaching here means Preview 1 (no --component).
					throw new UnsupportedOperationException(LispNames.HTTP_HANDLER
							+ " requires --component (it compiles to a wasi:http/incoming-handler component "
							+ "runnable with `wasmtime serve`)");
				}
				if (LispNames.AWAIT.equals(qn.member())) {
					WasmAwaitCompiler.compile(cons, ctx);
					return;
				}
				if (LispNames.FUTUREP.equals(qn.member())) {
					// one ref.test against TYPE_P1_FUTURE (the degenerate Preview-1
					// future), plus TYPE_FUTURE under the asyncMode state machines
					WasmFuturepCompiler.compile(cons, ctx);
					return;
				}
				if (LispNames.ASYNC_RUN.equals(qn.member())) {
					WasmAsyncRunCompiler.compile(cons, ctx);
					return;
				}
				if (LispNames.ASYNC.equals(qn.member())) {
					// normally rewritten by the compile() pre-pass; a stray nested
					// wrapper expands to async-defun/async-lambda and re-dispatches here
					WasmExprCompiler.compileExpr(LispMacroExpander.expandAsync(cons), ctx);
					return;
				}
				if (LispNames.ASYNC_DEFUN.equals(qn.member())) {
					if (ctx.futureTypeIndex >= 0) {
						// asyncMode compiles top-level async-defuns as state machines;
						// there is no nested form (the degenerate %async-run lowering
						// would silently change its semantics).
						throw new UnsupportedOperationException(LispNames.ASYNC_DEFUN_QUALIFIED
								+ " is only supported as a top-level form with --component"
								+ " (use rontolisp:async-lambda for a nested async function)");
					}
					// normally lowered by the compile() pre-pass; a stray nested form
					// lowers here
					WasmExprCompiler.compileExpr(LispMacroExpander.expandAsyncDefun(cons), ctx);
					return;
				}
				if (LispNames.ASYNC_LAMBDA.equals(qn.member())) {
					if (ctx.futureTypeIndex >= 0) {
						WasmAsyncEmit.compileAsyncLambdaValue(cons, ctx);
						return;
					}
					WasmExprCompiler.compileExpr(LispMacroExpander.expandAsyncLambda(cons), ctx);
					return;
				}
				if (LispNames.FUTURE_NEW_INTERNAL.equals(qn.member())
						|| LispNames.FUTURE_SETTLE_INTERNAL.equals(qn.member())
						|| LispNames.FUTURE_REJECT_INTERNAL.equals(qn.member())
						|| LispNames.SUBTASK_FUTURE_INTERNAL.equals(qn.member())
						|| LispNames.WASI_STREAM_NEW_INTERNAL.equals(qn.member())
						|| LispNames.FUTURE_FORCE_INTERNAL.equals(qn.member())) {
					WasmFutureInternalCompiler.compile(qn.member(), cons, ctx);
					return;
				}
				if (LispNames.READ_LINE_RAW_INTERNAL.equals(qn.member())
						|| LispNames.READ_CHAR_RAW_INTERNAL.equals(qn.member())
						|| LispNames.READ_BYTE_RAW_INTERNAL.equals(qn.member())
						|| LispNames.WRITE_LINE_RAW_INTERNAL.equals(qn.member())
						|| LispNames.WRITE_BYTE_RAW_INTERNAL.equals(qn.member())
						|| LispNames.WRITE_STRING_RAW_INTERNAL.equals(qn.member())
						|| LispNames.CLOSE_RAW_INTERNAL.equals(qn.member())) {
					// The NATIVE stream built-ins under their internal alias names: the
					// %io-* socket-dispatch defuns sockets.lisp splices fall back through
					// these, so the compile-time socket rewrite of the public names
					// cannot
					// recurse (component-only; the names exist only post-splice).
					if (!ctx.component) {
						throw new UnsupportedOperationException(
								"rontolisp::" + qn.member() + " is an internal --component binding");
					}
					switch (qn.member()) {
						case LispNames.READ_LINE_RAW_INTERNAL -> WasmReadLineCompiler.compile(cons, ctx);
						case LispNames.READ_CHAR_RAW_INTERNAL -> WasmReadCharCompiler.compile(cons, ctx);
						case LispNames.READ_BYTE_RAW_INTERNAL -> WasmReadByteCompiler.compile(cons, ctx);
						case LispNames.WRITE_LINE_RAW_INTERNAL -> WasmWriteLineCompiler.compile(cons, ctx);
						case LispNames.WRITE_BYTE_RAW_INTERNAL -> WasmWriteByteCompiler.compile(cons, ctx);
						case LispNames.WRITE_STRING_RAW_INTERNAL ->
							WasmWriteStringCompiler.compileWriteString(cons, ctx);
						default -> WasmCloseCompiler.compile(cons, ctx);
					}
					return;
				}
				if (LispNames.RANDOM_BYTE_INTERNAL.equals(qn.member())) {
					// One cryptographically strong byte: the low byte of a WASI
					// random_get draw (real host entropy in Preview 1, wasi:random
					// under --component), boxed as an i31 fixnum.
					WasmRandomCompiler.compileRandomByte(cons, ctx);
					return;
				}
				if (LispNames.WAIT_FOR.equals(qn.member()) && !ctx.component) {
					// Under --component, wait-for is the spliced wait.lisp defun (over
					// the wit-imported wasi:clocks monotonic-clock, a pending future the
					// scheduler settles) -- the symbol falls through to the ordinary
					// call path, which resolves the defun. Preview 1 keeps the special
					// form (the compile error: no host timer).
					throw new UnsupportedOperationException(
							"rontolisp:" + qn.member() + " requires the interpreter, the JVM backend or --component"
									+ " (no host timer is wired on Preview 1 WASM)");
				}
				if (LispNames.ASYNC_STREAMP.equals(qn.member()) || LispNames.STREAM_READ.equals(qn.member())
						|| LispNames.STREAM_CLOSE.equals(qn.member())) {
					// asyncMode --component: streamp/stream-read/stream-close operate on
					// the first-class TYPE_WASI_STREAM values fetch/serve bodies produce.
					if (ctx.wasiStreamTypeIndex >= 0) {
						WasmWasiStreamCompiler.compile(qn.member(), cons, ctx);
						return;
					}
					throw new UnsupportedOperationException("rontolisp:" + qn.member()
							+ " requires the interpreter, the JVM backend or an asynchronous --component program"
							+ " (streams come from rontolisp:fetch / rontolisp:http-handler bodies there)");
				}
				if (LispNames.MAKE_STREAM.equals(qn.member()) || LispNames.STREAM_WRITE.equals(qn.member())) {
					throw new UnsupportedOperationException(
							"rontolisp:" + qn.member() + " requires the interpreter or the JVM backend"
									+ " (guest-created streams are not available on the WASM backends yet; a"
									+ " --component program's streams come from fetch / http-handler bodies)");
				}
				if ((LispNames.TCP_CONNECT.equals(qn.member()) || LispNames.TCP_LISTEN.equals(qn.member())
						|| LispNames.TCP_ACCEPT.equals(qn.member()) || LispNames.TCP_LOCAL_PORT.equals(qn.member())
						|| LispNames.TCP_LOCAL_ADDRESS.equals(qn.member())
						|| LispNames.TCP_PEER_ADDRESS.equals(qn.member())
						|| LispNames.TCP_PEER_PORT.equals(qn.member())) && !ctx.component) {
					// Under --component the tcp built-ins are the spliced sockets.lisp
					// defuns (over the wit-imported wasi:sockets@0.3.0) -- the symbol
					// falls
					// through to the ordinary call path, which resolves the defun (the
					// wait-for pattern). Preview 1 keeps the compile error (no host
					// sockets).
					throw new UnsupportedOperationException(
							"rontolisp:" + qn.member() + " requires the interpreter, the JVM backend or --component"
									+ " (no host socket API is wired on Preview 1 WASM)");
				}
				if (LispNames.WITH_ARENA.equals(qn.member())) {
					// A reclamation boundary for --no-gc; the wasm-GC heap is
					// garbage-collected, so the body runs as a plain progn.
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithArena(cons), ctx);
					return;
				}
				if (LispNames.TLS_CONNECT.equals(qn.member()) || LispNames.TLS_LISTEN.equals(qn.member())
						|| LispNames.TLS_LISTEN_PEM.equals(qn.member())
						|| LispNames.TLS_LISTEN_P12.equals(qn.member())) {
					// TLS is not implemented on the WASM backend: the wasi:tls proposal
					// wasmtime exposes is an experimental client-only 0.3 draft (no
					// server-side interface), so unlike the plain tcp built-ins there is
					// no component fallback -- the tls built-ins are interpreter/JVM
					// only.
					// (%tls-listen-p12 is the internal shape the tls-listen-pem inliner
					// produces on the JVM path; the name is normalized to tls-listen-pem
					// in the message.)
					String name = LispNames.TLS_LISTEN_P12.equals(qn.member()) ? LispNames.TLS_LISTEN_PEM : qn.member();
					throw new UnsupportedOperationException("rontolisp:" + name
							+ " is not supported on the WASM backend (TLS is interpreter/JVM only); use the interpreter or the JVM backend");
				}
				// Other rontolisp: members (user defuns in that package) fall through.
			}
			// The usocket with-* convenience macros are built-in LispMacroExpander
			// expansions (the rontolisp:with-arena pattern) over the usocket.lisp defuns.
			if (qn != null && LispNames.USOCKET_PKG.equals(qn.pkg())) {
				switch (qn.member()) {
					// unwindProtect = ctx.ehMode: a literal usocket
					// with-*/guard flips the module into EH mode via the gate, so these
					// sites ride unwind-protect / the typed handler-case re-signal like
					// the interpreter and the JVM; the flag is only false for
					// internally-generated occurrences in a non-EH module.
					case LispNames.USOCKET_WITH_CLIENT_SOCKET -> {
						compileExpr(LispMacroExpander.expandUsocketWithClientSocket(cons, ctx.ehMode), ctx);
						return;
					}
					case LispNames.USOCKET_WITH_CONNECTED_SOCKET, LispNames.USOCKET_WITH_SERVER_SOCKET -> {
						compileExpr(LispMacroExpander.expandUsocketWithConnectedSocket(cons, ctx.ehMode), ctx);
						return;
					}
					case LispNames.USOCKET_WITH_SOCKET_LISTENER -> {
						compileExpr(LispMacroExpander.expandUsocketWithSocketListener(cons, ctx.ehMode), ctx);
						return;
					}
					case LispNames.USOCKET_GUARD -> {
						compileExpr(LispMacroExpander.expandUsocketGuard(cons, ctx.ehMode), ctx);
						return;
					}
					default -> {
						// Other usocket: members (the usocket.lisp defuns) fall through
						// to
						// the ordinary qualified-call path.
					}
				}
			}
			switch (sym.name()) {
				case LispNames.ADD ->
					WasmArithCompiler.compile(cons, ctx, Instruction.F64_ADD, WasmLispCompiler.FUNC_RAT_ADD);
				case LispNames.SUB ->
					WasmArithCompiler.compile(cons, ctx, Instruction.F64_SUB, WasmLispCompiler.FUNC_RAT_SUB);
				case LispNames.MUL ->
					WasmArithCompiler.compile(cons, ctx, Instruction.F64_MUL, WasmLispCompiler.FUNC_RAT_MUL);
				case LispNames.DIV ->
					WasmArithCompiler.compile(cons, ctx, Instruction.F64_DIV, WasmLispCompiler.FUNC_RAT_DIV);
				case LispNames.MOD -> WasmArithCompiler.compileModRem(cons, ctx, WasmLispCompiler.FUNC_RAT_MOD);
				case LispNames.REM -> WasmArithCompiler.compileModRem(cons, ctx, WasmLispCompiler.FUNC_RAT_REM);
				case LispNames.EQ -> compileComparison(cons, ctx, Instruction.I32_EQ, Instruction.F64_EQ);
				case LispNames.LT -> compileComparison(cons, ctx, Instruction.I32_LT_S, Instruction.F64_LT);
				case LispNames.GT -> compileComparison(cons, ctx, Instruction.I32_GT_S, Instruction.F64_GT);
				case LispNames.LE -> compileComparison(cons, ctx, Instruction.I32_LE_S, Instruction.F64_LE);
				case LispNames.GE -> compileComparison(cons, ctx, Instruction.I32_GE_S, Instruction.F64_GE);
				case LispNames.PRINT -> WasmPrintCompiler.compile(cons, ctx);
				case LispNames.PRIN1 -> WasmPrin1Compiler.compile(cons, ctx);
				case LispNames.PRINC -> WasmPrincCompiler.compile(cons, ctx);
				case LispNames.TERPRI -> WasmTerpriCompiler.compile(cons, ctx);
				case LispNames.FRESH_LINE -> WasmFreshLineCompiler.compile(cons, ctx);
				case LispNames.PRINC_TO_STRING -> WasmPrincToStringCompiler.compile(cons, ctx);
				case LispNames.PRIN1_TO_STRING -> WasmPrin1ToStringCompiler.compile(cons, ctx);
				case LispNames.STRING_CONCAT -> WasmStringConcatCompiler.compile(cons, ctx);
				case LispNames.GENSYM -> WasmGensymCompiler.compile(cons, ctx);
				case LispNames.STRING -> WasmSymbolApiCompiler.compileString(cons, ctx);
				case LispNames.SYMBOL_NAME -> WasmSymbolApiCompiler.compileSymbolName(cons, ctx);
				case LispNames.INTERN -> WasmSymbolApiCompiler.compileIntern(cons, ctx);
				case LispNames.FIND_SYMBOL -> WasmSymbolApiCompiler.compileFindSymbol(cons, ctx);
				case LispNames.MAKE_SYMBOL -> WasmSymbolApiCompiler.compileMakeSymbol(cons, ctx);
				case LispNames.BOUNDP -> WasmSymbolApiCompiler.compileBoundp(cons, ctx);
				case LispNames.FBOUNDP -> WasmSymbolApiCompiler.compileFboundp(cons, ctx);
				case LispNames.SYMBOL_VALUE -> WasmSymbolApiCompiler.compileSymbolValue(cons, ctx);
				case LispNames.CONCATENATE -> WasmExprCompiler.compileExpr(ConcatenateForms.expand(cons), ctx);
				case LispNames.READ_LINE -> WasmReadLineCompiler.compile(cons, ctx);
				case LispNames.READ_CHAR -> WasmReadCharCompiler.compile(cons, ctx);
				case LispNames.OPEN -> WasmOpenCompiler.compile(cons, ctx);
				case LispNames.CLOSE -> WasmCloseCompiler.compile(cons, ctx);
				case LispNames.WRITE_LINE -> WasmWriteLineCompiler.compile(cons, ctx);
				case LispNames.WRITE_STRING -> {
					LispVal bounded = LispMacroExpander.lowerWriteStringBounds(cons);
					if (bounded != null) {
						WasmExprCompiler.compileExpr(bounded, ctx);
					}
					else {
						WasmWriteStringCompiler.compileWriteString(cons, ctx);
					}
				}
				case LispNames.WRITE_TO_STRING -> WasmPrin1ToStringCompiler.compile(cons, ctx);
				case LispNames.MAKE_STRING_OUTPUT_STREAM -> WasmWriteStringCompiler.compileMakeOutputStream(cons, ctx);
				case LispNames.MAKE_STRING_INPUT_STREAM -> WasmWriteStringCompiler.compileMakeInputStream(cons, ctx);
				case LispNames.STRING_STREAM_CONTENTS -> WasmWriteStringCompiler.compileContents(cons, ctx);
				// unwindProtect = ctx.ehMode: a literal with-* flips the
				// module into EH mode via the gate, so these expansions ride
				// unwind-protect like the interpreter/JVM (close on EVERY exit); the flag
				// is only false for internally-generated occurrences (a :report lambda's
				// with-output-to-string) inside a non-EH module, which keep the
				// close-after-body shape so they still compile without the tag section.
				case LispNames.WITH_OUTPUT_TO_STRING ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithOutputToString(cons, ctx.ehMode), ctx);
				case LispNames.WITH_INPUT_FROM_STRING ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithInputFromString(cons, ctx.ehMode), ctx);
				case LispNames.PUSHNEW -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPushnew(cons), ctx);
				case LispNames.DEFTYPE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDeftype(cons), ctx);
				case LispNames.DEFINE_CONDITION ->
					// Like defclass: top-level define-conditions are spliced into their
					// generated defuns before Pass 1; one reaching this compiler is
					// nested.
					throw new UnsupportedOperationException(
							LispNames.DEFINE_CONDITION + " is only supported as a top-level form");
				case LispNames.DEFINE_SETF_EXPANDER ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandDefineSetfExpander(cons), ctx);
				case LispNames.DEFINE_COMPILER_MACRO ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandDefineCompilerMacro(cons), ctx);
				case LispNames.RESTART_CASE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandRestartCase(cons), ctx);
				case LispNames.MAKE_CONDITION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeCondition(cons, ctx.closRegistry), ctx);
				case LispNames.DOCUMENTATION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandDocumentation(cons), ctx);
				case LispNames.WITH_OPEN_STREAM ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithOpenStream(cons, ctx.ehMode), ctx);
				case LispNames.WITH_OPEN_FILE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithOpenFile(cons, ctx.ehMode), ctx);
				case LispNames.READ_BYTE -> WasmReadByteCompiler.compile(cons, ctx);
				case LispNames.WRITE_BYTE -> WasmWriteByteCompiler.compile(cons, ctx);
				case LispNames.FORCE_OUTPUT, LispNames.FINISH_OUTPUT -> {
					// Every WASM write goes out synchronously (fd_write / the component's
					// sock-stream-write park per call), so flushing is the identity: the
					// designator is still evaluated, the value is nil.
					java.util.List<LispVal> foParts = cons.toList();
					if (foParts.size() > 2) {
						throw new UnsupportedOperationException(
								"force-output expects 0 or 1 arguments, got " + (foParts.size() - 1));
					}
					LispVal foExpansion = foParts.size() == 2
							? new LispCons(new LispSymbol(LispNames.PROGN),
									new LispCons(foParts.get(1), new LispCons(LispNil.INSTANCE, LispNil.INSTANCE)))
							: LispNil.INSTANCE;
					WasmExprCompiler.compileExpr(foExpansion, ctx);
				}
				case LispNames.OPEN_STREAM_P ->
					// Without the sockets library spliced (which rewrites this to its
					// table-backed dispatch defun) there is no per-fd open/closed record
					// here: a non-nil stream designator answers t.
					WasmExprCompiler.compileExpr(LispMacroExpander.expandOpenStreamPLite(cons), ctx);
				case LispNames.LISTEN ->
					// Under --component with sockets spliced, listen is rewritten to the
					// %io-listen dispatch defun before compilation (WasmSocketsRewrite);
					// one reaching this compiler has no non-blocking probe behind it.
					throw new UnsupportedOperationException(
							"listen requires the interpreter, the JVM backend or a --component socket stream"
									+ " (no non-blocking input probe exists on this WASM target)");
				case LispNames.READ_SEQUENCE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandReadSequence(cons), ctx);
				case LispNames.WRITE_SEQUENCE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWriteSequence(cons), ctx);
				case LispNames.MAKE_STRING ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeString(cons), ctx);
				case LispNames.REPLACE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandReplace(cons), ctx);
				case LispNames.SCHAR_SET ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandScharSetFunctional(cons), ctx);
				case LispNames.LOWER_CASE_P ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandLowerCaseP(cons), ctx);
				case LispNames.UPPER_CASE_P ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandUpperCaseP(cons), ctx);
				case LispNames.CONSTANTP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandConstantp(cons), ctx);
				case LispNames.STREAMP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandStreamp(cons), ctx);
				case LispNames.SIMPLE_STRING_P ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSimpleStringP(cons), ctx);
				case LispNames.INPUT_STREAM_P, LispNames.OUTPUT_STREAM_P ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandStreamDirectionP(cons), ctx);
				case LispNames.FILE_POSITION, LispNames.FILE_LENGTH, LispNames.PATHNAMEP ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandConstantResult(cons, LispNil.INSTANCE), ctx);
				case LispNames.STREAM_ELEMENT_TYPE -> WasmExprCompiler.compileExpr(
						LispMacroExpander.expandConstantResult(cons, LispMacroExpander.quotedCharacterTypeName()), ctx);
				case LispNames.MAKE_BROADCAST_STREAM ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeBroadcastStream(cons), ctx);
				case LispNames.FDEFINITION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandFdefinition(cons), ctx);
				case LispNames.MASK_FIELD -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMaskField(cons), ctx);
				case LispNames.SCALE_FLOAT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandScaleFloat(cons), ctx);
				case LispNames.CLASS_OF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandClassOf(cons), ctx);
				case LispNames.CLASS_SLOT_DEFS_INTERNAL ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandClassSlotDefs(cons, ctx.closRegistry), ctx);
				case LispNames.SLOT_BOUNDP ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSlotBoundp(cons, ctx.closRegistry), ctx);
				case LispNames.SLOT_MAKUNBOUND ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSlotMakunbound(cons, ctx.closRegistry), ctx);
				case LispNames.SIMPLE_CONDITION_FORMAT_CONTROL -> WasmExprCompiler
					.compileExpr(LispMacroExpander.expandSimpleConditionFormatControl(cons, ctx.closRegistry), ctx);
				case LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS -> WasmExprCompiler
					.compileExpr(LispMacroExpander.expandSimpleConditionFormatArguments(cons, ctx.closRegistry), ctx);
				case LispNames.IEEE754_DOUBLE_BITS, LispNames.IEEE754_DOUBLE_FROM_BITS, LispNames.IEEE754_SINGLE_BITS,
						LispNames.IEEE754_SINGLE_FROM_BITS ->
					// The IEEE 754 bit primitives need the 64-bit unsigned model the WASM
					// numeric model lacks: cold-path runtime signal so a library carrying
					// them (the float-features shim) still compiles.
					WasmExprCompiler.compileExpr(
							LispMacroExpander.expandUnsupportedCall(cons,
									((LispSymbol) cons.car()).name() + " is unsupported on the WASM numeric model"),
							ctx);
				case LispNames.READ_EVAL, LispNames.READ_EVAL_TEMPLATE ->
					// Identity: a #. marker split into code position by a backquote
					// template
					// arrives here with its (already evaluated) argument.
					WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
				case LispNames.STRING_UPCASE -> WasmStringUpcaseCompiler.compileUpcase(cons, ctx);
				case LispNames.STRING_DOWNCASE -> WasmStringUpcaseCompiler.compileDowncase(cons, ctx);
				case LispNames.STRING_CAPITALIZE -> WasmStringCapitalizeCompiler.compile(cons, ctx);
				case LispNames.SUBSEQ, LispNames.SUBSEQ_CORE -> WasmSubseqCompiler.compile(cons, ctx);
				case LispNames.CHAR, LispNames.SCHAR -> WasmCharCompiler.compileChar(cons, ctx);
				case LispNames.CHAR_CODE -> WasmCharCompiler.compileCharCode(cons, ctx);
				case LispNames.CODE_CHAR -> WasmCharCompiler.compileCodeChar(cons, ctx);
				case LispNames.CHARACTERP -> WasmCharCompiler.compileCharacterp(cons, ctx);
				case LispNames.CHAR_UPCASE -> WasmCharCompiler.compileUpcase(cons, ctx);
				case LispNames.CHAR_DOWNCASE -> WasmCharCompiler.compileDowncase(cons, ctx);
				case LispNames.ALPHA_CHAR_P -> WasmCharCompiler.compileAlphaCharP(cons, ctx);
				case LispNames.DIGIT_CHAR_P -> WasmCharCompiler.compileDigitCharP(cons, ctx);
				case LispNames.CHAR_EQ -> WasmCharCompiler.compileEq(cons, ctx);
				case LispNames.CHAR_LT -> WasmCharCompiler.compileLt(cons, ctx);
				case LispNames.CHAR_LE -> WasmCharCompiler.compileLe(cons, ctx);
				case LispNames.CHAR_GT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandCharDescending(cons, LispNames.CHAR_LT), ctx);
				case LispNames.CHAR_GE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandCharDescending(cons, LispNames.CHAR_LE), ctx);
				case LispNames.CHAR_NE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCharNe(cons), ctx);
				case LispNames.CHAR_EQUAL -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCharEqual(cons), ctx);
				case LispNames.PARSE_INTEGER ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandParseInteger(cons), ctx);
				case LispNames.VALUES_LIST ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandValuesList(cons), ctx);
				case LispNames.COPY_READTABLE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandCopyReadtable(cons), ctx);
				case LispNames.SET_DISPATCH_MACRO_CHARACTER ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSetDispatchMacroCharacter(cons), ctx);
				case LispNames.COMPLEX -> WasmExprCompiler.compileExpr(LispMacroExpander.expandComplexLite(cons), ctx);
				case LispNames.NE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNumericNotEqual(cons), ctx);
				case LispNames.READ_FROM_STRING -> WasmReadFromStringCompiler.compile(cons, ctx);
				// A string=/string-equal call with the bounding-index keywords is lowered
				// onto subseq first, so the intrinsic below always sees two strings.
				case LispNames.STRING_EQ -> {
					if (LispMacroExpander.hasStringComparisonBounds(cons)) {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandStringComparisonBounds(cons), ctx);
					}
					else {
						WasmStringEqCompiler.compileEq(cons, ctx);
					}
				}
				case LispNames.STRING_EQUAL -> {
					if (LispMacroExpander.hasStringComparisonBounds(cons)) {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandStringComparisonBounds(cons), ctx);
					}
					else {
						WasmStringEqCompiler.compileEqual(cons, ctx);
					}
				}
				case LispNames.STRING_TRIM -> WasmStringTrimCompiler.compileTrim(cons, ctx);
				case LispNames.STRING_LEFT_TRIM -> WasmStringTrimCompiler.compileLeft(cons, ctx);
				case LispNames.STRING_RIGHT_TRIM -> WasmStringTrimCompiler.compileRight(cons, ctx);
				case LispNames.READ -> WasmReadCompiler.compile(cons, ctx);
				case LispNames.LOAD -> WasmLoadCompiler.compile(cons, ctx);
				// A literal top-level require/provide (and the asdf directives) was
				// consumed by the compile-time LoadInliner pass; anything left is nested
				// or non-literal, which the compiled runtime reader cannot execute
				// (unlike a runtime load).
				case LispNames.REQUIRE, LispNames.PROVIDE, LispNames.ASDF_LOAD_SYSTEM, LispNames.ASDF_DEFSYSTEM,
						LispNames.QL_QUICKLOAD ->
					throw new UnsupportedOperationException(
							sym.name() + " is only supported as a literal top-level form on the compile path");
				case LispNames.EVAL -> WasmEvalCompiler.compile(cons, ctx);
				case LispNames.QUOTE -> WasmQuoteCompiler.compile(cons, ctx);
				case LispNames.IF -> WasmIfCompiler.compile(cons, ctx);
				case LispNames.WHILE -> WasmWhileCompiler.compile(cons, ctx);
				case LispNames.LET -> WasmLetCompiler.compile(cons, ctx);
				case LispNames.PROGV ->
					// progv binds a runtime-computed list of symbols; the compiler cannot
					// name the wasm globals to save/restore. Interpreter only for now.
					throw new UnsupportedOperationException(
							LispNames.PROGV + " is not supported on the WASM backend (interpreter only)");
				case LispNames.UNWIND_PROTECT -> WasmUnwindProtectCompiler.compile(cons, ctx);
				case LispNames.HANDLER_CASE -> WasmHandlerCaseCompiler.compile(cons, ctx);
				case LispNames.IGNORE_ERRORS ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandIgnoreErrors(cons), ctx);
				case LispNames.PROGN -> WasmPrognCompiler.compile(cons, ctx);
				case LispNames.TAGBODY -> WasmTagbodyCompiler.compile(cons, ctx);
				case LispNames.GO -> WasmTagbodyCompiler.compileGo(cons, ctx);
				case LispNames.PRINT_UNREADABLE_OBJECT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandPrintUnreadableObject(cons), ctx);
				case LispNames.DO_EXTERNAL_SYMBOLS ->
					// Real on the interpreter (registry-backed); inside #. the macro-time
					// evaluator resolves it before compilation. A runtime occurrence has
					// no
					// package registry behind it here.
					throw new UnsupportedOperationException(LispNames.DO_EXTERNAL_SYMBOLS
							+ " requires the interpreter (no runtime package registry in compiled mode)");
				case LispNames.WITH_PACKAGE_ITERATOR ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithPackageIterator(cons), ctx);
				case LispNames.PROG -> WasmExprCompiler.compileExpr(LispMacroExpander.expandProg(cons, false), ctx);
				case LispNames.PROG_STAR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandProg(cons, true), ctx);
				case LispNames.SETQ -> WasmSetqCompiler.compile(cons, ctx);
				case LispNames.LAMBDA -> WasmLambdaCompiler.compileValue(cons, ctx);
				case LispNames.DEFUN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDefun(cons), ctx);
				case LispNames.DEFSTRUCT ->
					// Top-level defstructs are spliced into defuns before Pass 1; one
					// reaching this compiler is nested inside another form.
					throw new UnsupportedOperationException(
							LispNames.DEFSTRUCT + " is only supported as a top-level form");
				case LispNames.DEFCLASS, LispNames.DEFGENERIC, LispNames.DEFMETHOD ->
					// Like defstruct: the CLOS forms are spliced before Pass 1.
					throw new UnsupportedOperationException(sym.name() + " is only supported as a top-level form");
				case LispNames.MAKE_INSTANCE -> WasmExprCompiler
					.compileExpr(LispMacroExpander.expandMakeInstance(cons, ctx.closRegistry, true), ctx);
				case LispNames.SLOT_VALUE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSlotValue(cons, ctx.closRegistry), ctx);
				case LispNames.WITH_SLOTS -> WasmExprCompiler.compileExpr(LispMacroExpander.expandWithSlots(cons), ctx);
				case LispNames.DEFVAR -> WasmDefvarCompiler.compile(cons, ctx, false);
				case LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> WasmDefvarCompiler.compile(cons, ctx, true);
				case LispNames.LIST -> WasmListCompiler.compile(cons, ctx);
				case LispNames.CAR -> WasmCarCompiler.compile(cons, ctx);
				case LispNames.CDR -> WasmCdrCompiler.compile(cons, ctx);
				case LispNames.CONS -> WasmConsCompiler.compile(cons, ctx);
				case LispNames.NTHCDR -> WasmNthcdrCompiler.compile(cons, ctx);
				case LispNames.RPLACA -> WasmRplacaCompiler.compile(cons, ctx);
				case LispNames.RPLACD -> WasmRplacdCompiler.compile(cons, ctx);
				case LispNames.SETF -> WasmExprCompiler
					.compileExpr(LispMacroExpander.expandSetf(cons, ctx.structAccessors, ctx.closRegistry), ctx);
				case LispNames.PUSH -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPush(cons), ctx);
				case LispNames.POP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPop(cons), ctx);
				case LispNames.REMF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandRemf(cons), ctx);
				case LispNames.LET_STAR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandLetStar(cons), ctx);
				case LispNames.DOLIST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDolist(cons), ctx);
				case LispNames.DO -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDo(cons), ctx);
				case LispNames.DO_STAR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDoStar(cons), ctx);
				case LispNames.LOOP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandLoop(cons), ctx);
				case LispNames.BLOCK_INTERNAL -> WasmBlockCompiler.compile(cons, ctx);
				case LispNames.BLOCK -> WasmBlockCompiler.compileNamed(cons, ctx);
				case LispNames.FN_BLOCK_INTERNAL -> WasmBlockCompiler.compileFnBlock(cons, ctx);
				case LispNames.NLX_TAG_INTERNAL -> WasmNlxCompiler.compileTag(ctx);
				case LispNames.NLX_CATCH_INTERNAL -> WasmNlxCompiler.compileCatch(cons, ctx);
				case LispNames.NLX_THROW_INTERNAL -> WasmNlxCompiler.compileThrow(cons, ctx);
				case LispNames.RETURN_FROM -> WasmReturnFromCompiler.compile(cons, ctx);
				case LispNames.RETURN -> WasmReturnCompiler.compile(cons, ctx);
				case LispNames.INCF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandIncf(cons), ctx);
				case LispNames.DECF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDecf(cons), ctx);
				case LispNames.FORMAT -> WasmExprCompiler.compileExpr(LispMacroExpander.expandFormat(cons), ctx);
				case LispNames.LENGTH -> WasmLengthCompiler.compile(cons, ctx);
				case LispNames.REVERSE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandReverse(cons), ctx);
				case LispNames.MEMBER -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMember(cons), ctx);
				case LispNames.FIND -> WasmExprCompiler.compileExpr(LispMacroExpander.expandFind(cons), ctx);
				case LispNames.FIND_IF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandFindIf(cons), ctx);
				case LispNames.FIND_IF_NOT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandFindIfNot(cons), ctx);
				case LispNames.MEMBER_IF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMemberIf(cons), ctx);
				case LispNames.POSITION -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPosition(cons), ctx);
				case LispNames.POSITION_IF ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandPositionIf(cons), ctx);
				case LispNames.POSITION_IF_NOT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandPositionIfNot(cons), ctx);
				case LispNames.COMPLEMENT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandComplement(cons), ctx);
				case LispNames.COUNT -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCount(cons), ctx);
				case LispNames.COUNT_IF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCountIf(cons), ctx);
				case LispNames.ASSOC -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAssoc(cons), ctx);
				case LispNames.ASSOC_IF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAssocIf(cons), ctx);
				case LispNames.GETF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandGetf(cons), ctx);
				case LispNames.EVERY -> WasmExprCompiler.compileExpr(LispMacroExpander.expandEvery(cons), ctx);
				case LispNames.SOME -> WasmExprCompiler.compileExpr(LispMacroExpander.expandSome(cons), ctx);
				case LispNames.REMOVE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandRemove(cons), ctx);
				case LispNames.REMOVE_IF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandRemoveIf(cons), ctx);
				case LispNames.REMOVE_IF_NOT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandRemoveIfNot(cons), ctx);
				case LispNames.DELETE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDelete(cons), ctx);
				case LispNames.DELETE_IF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDeleteIf(cons), ctx);
				case LispNames.DELETE_IF_NOT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandDeleteIfNot(cons), ctx);
				case LispNames.SUBSTITUTE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSubstitute(cons), ctx);
				case LispNames.NSUBSTITUTE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandNsubstitute(cons), ctx);
				case LispNames.REMOVE_DUPLICATES ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandRemoveDuplicates(cons), ctx);
				case LispNames.NCONC -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNconc(cons), ctx);
				case LispNames.LAST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandLast(cons), ctx);
				case LispNames.BUTLAST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandButlast(cons), ctx);
				case LispNames.IDENTITY -> WasmExprCompiler.compileExpr(LispMacroExpander.expandIdentity(cons), ctx);
				case LispNames.COPY_LIST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCopyList(cons), ctx);
				case LispNames.NREVERSE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNreverse(cons), ctx);
				case LispNames.MAKE_LIST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeList(cons), ctx);
				case LispNames.UNION -> WasmExprCompiler.compileExpr(LispMacroExpander.expandUnion(cons), ctx);
				case LispNames.INTERSECTION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandIntersection(cons), ctx);
				case LispNames.SET_DIFFERENCE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSetDifference(cons), ctx);
				case LispNames.ADJOIN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAdjoin(cons), ctx);
				case LispNames.EQ_GENERAL -> WasmEqGeneralCompiler.compile(cons, ctx);
				case LispNames.EQL -> WasmEqGeneralCompiler.compileEql(cons, ctx);
				case LispNames.EQUAL -> WasmEqualCompiler.compile(cons, ctx);
				case LispNames.REMF_TAIL -> WasmRemfTailCompiler.compile(cons, ctx);
				case LispNames.MAKE_HASH_TABLE -> WasmHashTableCompiler.compileMake(cons, ctx);
				case LispNames.GETHASH -> WasmHashTableCompiler.compileGet(cons, ctx);
				case LispNames.PUTHASH -> WasmHashTableCompiler.compilePut(cons, ctx);
				case LispNames.REMHASH -> WasmHashTableCompiler.compileRem(cons, ctx);
				case LispNames.CLRHASH -> WasmHashTableCompiler.compileClr(cons, ctx);
				case LispNames.HASH_TABLE_COUNT -> WasmHashTableCompiler.compileCount(cons, ctx);
				case LispNames.HASH_TABLE_TEST ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandHashTableTest(cons), ctx);
				case LispNames.HASH_TABLE_SIZE -> WasmHashTableCompiler.compileCount(cons, ctx);
				case LispNames.HASH_TABLE_REHASH_SIZE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandHashTableGrowthConstant(cons, 1.5), ctx);
				case LispNames.HASH_TABLE_REHASH_THRESHOLD ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandHashTableGrowthConstant(cons, 1.0), ctx);
				case LispNames.HASH_TABLE_P -> WasmHashTableCompiler.compileP(cons, ctx);
				case LispNames.MAPHASH -> WasmHashTableCompiler.compileMaphash(cons, ctx);
				case LispNames.MAKE_ARRAY -> WasmArrayCompiler.compileMake(cons, ctx);
				case LispNames.AREF -> WasmArrayCompiler.compileAref(cons, ctx);
				case LispNames.ASET -> WasmArrayCompiler.compileAset(cons, ctx);
				case LispNames.ARRAY_DIMENSIONS -> WasmArrayCompiler.compileDims(cons, ctx);
				case LispNames.ROW_MAJOR_AREF -> WasmArrayCompiler.compileRowMajorAref(cons, ctx);
				case LispNames.ROW_MAJOR_ASET -> WasmArrayCompiler.compileRowMajorAset(cons, ctx);
				case LispNames.ARRAY_ROW_MAJOR_INDEX ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandArrayRowMajorIndex(cons), ctx);
				case LispNames.VECTOR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandVector(cons), ctx);
				case LispNames.SVREF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandSvref(cons), ctx);
				case LispNames.ARRAY_RANK -> WasmExprCompiler.compileExpr(LispMacroExpander.expandArrayRank(cons), ctx);
				case LispNames.ARRAY_DIMENSION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandArrayDimension(cons), ctx);
				case LispNames.ARRAY_TOTAL_SIZE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandArrayTotalSize(cons), ctx);
				case LispNames.FILL_POINTER -> WasmArrayCompiler.compileFillPointer(cons, ctx);
				case LispNames.SET_FILL_POINTER -> WasmArrayCompiler.compileSetFillPointer(cons, ctx);
				case LispNames.ARRAY_HAS_FILL_POINTER_P -> WasmArrayCompiler.compileHasFillPointer(cons, ctx);
				case LispNames.ADJUSTABLE_ARRAY_P -> WasmArrayCompiler.compileAdjustableArrayP(cons, ctx);
				case LispNames.ARRAY_ELEMENT_TYPE -> WasmArrayCompiler.compileElementType(cons, ctx);
				case LispNames.VECTOR_PUSH -> WasmArrayCompiler.compileVectorPush(cons, ctx);
				case LispNames.VECTOR_POP -> WasmArrayCompiler.compileVectorPop(cons, ctx);
				case LispNames.VECTOR_PUSH_EXTEND -> WasmArrayCompiler.compileVectorPushExtend(cons, ctx);
				case LispNames.ADJUST_ARRAY ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandAdjustArray(cons), ctx);
				case LispNames.ARRAY_BECOME -> WasmArrayCompiler.compileArrayBecome(cons, ctx);
				case LispNames.ARRAY_DISPLACEMENT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandArrayDisplacement(cons), ctx);
				case LispNames.ARRAY_DISP_TARGET -> WasmArrayCompiler.compileDispTarget(cons, ctx);
				case LispNames.ARRAY_DISP_OFFSET -> WasmArrayCompiler.compileDispOffset(cons, ctx);
				case LispNames.COERCE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCoerce(cons), ctx);
				case LispNames.MAP_INTO -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMapInto(cons), ctx);
				case LispNames.APPEND -> WasmAppendCompiler.compile(cons, ctx);
				case LispNames.FUNCALL -> WasmFunctionCallCompiler.compileFuncall(cons, ctx);
				case LispNames.FUNCTION -> WasmFunctionFormCompiler.compile(cons, ctx);
				case LispNames.SYMBOL_FUNCTION -> WasmFunctionFormCompiler.compileSymbolFunction(cons, ctx);
				case LispNames.MAP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMap(cons), ctx);
				case LispNames.MAPCAR -> WasmMapcarCompiler.compile(cons, ctx);
				case LispNames.MAPC -> WasmMapcCompiler.compile(cons, ctx);
				case LispNames.MAPCAN -> WasmMapcanCompiler.compile(cons, ctx);
				case LispNames.REDUCE -> {
					// :from-end/:key lower to a plain reduce first; then a string
					// sequence
					// folds over a list of its characters (the wrapper is null when the
					// call
					// is already the inner list fold).
					LispVal loweredReduce = LispMacroExpander.expandReduce(cons);
					if (loweredReduce != null) {
						WasmExprCompiler.compileExpr(loweredReduce, ctx);
					}
					else {
						LispVal wrappedReduce = LispMacroExpander.wrapReduceForStringSeq(cons);
						if (wrappedReduce != null) {
							WasmExprCompiler.compileExpr(wrappedReduce, ctx);
						}
						else {
							WasmReduceCompiler.compile(cons, ctx);
						}
					}
				}
				case LispNames.SORT -> {
					// (sort seq pred :key ...) routes through stable-sort; otherwise a
					// string sequence sorts as a list of its characters and is coerced
					// back
					// to a string; null when the call is already the inner sort.
					LispVal keyedSort = LispMacroExpander.expandSortWithKey(cons);
					if (keyedSort != null) {
						WasmExprCompiler.compileExpr(keyedSort, ctx);
					}
					else {
						LispVal wrappedSort = LispMacroExpander.wrapSortForStringSeq(cons);
						if (wrappedSort != null) {
							WasmExprCompiler.compileExpr(wrappedSort, ctx);
						}
						else {
							WasmSortCompiler.compile(cons, ctx);
						}
					}
				}
				case LispNames.STABLE_SORT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandStableSort(cons), ctx);
				case LispNames.COPY_SEQ -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCopySeq(cons), ctx);
				case LispNames.VECTORP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandVectorp(cons), ctx);
				case LispNames.ARRAYP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandArrayp(cons), ctx);
				case LispNames.APPLY -> WasmApplyCompiler.compile(cons, ctx);
				case LispNames.NULL -> WasmNullPredCompiler.compile(cons, ctx);
				case LispNames.ATOM -> WasmAtomCompiler.compile(cons, ctx);
				case LispNames.NUMBERP -> WasmNumberpCompiler.compile(cons, ctx);
				case LispNames.INTEGERP -> WasmIntegerpCompiler.compile(cons, ctx);
				case LispNames.FLOATP -> WasmFloatpCompiler.compile(cons, ctx);
				case LispNames.RATIONALP -> WasmRationalpCompiler.compile(cons, ctx);
				case LispNames.NUMERATOR -> WasmRatioAccessorCompiler.compile(cons, ctx, WasmLispCompiler.FUNC_RAT_NUM);
				case LispNames.DENOMINATOR ->
					WasmRatioAccessorCompiler.compile(cons, ctx, WasmLispCompiler.FUNC_RAT_DEN);
				case LispNames.SYMBOLP -> WasmSymbolpCompiler.compile(cons, ctx);
				case LispNames.STRINGP -> WasmStringpCompiler.compile(cons, ctx);
				case LispNames.LISTP -> WasmListpCompiler.compile(cons, ctx);
				case LispNames.CONSP -> WasmConspCompiler.compile(cons, ctx);
				case LispNames.OBJ_NEW -> WasmInstanceCompiler.compileNew(cons, ctx);
				case LispNames.OBJ_REF -> WasmInstanceCompiler.compileRef(cons, ctx);
				case LispNames.OBJ_SET -> WasmInstanceCompiler.compileSet(cons, ctx);
				case LispNames.OBJ_IS -> WasmInstanceCompiler.compileIs(cons, ctx);
				case LispNames.OBJ_TAG -> WasmInstanceCompiler.compileTag(cons, ctx);
				case LispNames.OBJ_P -> WasmInstanceCompiler.compileP(cons, ctx);
				case LispNames.OBJ_SLOTS -> WasmInstanceCompiler.compileSlots(cons, ctx);
				case LispNames.FUNCTIONP -> WasmFunctionpCompiler.compile(cons, ctx);
				case LispNames.ARRAYP_INTERNAL -> WasmArraypCompiler.compile(cons, ctx);
				case LispNames.KEYWORDP -> WasmKeywordpCompiler.compile(cons, ctx);
				case LispNames.FLOAT -> WasmFloatConvCompiler.compile(LispMacroExpander.normalizeFloatCall(cons), ctx);
				case LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING, LispNames.ROUND -> {
					// (floor a b) -> (floor (/ a b)); the one-argument form compiles
					// natively.
					LispVal withDivisor = LispMacroExpander.expandFloorFamilyDivisor(cons);
					if (withDivisor != null) {
						WasmExprCompiler.compileExpr(withDivisor, ctx);
					}
					else {
						switch (sym.name()) {
							case LispNames.TRUNCATE -> WasmIntConvCompiler.compileTruncate(cons, ctx);
							case LispNames.FLOOR -> WasmIntConvCompiler.compileFloor(cons, ctx);
							case LispNames.CEILING -> WasmIntConvCompiler.compileCeiling(cons, ctx);
							default -> WasmIntConvCompiler.compileRound(cons, ctx);
						}
					}
				}
				case LispNames.COND -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCond(cons), ctx);
				case LispNames.CASE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCase(cons), ctx);
				case LispNames.ECASE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandEcase(cons), ctx);
				case LispNames.CCASE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCcase(cons), ctx);
				case LispNames.ERROR ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandError(cons, ctx.closRegistry), ctx);
				case LispNames.CERROR ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandCerror(cons, ctx.closRegistry), ctx);
				case LispNames.ERROR_INTERNAL -> WasmErrorCompiler.compile(cons, ctx);
				case LispNames.ERROR_COND_INTERNAL ->
					// Outside EH mode the condition-carrying variant traps like %error (a
					// WASM trap is uncatchable and carries no payload; the arguments are
					// not evaluated); in EH mode it throws the typed instance.
					WasmErrorCompiler.compileCond(cons, ctx);
				case LispNames.WARN ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWarn(cons, ctx.closRegistry), ctx);
				case LispNames.WARN_INTERNAL -> WasmWarnCompiler.compile(cons, ctx);
				case LispNames.SIGNAL ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSignalMacro(cons, ctx.closRegistry), ctx);
				case LispNames.SIGNAL_COND_INTERNAL -> WasmSignalCondCompiler.compile(cons, ctx);
				case LispNames.HC_DEPTH_DEC_INTERNAL -> WasmHandlerCaseCompiler.compileDepthDec(ctx);
				case LispNames.AND -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAnd(cons), ctx);
				case LispNames.OR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandOr(cons), ctx);
				case LispNames.WHEN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandWhen(cons), ctx);
				case LispNames.DOTIMES -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDotimes(cons), ctx);
				case LispNames.PROG1 -> WasmExprCompiler.compileExpr(LispMacroExpander.expandProg1(cons), ctx);
				case LispNames.TIME -> WasmExprCompiler.compileExpr(LispMacroExpander.expandTime(cons), ctx);
				case LispNames.UNLESS -> WasmExprCompiler.compileExpr(LispMacroExpander.expandUnless(cons), ctx);
				case LispNames.ONE_PLUS -> WasmExprCompiler.compileExpr(LispMacroExpander.expandOnePlus(cons), ctx);
				case LispNames.ONE_MINUS -> WasmExprCompiler.compileExpr(LispMacroExpander.expandOneMinus(cons), ctx);
				case LispNames.ZEROP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandZerop(cons), ctx);
				case LispNames.PLUSP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPlusp(cons), ctx);
				case LispNames.MINUSP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMinusp(cons), ctx);
				case LispNames.EVENP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandEvenp(cons), ctx);
				case LispNames.ODDP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandOddp(cons), ctx);
				case LispNames.ABS -> WasmAbsCompiler.compile(cons, ctx);
				case LispNames.MIN -> {
					if (isBinaryCall(cons)) {
						WasmMinCompiler.compile(cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx);
					}
				}
				case LispNames.MAX -> {
					if (isBinaryCall(cons)) {
						WasmMaxCompiler.compile(cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx);
					}
				}
				case LispNames.RANDOM -> WasmRandomCompiler.compile(cons, ctx);
				case LispNames.GET_UNIVERSAL_TIME, LispNames.GET_INTERNAL_REAL_TIME, LispNames.GET_INTERNAL_RUN_TIME ->
					WasmTimeCompiler.compile(cons, ctx, sym.name());
				case LispNames.GETENV -> WasmGetenvCompiler.compile(cons, ctx);
				case LispNames.SQRT -> WasmSqrtCompiler.compile(cons, ctx);
				case LispNames.EXP -> WasmExpCompiler.compile(cons, ctx);
				case LispNames.LOG -> WasmLogCompiler.compile(cons, ctx);
				case LispNames.TANH -> WasmTanhCompiler.compile(cons, ctx);
				case LispNames.SIN, LispNames.COS, LispNames.TAN -> WasmSinCosCompiler.compile(cons, ctx, sym.name());
				case LispNames.ASIN, LispNames.ACOS, LispNames.ATAN -> WasmAtanCompiler.compile(cons, ctx, sym.name());
				case LispNames.SINH, LispNames.COSH -> WasmSinhCoshCompiler.compile(cons, ctx, sym.name());
				case LispNames.ISQRT -> WasmIsqrtCompiler.compile(cons, ctx);
				case LispNames.SIGNUM -> WasmSignumCompiler.compile(cons, ctx);
				case LispNames.LOGAND -> {
					if (isBinaryCall(cons)) {
						WasmBitwiseCompiler.compileLogand(cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx);
					}
				}
				case LispNames.LOGIOR -> {
					if (isBinaryCall(cons)) {
						WasmBitwiseCompiler.compileLogior(cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx);
					}
				}
				case LispNames.LOGXOR -> {
					if (isBinaryCall(cons)) {
						WasmBitwiseCompiler.compileLogxor(cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx);
					}
				}
				case LispNames.LOGNOT -> WasmBitwiseCompiler.compileLognot(cons, ctx);
				case LispNames.ASH -> WasmBitwiseCompiler.compileAsh(cons, ctx);
				case LispNames.INTEGER_LENGTH -> WasmBitwiseCompiler.compileIntegerLength(cons, ctx);
				case LispNames.LOGBITP -> WasmBitwiseCompiler.compileLogbitp(cons, ctx);
				case LispNames.LIST_STAR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandListStar(cons), ctx);
				case LispNames.ACONS -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAcons(cons), ctx);
				case LispNames.ENDP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandEndp(cons), ctx);
				case LispNames.ELT -> WasmExprCompiler.compileExpr(LispMacroExpander.expandElt(cons), ctx);
				case LispNames.RASSOC -> WasmExprCompiler.compileExpr(LispMacroExpander.expandRassoc(cons), ctx);
				case LispNames.PAIRLIS -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPairlis(cons), ctx);
				case LispNames.COPY_ALIST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCopyAlist(cons), ctx);
				case LispNames.REVAPPEND -> WasmExprCompiler.compileExpr(LispMacroExpander.expandRevappend(cons), ctx);
				case LispNames.NRECONC -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNreconc(cons), ctx);
				case LispNames.MAPLIST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMaplist(cons), ctx);
				case LispNames.MAPCON -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMapcon(cons), ctx);
				case LispNames.MAPL -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMapl(cons), ctx);
				case LispNames.NOTANY -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNotany(cons), ctx);
				case LispNames.NOTEVERY -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNotevery(cons), ctx);
				case LispNames.PROG2 -> WasmExprCompiler.compileExpr(LispMacroExpander.expandProg2(cons), ctx);
				case LispNames.PSETQ -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPsetq(cons), ctx);
				case LispNames.PSETF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPsetf(cons), ctx);
				case LispNames.TYPECASE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandTypecase(cons, ctx.closRegistry), ctx);
				case LispNames.ETYPECASE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandEtypecase(cons, ctx.closRegistry), ctx);
				case LispNames.TYPEP ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandTypep(cons, ctx.closRegistry), ctx);
				case LispNames.SUBTYPEP ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSubtypep(cons, ctx.closRegistry), ctx);
				case LispNames.CHECK_TYPE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCheckType(cons), ctx);
				case LispNames.ASSERT -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAssert(cons), ctx);
				case LispNames.DECLARE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDeclare(cons), ctx);
				case LispNames.DECLAIM -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDeclaim(cons), ctx);
				case LispNames.PROCLAIM -> WasmExprCompiler.compileExpr(LispMacroExpander.expandProclaim(cons), ctx);
				case LispNames.THE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandThe(cons), ctx);
				case LispNames.EVAL_WHEN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandEvalWhen(cons), ctx);
				case LispNames.LOCALLY -> WasmExprCompiler.compileExpr(LispMacroExpander.expandLocally(cons), ctx);
				case LispNames.WITH_STANDARD_IO_SYNTAX ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithStandardIoSyntax(cons), ctx);
				case LispNames.WRITE_CHAR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandWriteChar(cons), ctx);
				case LispNames.FLET -> WasmExprCompiler.compileExpr(LispMacroExpander.expandFlet(cons), ctx);
				case LispNames.LABELS -> WasmExprCompiler.compileExpr(LispMacroExpander.expandLabels(cons), ctx);
				case LispNames.VALUES -> WasmExprCompiler.compileExpr(LispMacroExpander.expandValues(cons), ctx);
				case LispNames.MULTIPLE_VALUE_BIND ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMultipleValueBind(cons), ctx);
				case LispNames.MULTIPLE_VALUE_LIST ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMultipleValueList(cons), ctx);
				case LispNames.MULTIPLE_VALUE_CALL ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMultipleValueCall(cons), ctx);
				case LispNames.NTH_VALUE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNthValue(cons), ctx);
				case LispNames.MULTIPLE_VALUE_SETQ ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMultipleValueSetq(cons), ctx);
				case LispNames.MULTIPLE_VALUE_PROG1 ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMultipleValueProg1(cons), ctx);
				case LispNames.ROTATEF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandRotatef(cons), ctx);
				case LispNames.SHIFTF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandShiftf(cons), ctx);
				case LispNames.LOAD_TIME_VALUE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandLoadTimeValue(cons), ctx);
				case LispNames.BYTE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandByte(cons), ctx);
				case LispNames.BYTE_SIZE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandByteSize(cons), ctx);
				case LispNames.BYTE_POSITION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandBytePosition(cons), ctx);
				case LispNames.LDB -> WasmExprCompiler.compileExpr(LispMacroExpander.expandLdb(cons), ctx);
				case LispNames.DPB -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDpb(cons), ctx);
				case LispNames.LOGANDC1, LispNames.LOGANDC2, LispNames.LOGORC1, LispNames.LOGORC2 ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandLogComplement(cons), ctx);
				case LispNames.MAKE_SEQUENCE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeSequence(cons), ctx);
				case LispNames.DESTRUCTURING_BIND ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandDestructuringBind(cons), ctx);
				case LispNames.GCD -> {
					if (isBinaryCall(cons)) {
						WasmGcdCompiler.compile(cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx);
					}
				}
				case LispNames.LCM -> {
					if (isBinaryCall(cons)) {
						WasmLcmCompiler.compile(cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx);
					}
				}
				case LispNames.EXPT -> WasmExptCompiler.compile(cons, ctx);
				case LispNames.FIRST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandFirst(cons), ctx);
				case LispNames.REST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandRest(cons), ctx);
				case LispNames.NTH -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNth(cons), ctx);
				case LispNames.SECOND -> WasmExprCompiler.compileExpr(LispMacroExpander.expandSecond(cons), ctx);
				case LispNames.THIRD -> WasmExprCompiler.compileExpr(LispMacroExpander.expandThird(cons), ctx);
				case LispNames.FOURTH -> WasmExprCompiler.compileExpr(LispMacroExpander.expandFourth(cons), ctx);
				case LispNames.NOT -> WasmNullPredCompiler.compile(cons, ctx);
				default -> {
					if (LispMacroExpander.isCarCdrComposition(sym.name())) {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandCarCdrComposition(cons), ctx);
					}
					else {
						WasmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx);
					}
				}
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& LispNames.LAMBDA.equals(headSym.name())) {
			WasmLambdaCompiler.compileCall(headCons, cons, ctx);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + cons.print());
		}
	}

	/**
	 * Compiles a numeric comparison. The binary form uses the dedicated comparison
	 * compiler; any other arity is desugared into nested binary comparisons.
	 */
	private static void compileComparison(LispCons cons, WasmLispCompiler.Ctx ctx, int i32Opcode, int f64Opcode) {
		if (isBinaryCall(cons)) {
			WasmComparisonCompiler.compile(cons, ctx, i32Opcode, f64Opcode);
		}
		else {
			WasmExprCompiler.compileExpr(LispMacroExpander.expandComparison(cons), ctx);
		}
	}

	/**
	 * Returns whether the call has exactly two arguments (operator plus two operands).
	 */
	private static boolean isBinaryCall(LispCons cons) {
		return cons.toList().size() == 3;
	}

}
