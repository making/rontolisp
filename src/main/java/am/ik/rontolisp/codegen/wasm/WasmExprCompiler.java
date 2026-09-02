package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.UiopExports;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.ClRedefinitionWarnings;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.ConcatenateForms;
import am.ik.rontolisp.compiler.MutableStringProducers;
import am.ik.rontolisp.compiler.StreamDesignators;
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

	/**
	 * Compiles a printing operator, routed through {@code print-object} when the program
	 * defines a method on it and through {@code %print-cased} when it mentions
	 * {@code *print-case*}; compiled as it always was otherwise -- the gate that keeps
	 * every module using neither byte-identical.
	 */
	private static void compilePrintOperator(LispCons cons, WasmLispCompiler.Ctx ctx, Runnable plain) {
		LispVal hooked = LispMacroExpander.expandPrintObjectHook(cons, ctx.closRegistry, ctx.printCase);
		if (hooked == null) {
			plain.run();
			return;
		}
		compileExpr(hooked, ctx);
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
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_RATIO);
			}
			case LispDouble d -> {
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(d.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			}
			case LispString s -> WasmEmitHelper.compileStringLiteral(s.literal(), ctx);
			case am.ik.rontolisp.LispChar c -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(c.codePoint());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
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
			case am.ik.rontolisp.LispIntVector iv -> WasmQuoteCompiler.compileIntVectorLiteral(iv, ctx);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	/**
	 * Compiles a statement-position expression (its value is discarded): a
	 * {@code setf}/{@code %aset} store into a packed integer vector skips materializing
	 * the value-as-stored entirely (the hot-loop store allocates nothing); anything else
	 * compiles normally and DROPs. The caller must NOT emit its own DROP.
	 */
	static void compileForEffect(LispVal expr, WasmLispCompiler.Ctx ctx) {
		// A self-evaluating literal in statement position (a defun docstring, a bare
		// number in a progn) has no effect and no consumer: emit nothing. A string
		// literal otherwise BUILDS its runtime string on every evaluation only to
		// drop it -- ironclad's docstring'd hot loops paid a _str_build per call.
		if (ctx.asyncResume == null && (expr instanceof am.ik.rontolisp.LispString || expr instanceof LispInteger
				|| expr instanceof am.ik.rontolisp.LispDouble || expr instanceof am.ik.rontolisp.LispChar
				|| expr instanceof am.ik.rontolisp.LispBigInteger || expr instanceof am.ik.rontolisp.LispRatio)) {
			return;
		}
		if (ctx.asyncResume == null && expr instanceof LispCons cons && cons.isProperList()
				&& cons.car() instanceof LispSymbol sym) {
			if (LispNames.SETF.equals(sym.name())) {
				LispCons knownArrayStore = WasmArrayCompiler.nonStringArefStore(cons, ctx);
				if (knownArrayStore != null) {
					// A variable place whose array kind is pinned down cannot be a
					// string, so the expansion's stringp/schar-set branch is dead --
					// compile the %aset (itself single-arm) directly.
					WasmArrayCompiler.compileAset(knownArrayStore, ctx, false);
					return;
				}
				compileForEffect(LispMacroExpander.expandSetf(cons, ctx.structAccessors, ctx.closRegistry), ctx);
				return;
			}
			if (LispNames.ASET.equals(sym.name())) {
				WasmArrayCompiler.compileAset(cons, ctx, false);
				return;
			}
			if (LispNames.SETQ.equals(sym.name())) {
				WasmSetqCompiler.compileForEffect(cons, ctx);
				return;
			}
			// Statement position propagates through the sequencing forms, so a
			// tail-position setq of an unboxed local inside them stops re-reading the
			// value it just stored only to have it dropped here (ironclad's SHA-256
			// rounds end in (let ((x ..)) (setf d .. h ..)) and paid a _ub_read per
			// round for the discarded let value).
			if (LispNames.LET.equals(sym.name())) {
				WasmLetCompiler.compile(cons, ctx, true);
				return;
			}
			if (LispNames.LET_STAR.equals(sym.name())) {
				compileForEffect(LispMacroExpander.expandLetStar(cons), ctx);
				return;
			}
			if (LispNames.PROGN.equals(sym.name())) {
				WasmPrognCompiler.compileForEffect(cons, ctx);
				return;
			}
		}
		compileExpr(expr, ctx);
		ctx.writer.write(Instruction.DROP);
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
			if (WasmDynVars.handles(ctx, name)) {
				WasmDynVars.emitRead(ctx, name, java.util.Objects.requireNonNull(ctx.globalIndices.get(name)));
				return;
			}
			ctx.writer.write(Instruction.GET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(java.util.Objects.requireNonNull(ctx.globalIndices.get(name)));
			return;
		}
		// An unboxed (dual-representation) local: box on demand (an i31 for the fixnum
		// range -- allocation-free), or hand out the shadow.
		WasmIntFusionCompiler.RawLocal raw = ctx.rawLocals.get(name);
		if (raw != null) {
			WasmIntFusionCompiler.emitRawLocalBoxedRead(raw, ctx);
			return;
		}
		// Check local variables
		Integer slot = ctx.locals.get(name);
		if (slot != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			if (ctx.boxedVars.contains(name)) {
				// Unbox from cell
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
				ctx.writer.writeUnsignedLeb128(0);
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
		// global. --reentrant: a dynamically-bound special reads DYNAMIC-FIRST through
		// the per-call task record (WasmDynVars), the global being only its default.
		Integer globalIndex = ctx.globalIndices.get(name);
		if (globalIndex != null) {
			if (WasmDynVars.handles(ctx, name)) {
				WasmDynVars.emitRead(ctx, name, globalIndex);
				return;
			}
			ctx.writer.write(Instruction.GET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(globalIndex);
			return;
		}
		if (ctx.dynamic) {
			WasmDynamicCallCompiler.compileVarRef(name, ctx);
			return;
		}
		if (LispNames.STANDARD_OUTPUT_VAR.equals(name) || LispNames.STANDARD_INPUT_VAR.equals(name)) {
			// The standard stream variables hold the designator t (the interpreter's
			// permanent value; the program never binds this one, so print/read-family
			// redirection through it does not exist here).
			compileExpr(LispTrue.INSTANCE, ctx);
			return;
		}
		if (LispNames.ERROR_OUTPUT_VAR.equals(name)) {
			// *error-output* is the process standard ERROR, which t does not name: it is
			// the stream VALUE over the reserved handle 2 -- the fd the write helpers
			// already send stderr to (the program never binds this one, so warn's
			// redirect does not exist here). Mentioning the variable is what turns the
			// stream-value gate on (LispMacroExpander.mayCreateStreamValues), so the
			// constructor form always compiles here.
			compileExpr(StreamDesignators.standardError(), ctx);
			return;
		}
		// Lisp-2: a bare symbol is a variable reference only; functions must be
		// referenced via (function name) / #'name.
		throw new UnsupportedOperationException("Cannot compile symbol: " + name);
	}

	private static void compileCons(LispCons cons, WasmLispCompiler.Ctx ctx) {
		try {
			compileConsLocated(cons, ctx);
		}
		catch (RuntimeException ex) {
			// The innermost cons that came from source names the position; the exception
			// itself is rethrown untouched, since passes above catch it by type.
			throw SourceProvenance.noteFailure(cons, ex);
		}
	}

	private static void compileConsLocated(LispCons cons, WasmLispCompiler.Ctx ctx) {
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
				if (LispNames.FETCH.equals(qn.member())) {
					if (ctx.component || ctx.hostFetch) {
						// Under --component, fetch is the spliced http.lisp defun (over
						// wit-imported wasi:http) on BOTH serve and non-serve -- a served
						// handler that fetches reaches http.lisp's fetch half too. Under
						// --no-wasi --host-fetch it is the spliced HostFetchLibrary
						// defun over the injected env.fetch host import. Either way, run
						// the compile-time checks a defun cannot -- arity and a literal
						// unsupported :method -- then fall through to the ordinary call
						// path, which resolves the defun. Preview 1 keeps the special
						// form (the component-only compile error).
						WasmFetchCompiler.validate(cons);
					}
					else {
						WasmFetchCompiler.reject(ctx.noWasi);
					}
				}
				if (LispNames.HTTP_HANDLER.equals(qn.member())) {
					// In component mode HttpLibrary.process rewrites http-handler into
					// the %serve-handle export before compilation, so it never reaches
					// here; reaching here means Preview 1 (no --component), which has
					// no incoming TCP by design (.kb/tcp-sockets.md). A CALL-TIME error
					// (the socket policy), not a compile error: the call may be
					// dead code -- the clack-handler-rontolisp shim's run defun is
					// compiled whenever clack is loaded, reached only by an actual
					// clackup.
					WasmExprCompiler.compileExpr(LispMacroExpander.callTimeUnsupportedStub(LispNames.HTTP_HANDLER
							+ " requires --component (it compiles to a wasi:http/incoming-handler component "
							+ "runnable with `wasmtime serve`)"), ctx);
					return;
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
						|| LispNames.STREAM_NEW_INTERNAL.equals(qn.member())
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
						|| LispNames.READ_SEQUENCE_RAW_INTERNAL.equals(qn.member())
						|| LispNames.WRITE_SEQUENCE_RAW_INTERNAL.equals(qn.member())
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
					// The socket rewrite maps a 0/1-argument (read-char s) to
					// (%io-read-char s), whose non-socket arm lands here -- so the typed
					// end-of-file lowering has to apply under the alias too, or a
					// component would keep the old uncatchable TRAP for exactly the
					// programs that splice sockets.lisp.
					LispVal typedRaw = switch (qn.member()) {
						case LispNames.READ_CHAR_RAW_INTERNAL, LispNames.READ_BYTE_RAW_INTERNAL ->
							LispMacroExpander.expandReadEofSignal(cons, true);
						case LispNames.READ_LINE_RAW_INTERNAL -> LispMacroExpander.expandReadEofSignal(cons, false);
						default -> null;
					};
					if (typedRaw != null) {
						WasmExprCompiler.compileExpr(typedRaw, ctx);
						return;
					}
					switch (qn.member()) {
						case LispNames.READ_LINE_RAW_INTERNAL -> {
							// The %io-read-line fallback a component's socket splice
							// routes a non-socket read-line through: wrap like the
							// public case, so a component's read-line result carries
							// the same identity as Preview 1's. The eof-value shape
							// takes the same detour, so only the LINE is wrapped.
							LispVal compatRaw = LispMacroExpander.expandReadLineCompat(cons);
							if (compatRaw != null) {
								WasmExprCompiler.compileExpr(compatRaw, ctx);
							}
							else {
								WasmReadLineCompiler.compile(cons, ctx);
								WasmEmitHelper.emitToMutStrCall(ctx);
							}
						}
						case LispNames.READ_CHAR_RAW_INTERNAL -> WasmReadCharCompiler.compile(cons, ctx);
						case LispNames.READ_BYTE_RAW_INTERNAL -> WasmReadByteCompiler.compile(cons, ctx);
						case LispNames.WRITE_LINE_RAW_INTERNAL -> WasmWriteLineCompiler.compile(cons, ctx);
						case LispNames.WRITE_BYTE_RAW_INTERNAL -> WasmWriteByteCompiler.compile(cons, ctx);
						case LispNames.WRITE_STRING_RAW_INTERNAL ->
							WasmWriteStringCompiler.compileWriteString(cons, ctx);
						case LispNames.READ_SEQUENCE_RAW_INTERNAL ->
							WasmExprCompiler.compileExpr(LispMacroExpander.expandReadSequence(cons), ctx);
						case LispNames.WRITE_SEQUENCE_RAW_INTERNAL ->
							WasmExprCompiler.compileExpr(LispMacroExpander.expandWriteSequence(cons), ctx);
						// The designator resolution has to apply under the alias too:
						// the socket rewrite maps (close s) to (%io-close s), whose
						// non-socket arm lands here, so without it a component would
						// hand a synonym stream or an open-stream VALUE to the
						// handle-typed close and TRAP. The read/write aliases need
						// nothing -- they share the compilers whose designator seam
						// already resolves it.
						default -> {
							if (ctx.usesSynonymStreams || ctx.usesStreamValues) {
								WasmExprCompiler.compileExpr(LispMacroExpander.expandCloseOverStream(cons,
										ctx.usesSynonymStreams, ctx.functions.containsKey(LispNames.STREAM_TARGET)),
										ctx);
							}
							else {
								WasmCloseCompiler.compile(cons, ctx);
							}
						}
					}
					return;
				}
				if (LispNames.STR_BYTE_LENGTH_INTERNAL.equals(qn.member())
						|| LispNames.STR_BYTE_REF_INTERNAL.equals(qn.member())
						|| LispNames.STR_FROM_BYTE_INTERNAL.equals(qn.member())) {
					// Byte-level string access for sockets.lisp's chunk bookkeeping: a
					// socket chunk's BYTES are the wire truth, and the character
					// accessors
					// UTF-8-decode them (component-only; the names exist only
					// post-splice).
					WasmStrByteCompiler.compile(qn.member(), cons, ctx);
					return;
				}
				if (LispNames.OCTETS_TO_STRING_STRICT_INTERNAL.equals(qn.member())) {
					// The STRICT half of the prelude's lenient octet decoder: a packed
					// (unsigned-byte 8) vector validated as UTF-8 and copied into the
					// string its bytes spell, or nil when they are not valid UTF-8.
					if (cons.toList().size() != 2) {
						throw new UnsupportedOperationException(
								"rontolisp::" + qn.member() + " expects 1 argument, got " + (cons.toList().size() - 1));
					}
					WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
					ctx.writer.write(Instruction.CALL);
					ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_IV_UTF8_STR);
					return;
				}
				if (LispNames.RANDOM_BYTE_INTERNAL.equals(qn.member())) {
					if (ctx.noWasi && !ctx.hostRandom) {
						// The one place where the module-local PRNG must NOT stand in
						// for the host. `random` is an inlined SplitMix64 on EVERY wasm
						// build (CL's random is a pseudo-random draw from
						// *random-state*, so a module-local generator -- with a fixed
						// start where there is no host to seed it from -- is inside its
						// contract, .kb/random.md), but random-bytes promises
						// CRYPTOGRAPHIC entropy -- answering that from a fixed-seed
						// generator is precisely the "data the program cannot tell from
						// real" the trapping stubs exist to avoid. A call-time error, not
						// a compile error: the site may be dead code in a spliced
						// library.
						// --host-random lifts exactly this: the random_get slot then
						// forwards to a host import, so the bytes ARE the host's and
						// nothing is being passed off as something it is not.
						WasmExprCompiler
							.compileExpr(LispMacroExpander.callTimeUnsupportedStub("rontolisp:" + LispNames.RANDOM_BYTES
									+ " requires a host entropy source, which --no-wasi excludes (a --no-wasi module"
									+ " imports nothing; its `random` is a deterministic generator and must not be"
									+ " passed off as cryptographic entropy). Add --host-random to route random_get"
									+ " at a host import (env.random_get) and this works again"), ctx);
						return;
					}
					// One cryptographically strong byte: the low byte of a WASI
					// random_get draw (real host entropy in Preview 1, wasi:random
					// under --component, the env.random_get host import under
					// --no-wasi --host-random), boxed as an i31 fixnum. A host call PER
					// BYTE, deliberately -- this is the one caller `random`'s cheap
					// module-local generator may never answer.
					WasmRandomCompiler.compileRandomByte(cons, ctx);
					return;
				}
				if (LispNames.WAIT_FOR.equals(qn.member()) && !ctx.component) {
					// Under --component, wait-for is the spliced wait.lisp defun (over
					// the wit-imported wasi:clocks monotonic-clock, a pending future the
					// scheduler settles) -- the symbol falls through to the ordinary
					// call path, which resolves the defun. Preview 1 keeps the special
					// form (the compile error: no host timer), and so does a --no-wasi
					// build of either shape, whose message names the actual conflict.
					if (ctx.noWasi) {
						throw new UnsupportedOperationException("rontolisp:" + qn.member()
								+ " requires the component's wasi:clocks timer import, which --no-wasi excludes"
								+ " (a --no-wasi build imports nothing); drop --no-wasi");
					}
					throw new UnsupportedOperationException(
							"rontolisp:" + qn.member() + " requires the interpreter, the JVM backend or --component"
									+ " (no host timer is wired on Preview 1 WASM)");
				}
				if (LispNames.ASYNC_STREAMP.equals(qn.member()) || LispNames.STREAM_READ.equals(qn.member())
						|| LispNames.STREAM_CLOSE.equals(qn.member())) {
					// streamp/stream-read/stream-close operate on whichever first-class
					// stream value this module can hold: the TYPE_WASI_STREAM of an
					// asyncMode --component's fetch/serve bodies, or the TYPE_P1_STREAM a
					// Preview 1 / --no-wasi module builds over a host import.
					if (ctx.wasiStreamTypeIndex >= 0 || ctx.p1StreamTypeIndex >= 0) {
						WasmStreamCompiler.compile(qn.member(), cons, ctx);
						return;
					}
					// No stream value can EXIST here (nothing names %stream-new, and
					// there is no async block to produce one). The predicate is still
					// total -- nothing is a stream, so streamp is nil ...
					if (LispNames.ASYNC_STREAMP.equals(qn.member())) {
						WasmStreamCompiler.compileStreampConstantNil(cons, ctx);
						return;
					}
					// ... but reading or closing one really is a bug to report. The SITE
					// may still be dead code (the clack-handler-rontolisp bridge drains a
					// request body it can never receive on Preview 1), so it signals at
					// CALL time and never rejects the program (the socket policy).
					WasmExprCompiler.compileExpr(LispMacroExpander.callTimeUnsupportedStub("rontolisp:" + qn.member()
							+ " requires a stream value, and this module can hold none (they come from"
							+ " rontolisp:fetch / rontolisp:http-handler bodies on an asynchronous --component"
							+ " program, and from rontolisp::%stream-new elsewhere)"), ctx);
					return;
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
						|| LispNames.TCP_PEER_ADDRESS.equals(qn.member()) || LispNames.TCP_PEER_PORT.equals(qn.member())
						|| LispNames.TCP_SET_TIMEOUT.equals(qn.member())) && !ctx.component) {
					// Under --component the tcp built-ins are the spliced sockets.lisp
					// defuns (over the wit-imported wasi:sockets@0.3.0) -- the symbol
					// falls
					// through to the ordinary call path, which resolves the defun (the
					// wait-for pattern). Preview 1 has no host sockets; the call site
					// compiles to a call-time error (not a compile error) so a spliced
					// library whose socket layer is dead code still compiles -- s-sql
					// depends on cl-postgres but never opens a connection, and the
					// pruner cannot drop cl-postgres' defmethod-anchored socket chain.
					// A --no-wasi build (either shape) keeps the same call-time policy
					// with a message naming the actual conflict.
					WasmExprCompiler.compileExpr(LispMacroExpander.callTimeUnsupportedStub(ctx.noWasi
							? "rontolisp:" + qn.member() + " requires the component's wasi:sockets imports, which"
									+ " --no-wasi excludes (a --no-wasi build imports nothing); drop --no-wasi"
							: "rontolisp:" + qn.member() + " requires the interpreter, the JVM backend or --component"
									+ " (no host socket API is wired on Preview 1 WASM)"),
							ctx);
					return;
				}
				if (LispNames.WITH_ARENA.equals(qn.member())) {
					// A reclamation boundary for --no-gc; the wasm-GC heap is
					// garbage-collected, so the body runs as a plain progn.
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithArena(cons), ctx);
					return;
				}
				if (LispNames.WITH_MUTEX.equals(qn.member())) {
					// unwindProtect = ctx.ehMode, like the usocket with-* family --
					// except that with-mutex does NOT flip the module into EH mode (see
					// programUsesEhForm): the release it would guarantee is a no-op here.
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithMutex(cons, ctx.ehMode), ctx);
					return;
				}
				if (LispNames.MAKE_MUTEX.equals(qn.member()) || LispNames.MUTEX_ACQUIRE.equals(qn.member())
						|| LispNames.MUTEX_RELEASE.equals(qn.member())) {
					// No-ops: both WASM backends are single-threaded by construction.
					WasmMutexCompiler.compile(qn.member(), cons, ctx);
					return;
				}
				if ((LispNames.TLS_CONNECT.equals(qn.member()) || LispNames.TLS_UPGRADE.equals(qn.member()))
						&& !ctx.component) {
					// Under --component the CLIENT tls built-ins are the spliced tls.lisp
					// defuns (over the wit-imported wasi:tls@0.3.0-draft) -- the symbol
					// falls through to the ordinary call path like the tcp family above.
					// Preview 1 has no wasi:tls host API, so there it stays a compile
					// error (unlike the tcp call-time stubs: no spliced library carries a
					// dead tls-connect call site the way s-sql carries cl-postgres'
					// socket layer -- the cl+ssl shim's tls-upgrade site is prunable).
					throw new UnsupportedOperationException(
							"rontolisp:" + qn.member() + " requires the interpreter, the JVM backend or --component"
									+ " (no wasi:tls host API is wired on Preview 1 WASM)");
				}
				if (LispNames.TLS_LISTEN.equals(qn.member()) || LispNames.TLS_LISTEN_PEM.equals(qn.member())
						|| LispNames.TLS_LISTEN_P12.equals(qn.member())) {
					// PERMANENT, not a not-yet: the wasi:tls proposal is client-only by
					// design -- client.wit is all any draft defines, there is no
					// server/accept interface to bind -- so TLS servers are
					// interpreter/JVM only on every WASM target, --component included.
					// (%tls-listen-p12 is the internal shape the tls-listen-pem inliner
					// produces on the JVM path; the name is normalized to tls-listen-pem
					// in the message.)
					String name = LispNames.TLS_LISTEN_P12.equals(qn.member()) ? LispNames.TLS_LISTEN_PEM : qn.member();
					throw new UnsupportedOperationException("rontolisp:" + name
							+ " is not supported on any WASM target: the wasi:tls proposal defines no server"
							+ " interface (it is client-only by design), so TLS servers are interpreter/JVM only");
				}
				// Other rontolisp: members (user defuns in that package) fall through.
			}
			// The uiop MACROS with real expansions. A macro cannot reach the uiop stub
			// lowering (which only sees function-call shapes), and these are not stubs:
			// smart-buffer's disk-spill path runs with-temporary-file, and the
			// definition wrappers (with-deprecation / with-upgradability) reach here for
			// the occurrences nested in an expression -- the flattening pass has already
			// spliced the top-level ones. One dispatcher shared with the interpreter,
			// the JVM compiler and FreeVarAnalyzer; unwindProtect = ctx.ehMode, like the
			// usocket with-*s below, so outside EH mode with-temporary-file's cleanup
			// runs on normal exit only.
			if (qn != null && UiopExports.isUiopFamily(qn.pkg())) {
				// Other uiop members fall through to the stub lowering.
				LispVal uiopMacro = LispMacroExpander.expandUiopMacro(cons, ctx.ehMode);
				if (uiopMacro != null) {
					compileExpr(uiopMacro, ctx);
					return;
				}
				// A uiop MACRO nothing implements yet, lowered HERE rather than in
				// WasmFunctionCallCompiler: its synthesized stub is a real variadic defun
				// (so the name is fboundp), which means the ordinary call path finds it
				// and compiles its ARGUMENT FORMS before signalling -- and a macro that
				// does nothing must not evaluate what it was handed. The stub lowering
				// further down only runs for a name with no defun at all.
				LispVal uiopStub = LispMacroExpander.expandUnimplementedUiopMacro(cons);
				if (uiopStub != null) {
					compileExpr(uiopStub, ctx);
					return;
				}
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
			// bordeaux-threads:with-lock-held -- and its recursive twin, the shim's
			// lock being reentrant -- is the same built-in expansion as
			// rontolisp:with-mutex; the rest of the bt shim is bordeaux-threads.lisp
			// defuns, which fall through to the ordinary qualified-call path.
			if (qn != null && LispNames.BORDEAUX_THREADS_PKG.equals(qn.pkg())
					&& (LispNames.WITH_LOCK_HELD.equals(qn.member())
							|| LispNames.WITH_RECURSIVE_LOCK_HELD.equals(qn.member()))) {
				compileExpr(LispMacroExpander.expandWithMutex(cons, ctx.ehMode), ctx);
				return;
			}
			// torch:no-grad is a built-in LispMacroExpander expansion (the usocket with-*
			// pattern): a let that dynamically rebinds the spliced torch.lisp
			// defparameter torch::*grad-enabled* (ordinary special-binding
			// save/restore, so no EH mode is forced). The other torch: members are the
			// torch.lisp defuns and fall through to the ordinary qualified-call path.
			if (qn != null && LispNames.TORCH_PKG.equals(qn.pkg()) && LispNames.TORCH_NO_GRAD.equals(qn.member())) {
				compileExpr(LispMacroExpander.expandTorchNoGrad(cons), ctx);
				return;
			}
			// sleep: on Preview 1 a spin on the clock, the only wait its nine imports
			// can express. Under --component it is the SPLICED wait.lisp defun instead
			// (eval/WaitForLibrary), which awaits the real wasi:clocks timer and so
			// YIELDS -- the rest of the instance keeps running -- so the name falls
			// through to the ordinary call path that resolves the defun, exactly like
			// uiop:getenv above. The lowering cannot live here for the component: the
			// await it introduces has to exist before WasmLispCompiler's async pass runs,
			// and this compiler runs long after it. Reaching here with no such defun
			// means
			// the pipeline skipped the splice.
			// A program that defines its own function on a cl name loses every call site
			// the operator dispatch below claims -- silently, until this. Armed here and
			// disarmed in the switch's default arm (the ordinary call path, which DOES
			// resolve the defun), so a cl name this backend never intercepts stays
			// quiet: wait.lisp's `sleep` under --component, compile-runtime.lisp's
			// `compile`. See compiler/ClRedefinitionWarnings for why the answer is a
			// diagnostic rather than honouring the definition.
			boolean redefinedClFunction = ClRedefinitionWarnings.redefinesClFunction(sym.name(), ctx.userDefunNames);
			if (LispNames.SLEEP.equals(sym.name())) {
				if (!ctx.component) {
					if (redefinedClFunction) {
						warnClRedefinition(sym.name(), cons, ctx);
					}
					if (ctx.noWasi) {
						// Preview 1 elapses an interval by SPINNING on the clock (its
						// nine imports carry no timer). A --no-wasi module has neither:
						// its clock is a cell only the host writes, and no host can
						// write it while a call is running -- so the spin would be an
						// infinite loop rather than a wait. Signalling names that; the
						// argument still evaluates for effect, and the condition is
						// catchable like every other --no-wasi refusal.
						compileExpr(LispMacroExpander.expandConstantResult(cons,
								LispMacroExpander.callTimeUnsupportedStub(sym.name()
										+ " cannot wait on a --no-wasi module: it imports no timer, and its clock"
										+ " cannot advance while a call is running (only a host write moves it), so"
										+ " no interval could elapse here")),
								ctx);
						return;
					}
					compileExpr(LispMacroExpander.expandSleep(cons, true), ctx);
					return;
				}
				if (!ctx.functions.containsKey(LispNames.SLEEP)) {
					throw new UnsupportedOperationException(LispNames.SLEEP
							+ " under --component is the spliced wait.lisp binding, but the program was compiled without it (eval/WaitForLibrary.process must run on the compile path)");
				}
			}
			switch (sym.name()) {
				case LispNames.ADD -> {
					if (!WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						WasmArithCompiler.compile(cons, ctx, Instruction.F64_ADD, WasmLispCompiler.FUNC_RAT_ADD);
					}
				}
				case LispNames.SUB -> {
					if (!WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						WasmArithCompiler.compile(cons, ctx, Instruction.F64_SUB, WasmLispCompiler.FUNC_RAT_SUB);
					}
				}
				case LispNames.MUL -> {
					if (!WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						WasmArithCompiler.compile(cons, ctx, Instruction.F64_MUL, WasmLispCompiler.FUNC_RAT_MUL);
					}
				}
				case LispNames.DIV ->
					WasmArithCompiler.compile(cons, ctx, Instruction.F64_DIV, WasmLispCompiler.FUNC_RAT_DIV);
				case LispNames.MOD -> {
					if (!WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						WasmArithCompiler.compileModRem(cons, ctx, WasmLispCompiler.FUNC_RAT_MOD);
					}
				}
				case LispNames.REM -> {
					if (!WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						WasmArithCompiler.compileModRem(cons, ctx, WasmLispCompiler.FUNC_RAT_REM);
					}
				}
				case LispNames.EQ -> compileComparison(cons, ctx, Instruction.I32_EQ, Instruction.F64_EQ);
				case LispNames.LT -> compileComparison(cons, ctx, Instruction.I32_LT_S, Instruction.F64_LT);
				case LispNames.GT -> compileComparison(cons, ctx, Instruction.I32_GT_S, Instruction.F64_GT);
				case LispNames.LE -> compileComparison(cons, ctx, Instruction.I32_LE_S, Instruction.F64_LE);
				case LispNames.GE -> compileComparison(cons, ctx, Instruction.I32_GE_S, Instruction.F64_GE);
				case LispNames.PRINT ->
					compilePrintOperator(cons, ctx, () -> WasmPrintCompiler.compilePrint(cons, ctx));
				case LispNames.PRIN1 ->
					compilePrintOperator(cons, ctx, () -> WasmPrintCompiler.compilePrin1(cons, ctx));
				case LispNames.PRINC ->
					compilePrintOperator(cons, ctx, () -> WasmPrintCompiler.compilePrinc(cons, ctx));
				case LispNames.TERPRI -> WasmTerpriCompiler.compile(cons, ctx);
				case LispNames.FRESH_LINE -> WasmFreshLineCompiler.compile(cons, ctx);
				// The public print-to-string names finish with the mutable-result wrap
				// every flipped producer emits (a no-op unless the producer flip is on);
				// the %princ-piece / %prin1-piece aliases the expander builds its own
				// pieces with are the same routed conversion WITHOUT it
				// (.kb/string-write-runtime.md, "The fourth round").
				case LispNames.PRINC_TO_STRING -> {
					compilePrintOperator(cons, ctx, () -> WasmPrincToStringCompiler.compile(cons, ctx));
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				case LispNames.PRIN1_TO_STRING -> {
					compilePrintOperator(cons, ctx, () -> WasmPrin1ToStringCompiler.compile(cons, ctx));
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				case LispNames.PRINC_PIECE_INTERNAL ->
					compilePrintOperator(cons, ctx, () -> WasmPrincToStringCompiler.compile(cons, ctx));
				case LispNames.PRIN1_PIECE_INTERNAL ->
					compilePrintOperator(cons, ctx, () -> WasmPrin1ToStringCompiler.compile(cons, ctx));
				// The print-object-free aliases the generated renderer's fallback calls.
				case LispNames.PRINC_TO_STRING_RAW -> WasmPrincToStringCompiler.compile(cons, ctx);
				// A fold-produced fresh-string constant: the literal, plus one
				// mutable-copy wrap so each evaluation answers a fresh mutable string
				// (PureBuiltinFolder's %str-fresh spelling).
				case LispNames.STR_FRESH -> {
					WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				case LispNames.PRIN1_TO_STRING_RAW -> WasmPrin1ToStringCompiler.compile(cons, ctx);
				case LispNames.STRING_CONCAT -> WasmStringConcatCompiler.compile(cons, ctx);
				case LispNames.FIXED_DECIMAL -> WasmFixedDecimalCompiler.compile(cons, ctx);
				case LispNames.GENSYM -> WasmGensymCompiler.compile(cons, ctx);
				case LispNames.STRING -> WasmSymbolApiCompiler.compileString(cons, ctx);
				case LispNames.SYMBOL_NAME -> WasmSymbolApiCompiler.compileSymbolName(cons, ctx);
				case LispNames.INTERN -> WasmSymbolApiCompiler.compileIntern(cons, ctx);
				case LispNames.FIND_SYMBOL -> WasmSymbolApiCompiler.compileFindSymbol(cons, ctx);
				case LispNames.FIND_SYMBOL_STATUS -> WasmSymbolApiCompiler.compileFindSymbolStatus(cons, ctx);
				// A runtime export/unexport (inside a defun body): the compiled package
				// registry is frozen, so evaluate the arguments and yield t.
				case LispNames.EXPORT, LispNames.UNEXPORT, LispNames.IMPORT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandRuntimeExport(cons), ctx);
				// The package-registry queries: answered from the use table baked in at
				// compile time (the compiled runtimes have no registry).
				case LispNames.LIST_ALL_PACKAGES, LispNames.PACKAGE_USE_LIST, LispNames.PACKAGE_USED_BY_LIST ->
					WasmExprCompiler.compileExpr(
							LispMacroExpander.expandPackageQuery(cons, ctx.packageTable, ctx.packageUseTable), ctx);
				case LispNames.MAKE_SYMBOL -> WasmSymbolApiCompiler.compileMakeSymbol(cons, ctx);
				case LispNames.BOUNDP -> WasmSymbolApiCompiler.compileBoundp(cons, ctx);
				case LispNames.FBOUNDP -> WasmSymbolApiCompiler.compileFboundp(cons, ctx);
				case LispNames.SYMBOL_VALUE -> WasmSymbolApiCompiler.compileSymbolValue(cons, ctx);
				case LispNames.FMAKUNBOUND -> WasmSymbolApiCompiler.compileFmakunbound(cons, ctx);
				case LispNames.SET_SYMBOL_FUNCTION_INTERNAL ->
					WasmSymbolApiCompiler.compileSetSymbolFunction(cons, ctx);
				case LispNames.FENV_FUNCTION_INTERNAL -> WasmSymbolApiCompiler.compileFenvFunction(cons, ctx);
				// Only a COMPUTED designator reaches here: PackageResolver folds a
				// literal
				// one to the quoted package keyword before the compiler ever sees it.
				case LispNames.FIND_PACKAGE -> WasmExprCompiler.compileExpr(
						LispMacroExpander.expandRuntimeFindPackage(cons.toList().get(1), ctx.packageTable), ctx);
				case LispNames.CONCATENATE -> {
					WasmExprCompiler.compileExpr(ConcatenateForms.expand(cons, ctx.usesSeqString, ctx.closRegistry),
							ctx);
					// The string family's fresh result carries a writable identity
					// (a no-op unless the producer flip is on -- see _to_mut_str).
					if (ConcatenateForms.literalResultFamily(cons.toList().get(1),
							ctx.closRegistry) == ConcatenateForms.ResultFamily.STRING) {
						WasmEmitHelper.emitToMutStrCall(ctx);
					}
				}
				case LispNames.READ_LINE -> {
					LispVal typed = LispMacroExpander.expandReadEofSignal(cons, false);
					LispVal compat = typed == null ? LispMacroExpander.expandReadLineCompat(cons) : null;
					if (typed != null) {
						WasmExprCompiler.compileExpr(typed, ctx);
					}
					else if (compat != null) {
						// (read-line s nil eof-value) -> (or (read-line s) eof-value).
						// Compiling the rewrite here rather than below the wrap keeps
						// the wrap on the LINE only: the eof-value is the caller's own
						// object and must come back by identity, not as a copy of it.
						WasmExprCompiler.compileExpr(compat, ctx);
					}
					else {
						WasmReadLineCompiler.compile(cons, ctx);
						WasmEmitHelper.emitToMutStrCall(ctx);
					}
				}
				case LispNames.READ_CHAR -> {
					LispVal typed = LispMacroExpander.expandReadEofSignal(cons, true);
					if (typed != null) {
						WasmExprCompiler.compileExpr(typed, ctx);
					}
					else {
						WasmReadCharCompiler.compile(cons, ctx);
					}
				}
				case LispNames.PEEK_CHAR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPeekChar(cons), ctx);
				case LispNames.READ_CHAR_NO_HANG ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandReadCharNoHang(cons), ctx);
				case LispNames.UNREAD_CHAR ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandUnreadChar(cons), ctx);
				case LispNames.PEEK_CHAR_INTERNAL -> {
					LispVal typed = LispMacroExpander.expandReadEofSignal(cons, true);
					if (typed != null) {
						WasmExprCompiler.compileExpr(typed, ctx);
					}
					else {
						WasmPeekCharCompiler.compile(cons, ctx);
					}
				}
				case LispNames.MAKE_SYNONYM_STREAM ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeSynonymStream(cons), ctx);
				case LispNames.OPEN -> {
					WasmOpenCompiler.compile(coercePathArgWhenGated(cons, 0, ctx), ctx);
					wrapStreamValue(ctx, am.ik.rontolisp.LispLayout.Kinds.FILE);
				}
				case LispNames.CLOSE -> {
					// Closing a SYNONYM stream closes the synonym, not what it forwards
					// to -- which is nothing to do; an OPEN stream resolves to its
					// handle. The guard is emitted only when the program can build one
					// of the two; %close is the raw-handle close it falls through to.
					if (ctx.usesSynonymStreams || ctx.usesStreamValues) {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandCloseOverStream(cons,
								ctx.usesSynonymStreams, ctx.functions.containsKey(LispNames.STREAM_TARGET)), ctx);
					}
					else {
						WasmCloseCompiler.compile(cons, ctx);
					}
				}
				case LispNames.CLOSE_INTERNAL -> WasmCloseCompiler.compile(cons, ctx);
				case LispNames.PROBE_FILE_INTERNAL -> WasmProbeFileCompiler.compile(cons, ctx);
				// The host environment read behind uiop:getenv (the public name is Lisp
				// over it -- uiop-os.lisp -- consulting the override map a
				// (setf (uiop:getenv ...)) wrote first). Under --component it is the
				// spliced environment.lisp defun over wit-imported wasi:cli/environment
				// (eval/EnvironmentLibrary), so the call falls through to the ordinary
				// call path: there is no preview1 host to fill the environ buffer
				// _getenv scans. Reaching here with no such defun means the pipeline
				// skipped the splice, which the ordinary path would otherwise turn into
				// a runtime "undefined function".
				case LispNames.HOST_GETENV -> {
					if (!ctx.component) {
						WasmGetenvCompiler.compile(cons, ctx);
					}
					else if (!ctx.functions.containsKey(LispNames.HOST_GETENV)) {
						throw new UnsupportedOperationException(LispNames.HOST_GETENV
								+ " under --component is the spliced environment.lisp binding, but the program was compiled without it (eval/EnvironmentLibrary.process must run on the compile path)");
					}
					else {
						// The ordinary call path resolves the spliced defun.
						WasmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx);
					}
					// The host's answer is a fresh string on every path here, so it
					// carries the same writable identity the other producers do; a
					// missing variable answers nil and passes the wrap through.
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				// The host command line behind the uiop/image family (the public five
				// are Lisp over it -- uiop-image.lisp). Preview 1 scans the buffer its
				// two appended WASI imports fill (_argv); --component takes the spliced
				// environment.lisp defun over wit-imported wasi:cli/environment's
				// get-arguments, the sibling of the get-environment binding, and so
				// falls through to the ordinary call path like %host-getenv does. A
				// --no-wasi reactor answers nil: it owns no WASI world and is entered
				// through exported functions, so there is no command line of its own to
				// read -- the same value-not-a-code-path rule %host-getcwd follows.
				case LispNames.HOST_ARGV -> {
					if (ctx.noWasi) {
						WasmExprCompiler.compileExpr(LispNil.INSTANCE, ctx);
					}
					else if (!ctx.component) {
						WasmArgvCompiler.compile(cons, ctx);
					}
					else if (!ctx.functions.containsKey(LispNames.HOST_ARGV)) {
						throw new UnsupportedOperationException(LispNames.HOST_ARGV
								+ " under --component is the spliced environment.lisp binding, but the program was compiled without it (eval/EnvironmentLibrary.process must run on the compile path)");
					}
					else {
						WasmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx);
					}
				}
				// %host-getcwd: nil on both WASM backends. A WASI program has preopened
				// directories and no CURRENT one -- there is no cwd to answer and no
				// chdir to move it -- and uiop:getcwd's one shared Lisp definition turns
				// that nil into its not-implemented-error, so the divergence is a value
				// rather than a second code path.
				case LispNames.HOST_GETCWD -> WasmExprCompiler.compileExpr(LispNil.INSTANCE, ctx);
				// %target-machine-type: the ABI this artifact targets, the one thing the
				// environment-enquiry family (machine-type, a prelude defun over it)
				// answers differently per backend. Both WASM backends emit wasm32
				// modules, so the module -- not the host processor, which no wasm
				// program can see -- is the answer, matching uiop:architecture.
				case LispNames.TARGET_MACHINE_TYPE -> WasmExprCompiler.compileExpr(new LispString("WASM32"), ctx);
				case LispNames.LIST_DIRECTORY -> WasmListDirectoryCompiler.compile(cons, ctx);
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
				case LispNames.WRITE_TO_STRING -> {
					compilePrintOperator(cons, ctx, () -> WasmPrin1ToStringCompiler.compile(cons, ctx));
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				case LispNames.MAKE_STRING_OUTPUT_STREAM_INTERNAL -> {
					WasmWriteStringCompiler.compileMakeOutputStream(cons, ctx);
					wrapStreamValue(ctx, am.ik.rontolisp.LispLayout.Kinds.STRING_OUTPUT);
				}
				case LispNames.MAKE_STRING_OUTPUT_STREAM ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeStringOutputStream(cons), ctx);
				case LispNames.GET_OUTPUT_STREAM_STRING ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandGetOutputStreamString(cons), ctx);
				case LispNames.MAKE_STRING_INPUT_STREAM_INTERNAL -> {
					WasmWriteStringCompiler.compileMakeInputStream(cons, ctx);
					wrapStreamValue(ctx, am.ik.rontolisp.LispLayout.Kinds.STRING_INPUT);
				}
				case LispNames.MAKE_STRING_INPUT_STREAM ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeStringInputStream(cons), ctx);
				case LispNames.STRING_STREAM_CONTENTS_INTERNAL -> {
					// The with-output-to-string / get-output-stream-string capture.
					WasmWriteStringCompiler.compileContents(cons, ctx);
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				// unwindProtect = ctx.ehMode: a literal with-* flips the
				// module into EH mode via the gate, so these expansions ride
				// unwind-protect like the interpreter/JVM (close on EVERY exit); the flag
				// is only false for internally-generated occurrences (a :report lambda's
				// with-output-to-string) inside a non-EH module, which keep the
				// close-after-body shape so they still compile without the tag section.
				case LispNames.WITH_OUTPUT_TO_STRING ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithOutputToString(cons, ctx.ehMode), ctx);
				case LispNames.PPRINT_LOGICAL_BLOCK ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandPprintLogicalBlock(cons), ctx);
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
				case LispNames.RESTART_BIND ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandRestartBind(cons), ctx);
				case LispNames.WITH_SIMPLE_RESTART ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithSimpleRestart(cons), ctx);
				case LispNames.MAKE_CONDITION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeCondition(cons, ctx.closRegistry), ctx);
				case LispNames.DOCUMENTATION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandDocumentation(cons), ctx);
				case LispNames.WITH_OPEN_STREAM ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithOpenStream(cons, ctx.ehMode), ctx);
				case LispNames.WITH_OPEN_FILE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithOpenFile(cons, ctx.ehMode), ctx);
				case LispNames.READ_BYTE -> {
					LispVal typed = LispMacroExpander.expandReadEofSignal(cons, true);
					if (typed != null) {
						WasmExprCompiler.compileExpr(typed, ctx);
					}
					else {
						WasmReadByteCompiler.compile(cons, ctx);
					}
				}
				case LispNames.WRITE_BYTE -> WasmWriteByteCompiler.compile(cons, ctx);
				case LispNames.CLEAR_OUTPUT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandClearOutput(cons), ctx);
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
					// one reaching this compiler has no non-blocking probe behind it. A
					// CALL-time error rather than a compile error (the socket policy):
					// the
					// usocket shim's wait-for-input polls through listen,
					// so every spliced usocket program carries a listen call site that
					// is dead code on Preview 1 -- it must compile, and a program that
					// actually calls it gets this message.
					WasmExprCompiler.compileExpr(LispMacroExpander.expandConstantResult(cons, LispMacroExpander
						.callTimeUnsupportedStub("listen requires the interpreter, the JVM backend or a --component"
								+ " socket stream (no non-blocking input probe exists on this" + " WASM target)")),
							ctx);
				case LispNames.READ_SEQUENCE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandReadSequence(cons), ctx);
				case LispNames.WRITE_SEQUENCE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWriteSequence(cons), ctx);
				case LispNames.READ_SEQUENCE_PACKED, LispNames.WRITE_SEQUENCE_PACKED ->
					WasmSequencePackedCompiler.compile(cons, ctx);
				case LispNames.MAKE_STRING ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeString(cons), ctx);
				// A site whose DESTINATION is provably an array calls the shared
				// runtime's array arm directly, skipping the %arrayp dispatch. A program
				// with no unproven site then leaves the wide one -- the list rewrite and
				// the string rebuild -- without a caller (.kb/sequence-op-runtimes.md).
				case LispNames.REPLACE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandReplace(cons, true,
						ctx.functions.containsKey(LispNames.REPLACE_RUNTIME),
						routesToArrayArm(cons, LispNames.REPLACE_ARRAY_RUNTIME, ctx)), ctx);
				case LispNames.FILL -> WasmExprCompiler
					.compileExpr(LispMacroExpander.expandFill(cons, ctx.functions.containsKey(LispNames.FILL_RUNTIME),
							routesToArrayArm(cons, LispNames.FILL_ARRAY_RUNTIME, ctx)), ctx);
				case LispNames.SCHAR_SET ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandScharSetFunctional(cons), ctx);
				case LispNames.LOWER_CASE_P ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandLowerCaseP(cons), ctx);
				case LispNames.UPPER_CASE_P ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandUpperCaseP(cons), ctx);
				case LispNames.CONSTANTP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandConstantp(cons), ctx);
				case LispNames.STREAMP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandStreamp(cons,
						ctx.usesSynonymStreams, ctx.usesStreamValues, ctx.closRegistry), ctx);
				case LispNames.SIMPLE_STRING_P ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSimpleStringP(cons), ctx);
				case LispNames.INPUT_STREAM_P, LispNames.OUTPUT_STREAM_P -> WasmExprCompiler.compileExpr(
						LispMacroExpander.expandStreamDirectionP(cons, ctx.usesSynonymStreams, ctx.usesStreamValues),
						ctx);
				// file-length is REAL here: it stats the stream's descriptor through the
				// fd_filestat_get import and answers nil only for what genuinely has no
				// length (a string stream, a standard stream, a socket, a closed or
				// non-handle designator) -- the same set the interpreter and the JVM
				// answer nil for.
				case LispNames.FILE_LENGTH -> WasmFileLengthCompiler.compile(cons, ctx);
				// file-position and file-write-date answer nil here rather than
				// signalling: neither has a call imported, and "cannot be determined" is
				// what Common Lisp prescribes for exactly that. %make-directories,
				// %delete-file and %rename-file have no such escape -- the directory/file
				// either changed
				// or
				// it did not -- so they signal.
				case LispNames.FILE_POSITION, LispNames.FILE_WRITE_DATE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandConstantResult(cons, LispNil.INSTANCE), ctx);
				case LispNames.PATHNAMEP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPathnamep(cons), ctx);
				case LispNames.MAKE_DIRECTORIES ->
					WasmExprCompiler.compileExpr(LispMacroExpander.makeDirectoriesStub(), ctx);
				case LispNames.DELETE_FILE_INTERNAL ->
					WasmExprCompiler.compileExpr(LispMacroExpander.deleteFileStub(), ctx);
				case LispNames.RENAME_FILE_INTERNAL ->
					WasmExprCompiler.compileExpr(LispMacroExpander.renameFileStub(), ctx);
				case LispNames.STREAM_ELEMENT_TYPE -> WasmExprCompiler.compileExpr(
						LispMacroExpander.expandConstantResult(cons, LispMacroExpander.quotedCharacterTypeName()), ctx);
				case LispNames.MAKE_BROADCAST_STREAM ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeBroadcastStream(cons), ctx);
				case LispNames.FDEFINITION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandFdefinition(cons), ctx);
				case LispNames.MASK_FIELD -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMaskField(cons), ctx);
				case LispNames.SCALE_FLOAT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandScaleFloat(cons), ctx);
				case LispNames.CLASS_OF ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandClassOf(cons, true), ctx);
				case LispNames.CLASS_DESIGNATOR_INTERNAL ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandClassDesignator(cons), ctx);
				case LispNames.CLASS_SLOT_DEFS_INTERNAL ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandClassSlotDefs(cons, ctx.closRegistry), ctx);
				case LispNames.SLOT_BOUNDP ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSlotBoundp(cons, ctx.closRegistry), ctx);
				case LispNames.SLOT_EXISTS_P ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSlotExistsP(cons, ctx.closRegistry), ctx);
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
				case LispNames.STRING_UPCASE -> {
					WasmStringUpcaseCompiler.compileUpcase(LispMacroExpander.normalizeStringDesignatorArg(cons, 1),
							ctx);
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				case LispNames.STRING_DOWNCASE -> {
					WasmStringUpcaseCompiler.compileDowncase(LispMacroExpander.normalizeStringDesignatorArg(cons, 1),
							ctx);
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				case LispNames.STRING_CAPITALIZE -> {
					WasmStringCapitalizeCompiler.compile(LispMacroExpander.normalizeStringDesignatorArg(cons, 1), ctx);
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
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
				case LispNames.READTABLE_CASE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandReadtableCase(cons), ctx);
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
						WasmStringEqCompiler
							.compileEq((LispCons) LispMacroExpander.normalizeStringComparisonDesignators(cons), ctx);
					}
				}
				case LispNames.STRING_EQUAL -> {
					if (LispMacroExpander.hasStringComparisonBounds(cons)) {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandStringComparisonBounds(cons), ctx);
					}
					else {
						WasmStringEqCompiler
							.compileEqual((LispCons) LispMacroExpander.normalizeStringComparisonDesignators(cons), ctx);
					}
				}
				// The trim family answers a fresh string, so its result carries the same
				// writable identity every other flipped producer's does.
				case LispNames.STRING_TRIM -> {
					WasmStringTrimCompiler.compileTrim(LispMacroExpander.normalizeStringTrimArgs(cons), ctx);
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				case LispNames.STRING_LEFT_TRIM -> {
					WasmStringTrimCompiler.compileLeft(LispMacroExpander.normalizeStringTrimArgs(cons), ctx);
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				case LispNames.STRING_RIGHT_TRIM -> {
					WasmStringTrimCompiler.compileRight(LispMacroExpander.normalizeStringTrimArgs(cons), ctx);
					WasmEmitHelper.emitToMutStrCall(ctx);
				}
				case LispNames.LOAD -> WasmLoadCompiler.compile(coercePathArgWhenGated(cons, 0, ctx), ctx);
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
				// a direct backend compile with no LoadInliner in front (a test seam).
				case LispNames.ASDF_LOAD_SYSTEM, LispNames.QL_QUICKLOAD -> {
					if (ctx.functions.containsKey(sym.name())) {
						WasmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.callTimeUnsupportedStub(
								sym.name() + " cannot load a system at run time on the compiled backends"
										+ " (systems are spliced at compile time)"),
								ctx);
					}
				}
				// asdf:find-system: a real defun whenever the asdf runtime was spliced
				// (the CLI pipeline splices it on any reference); without the splice it
				// keeps the historical nil lowering (see
				// LispMacroExpander.expandRuntimeFindSystem).
				case LispNames.ASDF_FIND_SYSTEM -> {
					if (ctx.functions.containsKey(sym.name())) {
						WasmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandRuntimeFindSystem(cons), ctx);
					}
				}
				case LispNames.EVAL -> WasmEvalCompiler.compile(cons, ctx);
				case LispNames.QUOTE -> WasmQuoteCompiler.compile(cons, ctx);
				// quote for a compiler-synthesized name: same value, but the spelling is
				// not recorded as program-spelled (see LispNames.UNSPELLED_QUOTE).
				case LispNames.UNSPELLED_QUOTE ->
					WasmEmitHelper.compileUnspelledLiteral(((LispSymbol) ((LispCons) cons.cdr()).car()).name(), ctx);
				case LispNames.IF -> WasmIfCompiler.compile(cons, ctx);
				case LispNames.WHILE -> WasmWhileCompiler.compile(cons, ctx);
				case LispNames.LET -> WasmLetCompiler.compile(cons, ctx);
				case LispNames.PROGV ->
					// The symbols are runtime-computed, but the candidate SPECIALS are
					// static: lower to a loop dispatching each name over that set, with
					// an unwind-protect carrying the restores (.kb/dynamic-special-
					// variables.md). The unwind-protect is why progv forces EH mode.
					WasmExprCompiler
						.compileExpr(LispMacroExpander.expandProgvForCompile(cons, ctx.specialVars, ctx.usesEval), ctx);
				case LispNames.PROGV_DYN_BIND -> WasmProgvCompiler.compileDynBind(cons, ctx);
				case LispNames.PROGV_DYN_UNBIND -> WasmProgvCompiler.compileDynUnbind(cons, ctx);
				case LispNames.PROGV_GENV -> WasmProgvCompiler.compileGenvRead(ctx);
				case LispNames.PROGV_GENV_SET -> WasmProgvCompiler.compileGenvWrite(cons, ctx);
				case LispNames.SYMBOL_VALUE_RAW -> WasmSymbolApiCompiler.compileSymbolValueRaw(cons, ctx);
				case LispNames.UNWIND_PROTECT -> WasmUnwindProtectCompiler.compile(cons, ctx);
				case LispNames.HANDLER_CASE -> WasmHandlerCaseCompiler.compile(cons, ctx);
				case LispNames.HB_GUARD_INTERNAL -> WasmHandlerCaseCompiler.compileGuard(cons, ctx);
				case LispNames.HANDLER_BIND ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandHandlerBind(cons, ctx.closRegistry), ctx);
				case LispNames.IGNORE_ERRORS ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandIgnoreErrors(cons), ctx);
				case LispNames.PROGN -> WasmPrognCompiler.compile(cons, ctx);
				case LispNames.TAGBODY -> WasmTagbodyCompiler.compile(cons, ctx);
				case LispNames.GO -> WasmTagbodyCompiler.compileGo(cons, ctx);
				case LispNames.PRINT_UNREADABLE_OBJECT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandPrintUnreadableObject(cons), ctx);
				case LispNames.DO_EXTERNAL_SYMBOLS, LispNames.DO_SYMBOLS ->
					// Real on the interpreter (registry-backed); inside #. the macro-time
					// evaluator resolves it before compilation. A runtime occurrence has
					// no
					// package registry behind it here.
					throw new UnsupportedOperationException(
							sym.name() + " requires the interpreter (no runtime package registry in compiled mode)");
				case LispNames.WITH_PACKAGE_ITERATOR ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithPackageIterator(cons), ctx);
				case LispNames.WITH_HASH_TABLE_ITERATOR ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithHashTableIterator(cons), ctx);
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
				case LispNames.SYMBOL_MACROLET ->
					// No user-macro hook: UserMacroExpander has already expanded every
					// user
					// macro on the compile path.
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSymbolMacrolet(cons), ctx);
				case LispNames.WITH_ACCESSORS ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithAccessors(cons), ctx);
				case LispNames.CHANGE_CLASS -> WasmExprCompiler
					.compileExpr(LispMacroExpander.expandChangeClass(cons, ctx.closRegistry, true), ctx);
				case LispNames.DEFVAR -> WasmDefvarCompiler.compile(cons, ctx, false);
				case LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> WasmDefvarCompiler.compile(cons, ctx, true);
				case LispNames.LIST -> WasmListCompiler.compile(cons, ctx);
				case LispNames.CAR -> WasmCarCompiler.compile(cons, ctx);
				case LispNames.CDR -> WasmCdrCompiler.compile(cons, ctx);
				case LispNames.CONS -> WasmConsCompiler.compile(cons, ctx);
				case LispNames.NTHCDR -> WasmNthcdrCompiler.compile(cons, ctx);
				case LispNames.RPLACA -> WasmRplacaCompiler.compile(cons, ctx);
				case LispNames.RPLACD -> WasmRplacdCompiler.compile(cons, ctx);
				case LispNames.SETF -> {
					LispCons knownArrayStore = WasmArrayCompiler.nonStringArefStore(cons, ctx);
					if (knownArrayStore != null) {
						// The variable place's pinned array kind makes the expansion's
						// stringp/schar-set branch dead; the %aset answers the value as
						// stored, which IS the setf value here.
						WasmArrayCompiler.compileAset(knownArrayStore, ctx, true);
					}
					else {
						WasmExprCompiler.compileExpr(
								LispMacroExpander.expandSetf(cons, ctx.structAccessors, ctx.closRegistry), ctx);
					}
				}
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
				case LispNames.CATCH -> WasmNlxCompiler.compileTagCatch(cons, ctx);
				case LispNames.THROW -> WasmNlxCompiler.compileTagThrow(cons, ctx);
				case LispNames.RETURN_FROM -> WasmReturnFromCompiler.compile(cons, ctx);
				case LispNames.RETURN -> WasmReturnCompiler.compile(cons, ctx);
				case LispNames.INCF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandIncf(cons), ctx);
				case LispNames.DECF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDecf(cons), ctx);
				case LispNames.FORMAT -> {
					WasmExprCompiler.compileExpr(LispMacroExpander.expandFormat(cons), ctx);
					// A literal-nil destination is a string PRODUCER: its capture
					// carries a writable identity (a computed destination stays
					// un-flipped, see MutableStringProducers).
					if (MutableStringProducers.isFormatToString(cons)) {
						WasmEmitHelper.emitToMutStrCall(ctx);
					}
				}
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
				case LispNames.RASSOC_IF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandRassocIf(cons), ctx);
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
				case LispNames.SUBSTITUTE_IF ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSubstituteIf(cons), ctx);
				case LispNames.SUBSTITUTE_IF_NOT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSubstituteIfNot(cons), ctx);
				case LispNames.NSUBSTITUTE_IF ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandNsubstituteIf(cons), ctx);
				case LispNames.NSUBSTITUTE_IF_NOT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandNsubstituteIfNot(cons), ctx);
				case LispNames.REMOVE_DUPLICATES, LispNames.DELETE_DUPLICATES ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandRemoveDuplicates(cons), ctx);
				case LispNames.NCONC -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNconc(cons), ctx);
				case LispNames.LAST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandLast(cons), ctx);
				case LispNames.BUTLAST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandButlast(cons), ctx);
				case LispNames.IDENTITY -> WasmExprCompiler.compileExpr(LispMacroExpander.expandIdentity(cons), ctx);
				case LispNames.COPY_LIST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCopyList(cons), ctx);
				case LispNames.NREVERSE -> {
					// A string/vector sequence reverses via a coerced list and is
					// rebuilt in its own representation; null when the call is already
					// the inner list reversal (wrapSortForStringSeq precedent).
					LispVal wrappedNreverse = LispMacroExpander.wrapNreverseForStringSeq(cons, true);
					if (wrappedNreverse != null) {
						WasmExprCompiler.compileExpr(wrappedNreverse, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandNreverse(cons), ctx);
					}
				}
				case LispNames.MAKE_LIST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeList(cons), ctx);
				case LispNames.UNION -> WasmExprCompiler.compileExpr(LispMacroExpander.expandUnion(cons), ctx);
				case LispNames.INTERSECTION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandIntersection(cons), ctx);
				case LispNames.SET_DIFFERENCE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSetDifference(cons), ctx);
				case LispNames.ADJOIN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAdjoin(cons), ctx);
				case LispNames.SUBSETP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandSubsetp(cons), ctx);
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
				case LispNames.HASH_TABLE_TEST -> WasmHashTableCompiler.compileTest(cons, ctx);
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
				case LispNames.REPLACE_BULK -> WasmArrayCompiler.compileReplaceBulk(cons, ctx);
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
				case LispNames.ARRAY_DEFAULT_ELEMENT -> WasmArrayCompiler.compileArrayDefaultElement(cons, ctx);
				case LispNames.ARRAY_ADOPT_ELEMENT_TYPE -> WasmArrayCompiler.compileArrayAdoptElementType(cons, ctx);
				case LispNames.ARRAY_ALIKE -> WasmArrayCompiler.compileArrayAlike(cons, ctx);
				case LispNames.ARRAY_DISPLACEMENT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandArrayDisplacement(cons), ctx);
				case LispNames.ARRAY_DISP_TARGET -> WasmArrayCompiler.compileDispTarget(cons, ctx);
				case LispNames.ARRAY_DISP_OFFSET -> WasmArrayCompiler.compileDispOffset(cons, ctx);
				case LispNames.COERCE -> {
					// A packed (unsigned-byte 8|16|32) result type lowers through the
					// shared %seq-int-vector helper, exactly as concatenate's does;
					// everything else is expandCoerce as before.
					LispVal packed = ConcatenateForms.packedVectorCoerce(cons, ctx.closRegistry);
					WasmExprCompiler.compileExpr(packed != null ? packed
							: LispMacroExpander.expandCoerce(cons, true,
									ctx.functions.containsKey(LispNames.SEQ_TO_LIST),
									ctx.functions.containsKey(LispNames.DEFTYPE_ALIAS_RUNTIME), null),
							ctx);
				}
				case LispNames.MAP_INTO -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMapInto(cons,
						ctx.functions.containsKey(LispNames.mapIntoRuntime(cons.toList().size() - 3))), ctx);
				case LispNames.APPEND -> WasmAppendCompiler.compile(cons, ctx);
				case LispNames.FUNCALL -> {
					// A direct (funcall __FLETn_f ...) of a registered local function in
					// a non-fused position: substitute and fuse the body right here,
					// mirroring the inlinable-defun direct-call path below.
					if (!WasmIntFusionCompiler.tryCompileLocalCall(cons, ctx)) {
						WasmFunctionCallCompiler.compileFuncall(cons, ctx);
					}
				}
				case LispNames.FUNCTION -> WasmFunctionFormCompiler.compile(cons, ctx);
				case LispNames.SYMBOL_FUNCTION -> WasmFunctionFormCompiler.compileSymbolFunction(cons, ctx);
				case LispNames.MAP -> {
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMap(cons), ctx);
					// (map 'string ...) builds a fresh string, so its result carries a
					// writable identity. (coerce seq 'string) reaches this through the
					// same form, and a STRING input passes through un-built and so
					// un-wrapped -- which is exactly what identity wants.
					if (MutableStringProducers.isMapToString(cons)) {
						WasmEmitHelper.emitToMutStrCall(ctx);
					}
				}
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
							// The list sort itself: the shared merge sort when the
							// program carries it, else the inline one (.kb/sort.md).
							LispVal sharedSort = LispMacroExpander.sortRuntimeCall(cons,
									ctx.functions.containsKey(LispNames.SORT_RUNTIME));
							if (sharedSort != null) {
								// The predicate is dispatched one frame down, inside the
								// helper, whose body is injected runtime and therefore
								// arms nothing -- so the SITE says a designator it
								// cannot read reaches a call, exactly as the inline sort
								// did through WasmDesignatorCall
								// (Ctx.runtimeDesignatorDispatch).
								if (!ctx.injectedRuntimeBody
										&& !LispMacroExpander.isStaticFunctionDesignator(cons.toList().get(2))) {
									ctx.runtimeDesignatorDispatch[0] = true;
								}
								WasmExprCompiler.compileExpr(sharedSort, ctx);
							}
							else {
								WasmSortCompiler.compile(cons, ctx);
							}
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
				case LispNames.OBJ_BECOME -> WasmInstanceCompiler.compileBecome(cons, ctx);
				case LispNames.OBJ_REF -> WasmInstanceCompiler.compileRef(cons, ctx);
				case LispNames.OBJ_SET -> WasmInstanceCompiler.compileSet(cons, ctx);
				case LispNames.OBJ_IS -> WasmInstanceCompiler.compileIs(cons, ctx);
				case LispNames.OBJ_TAG -> WasmInstanceCompiler.compileTag(cons, ctx);
				case LispNames.OBJ_P -> WasmInstanceCompiler.compileP(cons, ctx);
				case LispNames.OBJ_SLOTS -> WasmInstanceCompiler.compileSlots(cons, ctx);
				case LispNames.FUNCTIONP -> WasmFunctionpCompiler.compile(cons, ctx);
				case LispNames.ARRAYP_INTERNAL -> WasmArraypCompiler.compile(cons, ctx);
				case LispNames.SIMPLE_ARRAY_P_INTERNAL -> WasmArrayCompiler.compileSimpleArrayP(cons, ctx);
				case LispNames.STRING_DIMENSION_INTERNAL -> WasmArrayCompiler.compileStringDimension(cons, ctx);
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
				case LispNames.ERROR -> WasmExprCompiler
					.compileExpr(LispMacroExpander.expandError(cons, ctx.closRegistry, false, ctx.restartMode), ctx);
				case LispNames.CERROR -> WasmExprCompiler
					.compileExpr(LispMacroExpander.expandCerror(cons, ctx.closRegistry, ctx.restartMode), ctx);
				case LispNames.ERROR_INTERNAL -> WasmErrorCompiler.compile(cons, ctx);
				case LispNames.ERROR_COND_INTERNAL ->
					// Outside EH mode the condition-carrying variant traps like %error (a
					// WASM trap is uncatchable and carries no payload; the arguments are
					// not evaluated); in EH mode it throws the typed instance.
					WasmErrorCompiler.compileCond(cons, ctx);
				case LispNames.WARN -> WasmExprCompiler
					.compileExpr(LispMacroExpander.expandWarn(cons, ctx.closRegistry, ctx.restartMode), ctx);
				case LispNames.WARN_INTERNAL -> WasmWarnCompiler.compile(cons, ctx);
				case LispNames.SIGNAL -> WasmExprCompiler
					.compileExpr(LispMacroExpander.expandSignalMacro(cons, ctx.closRegistry, ctx.restartMode), ctx);
				case LispNames.SIGNAL_COND_INTERNAL -> WasmSignalCondCompiler.compile(cons, ctx);
				case LispNames.HC_DEPTH_DEC_INTERNAL -> WasmHandlerCaseCompiler.compileDepthDec(ctx);
				case LispNames.AND -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAnd(cons), ctx);
				case LispNames.OR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandOr(cons), ctx);
				case LispNames.WHEN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandWhen(cons), ctx);
				case LispNames.DOTIMES -> WasmDotimesCompiler.compile(cons, ctx);
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
				case LispNames.RANDOM -> {
					if (cons.toList().size() == 3) {
						// The optional random-state argument: normalized away (state
						// evaluated for effect, backend entropy draws).
						WasmExprCompiler.compileExpr(LispMacroExpander.expandRandomWithState(cons), ctx);
					}
					else {
						WasmRandomCompiler.compile(cons, ctx);
					}
				}
				case LispNames.MAKE_RANDOM_STATE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandConstantResult(cons, LispNil.INSTANCE), ctx);
				// Under --no-wasi these read the cell a host writes through the exported
				// __ronto_set_time hook instead of the (unimported) clock, and signal
				// while it is unset -- the branch is WasmTimeCompiler's, which is also
				// where the reason lives.
				case LispNames.GET_UNIVERSAL_TIME, LispNames.GET_INTERNAL_REAL_TIME, LispNames.GET_INTERNAL_RUN_TIME ->
					WasmTimeCompiler.compile(cons, ctx, sym.name());
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
					if (WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						// compiled as a fused integer expression tree
					}
					else if (isBinaryCall(cons)) {
						WasmBitwiseCompiler.compileLogand(cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx);
					}
				}
				case LispNames.LOGIOR -> {
					if (WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						// compiled as a fused integer expression tree
					}
					else if (isBinaryCall(cons)) {
						WasmBitwiseCompiler.compileLogior(cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx);
					}
				}
				case LispNames.LOGXOR -> {
					if (WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						// compiled as a fused integer expression tree
					}
					else if (isBinaryCall(cons)) {
						WasmBitwiseCompiler.compileLogxor(cons, ctx);
					}
					else {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx);
					}
				}
				case LispNames.LOGNOT -> {
					if (!WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						WasmBitwiseCompiler.compileLognot(cons, ctx);
					}
				}
				case LispNames.ASH -> {
					if (!WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						WasmBitwiseCompiler.compileAsh(cons, ctx);
					}
				}
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
				case LispNames.CTYPECASE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandCtypecase(cons, ctx.closRegistry), ctx);
				case LispNames.TYPEP ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandTypep(cons, ctx.closRegistry, false), ctx);
				case LispNames.SUBTYPEP ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSubtypep(cons, ctx.closRegistry), ctx);
				case LispNames.CHECK_TYPE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCheckType(cons), ctx);
				case LispNames.ASSERT -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAssert(cons), ctx);
				case LispNames.DECLARE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDeclare(cons), ctx);
				case LispNames.DECLAIM -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDeclaim(cons), ctx);
				case LispNames.PROCLAIM -> WasmExprCompiler.compileExpr(LispMacroExpander.expandProclaim(cons), ctx);
				case LispNames.THE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandThe(cons), ctx);
				case LispNames.EVAL_WHEN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandEvalWhen(cons), ctx);
				case LispNames.WITH_COMPILATION_UNIT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithCompilationUnit(cons), ctx);
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
				case LispNames.LOGTEST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandLogtest(cons), ctx);
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
					// The ordinary call path resolves the program's own defun, so
					// nothing was overridden here.
					redefinedClFunction = false;
					if (LispNames.isCarCdrComposition(sym.name())) {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandCarCdrComposition(cons), ctx);
					}
					// A direct call to a fusion-inlinable defun (mod32+/rol32-style
					// arithmetic wrapper) in a non-fused position: substitute and fuse
					// the body right here, so the call boundary's box/unbox round trip
					// disappears even outside a larger expression tree.
					else if (!ctx.inlinableDefuns.containsKey(sym.name())
							|| !WasmIntFusionCompiler.tryCompile(cons, ctx)) {
						WasmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx);
					}
				}
			}
			if (redefinedClFunction) {
				warnClRedefinition(sym.name(), cons, ctx);
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
	 * Reports, ONCE per name, that an operator interception overrode the program's own
	 * {@code defun} of a {@code cl} function. The first call site names the position, and
	 * the rest of them stay quiet -- a program that redefines {@code length} and then
	 * calls it fifty times has one thing wrong with it, not fifty.
	 */
	private static void warnClRedefinition(String name, LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (ctx.warnedClRedefinitions.add(name)) {
			CompileWarnings.warn(SourceProvenance.prefix(cons) + ClRedefinitionWarnings.message(name));
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

	/**
	 * Wraps a path-taking builtin's path argument in the pathname-to-namestring unwrap
	 * ({@link LispMacroExpander#coercePathArg}) -- but ONLY when this compilation's
	 * instance gate is on. With the gate off no pathname value can exist, and skipping
	 * the wrap keeps every instance-free program byte-identical to a build that never
	 * knew about pathnames (the {@code .kb/instance-syntax.md} rule).
	 */
	/**
	 * Wraps the raw handle a stream PRODUCER just left on the stack into the open stream
	 * VALUE, when a stream value can exist in this module at all. With the gate off no
	 * producer is wrapped and no consumer unwraps, so such a program keeps raw handles --
	 * and its exact bytes.
	 */
	private static void wrapStreamValue(WasmLispCompiler.Ctx ctx, String kind) {
		if (ctx.usesStreamValues) {
			WasmInstanceCompiler.emitWrapStream(ctx, kind);
		}
	}

	private static LispCons coercePathArgWhenGated(LispCons cons, int argIndex, WasmLispCompiler.Ctx ctx) {
		return ctx.instanceTypeIndex >= 0 ? LispMacroExpander.coercePathArg(cons, argIndex) : cons;
	}

	/**
	 * Whether a {@code replace} / {@code fill} site may call the named array-arm-only
	 * shared runtime instead of the wide dispatch: its first argument -- the DESTINATION
	 * sequence -- must be provably an array, and the program must carry that helper.
	 * Answering false only costs the site the narrower callee
	 * ({@code .kb/sequence-op-runtimes.md}).
	 */
	private static boolean routesToArrayArm(LispCons cons, String helper, WasmLispCompiler.Ctx ctx) {
		if (!ctx.functions.containsKey(helper) || !(cons.cdr() instanceof LispCons rest)) {
			return false;
		}
		return WasmArrayCompiler.provesArrayValue(rest.car(), ctx);
	}

}
