package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * The shared emission machinery of the {@code --component} async state machines (Phase 7
 * of the callback-async cutover). An {@code rontolisp:async-defun}/ {@code async-lambda}
 * (and a top level that awaits) compiles into an <strong>entry + resume</strong> pair:
 *
 * <ul>
 * <li>The <em>entry</em> keeps the function's public signature. It allocates the spill
 * array and a pending {@code TYPE_FUTURE}, packs both (plus the closure environment) into
 * a {@code TYPE_ASYNC_FRAME}, and eagerly calls the resume with state 0 inside a
 * {@code try_table (catch $lisp-cond)}: a normal completion settles the future, a
 * suspension (the resume returned its own frame -- the unforgeable suspend sentinel)
 * leaves it pending, and an escaping condition rejects it (the error re-signals at
 * {@code await}, matching the interpreter and the JVM).</li>
 * <li>The <em>resume</em> is an ordinary arity-1 function in the dispatch table (so a
 * settling future can wake it through a plain {@code TYPE_CLOSURE} waiter): parameter 0
 * is the frame, parameter 1 is unused (an await landing re-polls its spilled future),
 * local 2 is the {@code $rt} resume-target state (i32), and the original parameters and
 * temporaries follow from slot 3, mirrored one-to-one by the frame's spill array. The
 * prologue restores every local from the spill and jumps -- structurally, through
 * per-structure range guards emitted by the form compilers -- to the target state, so
 * every {@code try_table} on the path is re-entered from the top and the EH context is
 * re-established (the v1 requirement).</li>
 * </ul>
 *
 * A suspension is a plain wasm {@code return} of the frame: legal at every await because
 * awaits only compile at spine positions (empty operand stack -- the
 * {@link WasmAwaitNormalizer} hoist plus the {@code asyncSpine} check), and it skips
 * {@code unwind-protect} cleanups by construction (they re-arm on re-entry). The one
 * global it must repair is the handler-depth counter, decremented once per enclosing
 * {@code handler-case} protected region at the suspend site.
 */
final class WasmAsyncEmit {

	private WasmAsyncEmit() {
	}

	/** Resume-function slot of the {@code $rt} resume-target local (i32). */
	static final int RT_SLOT = 2;

	/** First resume-function slot mirrored by the frame's spill array. */
	static final int SPILL_BASE = 3;

	/** The compiled resume half of an async function. */
	record Resume(int funcId, int funcIndex, int localCount) {
	}

	/**
	 * Compiles a resume body and registers it in the lambda table (so the dispatch
	 * function can wake it), returning its identity and final local count (the entry's
	 * spill-array size).
	 * @param proto a context supplying the shared compilation state
	 * @param paramNames the async function's parameter names
	 * @param bodyExprs the async body
	 * @param freeVarNames the captured variables (an async-lambda's; empty for defuns and
	 * the top level)
	 * @param topLevel whether this is the implicit top-level async function
	 * @param usesEval the top-level eval-mirror flag
	 * @return the resume identity
	 */
	static Resume compileResume(WasmLispCompiler.Ctx proto, List<String> paramNames, List<LispVal> bodyExprs,
			List<String> freeVarNames, boolean topLevel, boolean usesEval) {
		int funcId = proto.nextFuncId[0]++;
		int funcIndex = proto.userFuncBase + proto.numDefuns + proto.lambdaDecls.size();
		int lambdaIdx = proto.lambdaDecls.size();
		// Reserve the slot first: lambdas discovered while compiling the body append
		// after it, keeping every funcIndex consistent.
		proto.lambdaDecls.add(new WasmLispCompiler.LambdaInfo(funcId, "_async_resume_" + funcId,
				List.of("%resume-value"), false, List.of(), List.of(), funcIndex, new byte[] { 0x00, 0x00, 0x0b }));
		proto.indirectCallArities.add(1);

		ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
		WasmWriter bodyWriter = new WasmWriter(bodyBuf);
		WasmLispCompiler.Ctx ctx = freshCtx(proto, bodyWriter, bodyBuf);
		ctx.asyncResume = new WasmLispCompiler.AsyncResume(funcId);
		ctx.topLevel = topLevel;
		ctx.usesEval = usesEval;
		for (int i = 0; i < paramNames.size(); i++) {
			ctx.locals.put(paramNames.get(i), SPILL_BASE + i);
		}
		ctx.nextLocal = SPILL_BASE + paramNames.size();
		int envSlot = -1;
		if (!freeVarNames.isEmpty()) {
			envSlot = ctx.allocTemp();
			ctx.closureEnvSlot = envSlot;
			Map<String, Integer> captures = new HashMap<>();
			for (int i = 0; i < freeVarNames.size(); i++) {
				captures.put(freeVarNames.get(i), i);
			}
			ctx.captures = captures;
		}
		else {
			ctx.closureEnvSlot = -1;
		}
		Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(bodyExprs, new HashSet<>(paramNames),
				proto.functions.keySet());
		ctx.boxedVars = capturedVars;
		compileGuardedProgn(bodyExprs, ctx);
		bodyWriter.write(Instruction.END);

		// Prologue, built now that the local count is final: $rt = frame.state, restore
		// every mirrored local from the spill, re-load the closure environment, and box
		// the captured parameters on the very first segment only.
		ByteArrayOutputStream prologueBuf = new ByteArrayOutputStream();
		WasmWriter p = new WasmWriter(prologueBuf);
		WasmLispCompiler.Ctx prologueCtx = freshCtx(proto, p, prologueBuf);
		frameField(p, ctx, 0);
		p.write(Instruction.SET_LOCAL);
		p.writeUnsignedLeb128(RT_SLOT);
		for (int slot = SPILL_BASE; slot < ctx.nextLocal; slot++) {
			frameField(p, ctx, 1);
			p.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			p.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
			p.write(Instruction.I32_CONST);
			p.writeSignedLeb128(slot);
			p.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
			p.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
			p.write(Instruction.SET_LOCAL);
			p.writeUnsignedLeb128(slot);
		}
		if (envSlot >= 0) {
			frameField(p, ctx, 3);
			p.write(Instruction.SET_LOCAL);
			p.writeUnsignedLeb128(envSlot);
		}
		boolean anyBoxed = false;
		for (String param : paramNames) {
			if (capturedVars.contains(param)) {
				anyBoxed = true;
				break;
			}
		}
		if (anyBoxed) {
			p.write(Instruction.GET_LOCAL);
			p.writeUnsignedLeb128(RT_SLOT);
			p.write(Instruction.I32_EQZ);
			p.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			for (int i = 0; i < paramNames.size(); i++) {
				if (capturedVars.contains(paramNames.get(i))) {
					WasmEmitHelper.emitBoxLocal(prologueCtx, SPILL_BASE + i);
				}
			}
			p.write(Instruction.END);
		}

		// Assemble: local declaration ([1 x i32][N x (ref null eq)]) + prologue + body.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		int eqLocals = ctx.nextLocal - SPILL_BASE;
		if (eqLocals > 0) {
			w.write(2);
			w.writeUnsignedLeb128(1);
			w.write(Type.I32);
			w.writeUnsignedLeb128(eqLocals);
			w.writeRefType(true, Type.EQ.code());
		}
		else {
			w.write(1);
			w.writeUnsignedLeb128(1);
			w.write(Type.I32);
		}
		w.write((Object) prologueBuf.toByteArray());
		w.write((Object) bodyBuf.toByteArray());
		byte[] bytes = out.toByteArray();
		proto.lambdaDecls.set(lambdaIdx, new WasmLispCompiler.LambdaInfo(funcId, "_async_resume_" + funcId,
				List.of("%resume-value"), false, List.of(), List.of(), funcIndex, bytes));
		return new Resume(funcId, funcIndex, ctx.nextLocal);
	}

	/**
	 * Builds an entry body (for an async defun: the defun's own function; slot 0 is the
	 * unused env). Locals declaration included.
	 * @param proto a context supplying the module's async indices
	 * @param paramCount the public parameter count
	 * @param envFromLocal0 whether slot 0 carries a closure environment to store in the
	 * frame (an async-lambda entry)
	 * @param resume the paired resume
	 * @return the function body bytes
	 */
	static byte[] buildEntryBody(WasmLispCompiler.Ctx proto, int paramCount, boolean envFromLocal0, Resume resume) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		int spillArr = paramCount + 1;
		int fut = paramCount + 2;
		int frame = paramCount + 3;
		int r = paramCount + 4;
		// locals: 4x (ref null eq)
		w.write(1);
		w.writeUnsignedLeb128(4);
		w.writeRefType(true, Type.EQ.code());
		// spill = array.new_default(resume local count); spill[3+i] = param i
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(resume.localCount());
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(spillArr);
		for (int i = 0; i < paramCount; i++) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(spillArr);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(SPILL_BASE + i);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(1 + i);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		}
		// future + frame
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(proto.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_NEW);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(fut);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(spillArr);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(fut);
		if (envFromLocal0) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
		}
		else {
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
		}
		// owner = the CURRENT task record (null at a synchronous boundary): the
		// routing key of _wake_list's cross-task doorbell deferral.
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(proto.currentTaskGlobalIndex);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(proto.frameTypeIndex);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(frame);
		// block $h (result eq) { try_table (catch $lisp-cond -> $h) { r = resume(frame,
		// nil) } ... } -- the landing rejects the future with the payload.
		w.write(Instruction.BLOCK);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.TRY_TABLE);
		w.writeRefType(true, Type.EQ.code());
		w.writeUnsignedLeb128(1);
		w.write(Instruction.CATCH);
		w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(frame);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(resume.funcIndex());
		w.write(Instruction.END); // try_table
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(r);
		// Suspended: the future stays pending.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(r);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(frame);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(fut);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// Completed: settle and return the future.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(fut);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(r);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(proto.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SETTLE);
		w.write(Instruction.DROP);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(fut);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // block $h; the payload is on the stack
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(r);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(fut);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(r);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(proto.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_REJECT);
		w.write(Instruction.DROP);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(fut);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits the entry protocol of the implicit top-level async function into the
	 * {@code _start} body (called when the top level contains awaits). A suspension hands
	 * the root future to {@code _sched_loop}, the blocking event loop that settles
	 * registered subtask futures (and, through the waiter cascade, the root) until it
	 * completes -- legal from the async-typed {@code run} task under base
	 * component-model-async. A rejected root re-signals out of the loop and reaches the
	 * surrounding catch-all prologue -- the uncaught-condition trap, as today.
	 * @param ctx the {@code _start} compilation context
	 * @param resume the top-level resume
	 */
	static void emitStartEntry(WasmLispCompiler.Ctx ctx, Resume resume) {
		WasmWriter w = ctx.writer;
		int spillArr = ctx.allocTemp();
		int frame = ctx.allocTemp();
		int r = ctx.allocTemp();
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(resume.localCount());
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(spillArr);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(spillArr);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_NEW);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		// owner: _start is a synchronous boundary, and CURRENT is null there.
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(ctx.currentTaskGlobalIndex);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(ctx.frameTypeIndex);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(frame);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(frame);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(resume.funcIndex());
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(r);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(r);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(frame);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		// Suspended: block on the event loop until the root future settles (a
		// rejection re-signals out of it into the catch-all prologue).
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(frame);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(ctx.frameTypeIndex);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(ctx.frameTypeIndex);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SCHED_LOOP);
		w.write(Instruction.DROP);
		w.write(Instruction.END);
	}

	/**
	 * Compiles a body sequence with resume-routing guards, leaving the last statement's
	 * value (nil when empty). A sequence without awaits compiles exactly as the plain
	 * emission would. The implicit TOP-LEVEL resume instead outlines its await-free runs
	 * into plain functions ({@link WasmToplevelEmit}): a program's top level is unbounded
	 * (the ci-spec corpus concatenates hundreds of cases), and a single resume carrying
	 * all of it -- with the spill-mirrored locals and per-statement guards -- grows past
	 * what Cranelift compiles in sane time (superlinear; the full corpus never finished).
	 * The chunks are the traditional plain shape the non-async {@code _start} always had,
	 * and the resume keeps only the await statements plus one guarded direct call per
	 * chunk. Cutting only at the awaits does not bound anything by itself -- an
	 * await-free run is as long as the program -- so the runs go through the same
	 * size-bounded chunker the synchronous top level uses.
	 * @param stmts the statements
	 * @param ctx the resume compilation context
	 */
	static void compileGuardedProgn(List<LispVal> stmts, WasmLispCompiler.Ctx ctx) {
		if (stmts.isEmpty()) {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			return;
		}
		int total = 0;
		for (LispVal stmt : stmts) {
			total += WasmAwaitAnalysis.countAwaits(stmt);
		}
		if (total == 0) {
			for (int i = 0; i < stmts.size(); i++) {
				if (i > 0) {
					ctx.writer.write(Instruction.DROP);
				}
				WasmExprCompiler.compileExpr(stmts.get(i), ctx);
			}
			return;
		}
		if (ctx.topLevel && ctx.asyncResume != null && !ctx.asyncResume.topLevelChunked) {
			// Outermost top-level body only: a NESTED guarded body (a top-level let's)
			// may reference enclosing locals, which an outlined chunk cannot see.
			ctx.asyncResume.topLevelChunked = true;
			compileTopLevelChunkedProgn(stmts, ctx);
			return;
		}
		for (int i = 0; i < stmts.size() - 1; i++) {
			compileGuardedStatement(stmts.get(i), ctx);
		}
		spine(stmts.get(stmts.size() - 1), ctx);
	}

	// The top-level resume body: await-free runs are outlined into chunk functions
	// (one guarded direct call each); statements with awaits stay inline. Every
	// statement's value is dropped and the progn result is nil (the top-level
	// future's value is unused).
	private static void compileTopLevelChunkedProgn(List<LispVal> stmts, WasmLispCompiler.Ctx ctx) {
		List<LispVal> run = new ArrayList<>();
		for (LispVal stmt : stmts) {
			if (WasmAwaitAnalysis.countAwaits(stmt) == 0) {
				run.add(stmt);
				continue;
			}
			flushTopLevelChunk(run, ctx);
			compileGuardedStatement(stmt, ctx);
		}
		flushTopLevelChunk(run, ctx);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	// Outlines the gathered await-free run into size-bounded chunk functions, each
	// called under the $rt == 0 guard (a resume targeting a later state skips it).
	// The run's boxed-variable set is computed ONCE, over the whole run, so where
	// WasmToplevelEmit chooses to cut cannot change how a variable is stored.
	private static void flushTopLevelChunk(List<LispVal> run, WasmLispCompiler.Ctx ctx) {
		if (run.isEmpty()) {
			return;
		}
		List<LispVal> stmts = new ArrayList<>(run);
		run.clear();
		Set<String> boxedVars = FreeVarAnalyzer.findCapturedVars(stmts, new HashSet<>(), ctx.functions.keySet());
		WasmToplevelEmit.emit(stmts, ctx, boxedVars, true);
	}

	/**
	 * Compiles one non-tail statement of a guarded sequence; its value is dropped. The
	 * statement is skipped when the resume target lies elsewhere, and entered when
	 * executing normally or when the target is one of its own suspend states.
	 * @param stmt the statement
	 * @param ctx the resume compilation context
	 */
	static void compileGuardedStatement(LispVal stmt, WasmLispCompiler.Ctx ctx) {
		int n = WasmAwaitAnalysis.countAwaits(stmt);
		WasmLispCompiler.AsyncResume ar = java.util.Objects.requireNonNull(ctx.asyncResume);
		if (n == 0) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(RT_SLOT);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ctx.wasmCtrlDepth++;
			spine(stmt, ctx);
			ctx.writer.write(Instruction.DROP);
			ctx.wasmCtrlDepth--;
			ctx.writer.write(Instruction.END);
			return;
		}
		int lo = ar.nextState;
		ctx.writer.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth++;
		emitRangeGuard(ctx, lo, lo + n - 1);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 0);
		spine(stmt, ctx);
		ctx.writer.write(Instruction.DROP);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
		assertStates(ctx, lo, n, stmt);
	}

	/**
	 * Compiles a spine child: a position where the operand stack is empty, so an await
	 * there may suspend.
	 * @param form the child form
	 * @param ctx the compilation context
	 */
	static void spine(LispVal form, WasmLispCompiler.Ctx ctx) {
		if (ctx.asyncResume != null) {
			ctx.asyncSpine = true;
		}
		WasmExprCompiler.compileExpr(form, ctx);
	}

	/**
	 * Pushes the i32 guard {@code $rt == 0 || (lo <= $rt <= hi)}.
	 * @param ctx the compilation context
	 * @param lo the first state of the guarded region
	 * @param hi the last state of the guarded region
	 */
	static void emitRangeGuard(WasmLispCompiler.Ctx ctx, int lo, int hi) {
		WasmWriter w = ctx.writer;
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(RT_SLOT);
		w.write(Instruction.I32_EQZ);
		emitInRange(ctx, lo, hi);
		w.write(Instruction.I32_OR);
	}

	/**
	 * Pushes the i32 test {@code lo <= $rt <= hi}.
	 * @param ctx the compilation context
	 * @param lo the range start
	 * @param hi the range end
	 */
	static void emitInRange(WasmLispCompiler.Ctx ctx, int lo, int hi) {
		WasmWriter w = ctx.writer;
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(RT_SLOT);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(lo);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(RT_SLOT);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(hi);
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
	}

	/**
	 * Asserts that emitting a region assigned exactly the states its pre-computed count
	 * promised -- the guard ranges are wrong otherwise (e.g. an expansion duplicated an
	 * await).
	 * @param ctx the compilation context
	 * @param lo the region's first state
	 * @param n the pre-computed count
	 * @param form the region's source form
	 */
	static void assertStates(WasmLispCompiler.Ctx ctx, int lo, int n, LispVal form) {
		int assigned = java.util.Objects.requireNonNull(ctx.asyncResume).nextState - lo;
		if (assigned != n) {
			throw new IllegalStateException("async state-count mismatch compiling " + form.print() + ": analysis saw "
					+ n + " awaits, emission assigned " + assigned);
		}
	}

	/**
	 * Compiles an await at a spine position of a resume body (the state-machine suspend
	 * point).
	 * @param cons the {@code (rontolisp:await expr)} form
	 * @param ctx the resume compilation context
	 */
	static void compileAwait(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("await expects 1 argument, got " + (args.size() - 1));
		}
		if (!ctx.asyncSpineCurrent) {
			throw new UnsupportedOperationException(
					"rontolisp:await in this position is not supported on the --component backend;"
							+ " bind the value first: (let ((v (rontolisp:await ...))) ...)");
		}
		WasmLispCompiler.AsyncResume ar = java.util.Objects.requireNonNull(ctx.asyncResume);
		WasmWriter w = ctx.writer;
		int k = ar.nextState++;
		int futSlot = ctx.allocTemp();
		// Candidate: the resume landing re-loads the spilled future; the normal path
		// evaluates the awaited expression (a resume targeting a state INSIDE that
		// expression routes through the normal arm and dispatches there).
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(RT_SLOT);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(k);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(RT_SLOT);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(futSlot);
		w.write(Instruction.ELSE);
		spine(args.get(1), ctx);
		ctx.wasmCtrlDepth--;
		w.write(Instruction.END);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(futSlot);
		// Poll: settled chains flatten to the value, a rejection re-signals, a pending
		// future comes back unchanged and suspends this frame.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(futSlot);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_POLL);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(futSlot);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(futSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(ctx.futureTypeIndex);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth++;
		// Suspend: record the state, spill the mirrored locals, register this frame as
		// a waiter, repair the handler-depth counter, and return the frame sentinel.
		frameCast(ctx);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(k);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(ctx.frameTypeIndex);
		w.writeUnsignedLeb128(0);
		int arrSlot = ctx.allocTemp();
		int spillBound = ctx.nextLocal;
		frameCast(ctx);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(ctx.frameTypeIndex);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(arrSlot);
		for (int slot = SPILL_BASE; slot < spillBound; slot++) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(arrSlot);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(slot);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(slot);
			w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		}
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(futSlot);
		// The waiter closure over the resume function: a third place a funcId becomes a
		// callable value (Ctx.valueFuncIds), and the only one outside
		// WasmFunctionFormCompiler/WasmLambdaCompiler -- the future runtime calls the
		// waiter back through the arity-1 dispatcher, so its case must survive.
		ctx.valueFuncIds.add(ar.resumeFuncId);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(ar.resumeFuncId);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_ADD_WAITER);
		w.write(Instruction.DROP);
		for (WasmLispCompiler.UnwindScope scope : ctx.unwindScopes) {
			if (isHandlerDepthScope(scope)) {
				WasmHandlerCaseCompiler.emitDepthAdjust(ctx, false);
			}
		}
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.RETURN);
		ctx.wasmCtrlDepth--;
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(futSlot);
	}

	/**
	 * Compiles an {@code rontolisp:async-lambda} value in state-machine mode: an entry +
	 * resume lambda pair sharing the async-lambda's captured environment (the entry
	 * stores it in the frame; the resume reads captures back out of it).
	 * @param cons the async-lambda form
	 * @param ctx the enclosing compilation context
	 */
	static void compileAsyncLambdaValue(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		LambdaLists.NativeForm nf = LambdaLists.toNative(parts.get(1), parts.subList(2, parts.size()));
		List<String> paramNames = nf.paramNames();
		List<LispVal> bodyExprs = nf.body();
		Set<String> enclosingLexicals = new HashSet<>(ctx.locals.keySet());
		enclosingLexicals.addAll(ctx.captures.keySet());
		List<String> freeVars = new ArrayList<>(FreeVarAnalyzer.findFreeVars(bodyExprs, new HashSet<>(paramNames),
				ctx.functions.keySet(), ctx.globals, enclosingLexicals));
		Resume resume = compileResume(ctx, paramNames, bodyExprs, freeVars, false, false);
		int entryFuncId = ctx.nextFuncId[0]++;
		int entryFuncIndex = ctx.userFuncBase + ctx.numDefuns + ctx.lambdaDecls.size();
		byte[] entryBody = buildEntryBody(ctx, paramNames.size(), true, resume);
		ctx.lambdaDecls.add(new WasmLispCompiler.LambdaInfo(entryFuncId, "_async_entry_" + entryFuncId, paramNames,
				nf.variadic(), List.of(), freeVars, entryFuncIndex, entryBody));
		WasmLambdaCompiler.emitClosureValue(entryFuncId, freeVars, ctx);
	}

	/**
	 * Returns whether an unwind scope is a {@code handler-case} protected region (its one
	 * cleanup is the internal handler-depth decrement) -- the scopes whose depth
	 * increments a suspension must undo.
	 */
	private static boolean isHandlerDepthScope(WasmLispCompiler.UnwindScope scope) {
		return scope.cleanupForms().size() == 1 && scope.cleanupForms().get(0) instanceof LispCons form
				&& form.car() instanceof LispSymbol head && LispNames.HC_DEPTH_DEC_INTERNAL.equals(head.name());
	}

	// frame (local 0) cast to TYPE_ASYNC_FRAME
	private static void frameCast(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(ctx.frameTypeIndex);
	}

	// frame field via a fresh writer (the prologue), reading through slot 0
	private static void frameField(WasmWriter w, WasmLispCompiler.Ctx ctx, int field) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(ctx.frameTypeIndex);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(ctx.frameTypeIndex);
		w.writeUnsignedLeb128(field);
	}

	/**
	 * Builds a fresh compilation context sharing {@code proto}'s module-wide state over a
	 * new writer (the async pairs compile their halves out of line).
	 * @param proto the prototype context
	 * @param writer the new writer
	 * @param bodyStream the new body stream
	 * @return the fresh context
	 */
	static WasmLispCompiler.Ctx freshCtx(WasmLispCompiler.Ctx proto, WasmWriter writer,
			ByteArrayOutputStream bodyStream) {
		WasmLispCompiler.Ctx ctx = WasmLispCompiler.Ctx.builder()
			.writer(writer)
			.bodyStream(bodyStream)
			.stringTable(proto.stringTable)
			.functions(proto.functions)
			.lambdaDecls(proto.lambdaDecls)
			.indirectCallArities(proto.indirectCallArities)
			// Module-wide and MUTATED during emission, like indirectCallArities above:
			// freshCtx also builds the synchronous top level, so dropping it here loses
			// every closure the top level materializes and the dispatch ladders lose
			// their cases for them (a trap at the first (funcall f ...)).
			.valueFuncIds(proto.valueFuncIds)
			.nextFuncId(proto.nextFuncId)
			.dynamic(proto.dynamic)
			// The level decides emission shape (the fusion/unboxed-local trades), so a
			// chunk built here must carry it: an async module's SYNCHRONOUS top level is
			// built through this method too, and would otherwise fuse under
			// --optimize=size while the same form in a defun did not.
			.optimize(proto.optimize)
			.component(proto.component)
			.serve(proto.serve)
			.ehMode(proto.ehMode)
			.blockExitTag(proto.blockExitTag)
			.restartMode(proto.restartMode)
			.usesSeqString(proto.usesSeqString)
			.ehDepthGlobalIndex(proto.ehDepthGlobalIndex)
			.simd(proto.simd)
			.userFuncBase(proto.userFuncBase)
			.numDefuns(proto.numDefuns)
			.userDefunNames(proto.userDefunNames)
			.usesFmakunbound(proto.usesFmakunbound)
			.packageTable(proto.packageTable)
			.structAccessors(proto.structAccessors)
			.closRegistry(proto.closRegistry)
			.globals(proto.globals)
			.specialVars(proto.specialVars)
			.globalIndices(proto.globalIndices)
			.futureTypeIndex(proto.futureTypeIndex)
			.frameTypeIndex(proto.frameTypeIndex)
			.wasiStreamTypeIndex(proto.wasiStreamTypeIndex)
			// NOT optional: freshCtx also builds the SYNCHRONOUS top level, so without
			// these a top-level %obj-* would compile with no type index and no layout
			// addresses while the same form inside a defun worked.
			.instanceTypeIndex(proto.instanceTypeIndex)
			.layoutAddresses(proto.layoutAddresses)
			.asyncFuncBase(proto.asyncFuncBase)
			.asyncDefunNames(proto.asyncDefunNames)
			.currentTaskGlobalIndex(proto.currentTaskGlobalIndex)
			.callbackExports(proto.callbackExports)
			.build();
		// A top level split across several contexts is still ONE top level: defvar's
		// compile-time "already initialized" set has to be the same object, or a name
		// defvar'd in two chunks is initialized twice.
		ctx.definedGlobals = proto.definedGlobals;
		return ctx;
	}

}
