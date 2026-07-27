package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code progn} special form.
 */
final class WasmPrognCompiler {

	private WasmPrognCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 1) {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(am.ik.wasm.Type.EQ.code());
			return;
		}
		if (ctx.asyncResume != null) {
			// state-machine mode: resume-routing guards per statement (a no-await
			// sequence compiles exactly like the plain loop below)
			WasmAsyncEmit.compileGuardedProgn(parts.subList(1, parts.size()), ctx);
			return;
		}
		for (int i = 1; i < parts.size() - 1; i++) {
			// Statement position: compileForEffect lets a packed integer-vector store
			// skip materializing its (discarded) value-as-stored.
			WasmExprCompiler.compileForEffect(parts.get(i), ctx);
		}
		WasmExprCompiler.compileExpr(parts.get(parts.size() - 1), ctx);
	}

}
