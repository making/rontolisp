package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.UiopExports;
import am.ik.rontolisp.compiler.ClRedefinitionWarnings;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.ConcatenateForms;
import am.ik.rontolisp.compiler.StreamDesignators;

import am.ik.jvm.Opcode;

/**
 * Compiles Lisp expressions to JVM bytecode. Serves as the entry point and dispatcher,
 * delegating to specialized compiler classes for each built-in function and special form.
 */
final class JvmExprCompiler {

	private JvmExprCompiler() {
	}

	/**
	 * Compiles a printing operator, routed through {@code print-object} when the program
	 * defines a method on it and through {@code %print-cased} when it mentions
	 * {@code *print-case*}; compiled as it always was otherwise -- the gate that keeps
	 * every program using neither byte-identical.
	 */
	private static void compilePrintOperator(LispCons cons, JvmLispCompiler.Ctx ctx, String className, Runnable plain) {
		LispVal hooked = LispMacroExpander.expandPrintObjectHook(cons, ctx.closRegistry, ctx.printCase);
		if (hooked == null) {
			plain.run();
			return;
		}
		compileExpr(hooked, ctx, className);
	}

	static void compileExpr(LispVal expr, JvmLispCompiler.Ctx ctx, String className) {
		switch (expr) {
			case LispInteger i -> JvmEmitHelper.compileLong(i.value(), ctx);
			case LispBigInteger b -> JvmEmitHelper.compileBigInteger(b.value(), ctx);
			case LispRatio r -> JvmEmitHelper.compileRatio(r, ctx);
			case LispDouble d -> JvmEmitHelper.compileDouble(d.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> JvmEmitHelper.compileTrue(ctx);
			case LispString s -> JvmEmitHelper.compileStringLiteral(s.literal(), ctx);
			case LispChar c -> JvmEmitHelper.compileCharLiteral(c.codePoint(), ctx);
			case LispSymbol sym -> {
				if (sym.isKeyword()) {
					JvmEmitHelper.compileStringLiteral(sym.name(), ctx);
				}
				else {
					compileSymbolRef(sym, ctx);
				}
			}
			case LispCons cons -> compileCons(cons, ctx, className);
			case am.ik.rontolisp.LispArray array -> JvmQuoteCompiler.compileLiteralArray(array, ctx, className);
			// An instance is self-evaluating (CLHS 3.1.2.1.3: neither a symbol nor a
			// cons), so a #S(...) literal in code position builds the same
			// Object[]{layout, slots...} %obj-new does.
			case am.ik.rontolisp.LispInstance inst -> JvmQuoteCompiler.compileLiteralInstance(inst, ctx, className);
			// A packed #d(...) double-float literal compiles to a native double[] with a
			// dimension header (the packed representation), disjoint from the general
			// array.
			case am.ik.rontolisp.LispDoubleFloatArray fa -> JvmQuoteCompiler.compilePackedLiteral(fa, ctx);
			// A packed #f(...) single-float literal compiles to a native float[] with a
			// dimension header (the single-float packed representation), disjoint from
			// the
			// general array and from the double[] packed representation.
			case am.ik.rontolisp.LispSingleFloatArray fa -> JvmQuoteCompiler.compileSinglePackedLiteral(fa, ctx);
			// A packed integer-vector literal compiles to its boxed general-array
			// equivalent for now (todo 194 stage 2 follow-up on this backend).
			case am.ik.rontolisp.LispIntVector iv -> JvmQuoteCompiler.compileLiteralIntVector(iv, ctx, className);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	static void compileSymbolRef(LispSymbol sym, JvmLispCompiler.Ctx ctx) {
		String name = sym.name();
		// An unboxed dual-representation local (.kb/jvm-int-fusion.md): never special,
		// never captured, never in ctx.locals -- resolved first.
		JvmIntFusionCompiler.RawLocal rawLocal = ctx.rawLocals.get(name);
		if (rawLocal != null) {
			JvmIntFusionCompiler.emitRawLocalBoxedRead(rawLocal, ctx);
			return;
		}
		// DYNAMIC-FIRST read of a dual-bound special (see JvmLetCompiler): in the
		// binding method the lexical slot exists only so nested lambdas can capture
		// it -- reads go to the dynamic store, so a called function's dynamic
		// rebinding or setq is visible (cl-ppcre's starts-with accumulation). Inside a
		// closure, the CAPTURE wins: the closure may run after the extent ended and
		// restored the previous binding (cl-ppcre's end-string).
		if (ctx.specialVars.contains(name) && !ctx.captures.containsKey(name) && ctx.locals.containsKey(name)
				&& ctx.globals.contains(name)) {
			compileSpecialRead(name, ctx);
			return;
		}
		Integer slot = ctx.locals.get(name);
		if (slot != null) {
			if (ctx.boxedVars.contains(name)) {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slot);
				ctx.emit(Opcode.CHECKCAST);
				ctx.emitU2(ctx.objectArrayClass.index());
				ctx.emit(Opcode.ICONST_0);
				ctx.emit(Opcode.AALOAD);
			}
			else {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slot);
			}
		}
		else if (ctx.captures.containsKey(name)) {
			int captureIdx = ctx.captures.get(name);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(ctx.closureEnvSlot);
			JvmEmitHelper.emitIntConst(ctx, 1 + captureIdx);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
		}
		else if (ctx.globals.contains(name)) {
			// A top-level global variable: read from its dedicated static field. Works
			// from any method body (main, defun, lambda), so a function can reference a
			// defvar/defparameter global. A dynamically-bound special reads its
			// thread's binding first (compileSpecialRead).
			compileSpecialRead(name, ctx);
		}
		else if (ctx.dynamic) {
			JvmDynamicCallCompiler.compileVarRef(name, ctx);
		}
		else if (LispNames.STANDARD_OUTPUT_VAR.equals(name) || LispNames.STANDARD_INPUT_VAR.equals(name)) {
			// The standard stream variables hold the designator t (the interpreter's
			// permanent value; the program never binds this one, so print/read-family
			// redirection through it does not exist here).
			JvmEmitHelper.compileTrue(ctx);
		}
		else if (LispNames.ERROR_OUTPUT_VAR.equals(name)) {
			// *error-output* is the process standard ERROR, which t does not name: it is
			// the reserved handle 2, the same designator the interpreter holds (the
			// program never binds this one, so warn's redirect does not exist here).
			JvmEmitHelper.compileLong(StreamDesignators.STANDARD_ERROR_HANDLE, ctx);
		}
		else {
			// Lisp-2: a bare symbol is a variable reference only; functions must be
			// referenced via (function name) / #'name.
			throw new UnsupportedOperationException("Cannot compile symbol reference: " + name);
		}
	}

	/**
	 * Reads a global variable. A special that is dynamically bound somewhere in the
	 * program reads DYNAMIC-FIRST through {@code _dget} (this thread's binding when one
	 * is active, else the {@code _g$} global default); every other global -- including a
	 * special that is never {@code let}-bound -- stays a single {@code getstatic}.
	 */
	static void compileSpecialRead(String name, JvmLispCompiler.Ctx ctx) {
		JvmDynVarRuntimeBuilder.DynVarRuntime dyn = ctx.dynVars;
		if (dyn != null) {
			am.ik.jvm.ConstantPool.FieldrefConstant tlField = dyn.fields().get(name);
			if (tlField != null) {
				ctx.emit(Opcode.GETSTATIC);
				ctx.emitU2(tlField.index());
				ctx.emit(Opcode.GETSTATIC);
				ctx.emitU2(java.util.Objects.requireNonNull(ctx.globalFields.get(name)).index());
				ctx.emit(Opcode.INVOKESTATIC);
				ctx.emitU2(dyn.dget().index());
				return;
			}
		}
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(java.util.Objects.requireNonNull(ctx.globalFields.get(name)).index());
	}

	private static void compileCons(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		try {
			compileConsLocated(cons, ctx, className);
		}
		catch (RuntimeException ex) {
			// The innermost cons that came from source names the position; the exception
			// itself is rethrown untouched, since passes above catch it by type.
			throw SourceProvenance.noteFailure(cons, ex);
		}
	}

	private static void compileConsLocated(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal head = cons.car();
		// A dotted tail is only meaningful as data (inside quote); in call position it
		// would otherwise be silently dropped by the toList() walks below.
		if (!(head instanceof LispSymbol qhead && LispNames.QUOTE.equals(qhead.name())) && !cons.isProperList()) {
			throw new UnsupportedOperationException("Improper list in call position: " + cons.print());
		}
		if (head instanceof LispSymbol sym) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			if (qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg())) {
				if (LispNames.VERSION.equals(qn.member())) {
					JvmVersionCompiler.compile(cons, ctx, className);
					return;
				}
				if (LispNames.FETCH.equals(qn.member())) {
					JvmFetchCompiler.compile(cons, ctx, className);
					return;
				}
				if (LispNames.AWAIT.equals(qn.member())) {
					JvmAwaitCompiler.compile(cons, ctx, className);
					return;
				}
				if (LispNames.FUTURE_FORCE_INTERNAL.equals(qn.member())) {
					// The function spelling of the resolve: same _await emission, legal
					// outside async bodies (the http-reactor transport's boundary).
					JvmAwaitCompiler.compile("rontolisp::" + LispNames.FUTURE_FORCE_INTERNAL, cons, ctx, className);
					return;
				}
				if (JvmAsyncOpsCompiler.handles(qn.member())) {
					JvmAsyncOpsCompiler.compile(qn.member(), cons, ctx, className);
					return;
				}
				if (LispNames.ASYNC.equals(qn.member())) {
					// normally rewritten by the compile() pre-pass; a stray nested
					// wrapper expands to async-defun/async-lambda and re-dispatches here
					JvmExprCompiler.compileExpr(LispMacroExpander.expandAsync(cons), ctx, className);
					return;
				}
				if (LispNames.ASYNC_DEFUN.equals(qn.member())) {
					// normally lowered by the compile() pre-pass; a stray nested form
					// (e.g. macro-synthesized after the pre-pass) lowers here
					JvmExprCompiler.compileExpr(LispMacroExpander.expandAsyncDefun(cons), ctx, className);
					return;
				}
				if (LispNames.ASYNC_LAMBDA.equals(qn.member())) {
					JvmExprCompiler.compileExpr(LispMacroExpander.expandAsyncLambda(cons), ctx, className);
					return;
				}
				if (LispNames.TCP_CONNECT.equals(qn.member()) || LispNames.TCP_LISTEN.equals(qn.member())
						|| LispNames.TCP_ACCEPT.equals(qn.member()) || LispNames.TCP_LOCAL_PORT.equals(qn.member())
						|| LispNames.TCP_LOCAL_ADDRESS.equals(qn.member())
						|| LispNames.TCP_PEER_ADDRESS.equals(qn.member()) || LispNames.TCP_PEER_PORT.equals(qn.member())
						|| LispNames.TCP_SET_TIMEOUT.equals(qn.member()) || LispNames.TLS_CONNECT.equals(qn.member())
						|| LispNames.TLS_UPGRADE.equals(qn.member()) || LispNames.TLS_LISTEN.equals(qn.member())
						|| LispNames.TLS_LISTEN_P12.equals(qn.member())) {
					JvmTcpCompiler.compile(qn.member(), cons, ctx, className);
					return;
				}
				if (LispNames.HTTP_HANDLER.equals(qn.member())) {
					JvmHttpHandlerCompiler.compile(cons, ctx, className);
					return;
				}
				if (JvmHttpServerSeamCompiler.handles(qn.member())) {
					JvmHttpServerSeamCompiler.compile(qn.member(), cons, ctx, className);
					return;
				}
				if (LispNames.OCTETS_TO_STRING_STRICT_INTERNAL.equals(qn.member())) {
					// The STRICT half of the prelude's lenient octet decoder: the JDK
					// UTF-8 decode of a packed octet vector, or nil when the bytes are
					// not valid UTF-8 (_utf8Strict, emitted because the reference gated
					// it).
					if (cons.toList().size() != 2) {
						throw new UnsupportedOperationException(
								"%octets-to-string-strict expects 1 argument, got " + (cons.toList().size() - 1));
					}
					compileExpr(cons.toList().get(1), ctx, className);
					ctx.emit(Opcode.INVOKESTATIC);
					ctx.emitU2(ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
							ctx.cp.addNameAndType(ctx.cp.addUtf8(JvmAsyncRuntimeBuilder.OCTETS_STRICT_METHOD),
									ctx.cp.addUtf8(JvmAsyncRuntimeBuilder.UNARY_DESC)))
						.index());
					return;
				}
				if (LispNames.RANDOM_BYTE_INTERNAL.equals(qn.member())) {
					// One cryptographically strong byte from the lazily created
					// SecureRandom (_randomByte, emitted because the reference gated it).
					if (cons.toList().size() != 1) {
						throw new UnsupportedOperationException(
								"%random-byte expects 0 arguments, got " + (cons.toList().size() - 1));
					}
					ctx.emit(Opcode.INVOKESTATIC);
					ctx.emitU2(ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
							ctx.cp.addNameAndType(ctx.cp.addUtf8(JvmSecureRandomRuntimeBuilder.METHOD),
									ctx.cp.addUtf8(JvmSecureRandomRuntimeBuilder.DESC)))
						.index());
					return;
				}
				if (LispNames.WASM_EXPORT.equals(qn.member())) {
					// rontolisp:wasm-export marks a function for direct WASM export; the
					// JVM
					// backend has no notion of it, so it is a no-op that yields nil.
					ctx.emit(Opcode.ACONST_NULL);
					return;
				}
				if (LispNames.JVM_EXPORT.equals(qn.member())) {
					// A top-level (rontolisp:jvm-export ...) was consumed in Pass 1;
					// reaching this compiler means the directive is nested inside a
					// body, where it cannot declare anything.
					throw new UnsupportedOperationException(
							"rontolisp:jvm-export is a top-level directive; it cannot appear inside a function body");
				}
				if (LispNames.WASM_IMPORT.equals(qn.member())) {
					// rontolisp:wasm-import declares a host function imported into WASM
					// output; on the JVM the error-signalling stub defun was registered
					// in pass 1, so the directive itself is a no-op that yields nil.
					ctx.emit(Opcode.ACONST_NULL);
					return;
				}
				if (LispNames.WITH_ARENA.equals(qn.member())) {
					// A reclamation boundary for --no-gc; the JVM heap is
					// garbage-collected, so the body runs as a plain progn.
					compileExpr(LispMacroExpander.expandWithArena(cons), ctx, className);
					return;
				}
				if (LispNames.WITH_MUTEX.equals(qn.member())) {
					compileExpr(LispMacroExpander.expandWithMutex(cons), ctx, className);
					return;
				}
				if (LispNames.MAKE_MUTEX.equals(qn.member()) || LispNames.MUTEX_ACQUIRE.equals(qn.member())
						|| LispNames.MUTEX_RELEASE.equals(qn.member())) {
					// A real ReentrantLock, handed out as the handle itself
					// (JvmMutexRuntimeBuilder, emitted because the reference gated it).
					compileMutex(qn.member(), cons, ctx, className);
					return;
				}
				if (LispNames.MAKE_THREAD.equals(qn.member()) || LispNames.JOIN_THREAD.equals(qn.member())
						|| LispNames.THREADP.equals(qn.member()) || LispNames.THREAD_ALIVE_P.equals(qn.member())
						|| LispNames.DESTROY_THREAD.equals(qn.member())
						|| LispNames.CURRENT_THREAD.equals(qn.member())) {
					// A real virtual thread behind a marker-headed opaque handle
					// (JvmThreadRuntimeBuilder, emitted because the reference gated it).
					compileThread(qn.member(), cons, ctx, className);
					return;
				}
				// Other rontolisp: members (user defuns in that package) fall through.
			}
			if (qn != null && LispNames.JAVA_PKG.equals(qn.pkg()) && JvmJavaInteropCompiler.handles(qn.member())) {
				JvmJavaInteropCompiler.compile(qn.member(), cons, ctx, className);
				return;
			}
			if (qn != null && LispNames.OBJC_PKG.equals(qn.pkg()) && JvmObjcInteropCompiler.handles(qn.member())) {
				JvmObjcInteropCompiler.compile(qn.member(), cons, ctx, className);
				return;
			}
			// The uiop MACROS with real expansions. A macro cannot reach the uiop stub
			// lowering (which only sees function-call shapes), and these are not stubs:
			// smart-buffer's disk-spill path runs with-temporary-file, and the
			// definition wrappers (with-deprecation / with-upgradability) reach here for
			// the occurrences nested in an expression -- the flattening pass has already
			// spliced the top-level ones. One dispatcher shared with the interpreter,
			// the WASM compiler and FreeVarAnalyzer.
			if (qn != null && UiopExports.isUiopFamily(qn.pkg())) {
				// Other uiop members fall through to the stub lowering.
				LispVal uiopMacro = LispMacroExpander.expandUiopMacro(cons, true);
				if (uiopMacro != null) {
					compileExpr(uiopMacro, ctx, className);
					return;
				}
				// A uiop MACRO nothing implements yet, lowered HERE rather than in
				// JvmFunctionCallCompiler: its synthesized stub is a real variadic defun
				// (so the name is fboundp), which means the ordinary call path finds it
				// and compiles its ARGUMENT FORMS before signalling -- and a macro that
				// does nothing must not evaluate what it was handed. The stub lowering
				// further down only runs for a name with no defun at all.
				LispVal uiopStub = LispMacroExpander.expandUnimplementedUiopMacro(cons);
				if (uiopStub != null) {
					compileExpr(uiopStub, ctx, className);
					return;
				}
			}
			// The usocket with-* convenience macros are built-in LispMacroExpander
			// expansions (the rontolisp:with-arena pattern) over the usocket.lisp defuns.
			if (qn != null && LispNames.USOCKET_PKG.equals(qn.pkg())) {
				switch (qn.member()) {
					case LispNames.USOCKET_WITH_CLIENT_SOCKET -> {
						compileExpr(LispMacroExpander.expandUsocketWithClientSocket(cons), ctx, className);
						return;
					}
					case LispNames.USOCKET_WITH_CONNECTED_SOCKET, LispNames.USOCKET_WITH_SERVER_SOCKET -> {
						compileExpr(LispMacroExpander.expandUsocketWithConnectedSocket(cons), ctx, className);
						return;
					}
					case LispNames.USOCKET_WITH_SOCKET_LISTENER -> {
						compileExpr(LispMacroExpander.expandUsocketWithSocketListener(cons), ctx, className);
						return;
					}
					case LispNames.USOCKET_GUARD -> {
						compileExpr(LispMacroExpander.expandUsocketGuard(cons, true), ctx, className);
						return;
					}
					default -> {
						// Other usocket: members (the usocket.lisp defuns) fall through
						// to
						// the ordinary qualified-call path.
					}
				}
			}
			// bordeaux-threads:with-lock-held is the same built-in expansion as
			// rontolisp:with-mutex; the rest of the bt shim is bordeaux-threads.lisp
			// defuns, which fall through to the ordinary qualified-call path.
			if (qn != null && LispNames.BORDEAUX_THREADS_PKG.equals(qn.pkg())
					&& LispNames.WITH_LOCK_HELD.equals(qn.member())) {
				compileExpr(LispMacroExpander.expandWithMutex(cons), ctx, className);
				return;
			}
			// torch:no-grad is a built-in LispMacroExpander expansion (the usocket with-*
			// pattern): a let that dynamically rebinds the spliced torch.lisp
			// defparameter torch::*grad-enabled*. The other torch: members are the
			// torch.lisp defuns and fall through to the ordinary qualified-call path.
			if (qn != null && LispNames.TORCH_PKG.equals(qn.pkg()) && LispNames.TORCH_NO_GRAD.equals(qn.member())) {
				compileExpr(LispMacroExpander.expandTorchNoGrad(cons), ctx, className);
				return;
			}
			// --gpu: vec:matvec is the one device member outside linalg:. Its call site
			// is a CHAIN -- the device attempt over temps, then the lane kernel when
			// --simd emitted one, else the spliced defun -- emitted whenever the device
			// bridge was, with or without --simd (.kb/gpu.md).
			if (qn != null && LispNames.VEC_PKG.equals(qn.pkg()) && ctx.gpuOps != null
					&& LispNames.VEC_MATVEC.equals(qn.member())) {
				JvmSimdCompiler.compileGpuMatvec(cons, ctx, className);
				return;
			}
			// --vec: route the six vectorizable vec: kernels to the embedded Vector API
			// bridge instead of the scalar vec.lisp defun. Only when the runtime was
			// emitted (ctx.simdOps != null); otherwise this falls through to the ordinary
			// qualified-call path and runs the spliced scalar reference.
			if (qn != null && LispNames.VEC_PKG.equals(qn.pkg()) && ctx.simdOps != null
					&& JvmSimdCompiler.handles(qn.member())) {
				JvmSimdCompiler.compile(qn.member(), cons, ctx, className);
				return;
			}
			// --simd / --blas: the accelerated linalg: kernels. Unlike vec:, each bridge
			// call is guarded -- a kernel that declines the operands (a general array, a
			// mixed width, a shape error) returns null and the emitted call site tries
			// the next attempt, ending at the scalar linalg.lisp defun over the same
			// temps.
			if (qn != null && LispNames.LINALG_PKG.equals(qn.pkg())
					&& JvmLinalgKernelCompiler.claims(qn.member(), ctx)) {
				JvmLinalgKernelCompiler.compile(qn.member(), cons, ctx, className);
				return;
			}
			// A program that defines its own function on a cl name loses every call site
			// the operator dispatch below claims -- silently, until this. Armed here and
			// disarmed in the default arm (the ordinary call path, which DOES resolve
			// the defun), so a cl name this backend never intercepts stays quiet:
			// compile-runtime.lisp's `compile`, a deliberate Lisp-source definition of a
			// standard function. See compiler/ClRedefinitionWarnings for why the answer
			// is a diagnostic rather than honouring the definition.
			boolean redefinedClFunction = ClRedefinitionWarnings.redefinesClFunction(sym.name(), ctx.userDefunNames);
			switch (sym.name()) {
				// The integer expression-tree fusion tries first on the arithmetic and
				// bitwise heads (.kb/jvm-int-fusion.md); when it declines (a single op
				// over plain leaves, a double literal, --optimize=size) nothing was
				// emitted and the per-op path below runs exactly as before.
				case LispNames.ADD -> {
					if (!JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.ADD, Opcode.DADD, className);
					}
				}
				case LispNames.SUB -> {
					if (!JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.SUB, Opcode.DSUB, className);
					}
				}
				case LispNames.MUL -> {
					if (!JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.MUL, Opcode.DMUL, className);
					}
				}
				case LispNames.DIV ->
					JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.DIV, Opcode.DDIV, className);
				case LispNames.MOD -> {
					if (!JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.MOD, Opcode.DREM, className);
					}
				}
				case LispNames.REM -> {
					if (!JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.REM, Opcode.DREM, className);
					}
				}
				case LispNames.EQ -> compileComparison(cons, ctx, className, Opcode.IFEQ);
				case LispNames.LT -> compileComparison(cons, ctx, className, Opcode.IFLT);
				case LispNames.GT -> compileComparison(cons, ctx, className, Opcode.IFGT);
				case LispNames.LE -> compileComparison(cons, ctx, className, Opcode.IFLE);
				case LispNames.GE -> compileComparison(cons, ctx, className, Opcode.IFGE);
				case LispNames.PRINT ->
					compilePrintOperator(cons, ctx, className, () -> JvmPrintCompiler.compile(cons, ctx, className));
				case LispNames.PRIN1 ->
					compilePrintOperator(cons, ctx, className, () -> JvmPrin1Compiler.compile(cons, ctx, className));
				case LispNames.PRINC ->
					compilePrintOperator(cons, ctx, className, () -> JvmPrincCompiler.compile(cons, ctx, className));
				case LispNames.TERPRI -> JvmTerpriCompiler.compile(cons, ctx, className);
				case LispNames.FRESH_LINE -> JvmFreshLineCompiler.compile(cons, ctx, className);
				case LispNames.PRINC_TO_STRING -> compilePrintOperator(cons, ctx, className,
						() -> JvmPrincToStringCompiler.compile(cons, ctx, className));
				case LispNames.PRIN1_TO_STRING -> compilePrintOperator(cons, ctx, className,
						() -> JvmPrin1ToStringCompiler.compile(cons, ctx, className));
				// The print-object-free aliases the generated renderer's fallback calls.
				case LispNames.PRINC_TO_STRING_RAW -> JvmPrincToStringCompiler.compile(cons, ctx, className);
				case LispNames.PRIN1_TO_STRING_RAW -> JvmPrin1ToStringCompiler.compile(cons, ctx, className);
				case LispNames.STRING_CONCAT -> JvmStringConcatCompiler.compile(cons, ctx, className);
				case LispNames.FIXED_DECIMAL -> JvmFixedDecimalCompiler.compile(cons, ctx, className);
				case LispNames.GENSYM -> JvmGensymCompiler.compile(cons, ctx, className);
				case LispNames.STRING -> JvmSymbolApiCompiler.compileString(cons, ctx, className);
				case LispNames.SYMBOL_NAME -> JvmSymbolApiCompiler.compileSymbolName(cons, ctx, className);
				case LispNames.INTERN -> JvmSymbolApiCompiler.compileIntern(cons, ctx, className);
				case LispNames.FIND_SYMBOL -> JvmSymbolApiCompiler.compileFindSymbol(cons, ctx, className);
				case LispNames.FIND_SYMBOL_STATUS -> JvmSymbolApiCompiler.compileFindSymbolStatus(cons, ctx, className);
				// A runtime export/unexport (inside a defun body): the compiled package
				// registry is frozen, so evaluate the arguments and yield t.
				case LispNames.EXPORT, LispNames.UNEXPORT, LispNames.IMPORT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRuntimeExport(cons), ctx, className);
				// The package-registry queries: answered from the use table baked in at
				// compile time (the compiled runtimes have no registry).
				case LispNames.LIST_ALL_PACKAGES, LispNames.PACKAGE_USE_LIST,
						LispNames.PACKAGE_USED_BY_LIST ->
					JvmExprCompiler.compileExpr(
							LispMacroExpander.expandPackageQuery(cons, ctx.packageTable, ctx.packageUseTable), ctx,
							className);
				case LispNames.MAKE_SYMBOL -> JvmSymbolApiCompiler.compileMakeSymbol(cons, ctx, className);
				case LispNames.BOUNDP -> JvmSymbolApiCompiler.compileBoundp(cons, ctx, className);
				case LispNames.FBOUNDP -> JvmSymbolApiCompiler.compileFboundp(cons, ctx, className);
				case LispNames.FMAKUNBOUND -> JvmSymbolApiCompiler.compileFmakunbound(cons, ctx, className);
				case LispNames.SET_SYMBOL_FUNCTION_INTERNAL ->
					JvmSymbolApiCompiler.compileSetSymbolFunction(cons, ctx, className);
				case LispNames.FENV_FUNCTION_INTERNAL -> JvmSymbolApiCompiler.compileFenvFunction(cons, ctx, className);
				case LispNames.SYMBOL_VALUE -> JvmSymbolApiCompiler.compileSymbolValue(cons, ctx, className);
				// Only a COMPUTED designator reaches here: PackageResolver folds a
				// literal
				// one to the quoted package keyword before the compiler ever sees it.
				case LispNames.FIND_PACKAGE -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandRuntimeFindPackage(cons.toList().get(1), ctx.packageTable), ctx,
						className);
				case LispNames.CONCATENATE -> JvmExprCompiler
					.compileExpr(ConcatenateForms.expand(cons, ctx.usesSeqString, ctx.closRegistry), ctx, className);
				case LispNames.READ_LINE -> {
					LispVal typed = LispMacroExpander.expandReadEofSignal(cons, false);
					if (typed != null) {
						JvmExprCompiler.compileExpr(typed, ctx, className);
					}
					else {
						JvmReadLineCompiler.compile(cons, ctx, className);
					}
				}
				case LispNames.READ_CHAR -> {
					LispVal typed = LispMacroExpander.expandReadEofSignal(cons, true);
					if (typed != null) {
						JvmExprCompiler.compileExpr(typed, ctx, className);
					}
					else {
						JvmReadCharCompiler.compile(cons, ctx, className);
					}
				}
				case LispNames.PEEK_CHAR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPeekChar(cons), ctx, className);
				case LispNames.READ_CHAR_NO_HANG ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandReadCharNoHang(cons), ctx, className);
				case LispNames.UNREAD_CHAR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandUnreadChar(cons), ctx, className);
				case LispNames.PEEK_CHAR_INTERNAL -> {
					LispVal typed = LispMacroExpander.expandReadEofSignal(cons, true);
					if (typed != null) {
						JvmExprCompiler.compileExpr(typed, ctx, className);
					}
					else {
						JvmPeekCharCompiler.compile(cons, ctx, className);
					}
				}
				case LispNames.MAKE_SYNONYM_STREAM ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMakeSynonymStream(cons), ctx, className);
				case LispNames.OPEN -> JvmOpenCompiler.compile(coercePathArgWhenGated(cons, 0, ctx), ctx, className);
				case LispNames.CLOSE -> {
					// Closing a SYNONYM stream closes the synonym, not what it forwards
					// to -- which is nothing to do. The guard is emitted only when the
					// program can build one; %close is the plain-designator close it
					// falls through to.
					if (ctx.usesSynonymStreams) {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandCloseOverSynonym(cons), ctx, className);
					}
					else {
						JvmCloseCompiler.compile(cons, ctx, className);
					}
				}
				case LispNames.CLOSE_INTERNAL -> JvmCloseCompiler.compile(cons, ctx, className);
				case LispNames.PROBE_FILE_INTERNAL -> JvmProbeFileCompiler.compile(cons, ctx, className);
				// The environment primitives behind uiop:getenv / uiop:getcwd. The public
				// names are Lisp (uiop-os.lisp): getenv consults the override map a
				// (setf (uiop:getenv ...)) wrote before falling back here, and getcwd
				// turns a nil answer into its not-implemented-error.
				case LispNames.HOST_GETENV -> JvmGetenvCompiler.compile(cons, ctx, className);
				case LispNames.HOST_GETCWD -> JvmGetcwdCompiler.compile(cons, ctx, className);
				// The command-line primitive behind the uiop/image family (the public
				// five are Lisp over it, uiop-image.lisp): main's own String[] behind
				// the class name, through the _argv helper.
				case LispNames.HOST_ARGV -> JvmArgvCompiler.compile(cons, ctx, className);
				// %target-machine-type: the ABI this artifact targets, the one thing the
				// environment-enquiry family (machine-type, a prelude defun over it)
				// answers differently per backend. A class file is CPU-independent, so
				// the answer names the ABI and not the host processor -- the same rule
				// uiop:architecture follows. A literal, so it folds like any constant.
				case LispNames.TARGET_MACHINE_TYPE ->
					JvmExprCompiler.compileExpr(new LispString("JVM"), ctx, className);
				// The exit primitive behind uiop:quit (uiop-image.lisp finishes the
				// output streams first, on every backend).
				case LispNames.HOST_EXIT -> JvmExitCompiler.compile(cons, ctx, className);
				case LispNames.LIST_DIRECTORY -> JvmListDirectoryCompiler.compile(cons, ctx, className);
				case LispNames.SLEEP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandSleep(cons, false), ctx, className);
				case LispNames.SLEEP_MS -> JvmSleepCompiler.compile(cons, ctx, className);
				case LispNames.WRITE_LINE -> JvmWriteLineCompiler.compile(cons, ctx, className);
				case LispNames.WRITE_STRING -> {
					LispVal bounded = LispMacroExpander.lowerWriteStringBounds(cons);
					if (bounded != null) {
						JvmExprCompiler.compileExpr(bounded, ctx, className);
					}
					else {
						JvmStringStreamCompiler.compileWriteString(cons, ctx, className);
					}
				}
				case LispNames.WRITE_TO_STRING -> compilePrintOperator(cons, ctx, className,
						() -> JvmPrin1ToStringCompiler.compile(cons, ctx, className));
				case LispNames.MAKE_STRING_OUTPUT_STREAM_INTERNAL ->
					JvmStringStreamCompiler.compileMakeOutputStream(cons, ctx, className);
				case LispNames.MAKE_STRING_OUTPUT_STREAM ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMakeStringOutputStream(cons), ctx, className);
				case LispNames.GET_OUTPUT_STREAM_STRING ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandGetOutputStreamString(cons), ctx, className);
				case LispNames.MAKE_STRING_INPUT_STREAM_INTERNAL ->
					JvmStringStreamCompiler.compileMakeInputStream(cons, ctx, className);
				case LispNames.MAKE_STRING_INPUT_STREAM ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMakeStringInputStream(cons), ctx, className);
				case LispNames.STRING_STREAM_CONTENTS_INTERNAL ->
					JvmStringStreamCompiler.compileContents(cons, ctx, className);
				case LispNames.WITH_OUTPUT_TO_STRING ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithOutputToString(cons), ctx, className);
				case LispNames.PPRINT_LOGICAL_BLOCK ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPprintLogicalBlock(cons), ctx, className);
				case LispNames.WITH_INPUT_FROM_STRING ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithInputFromString(cons), ctx, className);
				case LispNames.PUSHNEW ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPushnew(cons), ctx, className);
				case LispNames.DEFTYPE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDeftype(cons), ctx, className);
				case LispNames.DEFINE_CONDITION ->
					// Like defclass: top-level define-conditions are spliced into their
					// generated defuns before Pass 1; one reaching this compiler is
					// nested.
					throw new UnsupportedOperationException(
							LispNames.DEFINE_CONDITION + " is only supported as a top-level form");
				case LispNames.DEFINE_SETF_EXPANDER ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDefineSetfExpander(cons), ctx, className);
				case LispNames.DEFINE_COMPILER_MACRO ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDefineCompilerMacro(cons), ctx, className);
				case LispNames.RESTART_CASE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRestartCase(cons), ctx, className);
				case LispNames.RESTART_BIND ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRestartBind(cons), ctx, className);
				case LispNames.WITH_SIMPLE_RESTART ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithSimpleRestart(cons), ctx, className);
				case LispNames.MAKE_CONDITION -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandMakeCondition(cons, ctx.closRegistry), ctx, className);
				case LispNames.DOCUMENTATION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDocumentation(cons), ctx, className);
				case LispNames.WITH_OPEN_STREAM ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithOpenStream(cons, true), ctx, className);
				case LispNames.WITH_OPEN_FILE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithOpenFile(cons), ctx, className);
				case LispNames.READ_BYTE -> {
					LispVal typed = LispMacroExpander.expandReadEofSignal(cons, true);
					if (typed != null) {
						JvmExprCompiler.compileExpr(typed, ctx, className);
					}
					else {
						JvmReadByteCompiler.compile(cons, ctx, className);
					}
				}
				case LispNames.WRITE_BYTE -> JvmWriteByteCompiler.compile(cons, ctx, className);
				case LispNames.FORCE_OUTPUT, LispNames.FINISH_OUTPUT ->
					JvmForceOutputCompiler.compile(cons, ctx, className);
				case LispNames.CLEAR_OUTPUT ->
					// Nothing is buffered in a discardable way on any backend, so
					// clear-output evaluates its designator for effect and answers nil
					// (.kb/gray-streams.md). A Gray instance never reaches here -- the
					// pre-pass rewrote that call onto the dispatch helper.
					JvmExprCompiler.compileExpr(LispMacroExpander.expandClearOutput(cons), ctx, className);
				case LispNames.LISTEN -> JvmListenCompiler.compile(cons, ctx, className);
				case LispNames.OPEN_STREAM_P -> JvmOpenStreamPCompiler.compile(cons, ctx, className);
				case LispNames.READ_SEQUENCE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandReadSequence(cons), ctx, className);
				case LispNames.WRITE_SEQUENCE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWriteSequence(cons), ctx, className);
				case LispNames.READ_SEQUENCE_PACKED, LispNames.WRITE_SEQUENCE_PACKED ->
					JvmSequencePackedCompiler.compile(cons, ctx, className);
				case LispNames.MAKE_STRING ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMakeString(cons), ctx, className);
				case LispNames.REPLACE -> JvmExprCompiler.compileExpr(LispMacroExpander.expandReplace(cons,
						ctx.usesArrays, ctx.functions.containsKey(LispNames.REPLACE_RUNTIME)), ctx, className);
				case LispNames.FILL -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandFill(cons, ctx.functions.containsKey(LispNames.FILL_RUNTIME)), ctx,
						className);
				case LispNames.SCHAR_SET ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandScharSetFunctional(cons), ctx, className);
				case LispNames.LOWER_CASE_P ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandLowerCaseP(cons), ctx, className);
				case LispNames.UPPER_CASE_P ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandUpperCaseP(cons), ctx, className);
				case LispNames.CONSTANTP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandConstantp(cons), ctx, className);
				case LispNames.STREAMP -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandStreamp(cons, ctx.usesSynonymStreams), ctx, className);
				case LispNames.SIMPLE_STRING_P ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandSimpleStringP(cons), ctx, className);
				case LispNames.INPUT_STREAM_P, LispNames.OUTPUT_STREAM_P -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandStreamDirectionP(cons, ctx.usesSynonymStreams), ctx, className);
				case LispNames.FILE_POSITION -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandConstantResult(cons, LispNil.INSTANCE), ctx, className);
				case LispNames.PATHNAMEP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPathnamep(cons), ctx, className);
				case LispNames.FILE_WRITE_DATE ->
					JvmFileMetaCompiler.compile(coercePathArgWhenGated(cons, 0, ctx), ctx, className, sym.name());
				case LispNames.MAKE_DIRECTORIES, LispNames.DELETE_FILE_INTERNAL, LispNames.FILE_LENGTH,
						LispNames.RENAME_FILE_INTERNAL ->
					JvmFileMetaCompiler.compile(cons, ctx, className, sym.name());
				case LispNames.STREAM_ELEMENT_TYPE -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandConstantResult(cons, LispMacroExpander.quotedCharacterTypeName()), ctx,
						className);
				case LispNames.MAKE_BROADCAST_STREAM ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMakeBroadcastStream(cons), ctx, className);
				case LispNames.FDEFINITION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandFdefinition(cons), ctx, className);
				case LispNames.MASK_FIELD ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMaskField(cons), ctx, className);
				case LispNames.SCALE_FLOAT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandScaleFloat(cons), ctx, className);
				case LispNames.CLASS_OF -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandClassOf(cons, ctx.usesHashTables), ctx, className);
				case LispNames.CLASS_DESIGNATOR_INTERNAL -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandClassDesignator(cons, ctx.usesHashTables), ctx, className);
				case LispNames.CLASS_SLOT_DEFS_INTERNAL -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandClassSlotDefs(cons, ctx.closRegistry), ctx, className);
				case LispNames.SLOT_BOUNDP -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandSlotBoundp(cons, ctx.closRegistry), ctx, className);
				case LispNames.SLOT_EXISTS_P -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandSlotExistsP(cons, ctx.closRegistry), ctx, className);
				case LispNames.SLOT_MAKUNBOUND -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandSlotMakunbound(cons, ctx.closRegistry), ctx, className);
				case LispNames.SIMPLE_CONDITION_FORMAT_CONTROL -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandSimpleConditionFormatControl(cons, ctx.closRegistry), ctx, className);
				case LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandSimpleConditionFormatArguments(cons, ctx.closRegistry), ctx, className);
				case LispNames.IEEE754_DOUBLE_BITS -> JvmIeee754Compiler.compileDoubleBits(cons, ctx, className);
				case LispNames.IEEE754_DOUBLE_FROM_BITS ->
					JvmIeee754Compiler.compileDoubleFromBits(cons, ctx, className);
				case LispNames.IEEE754_SINGLE_BITS -> JvmIeee754Compiler.compileSingleBits(cons, ctx, className);
				case LispNames.IEEE754_SINGLE_FROM_BITS ->
					JvmIeee754Compiler.compileSingleFromBits(cons, ctx, className);
				case LispNames.READ_EVAL, LispNames.READ_EVAL_TEMPLATE ->
					// Identity: a #. marker split into code position by a backquote
					// template
					// arrives here with its (already evaluated) argument.
					JvmExprCompiler.compileExpr(cons.toList().get(1), ctx, className);
				case LispNames.STRING_UPCASE -> JvmStringUpcaseCompiler
					.compileUpcase(LispMacroExpander.normalizeStringDesignatorArg(cons, 1), ctx, className);
				case LispNames.STRING_DOWNCASE -> JvmStringUpcaseCompiler
					.compileDowncase(LispMacroExpander.normalizeStringDesignatorArg(cons, 1), ctx, className);
				case LispNames.STRING_CAPITALIZE -> JvmStringCapitalizeCompiler
					.compile(LispMacroExpander.normalizeStringDesignatorArg(cons, 1), ctx, className);
				case LispNames.SUBSEQ, LispNames.SUBSEQ_CORE -> JvmSubseqCompiler.compile(cons, ctx, className);
				case LispNames.CHAR, LispNames.SCHAR -> JvmCharCompiler.compileChar(cons, ctx, className);
				case LispNames.CHAR_CODE -> JvmCharCompiler.compileCharCode(cons, ctx, className);
				case LispNames.CODE_CHAR -> JvmCharCompiler.compileCodeChar(cons, ctx, className);
				case LispNames.CHAR_UPCASE -> JvmCharCompiler.compileUpcase(cons, ctx, className);
				case LispNames.CHAR_DOWNCASE -> JvmCharCompiler.compileDowncase(cons, ctx, className);
				case LispNames.CHARACTERP -> JvmCharCompiler.compileCharacterp(cons, ctx, className);
				case LispNames.ALPHA_CHAR_P -> JvmCharCompiler.compileAlphaCharP(cons, ctx, className);
				case LispNames.DIGIT_CHAR_P -> JvmCharCompiler.compileDigitCharP(cons, ctx, className);
				case LispNames.CHAR_EQ -> JvmCharCompiler.compileEq(cons, ctx, className);
				case LispNames.CHAR_LT -> JvmCharCompiler.compileLt(cons, ctx, className);
				case LispNames.CHAR_LE -> JvmCharCompiler.compileLe(cons, ctx, className);
				case LispNames.CHAR_GT -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandCharDescending(cons, LispNames.CHAR_LT), ctx, className);
				case LispNames.CHAR_GE -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandCharDescending(cons, LispNames.CHAR_LE), ctx, className);
				case LispNames.CHAR_NE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCharNe(cons), ctx, className);
				case LispNames.CHAR_EQUAL ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCharEqual(cons), ctx, className);
				case LispNames.PARSE_INTEGER ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandParseInteger(cons), ctx, className);
				case LispNames.VALUES_LIST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandValuesList(cons), ctx, className);
				case LispNames.COPY_READTABLE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCopyReadtable(cons), ctx, className);
				case LispNames.SET_DISPATCH_MACRO_CHARACTER -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandSetDispatchMacroCharacter(cons), ctx, className);
				case LispNames.READTABLE_CASE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandReadtableCase(cons), ctx, className);
				case LispNames.COMPLEX ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandComplexLite(cons), ctx, className);
				case LispNames.NE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNumericNotEqual(cons), ctx, className);
				case LispNames.READ_FROM_STRING -> JvmReadFromStringCompiler.compile(cons, ctx, className);
				// A string=/string-equal call with the bounding-index keywords is lowered
				// onto subseq first, so the intrinsic below always sees two strings.
				case LispNames.STRING_EQ -> {
					if (LispMacroExpander.hasStringComparisonBounds(cons)) {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandStringComparisonBounds(cons), ctx,
								className);
					}
					else {
						JvmStringEqCompiler.compileEq(
								(LispCons) LispMacroExpander.normalizeStringComparisonDesignators(cons), ctx,
								className);
					}
				}
				case LispNames.STRING_EQUAL -> {
					if (LispMacroExpander.hasStringComparisonBounds(cons)) {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandStringComparisonBounds(cons), ctx,
								className);
					}
					else {
						JvmStringEqCompiler.compileEqual(
								(LispCons) LispMacroExpander.normalizeStringComparisonDesignators(cons), ctx,
								className);
					}
				}
				case LispNames.STRING_TRIM ->
					JvmStringTrimCompiler.compileTrim(LispMacroExpander.normalizeStringTrimArgs(cons), ctx, className);
				case LispNames.STRING_LEFT_TRIM ->
					JvmStringTrimCompiler.compileLeft(LispMacroExpander.normalizeStringTrimArgs(cons), ctx, className);
				case LispNames.STRING_RIGHT_TRIM ->
					JvmStringTrimCompiler.compileRight(LispMacroExpander.normalizeStringTrimArgs(cons), ctx, className);
				case LispNames.QUOTE -> JvmQuoteCompiler.compile(cons, ctx, className);
				// quote for a compiler-synthesized name: same value, but the spelling is
				// not recorded as program-spelled (see LispNames.UNSPELLED_QUOTE).
				case LispNames.UNSPELLED_QUOTE ->
					JvmEmitHelper.compileUnspelledLiteral(((LispSymbol) ((LispCons) cons.cdr()).car()).name(), ctx);
				case LispNames.IF -> JvmIfCompiler.compile(cons, ctx, className);
				case LispNames.WHILE -> JvmWhileCompiler.compile(cons, ctx, className);
				case LispNames.LET -> JvmLetCompiler.compile(cons, ctx, className);
				case LispNames.PROGV ->
					// The symbols are runtime-computed, but the candidate SPECIALS are
					// static: lower to a loop dispatching each name over that set, with
					// an unwind-protect carrying the restores (.kb/dynamic-special-
					// variables.md).
					JvmExprCompiler.compileExpr(
							LispMacroExpander.expandProgvForCompile(cons, ctx.specialVars, ctx.evalStoreRef != null),
							ctx, className);
				case LispNames.PROGV_DYN_BIND -> JvmProgvCompiler.compileDynBind(cons, ctx, className);
				case LispNames.PROGV_DYN_UNBIND -> JvmProgvCompiler.compileDynUnbind(cons, ctx, className);
				case LispNames.PROGV_GENV -> JvmProgvCompiler.compileGenvRead(ctx, className);
				case LispNames.PROGV_GENV_SET -> JvmProgvCompiler.compileGenvWrite(cons, ctx, className);
				case LispNames.SYMBOL_VALUE_RAW -> JvmSymbolApiCompiler.compileSymbolValueRaw(cons, ctx, className);
				case LispNames.PROGN -> JvmPrognCompiler.compile(cons, ctx, className);
				case LispNames.TAGBODY -> JvmTagbodyCompiler.compile(cons, ctx, className);
				case LispNames.GO -> JvmGoCompiler.compile(cons, ctx, className);
				case LispNames.PRINT_UNREADABLE_OBJECT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPrintUnreadableObject(cons), ctx, className);
				case LispNames.WITH_PACKAGE_ITERATOR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithPackageIterator(cons), ctx, className);
				case LispNames.DO_EXTERNAL_SYMBOLS, LispNames.DO_SYMBOLS ->
					// Real on the interpreter (registry-backed); inside #. the macro-time
					// evaluator resolves it before compilation. A runtime occurrence has
					// no
					// package registry behind it here.
					throw new UnsupportedOperationException(
							sym.name() + " requires the interpreter (no runtime package registry in compiled mode)");
				case LispNames.PROG ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandProg(cons, false), ctx, className);
				case LispNames.PROG_STAR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandProg(cons, true), ctx, className);
				case LispNames.SETQ -> JvmSetqCompiler.compile(cons, ctx, className);
				case LispNames.LAMBDA -> JvmLambdaCompiler.compileValue(cons, ctx, className);
				case LispNames.DEFUN ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDefun(cons), ctx, className);
				case LispNames.DEFSTRUCT ->
					// Top-level defstructs are spliced into defuns before Pass 1; one
					// reaching this compiler is nested inside another form.
					throw new UnsupportedOperationException(
							LispNames.DEFSTRUCT + " is only supported as a top-level form");
				case LispNames.DEFCLASS, LispNames.DEFGENERIC, LispNames.DEFMETHOD ->
					// Like defstruct: the CLOS forms are spliced before Pass 1.
					throw new UnsupportedOperationException(sym.name() + " is only supported as a top-level form");
				case LispNames.MAKE_INSTANCE -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandMakeInstance(cons, ctx.closRegistry, true), ctx, className);
				case LispNames.SLOT_VALUE -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandSlotValue(cons, ctx.closRegistry), ctx, className);
				case LispNames.WITH_SLOTS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithSlots(cons), ctx, className);
				case LispNames.SYMBOL_MACROLET ->
					// No user-macro hook: UserMacroExpander has already expanded every
					// user
					// macro on the compile path.
					JvmExprCompiler.compileExpr(LispMacroExpander.expandSymbolMacrolet(cons), ctx, className);
				case LispNames.WITH_ACCESSORS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithAccessors(cons), ctx, className);
				case LispNames.CHANGE_CLASS -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandChangeClass(cons, ctx.closRegistry, true), ctx, className);
				case LispNames.DEFVAR -> JvmDefvarCompiler.compile(cons, ctx, className, false);
				case LispNames.DEFPARAMETER, LispNames.DEFCONSTANT ->
					JvmDefvarCompiler.compile(cons, ctx, className, true);
				case LispNames.LIST -> JvmListCompiler.compile(cons, ctx, className);
				case LispNames.CAR -> JvmCarCompiler.compile(cons, ctx, className);
				case LispNames.CDR -> JvmCdrCompiler.compile(cons, ctx, className);
				case LispNames.CONS -> JvmConsCompiler.compile(cons, ctx, className);
				case LispNames.NTHCDR -> JvmNthcdrCompiler.compile(cons, ctx, className);
				case LispNames.RPLACA -> JvmRplacaCompiler.compile(cons, ctx, className);
				case LispNames.RPLACD -> JvmRplacdCompiler.compile(cons, ctx, className);
				case LispNames.SETF -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandSetf(cons, ctx.structAccessors, ctx.closRegistry), ctx, className);
				case LispNames.PUSH -> JvmExprCompiler.compileExpr(LispMacroExpander.expandPush(cons), ctx, className);
				case LispNames.POP -> JvmExprCompiler.compileExpr(LispMacroExpander.expandPop(cons), ctx, className);
				case LispNames.REMF -> JvmExprCompiler.compileExpr(LispMacroExpander.expandRemf(cons), ctx, className);
				case LispNames.LET_STAR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandLetStar(cons), ctx, className);
				case LispNames.DOLIST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDolist(cons), ctx, className);
				case LispNames.DO -> JvmExprCompiler.compileExpr(LispMacroExpander.expandDo(cons), ctx, className);
				case LispNames.DO_STAR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDoStar(cons), ctx, className);
				case LispNames.LOOP -> JvmExprCompiler.compileExpr(LispMacroExpander.expandLoop(cons), ctx, className);
				case LispNames.BLOCK_INTERNAL -> JvmBlockCompiler.compile(cons, ctx, className);
				case LispNames.BLOCK -> JvmBlockCompiler.compileNamed(cons, ctx, className);
				case LispNames.FN_BLOCK_INTERNAL -> JvmBlockCompiler.compileFnBlock(cons, ctx, className);
				case LispNames.NLX_TAG_INTERNAL -> JvmNlxCompiler.compileTag(ctx);
				case LispNames.NLX_CATCH_INTERNAL -> JvmNlxCompiler.compileCatch(cons, ctx, className);
				case LispNames.NLX_THROW_INTERNAL -> JvmNlxCompiler.compileThrow(cons, ctx, className);
				case LispNames.CATCH -> JvmNlxCompiler.compileTagCatch(cons, ctx, className);
				case LispNames.THROW -> JvmNlxCompiler.compileTagThrow(cons, ctx, className);
				case LispNames.RETURN_FROM -> JvmReturnFromCompiler.compile(cons, ctx, className);
				case LispNames.UNWIND_PROTECT -> JvmUnwindProtectCompiler.compile(cons, ctx, className);
				case LispNames.RETURN -> JvmReturnCompiler.compile(cons, ctx, className);
				case LispNames.INCF -> JvmExprCompiler.compileExpr(LispMacroExpander.expandIncf(cons), ctx, className);
				case LispNames.DECF -> JvmExprCompiler.compileExpr(LispMacroExpander.expandDecf(cons), ctx, className);
				case LispNames.FORMAT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandFormat(cons), ctx, className);
				case LispNames.LENGTH -> JvmLengthCompiler.compile(cons, ctx, className);
				case LispNames.REVERSE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandReverse(cons, ctx.usesArrays), ctx, className);
				case LispNames.MEMBER ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMember(cons), ctx, className);
				case LispNames.FIND -> JvmExprCompiler.compileExpr(LispMacroExpander.expandFind(cons), ctx, className);
				case LispNames.FIND_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandFindIf(cons), ctx, className);
				case LispNames.FIND_IF_NOT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandFindIfNot(cons), ctx, className);
				case LispNames.MEMBER_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMemberIf(cons), ctx, className);
				case LispNames.POSITION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPosition(cons), ctx, className);
				case LispNames.POSITION_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPositionIf(cons), ctx, className);
				case LispNames.POSITION_IF_NOT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPositionIfNot(cons), ctx, className);
				case LispNames.COMPLEMENT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandComplement(cons), ctx, className);
				case LispNames.COUNT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCount(cons), ctx, className);
				case LispNames.COUNT_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCountIf(cons), ctx, className);
				case LispNames.ASSOC ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandAssoc(cons), ctx, className);
				case LispNames.ASSOC_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandAssocIf(cons), ctx, className);
				case LispNames.RASSOC_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRassocIf(cons), ctx, className);
				case LispNames.GETF -> JvmExprCompiler.compileExpr(LispMacroExpander.expandGetf(cons), ctx, className);
				case LispNames.EVERY ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandEvery(cons), ctx, className);
				case LispNames.SOME -> JvmExprCompiler.compileExpr(LispMacroExpander.expandSome(cons), ctx, className);
				case LispNames.REMOVE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRemove(cons, ctx.usesArrays), ctx, className);
				case LispNames.REMOVE_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRemoveIf(cons, ctx.usesArrays), ctx, className);
				case LispNames.REMOVE_IF_NOT -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandRemoveIfNot(cons, ctx.usesArrays), ctx, className);
				case LispNames.DELETE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDelete(cons), ctx, className);
				case LispNames.DELETE_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDeleteIf(cons), ctx, className);
				case LispNames.DELETE_IF_NOT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDeleteIfNot(cons), ctx, className);
				case LispNames.SUBSTITUTE -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandSubstitute(cons, ctx.usesArrays), ctx, className);
				case LispNames.NSUBSTITUTE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNsubstitute(cons), ctx, className);
				case LispNames.SUBSTITUTE_IF -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandSubstituteIf(cons, ctx.usesArrays, false), ctx, className);
				case LispNames.SUBSTITUTE_IF_NOT -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandSubstituteIf(cons, ctx.usesArrays, true), ctx, className);
				case LispNames.NSUBSTITUTE_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNsubstituteIf(cons), ctx, className);
				case LispNames.NSUBSTITUTE_IF_NOT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNsubstituteIfNot(cons), ctx, className);
				case LispNames.REMOVE_DUPLICATES, LispNames.DELETE_DUPLICATES -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandRemoveDuplicates(cons, ctx.usesArrays), ctx, className);
				case LispNames.NCONC ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNconc(cons), ctx, className);
				case LispNames.LAST -> JvmExprCompiler.compileExpr(LispMacroExpander.expandLast(cons), ctx, className);
				case LispNames.BUTLAST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandButlast(cons), ctx, className);
				case LispNames.IDENTITY ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandIdentity(cons), ctx, className);
				case LispNames.COPY_LIST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCopyList(cons), ctx, className);
				case LispNames.NREVERSE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNreverse(cons), ctx, className);
				case LispNames.MAKE_LIST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMakeList(cons), ctx, className);
				case LispNames.UNION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandUnion(cons), ctx, className);
				case LispNames.INTERSECTION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandIntersection(cons), ctx, className);
				case LispNames.SET_DIFFERENCE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandSetDifference(cons), ctx, className);
				case LispNames.ADJOIN ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandAdjoin(cons), ctx, className);
				case LispNames.EQ_GENERAL -> JvmEqGeneralCompiler.compileEq(cons, ctx, className);
				case LispNames.EQL -> JvmEqGeneralCompiler.compile(cons, ctx, className);
				case LispNames.EQUAL -> JvmEqualCompiler.compile(cons, ctx, className);
				case LispNames.REMF_TAIL -> JvmRemfTailCompiler.compile(cons, ctx, className);
				case LispNames.MAKE_HASH_TABLE -> JvmHashTableCompiler.compileMake(cons, ctx, className);
				case LispNames.GETHASH -> JvmHashTableCompiler.compileGet(cons, ctx, className);
				case LispNames.PUTHASH -> JvmHashTableCompiler.compilePut(cons, ctx, className);
				case LispNames.REMHASH -> JvmHashTableCompiler.compileRem(cons, ctx, className);
				case LispNames.CLRHASH -> JvmHashTableCompiler.compileClr(cons, ctx, className);
				case LispNames.HASH_TABLE_COUNT -> JvmHashTableCompiler.compileCount(cons, ctx, className);
				case LispNames.HASH_TABLE_TEST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandHashTableTest(cons), ctx, className);
				case LispNames.HASH_TABLE_SIZE -> JvmHashTableCompiler.compileCount(cons, ctx, className);
				case LispNames.HASH_TABLE_REHASH_SIZE -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandHashTableGrowthConstant(cons, 1.5), ctx, className);
				case LispNames.HASH_TABLE_REHASH_THRESHOLD -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandHashTableGrowthConstant(cons, 1.0), ctx, className);
				case LispNames.HASH_TABLE_P -> JvmHashTableCompiler.compileP(cons, ctx, className);
				case LispNames.MAPHASH -> JvmHashTableCompiler.compileMaphash(cons, ctx, className);
				case LispNames.MAKE_ARRAY -> JvmArrayCompiler.compileMake(cons, ctx, className);
				case LispNames.AREF -> JvmArrayCompiler.compileAref(cons, ctx, className);
				case LispNames.ASET -> JvmArrayCompiler.compileAset(cons, ctx, className);
				case LispNames.ARRAY_DIMENSIONS -> JvmArrayCompiler.compileDims(cons, ctx, className);
				case LispNames.ROW_MAJOR_AREF -> JvmArrayCompiler.compileRowMajorAref(cons, ctx, className);
				case LispNames.ROW_MAJOR_ASET -> JvmArrayCompiler.compileRowMajorAset(cons, ctx, className);
				// %replace-bulk (the replace runtime's engine-level copy arm, see
				// LispNames.REPLACE_BULK): no JVM bulk path yet, so it answers constant
				// nil and the caller's element loop runs. The arguments are the helper
				// body's own bindings (pure reads), so skipping their evaluation is
				// unobservable.
				case LispNames.REPLACE_BULK -> ctx.emit(Opcode.ACONST_NULL);
				case LispNames.ARRAY_ROW_MAJOR_INDEX ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandArrayRowMajorIndex(cons), ctx, className);
				case LispNames.VECTOR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandVector(cons), ctx, className);
				case LispNames.SVREF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandSvref(cons), ctx, className);
				case LispNames.ARRAY_RANK ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandArrayRank(cons), ctx, className);
				case LispNames.ARRAY_DIMENSION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandArrayDimension(cons), ctx, className);
				case LispNames.ARRAY_TOTAL_SIZE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandArrayTotalSize(cons), ctx, className);
				case LispNames.FILL_POINTER -> JvmArrayCompiler.compileFillPointer(cons, ctx, className);
				case LispNames.SET_FILL_POINTER -> JvmArrayCompiler.compileSetFillPointer(cons, ctx, className);
				case LispNames.ARRAY_HAS_FILL_POINTER_P -> JvmArrayCompiler.compileHasFillPointer(cons, ctx, className);
				case LispNames.ADJUSTABLE_ARRAY_P -> JvmArrayCompiler.compileAdjustableArrayP(cons, ctx, className);
				case LispNames.ARRAY_ELEMENT_TYPE -> {
					if (ctx.usesFloatArray || ctx.usesIntArray) {
						JvmArrayCompiler.compileElementType(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandArrayElementType(cons), ctx, className);
					}
				}
				case LispNames.VECTOR_PUSH -> JvmArrayCompiler.compileVectorPush(cons, ctx, className);
				case LispNames.VECTOR_POP -> JvmArrayCompiler.compileVectorPop(cons, ctx, className);
				case LispNames.VECTOR_PUSH_EXTEND -> JvmArrayCompiler.compileVectorPushExtend(cons, ctx, className);
				case LispNames.ADJUST_ARRAY ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandAdjustArray(cons), ctx, className);
				case LispNames.ARRAY_BECOME -> JvmArrayCompiler.compileArrayBecome(cons, ctx, className);
				case LispNames.ARRAY_ALIKE -> {
					// The type-preserving allocator (_ivAlike) when the program can
					// build a packed integer vector; otherwise every array is general
					// and the shared general lowering applies.
					if (ctx.usesIntArray) {
						JvmArrayCompiler.compileArrayAlike(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandArrayAlikeGeneral(cons), ctx, className);
					}
				}
				case LispNames.ARRAY_DISPLACEMENT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandArrayDisplacement(cons), ctx, className);
				case LispNames.ARRAY_DISP_TARGET -> JvmArrayCompiler.compileDispTarget(cons, ctx, className);
				case LispNames.ARRAY_DISP_OFFSET -> JvmArrayCompiler.compileDispOffset(cons, ctx, className);
				case LispNames.COERCE -> {
					// A packed (unsigned-byte 8|16|32) result type lowers through the
					// shared %seq-int-vector helper, exactly as concatenate's does;
					// everything else is expandCoerce as before.
					LispVal packed = ConcatenateForms.packedVectorCoerce(cons, ctx.closRegistry);
					JvmExprCompiler.compileExpr(packed != null ? packed : LispMacroExpander.expandCoerce(cons,
							ctx.usesArrays, ctx.functions.containsKey(LispNames.SEQ_TO_LIST)), ctx, className);
				}
				case LispNames.MAP_INTO -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandMapInto(cons,
								ctx.functions.containsKey(LispNames.mapIntoRuntime(cons.toList().size() - 3))),
						ctx, className);
				case LispNames.APPEND -> JvmAppendCompiler.compile(cons, ctx, className);
				case LispNames.EVAL -> JvmEvalCompiler.compile(cons, ctx, className);
				case LispNames.READ -> JvmReadCompiler.compile(cons, ctx, className);
				case LispNames.LOAD -> JvmLoadCompiler.compile(coercePathArgWhenGated(cons, 0, ctx), ctx, className);
				// A literal top-level require/provide (and the asdf directives) was
				// consumed by the compile-time LoadInliner pass; anything left is nested
				// or non-literal, which the compiled runtime reader cannot execute
				// (unlike a runtime load).
				// Same for the dist directives: which dists ql:quickload downloads
				// from is decided while the LoadInliner splices, so a nested/computed
				// one has nothing left to configure by the time the program runs.
				case LispNames.REQUIRE, LispNames.PROVIDE, LispNames.ASDF_DEFSYSTEM, LispNames.QL_DIST_INSTALL_DIST,
						LispNames.QL_UPDATE_DIST ->
					throw new UnsupportedOperationException(
							sym.name() + " is only supported as a literal top-level form on the compile path");
				// A nested/computed load reached at run time: the CLI pipeline splices
				// the asdf runtime (AsdfRuntimeLibrary) whenever these names occur, so
				// the calls resolve to its defuns -- an already-spliced system is a nil
				// no-op, anything else the call-time error. The stub below serves only
				// a direct backend compile with no LoadInliner in front (a test seam),
				// where nothing spliced the defuns.
				case LispNames.ASDF_LOAD_SYSTEM, LispNames.QL_QUICKLOAD -> {
					if (ctx.functions.containsKey(sym.name())) {
						JvmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.callTimeUnsupportedStub(
								sym.name() + " cannot load a system at run time on the compiled backends"
										+ " (systems are spliced at compile time)"),
								ctx, className);
					}
				}
				// asdf:find-system / asdf:test-system: real defuns whenever the asdf
				// runtime was spliced (the CLI pipeline splices it on any reference).
				// Without the splice, find-system keeps the historical nil lowering
				// ("no such system" after evaluating its arguments) so a direct backend
				// compile of the probe shape still builds.
				case LispNames.ASDF_FIND_SYSTEM -> {
					if (ctx.functions.containsKey(sym.name())) {
						JvmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandRuntimeFindSystem(cons), ctx, className);
					}
				}
				case LispNames.FUNCALL -> {
					// A funcall of a fusion-eligible flet lambda substitutes its body
					// into a fused tree (.kb/jvm-int-fusion.md); anything else takes
					// the ordinary dispatch.
					if (!JvmIntFusionCompiler.tryCompileLocalCall(cons, ctx, className)) {
						JvmFunctionCallCompiler.compileFuncall(cons, ctx, className);
					}
				}
				case LispNames.FUNCTION -> JvmFunctionFormCompiler.compile(cons, ctx, className);
				case LispNames.SYMBOL_FUNCTION -> JvmFunctionFormCompiler.compileSymbolFunction(cons, ctx, className);
				case LispNames.MAP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMap(cons, ctx.usesArrays), ctx, className);
				case LispNames.MAPCAR -> JvmMapcarCompiler.compile(cons, ctx, className);
				case LispNames.MAPC -> JvmMapcCompiler.compile(cons, ctx, className);
				case LispNames.MAPCAN -> JvmMapcanCompiler.compile(cons, ctx, className);
				case LispNames.REDUCE -> {
					// :from-end/:key lower to a plain reduce first; then a string
					// sequence
					// folds over a list of its characters (the wrapper is null when the
					// call
					// is already the inner list fold).
					LispVal loweredReduce = LispMacroExpander.expandReduce(cons);
					if (loweredReduce != null) {
						JvmExprCompiler.compileExpr(loweredReduce, ctx, className);
					}
					else {
						LispVal wrappedReduce = LispMacroExpander.wrapReduceForStringSeq(cons);
						if (wrappedReduce != null) {
							JvmExprCompiler.compileExpr(wrappedReduce, ctx, className);
						}
						else {
							JvmReduceCompiler.compile(cons, ctx, className);
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
						JvmExprCompiler.compileExpr(keyedSort, ctx, className);
					}
					else {
						LispVal wrappedSort = LispMacroExpander.wrapSortForStringSeq(cons, ctx.usesArrays);
						if (wrappedSort != null) {
							JvmExprCompiler.compileExpr(wrappedSort, ctx, className);
						}
						else {
							JvmSortCompiler.compile(cons, ctx, className);
						}
					}
				}
				case LispNames.STABLE_SORT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandStableSort(cons), ctx, className);
				case LispNames.COPY_SEQ ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCopySeq(cons), ctx, className);
				case LispNames.VECTORP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandVectorp(cons), ctx, className);
				case LispNames.ARRAYP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandArrayp(cons), ctx, className);
				case LispNames.APPLY -> JvmApplyCompiler.compile(cons, ctx, className);
				case LispNames.NULL -> JvmNullPredCompiler.compile(cons, ctx, className);
				case LispNames.ATOM -> JvmAtomCompiler.compile(cons, ctx, className);
				case LispNames.NUMBERP -> JvmNumberpCompiler.compile(cons, ctx, className);
				case LispNames.INTEGERP -> JvmIntegerpCompiler.compile(cons, ctx, className);
				case LispNames.FLOATP -> JvmFloatpCompiler.compile(cons, ctx, className);
				case LispNames.RATIONALP -> JvmRationalpCompiler.compile(cons, ctx, className);
				case LispNames.NUMERATOR -> JvmRatioAccessorCompiler.compileNumerator(cons, ctx, className);
				case LispNames.DENOMINATOR -> JvmRatioAccessorCompiler.compileDenominator(cons, ctx, className);
				case LispNames.SYMBOLP -> JvmSymbolpCompiler.compile(cons, ctx, className);
				case LispNames.STRINGP -> JvmStringpCompiler.compile(cons, ctx, className);
				case LispNames.LISTP -> JvmListpCompiler.compile(cons, ctx, className);
				case LispNames.CONSP -> JvmConspCompiler.compile(cons, ctx, className);
				case LispNames.OBJ_NEW -> JvmObjCompiler.compileNew(cons, ctx, className);
				case LispNames.OBJ_REF -> JvmObjCompiler.compileRef(cons, ctx, className);
				case LispNames.OBJ_SET -> JvmObjCompiler.compileSet(cons, ctx, className);
				case LispNames.OBJ_BECOME -> JvmObjCompiler.compileBecome(cons, ctx, className);
				case LispNames.OBJ_IS -> JvmObjCompiler.compileIs(cons, ctx, className);
				case LispNames.OBJ_TAG -> JvmObjCompiler.compileTag(cons, ctx, className);
				case LispNames.OBJ_P -> JvmObjCompiler.compileP(cons, ctx, className);
				case LispNames.OBJ_SLOTS -> JvmObjCompiler.compileSlots(cons, ctx, className);
				case LispNames.FUNCTIONP -> JvmFunctionpCompiler.compile(cons, ctx, className);
				case LispNames.ARRAYP_INTERNAL -> JvmArraypCompiler.compile(cons, ctx, className);
				case LispNames.KEYWORDP -> JvmKeywordpCompiler.compile(cons, ctx, className);
				case LispNames.FLOAT ->
					JvmFloatConvCompiler.compile(LispMacroExpander.normalizeFloatCall(cons), ctx, className);
				case LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING, LispNames.ROUND -> {
					// (floor a b) -> (floor (/ a b)); the one-argument form compiles
					// natively.
					LispVal withDivisor = LispMacroExpander.expandFloorFamilyDivisor(cons);
					if (withDivisor != null) {
						JvmExprCompiler.compileExpr(withDivisor, ctx, className);
					}
					else {
						switch (sym.name()) {
							case LispNames.TRUNCATE -> JvmIntConvCompiler.compileTruncate(cons, ctx, className);
							case LispNames.FLOOR -> JvmIntConvCompiler.compileFloor(cons, ctx, className);
							case LispNames.CEILING -> JvmIntConvCompiler.compileCeiling(cons, ctx, className);
							default -> JvmIntConvCompiler.compileRound(cons, ctx, className);
						}
					}
				}
				case LispNames.COND -> JvmExprCompiler.compileExpr(LispMacroExpander.expandCond(cons), ctx, className);
				case LispNames.CASE -> JvmExprCompiler.compileExpr(LispMacroExpander.expandCase(cons), ctx, className);
				case LispNames.ECASE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandEcase(cons), ctx, className);
				case LispNames.CCASE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCcase(cons), ctx, className);
				case LispNames.ERROR -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandError(cons, ctx.closRegistry, false, ctx.restartMode), ctx, className);
				case LispNames.CERROR -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandCerror(cons, ctx.closRegistry, ctx.restartMode), ctx, className);
				case LispNames.ERROR_INTERNAL -> JvmErrorCompiler.compile(cons, ctx, className);
				case LispNames.ERROR_COND_INTERNAL -> JvmErrorCondCompiler.compile(cons, ctx, className);
				case LispNames.WARN -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandWarn(cons, ctx.closRegistry, ctx.restartMode), ctx, className);
				case LispNames.WARN_INTERNAL -> JvmWarnCompiler.compile(cons, ctx, className);
				case LispNames.SIGNAL -> JvmExprCompiler.compileExpr(
						LispMacroExpander.expandSignalMacro(cons, ctx.closRegistry, ctx.restartMode), ctx, className);
				case LispNames.SIGNAL_COND_INTERNAL -> JvmSignalCondCompiler.compile(cons, ctx, className);
				case LispNames.HANDLER_CASE -> JvmHandlerCaseCompiler.compile(cons, ctx, className);
				case LispNames.HANDLER_BIND -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandHandlerBind(cons, ctx.closRegistry), ctx, className);
				case LispNames.IGNORE_ERRORS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandIgnoreErrors(cons), ctx, className);
				case LispNames.HC_DEPTH_DEC_INTERNAL -> JvmHandlerCaseCompiler.compileDepthDec(ctx, className);
				case LispNames.HB_GUARD_INTERNAL -> JvmHandlerCaseCompiler.compileGuard(cons, ctx, className);
				case LispNames.AND -> JvmExprCompiler.compileExpr(LispMacroExpander.expandAnd(cons), ctx, className);
				case LispNames.OR -> JvmExprCompiler.compileExpr(LispMacroExpander.expandOr(cons), ctx, className);
				case LispNames.WHEN -> JvmExprCompiler.compileExpr(LispMacroExpander.expandWhen(cons), ctx, className);
				case LispNames.DOTIMES -> {
					// A numeric loop over packed float arrays compiles to a guarded
					// primitive loop first; anything outside that subset takes the
					// ordinary expansion (.kb/jvm-typed-loops.md). Either way the loop
					// head has to sit at operand stack depth 0 -- the typed emitter
					// writes its backedge itself, and the expansion's `while` head is
					// one too -- so both run inside the spill
					// (JvmEmitHelper.inLoopScope).
					JvmEmitHelper.inLoopScope(ctx, () -> {
						if (!JvmTypedLoopCompiler.tryCompile(cons, ctx, className)) {
							JvmExprCompiler.compileExpr(LispMacroExpander.expandDotimes(cons), ctx, className);
						}
					});
				}
				case LispNames.PROG1 ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandProg1(cons), ctx, className);
				case LispNames.TIME -> JvmExprCompiler.compileExpr(LispMacroExpander.expandTime(cons), ctx, className);
				case LispNames.UNLESS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandUnless(cons), ctx, className);
				case LispNames.ONE_PLUS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandOnePlus(cons), ctx, className);
				case LispNames.ONE_MINUS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandOneMinus(cons), ctx, className);
				case LispNames.ZEROP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandZerop(cons), ctx, className);
				case LispNames.PLUSP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPlusp(cons), ctx, className);
				case LispNames.MINUSP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMinusp(cons), ctx, className);
				case LispNames.EVENP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandEvenp(cons), ctx, className);
				case LispNames.ODDP -> JvmExprCompiler.compileExpr(LispMacroExpander.expandOddp(cons), ctx, className);
				case LispNames.ABS -> JvmAbsCompiler.compile(cons, ctx, className);
				case LispNames.MIN -> {
					if (isBinaryCall(cons)) {
						JvmMinCompiler.compile(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx, className);
					}
				}
				case LispNames.MAX -> {
					if (isBinaryCall(cons)) {
						JvmMaxCompiler.compile(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx, className);
					}
				}
				case LispNames.SQRT, LispNames.EXP, LispNames.LOG, LispNames.SIN, LispNames.COS, LispNames.TAN,
						LispNames.ASIN, LispNames.ACOS, LispNames.ATAN, LispNames.SINH, LispNames.COSH,
						LispNames.TANH ->
					JvmMathFnCompiler.compile(cons, ctx, className, sym.name());
				case LispNames.RANDOM -> {
					if (cons.toList().size() == 3) {
						// The optional random-state argument: normalized away (state
						// evaluated for effect, backend entropy draws).
						JvmExprCompiler.compileExpr(LispMacroExpander.expandRandomWithState(cons), ctx, className);
					}
					else {
						JvmRandomCompiler.compile(cons, ctx, className);
					}
				}
				case LispNames.MAKE_RANDOM_STATE -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandConstantResult(cons, LispNil.INSTANCE), ctx, className);
				case LispNames.GET_UNIVERSAL_TIME, LispNames.GET_INTERNAL_REAL_TIME, LispNames.GET_INTERNAL_RUN_TIME ->
					JvmTimeCompiler.compile(cons, ctx, sym.name());
				case LispNames.ISQRT -> JvmIsqrtCompiler.compile(cons, ctx, className);
				case LispNames.EXPT -> JvmExptCompiler.compile(cons, ctx, className);
				case LispNames.GCD -> {
					if (isBinaryCall(cons)) {
						JvmGcdCompiler.compile(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx, className);
					}
				}
				case LispNames.LCM -> {
					if (isBinaryCall(cons)) {
						JvmLcmCompiler.compile(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx, className);
					}
				}
				case LispNames.SIGNUM -> JvmSignumCompiler.compile(cons, ctx, className);
				case LispNames.LOGAND -> {
					if (JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						// fused (.kb/jvm-int-fusion.md)
					}
					else if (isBinaryCall(cons)) {
						JvmBitwiseCompiler.compileLogand(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx, className);
					}
				}
				case LispNames.LOGIOR -> {
					if (JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						// fused (.kb/jvm-int-fusion.md)
					}
					else if (isBinaryCall(cons)) {
						JvmBitwiseCompiler.compileLogior(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx, className);
					}
				}
				case LispNames.LOGXOR -> {
					if (JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						// fused (.kb/jvm-int-fusion.md)
					}
					else if (isBinaryCall(cons)) {
						JvmBitwiseCompiler.compileLogxor(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx, className);
					}
				}
				case LispNames.LOGNOT -> {
					if (!JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						JvmBitwiseCompiler.compileLognot(cons, ctx, className);
					}
				}
				case LispNames.ASH -> {
					if (!JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						JvmBitwiseCompiler.compileAsh(cons, ctx, className);
					}
				}
				case LispNames.INTEGER_LENGTH -> JvmBitwiseCompiler.compileIntegerLength(cons, ctx, className);
				case LispNames.LOGBITP -> JvmBitwiseCompiler.compileLogbitp(cons, ctx, className);
				case LispNames.LIST_STAR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandListStar(cons), ctx, className);
				case LispNames.ACONS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandAcons(cons), ctx, className);
				case LispNames.ENDP -> JvmExprCompiler.compileExpr(LispMacroExpander.expandEndp(cons), ctx, className);
				case LispNames.ELT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandElt(cons, ctx.usesArrays), ctx, className);
				case LispNames.RASSOC ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRassoc(cons), ctx, className);
				case LispNames.PAIRLIS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPairlis(cons), ctx, className);
				case LispNames.COPY_ALIST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCopyAlist(cons), ctx, className);
				case LispNames.REVAPPEND ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRevappend(cons), ctx, className);
				case LispNames.NRECONC ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNreconc(cons), ctx, className);
				case LispNames.MAPLIST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMaplist(cons), ctx, className);
				case LispNames.MAPCON ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMapcon(cons), ctx, className);
				case LispNames.MAPL -> JvmExprCompiler.compileExpr(LispMacroExpander.expandMapl(cons), ctx, className);
				case LispNames.NOTANY ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNotany(cons), ctx, className);
				case LispNames.NOTEVERY ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNotevery(cons), ctx, className);
				case LispNames.PROG2 ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandProg2(cons), ctx, className);
				case LispNames.PSETQ ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPsetq(cons), ctx, className);
				case LispNames.PSETF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPsetf(cons), ctx, className);
				case LispNames.TYPECASE -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandTypecase(cons, ctx.closRegistry), ctx, className);
				case LispNames.ETYPECASE -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandEtypecase(cons, ctx.closRegistry), ctx, className);
				case LispNames.CTYPECASE -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandCtypecase(cons, ctx.closRegistry), ctx, className);
				case LispNames.TYPEP -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandTypep(cons, ctx.closRegistry, false), ctx, className);
				case LispNames.SUBTYPEP -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandSubtypep(cons, ctx.closRegistry), ctx, className);
				case LispNames.CHECK_TYPE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCheckType(cons), ctx, className);
				case LispNames.ASSERT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandAssert(cons), ctx, className);
				case LispNames.DECLARE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDeclare(cons), ctx, className);
				case LispNames.DECLAIM ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDeclaim(cons), ctx, className);
				case LispNames.PROCLAIM ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandProclaim(cons), ctx, className);
				case LispNames.THE -> JvmExprCompiler.compileExpr(LispMacroExpander.expandThe(cons), ctx, className);
				case LispNames.EVAL_WHEN ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandEvalWhen(cons), ctx, className);
				case LispNames.WITH_COMPILATION_UNIT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithCompilationUnit(cons), ctx, className);
				case LispNames.LOCALLY ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandLocally(cons), ctx, className);
				case LispNames.WITH_STANDARD_IO_SYNTAX ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithStandardIoSyntax(cons), ctx, className);
				case LispNames.WRITE_CHAR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWriteChar(cons), ctx, className);
				case LispNames.FLET -> JvmExprCompiler.compileExpr(LispMacroExpander.expandFlet(cons), ctx, className);
				case LispNames.LABELS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandLabels(cons), ctx, className);
				case LispNames.VALUES ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandValues(cons), ctx, className);
				case LispNames.MULTIPLE_VALUE_BIND ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMultipleValueBind(cons), ctx, className);
				case LispNames.MULTIPLE_VALUE_LIST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMultipleValueList(cons), ctx, className);
				case LispNames.MULTIPLE_VALUE_CALL ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMultipleValueCall(cons), ctx, className);
				case LispNames.NTH_VALUE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNthValue(cons), ctx, className);
				case LispNames.MULTIPLE_VALUE_SETQ ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMultipleValueSetq(cons), ctx, className);
				case LispNames.MULTIPLE_VALUE_PROG1 ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMultipleValueProg1(cons), ctx, className);
				case LispNames.ROTATEF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRotatef(cons), ctx, className);
				case LispNames.SHIFTF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandShiftf(cons), ctx, className);
				case LispNames.LOAD_TIME_VALUE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandLoadTimeValue(cons), ctx, className);
				case LispNames.BYTE -> JvmExprCompiler.compileExpr(LispMacroExpander.expandByte(cons), ctx, className);
				case LispNames.BYTE_SIZE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandByteSize(cons), ctx, className);
				case LispNames.BYTE_POSITION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandBytePosition(cons), ctx, className);
				case LispNames.LDB -> JvmExprCompiler.compileExpr(LispMacroExpander.expandLdb(cons), ctx, className);
				case LispNames.DPB -> JvmExprCompiler.compileExpr(LispMacroExpander.expandDpb(cons), ctx, className);
				case LispNames.LOGANDC1, LispNames.LOGANDC2, LispNames.LOGORC1, LispNames.LOGORC2 ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandLogComplement(cons), ctx, className);
				case LispNames.LOGTEST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandLogtest(cons), ctx, className);
				case LispNames.MAKE_SEQUENCE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMakeSequence(cons), ctx, className);
				case LispNames.DESTRUCTURING_BIND ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDestructuringBind(cons), ctx, className);
				case LispNames.FIRST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandFirst(cons), ctx, className);
				case LispNames.REST -> JvmExprCompiler.compileExpr(LispMacroExpander.expandRest(cons), ctx, className);
				case LispNames.NTH -> JvmExprCompiler.compileExpr(LispMacroExpander.expandNth(cons), ctx, className);
				case LispNames.SECOND ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandSecond(cons), ctx, className);
				case LispNames.THIRD ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandThird(cons), ctx, className);
				case LispNames.FOURTH ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandFourth(cons), ctx, className);
				case LispNames.NOT -> JvmNullPredCompiler.compile(cons, ctx, className);
				default -> {
					// The ordinary call path resolves the program's own defun, so
					// nothing was overridden here.
					redefinedClFunction = false;
					if (LispNames.isCarCdrComposition(sym.name())) {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandCarCdrComposition(cons), ctx, className);
					}
					// A ROOT-position call to a fusion-inlinable defun ((mod32+ a b) as
					// a setf value or argument) fuses like a call inside a tree would:
					// classify substitutes the body, so the site pays one outlined call
					// instead of a boxed call whose body re-guards its own arguments
					// (.kb/jvm-int-fusion.md). Anything else declines with nothing
					// emitted and takes the ordinary call path.
					else if (ctx.inlinableDefuns.containsKey(sym.name())
							&& JvmIntFusionCompiler.tryCompile(cons, ctx, className)) {
						// fused
					}
					else {
						JvmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx, className);
					}
				}
			}
			if (redefinedClFunction) {
				warnClRedefinition(sym.name(), cons, ctx);
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& LispNames.LAMBDA.equals(headSym.name())) {
			JvmLambdaCompiler.compileCall(headCons, cons, ctx, className);
		}
		else {
			JvmFunctionCallCompiler.compileGeneralIndirect(cons, ctx, className);
		}
	}

	/**
	 * Reports, ONCE per compile attempt and per name, that an operator interception
	 * overrode the program's own {@code defun} of a {@code cl} function. The first call
	 * site names the position, and the rest of them stay quiet -- a program that
	 * redefines {@code length} and then calls it fifty times has one thing wrong with it,
	 * not fifty.
	 */
	private static void warnClRedefinition(String name, LispCons cons, JvmLispCompiler.Ctx ctx) {
		if (ctx.warnedClRedefinitions.add(name)) {
			CompileWarnings.warn(SourceProvenance.prefix(cons) + ClRedefinitionWarnings.message(name));
		}
	}

	/**
	 * Compiles a numeric comparison. The binary form uses the dedicated comparison
	 * compiler; any other arity is desugared into nested binary comparisons.
	 */
	private static void compileComparison(LispCons cons, JvmLispCompiler.Ctx ctx, String className, int branchOpcode) {
		if (isBinaryCall(cons)) {
			// A comparison whose side is an integer tree fuses into one outlined raw
			// long compare (.kb/jvm-int-fusion.md); the both-plain shape keeps the
			// generic emission.
			if (!JvmIntFusionCompiler.tryCompileCompareValue(cons, ctx, className, branchOpcode)) {
				JvmComparisonCompiler.compile(cons, ctx, branchOpcode, className);
			}
		}
		else {
			JvmExprCompiler.compileExpr(LispMacroExpander.expandComparison(cons), ctx, className);
		}
	}

	/**
	 * Returns whether the call has exactly two arguments (operator plus two operands).
	 */
	private static boolean isBinaryCall(LispCons cons) {
		return cons.toList().size() == 3;
	}

	/**
	 * Compiles one of the three mutex primitives onto its {@link JvmMutexRuntimeBuilder}
	 * helper. {@code make-mutex} takes no arguments and yields a fresh
	 * {@code ReentrantLock}; acquire and release take the handle and return it, so a
	 * value-position call is still well typed.
	 */
	private static void compileMutex(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int arity = LispNames.MAKE_MUTEX.equals(member) ? 0 : 1;
		if (args.size() != arity + 1) {
			throw new UnsupportedOperationException(
					"rontolisp:" + member + " expects " + arity + " argument(s), got " + (args.size() - 1));
		}
		String method = switch (member) {
			case LispNames.MAKE_MUTEX -> JvmMutexRuntimeBuilder.NEW_METHOD;
			case LispNames.MUTEX_ACQUIRE -> JvmMutexRuntimeBuilder.ACQUIRE_METHOD;
			default -> JvmMutexRuntimeBuilder.RELEASE_METHOD;
		};
		String desc = arity == 0 ? JvmMutexRuntimeBuilder.NEW_DESC : JvmMutexRuntimeBuilder.UNARY_DESC;
		if (arity == 1) {
			compileExpr(args.get(1), ctx, className);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.cp
			.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
					ctx.cp.addNameAndType(ctx.cp.addUtf8(method), ctx.cp.addUtf8(desc)))
			.index());
	}

	/**
	 * Compiles one of the five thread primitives onto its {@link JvmThreadRuntimeBuilder}
	 * helper. {@code make-thread} takes the function and an optional bindings alist
	 * (compiled as nil when absent); the other four take the handle (or, for
	 * {@code threadp}, any value).
	 */
	private static void compileThread(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		String method;
		String desc;
		if (LispNames.CURRENT_THREAD.equals(member)) {
			if (args.size() != 1) {
				throw new UnsupportedOperationException(
						"rontolisp:" + member + " expects no arguments, got " + (args.size() - 1));
			}
			method = JvmThreadRuntimeBuilder.CURRENT_METHOD;
			desc = JvmThreadRuntimeBuilder.CURRENT_DESC;
		}
		else if (LispNames.MAKE_THREAD.equals(member)) {
			if (args.size() < 2 || args.size() > 3) {
				throw new UnsupportedOperationException(
						"rontolisp:" + member + " expects 1 or 2 argument(s), got " + (args.size() - 1));
			}
			compileExpr(args.get(1), ctx, className);
			if (args.size() == 3) {
				compileExpr(args.get(2), ctx, className);
			}
			else {
				ctx.emit(Opcode.ACONST_NULL);
			}
			method = JvmThreadRuntimeBuilder.SPAWN_METHOD;
			desc = JvmThreadRuntimeBuilder.SPAWN_DESC;
		}
		else {
			if (args.size() != 2) {
				throw new UnsupportedOperationException(
						"rontolisp:" + member + " expects 1 argument(s), got " + (args.size() - 1));
			}
			compileExpr(args.get(1), ctx, className);
			method = switch (member) {
				case LispNames.JOIN_THREAD -> JvmThreadRuntimeBuilder.JOIN_METHOD;
				case LispNames.THREADP -> JvmThreadRuntimeBuilder.THREADP_METHOD;
				case LispNames.THREAD_ALIVE_P -> JvmThreadRuntimeBuilder.ALIVE_METHOD;
				default -> JvmThreadRuntimeBuilder.DESTROY_METHOD;
			};
			desc = JvmThreadRuntimeBuilder.UNARY_DESC;
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.cp
			.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
					ctx.cp.addNameAndType(ctx.cp.addUtf8(method), ctx.cp.addUtf8(desc)))
			.index());
	}

	/**
	 * Wraps a path-taking builtin's path argument in the pathname-to-namestring unwrap
	 * ({@link LispMacroExpander#coercePathArg}) -- but ONLY when this compilation's
	 * instance gate is on. With the gate off no pathname value can exist, and skipping
	 * the wrap keeps every instance-free program byte-identical to a build that never
	 * knew about pathnames (the {@code .kb/instance-syntax.md} rule).
	 */
	private static LispCons coercePathArgWhenGated(LispCons cons, int argIndex, JvmLispCompiler.Ctx ctx) {
		return ctx.mayUseInstances ? LispMacroExpander.coercePathArg(cons, argIndex) : cons;
	}

}
