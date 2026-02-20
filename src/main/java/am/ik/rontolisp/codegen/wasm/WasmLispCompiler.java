package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.rontolisp.compiler.LispCompiler;
import am.ik.wasm.ExternalKind;
import am.ik.wasm.Instruction;
import am.ik.wasm.Section;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Compiles Lisp expressions to WASM binary with wasm-GC and WASI Preview 1. All Lisp
 * values are represented as (ref eq) on the stack: integers use i31ref, nil uses ref.null
 * eq, strings use a string struct, cons cells use a cons struct, and closures use a
 * closure struct. Supports first-class functions via dispatch functions and closure
 * structs.
 */
public final class WasmLispCompiler implements LispCompiler {

	// Function indices (imports come first)
	private static final int FUNC_FD_WRITE = 0; // imported

	private static final int FUNC_START = 1;

	private static final int FUNC_PRINT_I32 = 2;

	private static final int FUNC_WRITE_STR = 3;

	private static final int FUNC_PRINT_VAL = 4;

	private static final int FUNC_PRINT_I32_NO_NL = 5;

	private static final int FUNC_DISPATCH_BASE = 6;

	private static final int MAX_CALLABLE_ARITY = 7;

	// Dispatch functions occupy indices 6..13 (arities 0..7)
	private static final int FUNC_USER_BASE = FUNC_DISPATCH_BASE + MAX_CALLABLE_ARITY + 1; // 14

	// Type indices
	private static final int TYPE_FD_WRITE = 0;

	private static final int TYPE_START = 1;

	private static final int TYPE_PRINT_I32 = 2; // also for _print_i32_no_nl

	private static final int TYPE_CONS = 3; // in rec group

	private static final int TYPE_STRING = 4; // in rec group

	private static final int TYPE_CELL = 5; // in rec group - {(mut ref null eq)}

	private static final int TYPE_CLOSURE = 6; // in rec group - {i32 funcId, (ref null
												// eq)
												// env}

	private static final int TYPE_WRITE_STR = 7; // (i32, i32) -> ()

	private static final int TYPE_PRINT_VAL = 8; // ((ref null eq)) -> ()

	// Callable types: arity N = (ref null eq)^(N+1) -> (ref null eq)
	// Used by dispatch functions and user functions (defuns/lambdas) alike
	private static final int TYPE_CALLABLE_BASE = 9;

	// callable_arity_N type index = TYPE_CALLABLE_BASE + N (indices 9..16)

	// Memory layout
	private static final int PRINT_BUF_OFFSET = 0;

	private static final int IOV_OFFSET = 32;

	private static final int NWRITTEN_OFFSET = 48;

	private static final int OUT_BUF_OFFSET = 64;

	private static final int DATA_BASE_OFFSET = 128;

	@Override
	public byte[] compile(List<LispVal> program) {
		// Pass 1: Collect defun declarations and top-level expressions
		List<DefunDecl> defuns = new ArrayList<>();
		List<LispVal> topLevelExprs = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym && "defun".equals(sym.name())) {
				List<LispVal> parts = cons.toList();
				String funcName = ((LispSymbol) parts.get(1)).name();
				LispVal paramsVal = parts.get(2);
				List<String> paramNames;
				if (paramsVal instanceof LispNil) {
					paramNames = List.of();
				}
				else {
					paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
				}
				defuns.add(new DefunDecl(funcName, paramNames, parts.subList(3, parts.size())));
			}
			else if (isSetqLambda(expr)) {
				defuns.add(extractSetqLambda(expr));
			}
			else {
				topLevelExprs.add(expr);
			}
		}

		// Create string table
		StringTable stringTable = new StringTable(DATA_BASE_OFFSET);

		// Assign funcIds and build function info map
		int[] nextFuncId = { 0 };
		Map<String, WasmFunctionInfo> functions = new HashMap<>();
		for (int i = 0; i < defuns.size(); i++) {
			DefunDecl defun = defuns.get(i);
			int funcId = nextFuncId[0]++;
			int arity = defun.paramNames.size();
			functions.put(defun.name,
					new WasmFunctionInfo(defun.name, arity, funcId, TYPE_CALLABLE_BASE + arity, FUNC_USER_BASE + i));
		}

		// Shared state for lambda discovery
		List<LambdaInfo> lambdaDecls = new ArrayList<>();
		Set<Integer> indirectCallArities = new HashSet<>();

		// Pass 2a: Compile each defun body (with env param at slot 0)
		List<byte[]> userFunctionBodies = new ArrayList<>();
		for (DefunDecl defun : defuns) {
			ByteArrayOutputStream funcBody = new ByteArrayOutputStream();
			WasmWriter funcWriter = new WasmWriter(funcBody);
			Ctx funcCtx = new Ctx(funcWriter, funcBody, stringTable);
			funcCtx.functions = functions;
			funcCtx.lambdaDecls = lambdaDecls;
			funcCtx.indirectCallArities = indirectCallArities;
			funcCtx.nextFuncId = nextFuncId;

			// Slot 0 = env (unused for defuns), params start at slot 1
			funcCtx.closureEnvSlot = 0;
			for (int i = 0; i < defun.paramNames.size(); i++) {
				funcCtx.locals.put(defun.paramNames.get(i), i + 1);
			}
			funcCtx.nextLocal = defun.paramNames.size() + 1;

			// Determine which params are captured by nested lambdas
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(defun.bodyExprs,
					new HashSet<>(defun.paramNames), functions.keySet());
			funcCtx.boxedVars = capturedVars;
			// Box captured params
			for (String paramName : defun.paramNames) {
				if (capturedVars.contains(paramName)) {
					Integer slot = funcCtx.locals.get(paramName);
					if (slot != null) {
						emitBoxLocal(funcCtx, slot);
					}
				}
			}

			for (int i = 0; i < defun.bodyExprs.size(); i++) {
				if (i > 0) {
					funcWriter.write(Instruction.DROP);
				}
				compileExpr(defun.bodyExprs.get(i), funcCtx);
			}
			funcWriter.write(Instruction.END);

			// Rebuild with correct local declarations (extra locals beyond env+params)
			ByteArrayOutputStream finalFuncBody = new ByteArrayOutputStream();
			WasmWriter finalFuncWriter = new WasmWriter(finalFuncBody);
			int extraLocals = funcCtx.nextLocal - (defun.paramNames.size() + 1);
			if (extraLocals > 0) {
				finalFuncWriter.write(1);
				finalFuncWriter.write(extraLocals);
				finalFuncWriter.write(Type.REFNULL.code());
				finalFuncWriter.writeHeapType(Type.EQ.code());
			}
			else {
				finalFuncWriter.write(0);
			}
			finalFuncWriter.write((Object) funcBody.toByteArray());
			userFunctionBodies.add(finalFuncBody.toByteArray());
		}

		// Pass 2b: Build _start function body
		ByteArrayOutputStream startBody = new ByteArrayOutputStream();
		WasmWriter startWriter = new WasmWriter(startBody);
		Ctx ctx = new Ctx(startWriter, startBody, stringTable);
		ctx.functions = functions;
		ctx.lambdaDecls = lambdaDecls;
		ctx.indirectCallArities = indirectCallArities;
		ctx.nextFuncId = nextFuncId;

		for (LispVal expr : topLevelExprs) {
			compileExpr(expr, ctx);
			startWriter.write(Instruction.DROP);
		}
		startWriter.write(Instruction.END);

		ByteArrayOutputStream finalStartBody = new ByteArrayOutputStream();
		WasmWriter finalStartWriter = new WasmWriter(finalStartBody);
		int numLocals = ctx.nextLocal;
		if (numLocals > 0) {
			finalStartWriter.write(1);
			finalStartWriter.write(numLocals);
			finalStartWriter.write(Type.REFNULL.code());
			finalStartWriter.writeHeapType(Type.EQ.code());
		}
		else {
			finalStartWriter.write(0);
		}
		finalStartWriter.write((Object) startBody.toByteArray());

		// Pass 2c: Compile lambda bodies iteratively
		List<byte[]> lambdaFunctionBodies = new ArrayList<>();
		int lambdaIdx = 0;
		while (lambdaIdx < lambdaDecls.size()) {
			LambdaInfo lambda = lambdaDecls.get(lambdaIdx);
			ByteArrayOutputStream lambdaBody = new ByteArrayOutputStream();
			WasmWriter lambdaWriter = new WasmWriter(lambdaBody);
			Ctx lambdaCtx = new Ctx(lambdaWriter, lambdaBody, stringTable);
			lambdaCtx.functions = functions;
			lambdaCtx.lambdaDecls = lambdaDecls;
			lambdaCtx.indirectCallArities = indirectCallArities;
			lambdaCtx.nextFuncId = nextFuncId;

			// Slot 0 = env (closure environment)
			lambdaCtx.closureEnvSlot = 0;
			// Lambda params start at slot 1
			for (int i = 0; i < lambda.paramNames.size(); i++) {
				lambdaCtx.locals.put(lambda.paramNames.get(i), i + 1);
			}
			lambdaCtx.nextLocal = lambda.paramNames.size() + 1;

			// Set up captures mapping (free vars accessed from env cons list)
			Map<String, Integer> captures = new HashMap<>();
			for (int i = 0; i < lambda.freeVarNames.size(); i++) {
				captures.put(lambda.freeVarNames.get(i), i);
			}
			lambdaCtx.captures = captures;

			// Determine which locals are captured by further nested lambdas
			Set<String> lambdaLocalVars = new HashSet<>(lambda.paramNames);
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(lambda.bodyExprs, lambdaLocalVars,
					functions.keySet());
			lambdaCtx.boxedVars = capturedVars;
			// Box captured params of this lambda
			for (String paramName : lambda.paramNames) {
				if (capturedVars.contains(paramName)) {
					Integer slot = lambdaCtx.locals.get(paramName);
					if (slot != null) {
						emitBoxLocal(lambdaCtx, slot);
					}
				}
			}

			for (int i = 0; i < lambda.bodyExprs.size(); i++) {
				if (i > 0) {
					lambdaWriter.write(Instruction.DROP);
				}
				compileExpr(lambda.bodyExprs.get(i), lambdaCtx);
			}
			lambdaWriter.write(Instruction.END);

			ByteArrayOutputStream finalLambdaBody = new ByteArrayOutputStream();
			WasmWriter finalLambdaWriter = new WasmWriter(finalLambdaBody);
			int extraLocals = lambdaCtx.nextLocal - (lambda.paramNames.size() + 1);
			if (extraLocals > 0) {
				finalLambdaWriter.write(1);
				finalLambdaWriter.write(extraLocals);
				finalLambdaWriter.write(Type.REFNULL.code());
				finalLambdaWriter.writeHeapType(Type.EQ.code());
			}
			else {
				finalLambdaWriter.write(0);
			}
			finalLambdaWriter.write((Object) lambdaBody.toByteArray());
			lambdaFunctionBodies.add(finalLambdaBody.toByteArray());
			lambdaIdx++;
		}

		// Build dispatch function bodies
		int numDefuns = defuns.size();
		int numLambdas = lambdaDecls.size();
		List<byte[]> dispatchBodies = new ArrayList<>();
		for (int arity = 0; arity <= MAX_CALLABLE_ARITY; arity++) {
			if (indirectCallArities.contains(arity)) {
				dispatchBodies.add(buildDispatchBody(arity, defuns, lambdaDecls, numDefuns, stringTable));
			}
			else {
				// Unused arity: unreachable body
				ByteArrayOutputStream db = new ByteArrayOutputStream();
				WasmWriter dw = new WasmWriter(db);
				dw.write(0); // 0 locals
				dw.write(Instruction.UNREACHABLE);
				dw.write(Instruction.END);
				dispatchBodies.add(db.toByteArray());
			}
		}

		// Build helper function bodies
		byte[] printI32Body = buildPrintI32Core(true);
		byte[] writeStrBody = buildWriteStrBody();
		byte[] printValBody = buildPrintValBody(stringTable);
		byte[] printI32NoNlBody = buildPrintI32Core(false);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter mainWriter = new WasmWriter(out);
		mainWriter //
			.write("\0asm")
			.writeLittleEndian4(1)
			// Type section
			.writeTypeSection(types -> {
				// type 0: fd_write
				types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// type 1: _start
				types.addFunc(new Type[] {}, new Type[] {});
				// type 2: print_i32 / _print_i32_no_nl
				types.addFunc(new Type[] { Type.I32 }, new Type[] {});
				// types 3-6: struct types in rec group
				types.addRecGroup(rec -> {
					// type 3: cons struct
					rec.addSubFinalStruct(fields -> {
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					});
					// type 4: string struct
					rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.write(Type.I32));
					});
					// type 5: cell struct {(mut ref null eq) value}
					rec.addSubFinalStruct(fields -> {
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					});
					// type 6: closure struct {i32 funcId, (ref null eq) env}
					rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
					});
				});
				// type 7: _write_str
				types.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] {});
				// type 8: _print_val
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
					w.write(0);
				});
				// types 9-16: callable types for arities 0-7
				// Each: (ref null eq)^(arity+1) -> (ref null eq)
				for (int arity = 0; arity <= MAX_CALLABLE_ARITY; arity++) {
					int paramCount = arity + 1; // env + args
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(paramCount);
						for (int i = 0; i < paramCount; i++) {
							w.write(Type.REFNULL.code());
							w.writeHeapType(Type.EQ.code());
						}
						w.write(1);
						w.write(Type.REFNULL.code());
						w.writeHeapType(Type.EQ.code());
					});
				}
			})
			// Import section
			.writeImportSection(imports -> imports.addImport("wasi_snapshot_preview1", "fd_write",
					ExternalKind.FUNCTION, TYPE_FD_WRITE))
			// Function section
			.writeFunction(fnDef -> {
				fnDef.addFunction(TYPE_START) // _start
					.addFunction(TYPE_PRINT_I32) // print_i32
					.addFunction(TYPE_WRITE_STR) // _write_str
					.addFunction(TYPE_PRINT_VAL) // _print_val
					.addFunction(TYPE_PRINT_I32); // _print_i32_no_nl
				// Dispatch functions (arities 0-7)
				for (int arity = 0; arity <= MAX_CALLABLE_ARITY; arity++) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + arity);
				}
				// User defun functions
				for (DefunDecl defun : defuns) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + defun.paramNames.size());
				}
				// Lambda functions
				for (LambdaInfo lambda : lambdaDecls) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + lambda.paramNames.size());
				}
			})
			// Memory section
			.writeMemory(memories -> memories.addMemory(1))
			// Export section
			.writeExport(exports -> exports.addExport("memory", ExternalKind.MEMORY, 0)
				.addExport("_start", ExternalKind.FUNCTION, FUNC_START))
			// Code section
			.writeCode(code -> {
				code.addFunction(finalStartBody.toByteArray())
					.addFunction(printI32Body)
					.addFunction(writeStrBody)
					.addFunction(printValBody)
					.addFunction(printI32NoNlBody);
				// Dispatch function bodies
				for (byte[] body : dispatchBodies) {
					code.addFunction(body);
				}
				// User defun function bodies
				for (byte[] body : userFunctionBodies) {
					code.addFunction(body);
				}
				// Lambda function bodies
				for (byte[] body : lambdaFunctionBodies) {
					code.addFunction(body);
				}
			})
			// Data section
			.writeDataSection(data -> {
				byte[] stringData = stringTable.toByteArray();
				if (stringData.length > 0) {
					data.addActiveData(0, DATA_BASE_OFFSET, stringData);
				}
			});
		return out.toByteArray();
	}

	private void compileExpr(LispVal expr, Ctx ctx) {
		switch (expr) {
			case LispInteger i -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128((int) i.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispNil ignored -> {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			case LispTrue ignored -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispString s -> compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileSymbolRef(sym, ctx);
			case LispCons cons -> compileCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	private void compileSymbolRef(LispSymbol sym, Ctx ctx) {
		String name = sym.name();
		// Check local variables
		Integer slot = ctx.locals.get(name);
		if (slot != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			if (ctx.boxedVars.contains(name)) {
				// Unbox from cell
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(TYPE_CELL);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
				ctx.writer.writeSignedLeb128(TYPE_CELL);
				ctx.writer.writeSignedLeb128(0);
			}
			return;
		}
		// Check captured variables
		Integer captureIdx = ctx.captures.get(name);
		if (captureIdx != null) {
			emitLoadCapture(ctx, captureIdx);
			return;
		}
		// Check known functions (create function reference)
		WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			// Create closure struct {funcId, null env}
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(fi.funcId);
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(TYPE_CLOSURE);
			return;
		}
		throw new UnsupportedOperationException("Cannot compile symbol: " + name);
	}

	private void compileCons(LispCons cons, Ctx ctx) {
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case "+" -> compileArith(cons, ctx, Instruction.I32_ADD);
				case "-" -> compileArith(cons, ctx, Instruction.I32_SUB);
				case "*" -> compileArith(cons, ctx, Instruction.I32_MUL);
				case "/" -> compileArith(cons, ctx, Instruction.I32_DIV_S);
				case "mod" -> compileArith(cons, ctx, Instruction.I32_REM_S);
				case "=" -> compileComparison(cons, ctx, Instruction.I32_EQ);
				case "<" -> compileComparison(cons, ctx, Instruction.I32_LT_S);
				case ">" -> compileComparison(cons, ctx, Instruction.I32_GT_S);
				case "<=" -> compileComparison(cons, ctx, Instruction.I32_LE_S);
				case ">=" -> compileComparison(cons, ctx, Instruction.I32_GE_S);
				case "print" -> compilePrint(cons, ctx);
				case "quote" -> compileQuote(cons, ctx);
				case "if" -> compileIf(cons, ctx);
				case "let" -> compileLet(cons, ctx);
				case "progn" -> compileProgn(cons, ctx);
				case "setq" -> compileSetq(cons, ctx);
				case "lambda" -> compileLambdaValue(cons, ctx);
				case "defun" -> {
					ctx.writer.write(Instruction.REF_NULL);
					ctx.writer.writeHeapType(Type.EQ.code());
				}
				case "list" -> compileList(cons, ctx);
				case "car" -> compileCar(cons, ctx);
				case "cdr" -> compileCdr(cons, ctx);
				case "cons" -> compileConsBuiltin(cons, ctx);
				case "funcall" -> compileFuncall(cons, ctx);
				default -> {
					// Check if the symbol is a local/capture (indirect call) or a
					// known function (direct call)
					String name = sym.name();
					if (ctx.locals.containsKey(name) || ctx.captures.containsKey(name)) {
						compileIndirectCall(cons, ctx);
					}
					else {
						compileFunctionCall(name, cons, ctx);
					}
				}
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& "lambda".equals(headSym.name())) {
			compileLambdaCall(headCons, cons, ctx);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + cons.print());
		}
	}

	private void compileArith(LispCons cons, Ctx ctx, int opcode) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		castI31GetS(ctx);
		for (int i = 2; i < args.size(); i++) {
			compileExpr(args.get(i), ctx);
			castI31GetS(ctx);
			ctx.writer.write(opcode);
		}
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	private void compileComparison(LispCons cons, Ctx ctx, int opcode) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		castI31GetS(ctx);
		compileExpr(args.get(2), ctx);
		castI31GetS(ctx);
		ctx.writer.write(opcode);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	private void compilePrint(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(FUNC_PRINT_VAL);
		// Write newline
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(FUNC_WRITE_STR);
		// Return nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	private void compileStringLiteral(String displayForm, Ctx ctx) {
		StringTable.StringEntry entry = ctx.stringTable.addString(displayForm);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.length());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(TYPE_STRING);
	}

	private void compileQuote(LispCons cons, Ctx ctx) {
		LispVal quoted = ((LispCons) cons.cdr()).car();
		compileQuotedVal(quoted, ctx);
	}

	private void compileQuotedVal(LispVal val, Ctx ctx) {
		switch (val) {
			case LispInteger i -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128((int) i.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispNil ignored -> {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			case LispTrue ignored -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispString s -> compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot quote: " + val.print());
		}
	}

	private void compileQuotedCons(LispCons cons, Ctx ctx) {
		compileQuotedVal(cons.car(), ctx);
		compileQuotedVal(cons.cdr(), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(TYPE_CONS);
	}

	private void compileIf(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		if (parts.size() > 3) {
			compileExpr(parts.get(3), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.ELSE);
		compileExpr(parts.get(2), ctx);
		ctx.writer.write(Instruction.END);
	}

	private void compileLet(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		LispVal bindings = parts.get(1);
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);

		// Pre-scan body for captured vars
		List<LispVal> bodyExprs = parts.subList(2, parts.size());
		Set<String> letVarNames = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				letVarNames.add(((LispSymbol) pair.toList().get(0)).name());
			}
		}
		Set<String> capturedInLet = FreeVarAnalyzer.findCapturedVars(bodyExprs, letVarNames, ctx.functions.keySet());

		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				compileExpr(pairList.get(1), ctx);
				if (capturedInLet.contains(name)) {
					// Box in a cell
					ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
					ctx.writer.writeSignedLeb128(TYPE_CELL);
				}
				int slot = ctx.allocLocal(name);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(slot);
			}
		}

		// Save and extend boxedVars for the let body
		Set<String> savedBoxed = ctx.boxedVars;
		Set<String> newBoxed = new HashSet<>(savedBoxed);
		newBoxed.addAll(capturedInLet);
		ctx.boxedVars = newBoxed;

		for (int i = 2; i < parts.size(); i++) {
			if (i > 2) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(parts.get(i), ctx);
		}

		ctx.boxedVars = savedBoxed;
		ctx.locals = savedLocals;
	}

	private void compileProgn(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		for (int i = 1; i < parts.size(); i++) {
			if (i > 1) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(parts.get(i), ctx);
		}
	}

	private void compileSetq(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();

		// Check if variable is a boxed local
		Integer slot = ctx.locals.get(name);
		if (slot != null && ctx.boxedVars.contains(name)) {
			// Write to cell: compile value, save to temp, then set cell
			compileExpr(parts.get(2), ctx);
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Load cell
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(TYPE_CELL);
			// Push value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Set cell field
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			ctx.writer.writeSignedLeb128(TYPE_CELL);
			ctx.writer.writeSignedLeb128(0);
			// Return value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			return;
		}

		// Check if variable is a captured var
		Integer captureIdx = ctx.captures.get(name);
		if (captureIdx != null) {
			// Write to captured cell
			compileExpr(parts.get(2), ctx);
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Navigate to cell in env
			emitLoadCaptureCell(ctx, captureIdx);
			// Push value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Set cell
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			ctx.writer.writeSignedLeb128(TYPE_CELL);
			ctx.writer.writeSignedLeb128(0);
			// Return value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			return;
		}

		// Plain local (not boxed)
		compileExpr(parts.get(2), ctx);
		if (slot == null) {
			slot = ctx.allocLocal(name);
		}
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	private void compileFunctionCall(String name, LispCons cons, Ctx ctx) {
		WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			List<LispVal> args = cons.toList();
			// Push null env (defun functions ignore it)
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			for (int i = 1; i < args.size(); i++) {
				compileExpr(args.get(i), ctx);
			}
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(fi.funcIndex);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	private void compileIndirectCall(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		LispSymbol headSym = (LispSymbol) cons.car();
		int arity = args.size() - 1;
		ctx.indirectCallArities.add(arity);
		int dispatchFuncIdx = FUNC_DISPATCH_BASE + arity;

		// Push funcval as first arg to dispatch
		compileSymbolRef(headSym, ctx);
		// Note: compileSymbolRef may unbox from cell if boxed, but for function values
		// we need the closure struct. If the var is boxed, the unboxed value IS the
		// closure struct.

		// Push remaining args
		for (int i = 1; i < args.size(); i++) {
			compileExpr(args.get(i), ctx);
		}
		// Call dispatch function
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(dispatchFuncIdx);
	}

	private void compileFuncall(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		int arity = parts.size() - 2; // (funcall f arg0 ...) -> arity = num_args
		ctx.indirectCallArities.add(arity);
		int dispatchFuncIdx = FUNC_DISPATCH_BASE + arity;

		// Push funcval
		compileExpr(parts.get(1), ctx);
		// Push args
		for (int i = 2; i < parts.size(); i++) {
			compileExpr(parts.get(i), ctx);
		}
		// Call dispatch
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(dispatchFuncIdx);
	}

	private void compileLambdaValue(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		LispVal paramsVal = parts.get(1);
		List<String> paramNames;
		if (paramsVal instanceof LispNil) {
			paramNames = List.of();
		}
		else {
			paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		}
		List<LispVal> bodyExprs = parts.subList(2, parts.size());

		// Free variable analysis
		Set<String> boundVars = new HashSet<>(paramNames);
		boundVars.addAll(ctx.locals.keySet());
		if (ctx.captures != null) {
			boundVars.addAll(ctx.captures.keySet());
		}
		LinkedHashSet<String> freeVars = FreeVarAnalyzer.findFreeVars(bodyExprs, new HashSet<>(paramNames),
				ctx.functions.keySet());

		int funcId = ctx.nextFuncId[0]++;
		String methodName = "_lambda_" + funcId;
		int funcIndex = FUNC_USER_BASE + ctx.functions.size() + ctx.lambdaDecls.size();
		ctx.lambdaDecls
			.add(new LambdaInfo(funcId, methodName, paramNames, bodyExprs, new ArrayList<>(freeVars), funcIndex));

		// Emit closure creation: {funcId, env}
		// funcId
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(funcId);

		// Build env as cons list of cells for captured variables
		if (freeVars.isEmpty()) {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		else {
			// Build cons list right-to-left: (cons cell_0 (cons cell_1 ... null))
			// First push null (end of list)
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			// Iterate free vars in reverse
			List<String> freeVarList = new ArrayList<>(freeVars);
			for (int i = freeVarList.size() - 1; i >= 0; i--) {
				String varName = freeVarList.get(i);
				// Push the cell for this var
				emitLoadVarCell(varName, ctx);
				// Swap: we need (car=cell, cdr=rest) but stack is [rest, cell]
				// Use a temp local
				int tmpCdr = ctx.allocTemp();
				int tmpCar = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(tmpCar);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(tmpCdr);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(tmpCar);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(tmpCdr);
				// struct.new cons
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(TYPE_CONS);
			}
		}

		// struct.new closure
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(TYPE_CLOSURE);
	}

	private void compileList(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		// Build cons list right-to-left
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		for (int i = args.size() - 1; i >= 1; i--) {
			compileExpr(args.get(i), ctx);
			// Swap car and cdr on stack using temp
			int tmpCdr = ctx.allocTemp();
			int tmpCar = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCar);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCdr);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCar);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCdr);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(TYPE_CONS);
		}
	}

	private void compileCar(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(TYPE_CONS);
		ctx.writer.writeSignedLeb128(0);
	}

	private void compileCdr(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(TYPE_CONS);
		ctx.writer.writeSignedLeb128(1);
	}

	private void compileConsBuiltin(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(TYPE_CONS);
	}

	private void compileLambdaCall(LispCons lambda, LispCons call, Ctx ctx) {
		List<LispVal> lambdaParts = lambda.toList();
		LispVal paramsVal = lambdaParts.get(1);
		List<String> paramNames;
		if (paramsVal instanceof LispNil) {
			paramNames = List.of();
		}
		else {
			paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		}
		List<LispVal> bodyExprs = lambdaParts.subList(2, lambdaParts.size());
		List<LispVal> callArgs = call.toList();

		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);

		for (int i = 0; i < paramNames.size(); i++) {
			compileExpr(callArgs.get(i + 1), ctx);
			int slot = ctx.allocLocal(paramNames.get(i));
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
		}

		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(bodyExprs.get(i), ctx);
		}

		ctx.locals = savedLocals;
	}

	private void castI31GetS(Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	// --- Helper: load captured variable value from env cons list ---
	private void emitLoadCapture(Ctx ctx, int depth) {
		// Navigate env cons list to depth, get car (cell), then unbox
		emitLoadCaptureCell(ctx, depth);
		// Unbox from cell
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(TYPE_CELL);
		ctx.writer.writeSignedLeb128(0);
	}

	private void emitLoadCaptureCell(Ctx ctx, int depth) {
		// Load env from slot 0
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(ctx.closureEnvSlot);
		// Navigate through cons list: cdr depth times
		for (int i = 0; i < depth; i++) {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(TYPE_CONS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeSignedLeb128(TYPE_CONS);
			ctx.writer.writeSignedLeb128(1); // cdr
		}
		// Get car (the cell)
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(TYPE_CONS);
		ctx.writer.writeSignedLeb128(0); // car = cell
		// Cast to cell
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(TYPE_CELL);
	}

	/**
	 * Load the cell (boxed reference) for a variable, for building closure env.
	 */
	private void emitLoadVarCell(String varName, Ctx ctx) {
		// First check if it's a boxed local
		Integer slot = ctx.locals.get(varName);
		if (slot != null && ctx.boxedVars.contains(varName)) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			// The local IS the cell
			return;
		}
		// Check if it's a capture (already a cell in the env)
		Integer captureIdx = ctx.captures.get(varName);
		if (captureIdx != null) {
			emitLoadCaptureCell(ctx, captureIdx);
			return;
		}
		// Unboxed local: create a new cell
		if (slot != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(TYPE_CELL);
			return;
		}
		throw new UnsupportedOperationException("Cannot find variable for closure: " + varName);
	}

	private void emitBoxLocal(Ctx ctx, int slot) {
		// Box: load value, create cell, store cell back
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(TYPE_CELL);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	// --- Dispatch function body builder ---
	private byte[] buildDispatchBody(int arity, List<DefunDecl> defuns, List<LambdaInfo> lambdaDecls, int numDefuns,
			StringTable st) {
		// Collect all functions with matching arity
		record Target(int funcId, int funcIndex) {
		}
		List<Target> targets = new ArrayList<>();
		for (int i = 0; i < defuns.size(); i++) {
			if (defuns.get(i).paramNames.size() == arity) {
				targets.add(new Target(i, FUNC_USER_BASE + i));
			}
		}
		for (int i = 0; i < lambdaDecls.size(); i++) {
			LambdaInfo lambda = lambdaDecls.get(i);
			if (lambda.paramNames.size() == arity) {
				targets.add(new Target(lambda.funcId, lambda.funcIndex));
			}
		}

		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: param 0 = funcval, params 1..arity = args
		// Extra local for funcId extraction
		w.write(1); // 1 local group
		w.write(1); // 1 local of type i32
		w.write(Type.I32);

		int funcIdLocal = arity + 1; // after params

		if (targets.isEmpty()) {
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
			return body.toByteArray();
		}

		// Extract funcId from closure struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // funcval
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(TYPE_CLOSURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(TYPE_CLOSURE);
		w.writeSignedLeb128(0); // field 0: funcId
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(funcIdLocal);

		int numCases = targets.size();
		int maxFuncId = 0;
		for (Target t : targets) {
			maxFuncId = Math.max(maxFuncId, t.funcId);
		}

		// Block structure (all br_table targets are void for uniform arity):
		// block $result (result (ref null eq))
		// block $default void
		// block $case_0 void ;; outermost case
		// block $case_1 void
		// ...
		// block $case_{numCases-1} void ;; innermost case
		// br_table [depths...] default
		// end $case_{numCases-1}
		// target[numCases-1] code, br $result
		// end $case_{numCases-2}
		// ...
		// end $case_0
		// target[0] code, br $result
		// end $default
		// unreachable
		// end $result

		// Result block (typed)
		w.write(Instruction.BLOCK);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());

		// Default block (void)
		w.write(Instruction.BLOCK, 0x40);

		// Case blocks (void) - outermost case first
		for (int i = 0; i < numCases; i++) {
			w.write(Instruction.BLOCK, 0x40);
		}

		// Load funcId and br_table
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(funcIdLocal);

		// Build funcId -> br_table depth mapping
		// From innermost: br 0 exits $case_{numCases-1}, br 1 exits
		// $case_{numCases-2},
		// br K exits $case_{numCases-1-K}, br numCases exits $default
		// For target[j] (case j), depth = numCases - 1 - j
		Map<Integer, Integer> funcIdToCase = new HashMap<>();
		for (int j = 0; j < targets.size(); j++) {
			funcIdToCase.put(targets.get(j).funcId, j);
		}

		w.write(Instruction.BR_TABLE);
		w.writeUnsignedLeb128(maxFuncId + 1); // label count
		for (int fid = 0; fid <= maxFuncId; fid++) {
			Integer caseJ = funcIdToCase.get(fid);
			if (caseJ != null) {
				w.writeUnsignedLeb128(numCases - 1 - caseJ);
			}
			else {
				w.writeUnsignedLeb128(numCases); // default
			}
		}
		w.writeUnsignedLeb128(numCases); // default label

		// Case bodies: close blocks from innermost to outermost
		// k=0 closes innermost ($case_{numCases-1}), code for target[numCases-1]
		// k=1 closes $case_{numCases-2}, code for target[numCases-2]
		// ...
		for (int k = 0; k < numCases; k++) {
			w.write(Instruction.END); // end of $case_{numCases-1-k}
			int targetIdx = numCases - 1 - k;
			Target target = targets.get(targetIdx);
			// Extract env from closure struct
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(0); // funcval
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(TYPE_CLOSURE);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeSignedLeb128(TYPE_CLOSURE);
			w.writeSignedLeb128(1); // field 1: env
			// Push args
			for (int a = 1; a <= arity; a++) {
				w.write(Instruction.GET_LOCAL);
				w.writeSignedLeb128(a);
			}
			// Call target function
			w.write(Instruction.CALL);
			w.writeSignedLeb128(target.funcIndex);
			// Break to $result block with return value on stack
			// After closing $case_i, we're inside: $result, $default, case_0..case_{i-1}
			// br to $result = br (i + 1) = br (numCases - 1 - k + 1) = br (numCases - k)
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(numCases - k);
		}

		// End default block
		w.write(Instruction.END); // $default
		w.write(Instruction.UNREACHABLE);

		// End result block
		w.write(Instruction.END); // $result

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Builds the print_i32 helper function body.
	 */
	private byte[] buildPrintI32Core(boolean appendNewline) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(3);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I32);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.ELSE);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_REM_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_DIV_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(OUT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(45);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(OUT_BUF_OFFSET);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(10);
			w.write(Instruction.I32_STORE8, 0x00, 0x00);
		}

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(OUT_BUF_OFFSET);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET + 4);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
		}
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_FD_WRITE);
		w.write(Instruction.DROP);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	private byte[] buildWriteStrBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET + 4);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_FD_WRITE);
		w.write(Instruction.DROP);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _print_val helper function that prints any Lisp value without a trailing
	 * newline. Handles null (nil), i31ref (integer), string struct, closure struct, and
	 * cons struct (list).
	 */
	private byte[] buildPrintValBody(StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(2);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(1);
		w.write(Type.I32);

		// Check null (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check i31ref (integer)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check string struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(TYPE_STRING);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(TYPE_STRING);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check closure struct -> print "#<function>"
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Must be cons struct - print as list
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_PRINT_VAL);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_PRINT_VAL);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	private static boolean isSetqLambda(LispVal expr) {
		if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym && "setq".equals(sym.name())) {
			List<LispVal> parts = cons.toList();
			if (parts.size() == 3 && parts.get(1) instanceof LispSymbol && parts.get(2) instanceof LispCons valueCons
					&& valueCons.car() instanceof LispSymbol lambdaSym && "lambda".equals(lambdaSym.name())) {
				return true;
			}
		}
		return false;
	}

	private static DefunDecl extractSetqLambda(LispVal expr) {
		List<LispVal> parts = ((LispCons) expr).toList();
		String funcName = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		LispVal paramsVal = lambdaParts.get(1);
		List<String> paramNames;
		if (paramsVal instanceof LispNil) {
			paramNames = List.of();
		}
		else {
			paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		}
		return new DefunDecl(funcName, paramNames, lambdaParts.subList(2, lambdaParts.size()));
	}

	private record DefunDecl(String name, List<String> paramNames, List<LispVal> bodyExprs) {
	}

	private record WasmFunctionInfo(String name, int paramCount, int funcId, int typeIndex, int funcIndex) {
	}

	record LambdaInfo(int funcId, String methodName, List<String> paramNames, List<LispVal> bodyExprs,
			List<String> freeVarNames, int funcIndex) {
	}

	private static final class Ctx {

		final WasmWriter writer;

		final ByteArrayOutputStream bodyStream;

		final StringTable stringTable;

		Map<String, Integer> locals = new HashMap<>();

		Map<String, WasmFunctionInfo> functions = Map.of();

		Map<String, Integer> captures = Map.of();

		Set<String> boxedVars = Set.of();

		int closureEnvSlot = -1;

		List<LambdaInfo> lambdaDecls = new ArrayList<>();

		Set<Integer> indirectCallArities = new HashSet<>();

		int[] nextFuncId = new int[1];

		int nextLocal = 0;

		Ctx(WasmWriter writer, ByteArrayOutputStream bodyStream, StringTable stringTable) {
			this.writer = writer;
			this.bodyStream = bodyStream;
			this.stringTable = stringTable;
		}

		int allocLocal(String name) {
			int slot = this.nextLocal++;
			this.locals.put(name, slot);
			return slot;
		}

		int allocTemp() {
			return this.nextLocal++;
		}

	}

	static final class StringTable {

		private final ByteArrayOutputStream data = new ByteArrayOutputStream();

		private int nextOffset;

		final StringEntry nil;

		final StringEntry lparen;

		final StringEntry rparen;

		final StringEntry space;

		final StringEntry dot;

		final StringEntry newline;

		final StringEntry funcStr;

		StringTable(int baseOffset) {
			this.nextOffset = baseOffset;
			this.nil = addString("nil");
			this.lparen = addString("(");
			this.rparen = addString(")");
			this.space = addString(" ");
			this.dot = addString(" . ");
			this.newline = addString("\n");
			this.funcStr = addString("#<function>");
		}

		StringEntry addString(String s) {
			byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
			int offset = this.nextOffset;
			this.data.write(bytes, 0, bytes.length);
			this.nextOffset += bytes.length;
			return new StringEntry(offset, bytes.length);
		}

		byte[] toByteArray() {
			return this.data.toByteArray();
		}

		record StringEntry(int offset, int length) {
		}

	}

}
