package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * Emits the synchronous top level as a sequence of bounded chunk functions called from
 * {@code _start}, instead of concatenating every top-level form into one body.
 * <p>
 * The size of a single function body is what decides whether a module can be run at all:
 * a wasmtime cold compile needs memory superlinear -- about the 1.8th power -- in the
 * size of ONE body, so a program with a long top level does not merely compile slowly, it
 * exhausts the machine (850 KB of body peaks at 25.8 GB). A program's top level is
 * unbounded, so it is the one body that must not be allowed to grow with the source. See
 * {@code .kb/wasm-function-body-size.md}; the bound is pinned by
 * {@code WasmToplevelChunkingTest}.
 * <p>
 * The async top level ({@link WasmAsyncEmit#compileGuardedProgn}) cuts at its await
 * statements, which bounds nothing by itself -- an await-free run is as long as the
 * program -- so it shares the chunking here, with each chunk call wrapped in the resume's
 * {@code $rt == 0} guard. Each chunk is an arity-0 callable ({@code (env) -> eq}) called
 * directly, never through the dispatch, and its value is dropped -- exactly what the
 * concatenated body did with each form.
 */
final class WasmToplevelEmit {

	private WasmToplevelEmit() {
	}

	/**
	 * Close a chunk once its body passes this many bytes. A chunk therefore ends up at
	 * most this plus one form's worth of code, so the bound holds for any program whose
	 * individual top-level forms are of ordinary size. Small enough to leave a wide
	 * margin under the pinned 256 KiB, large enough that an ordinary program produces a
	 * handful of chunks rather than hundreds of tiny ones.
	 */
	private static final int CHUNK_TARGET_BYTES = 48 * 1024;

	/**
	 * Compiles every top-level form into chunk functions and emits the calls to them into
	 * {@code start}. Each chunk's value is dropped, so the stack is left exactly as the
	 * concatenated emission left it.
	 * @param exprs the top-level forms
	 * @param start the {@code _start} compilation context
	 */
	static void emit(List<LispVal> exprs, WasmLispCompiler.Ctx start) {
		emit(exprs, start, null, false);
	}

	/**
	 * Compiles a run of top-level forms into chunk functions and emits the calls into
	 * {@code start}.
	 * @param exprs the forms of one run
	 * @param start the enclosing top-level context ({@code _start}, or the top-level
	 * async resume)
	 * @param boxedVars the boxed-variable set every chunk of this run compiles under, or
	 * {@code null} to leave each chunk context's default. Computed once over the whole
	 * run rather than per chunk, so where the run is cut cannot change how a variable is
	 * stored
	 * @param guarded wrap each chunk call in the async resume's {@code $rt == 0} guard,
	 * so a resume targeting a later suspend state skips the chunk
	 */
	static void emit(List<LispVal> exprs, WasmLispCompiler.Ctx start, @Nullable Set<String> boxedVars,
			boolean guarded) {
		Chunk chunk = null;
		// A named local allocated in a chunk cannot be read from the next one. Every
		// top-level assignment is backed by a module global instead -- GlobalVarCollector
		// collects nested assignments precisely so the top level allocates none -- so
		// this should never trip; if it does, stop cutting rather than outline a reader
		// away from its variable. A program that trips it merely compiles as one chunk,
		// the way the whole top level used to.
		boolean pinnedByLocals = false;
		for (LispVal expr : exprs) {
			if (chunk == null) {
				chunk = openChunk(start, boxedVars);
			}
			int localsBefore = chunk.ctx.locals.size();
			WasmExprCompiler.compileExpr(expr, chunk.ctx);
			chunk.writer.write(Instruction.DROP);
			if (chunk.ctx.locals.size() > localsBefore) {
				pinnedByLocals = true;
			}
			if (!pinnedByLocals && chunk.body.size() >= CHUNK_TARGET_BYTES) {
				closeChunk(chunk, start, guarded);
				chunk = null;
			}
		}
		if (chunk != null) {
			closeChunk(chunk, start, guarded);
		}
	}

	private record Chunk(WasmLispCompiler.Ctx ctx, WasmWriter writer, ByteArrayOutputStream body, int lambdaIdx,
			int funcId, int funcIndex) {
	}

	/**
	 * Reserves the chunk's function index and lambda-table slot, and builds the context
	 * its body compiles into. The slot is reserved BEFORE the body is compiled, like
	 * {@code compileResume}: lambdas discovered while compiling the body append after it.
	 */
	private static Chunk openChunk(WasmLispCompiler.Ctx start, @Nullable Set<String> boxedVars) {
		int funcId = start.nextFuncId[0]++;
		int funcIndex = start.userFuncBase + start.functions.size() + start.lambdaDecls.size();
		int lambdaIdx = start.lambdaDecls.size();
		start.lambdaDecls.add(placeholder(funcId, funcIndex));
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter writer = new WasmWriter(body);
		WasmLispCompiler.Ctx ctx = WasmAsyncEmit.freshCtx(start, writer, body);
		// topLevel/usesEval carry over so eval-global mirroring keeps working inside a
		// chunk. On the synchronous path boxedVars stays at its default, which is what
		// _start itself uses, so a chunk's body is byte-identical to the run it was cut
		// from; the async path passes its run's set for the same reason.
		ctx.topLevel = true;
		ctx.usesEval = start.usesEval;
		ctx.closureEnvSlot = 0;
		ctx.nextLocal = 1;
		if (boxedVars != null) {
			ctx.boxedVars = boxedVars;
		}
		return new Chunk(ctx, writer, body, lambdaIdx, funcId, funcIndex);
	}

	/**
	 * Finishes the chunk's body and emits {@code ref.null eq; call chunk; drop} into the
	 * enclosing body, under the resume's {@code $rt == 0} guard when {@code guarded}.
	 */
	private static void closeChunk(Chunk chunk, WasmLispCompiler.Ctx start, boolean guarded) {
		chunk.writer.write(Instruction.REF_NULL);
		chunk.writer.writeHeapType(Type.EQ.code());
		chunk.writer.write(Instruction.END);

		start.lambdaDecls.set(chunk.lambdaIdx,
				body(chunk.funcId, chunk.funcIndex, WasmLispCompiler.buildLocalsAndPatch(chunk.ctx, 1, chunk.body)));

		WasmWriter s = start.writer;
		if (guarded) {
			s.write(Instruction.GET_LOCAL);
			s.writeSignedLeb128(WasmAsyncEmit.RT_SLOT);
			s.write(Instruction.I32_EQZ);
			s.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			start.wasmCtrlDepth++;
		}
		s.write(Instruction.REF_NULL);
		s.writeHeapType(Type.EQ.code());
		s.write(Instruction.CALL);
		s.writeSignedLeb128(chunk.funcIndex);
		s.write(Instruction.DROP);
		if (guarded) {
			start.wasmCtrlDepth--;
			s.write(Instruction.END);
		}
	}

	private static WasmLispCompiler.LambdaInfo placeholder(int funcId, int funcIndex) {
		return body(funcId, funcIndex, new byte[] { 0x00, 0x00, 0x0b });
	}

	private static WasmLispCompiler.LambdaInfo body(int funcId, int funcIndex, byte[] precompiled) {
		return new WasmLispCompiler.LambdaInfo(funcId, "_toplevel_chunk_" + funcId, List.of(), false, List.of(),
				List.of(), funcIndex, precompiled);
	}

}
