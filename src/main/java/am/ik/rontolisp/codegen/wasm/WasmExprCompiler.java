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
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	static void compileSymbolRef(LispSymbol sym, WasmLispCompiler.Ctx ctx) {
		String name = sym.name();
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
		if (head instanceof LispSymbol sym) {
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
					WasmFetchCompiler.compile(cons, ctx);
					return;
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
				if (LispNames.THEN.equals(qn.member())) {
					WasmThenCompiler.compile(cons, ctx);
					return;
				}
				if (LispNames.PROMISEP.equals(qn.member())) {
					WasmPromisepCompiler.compile(cons, ctx);
					return;
				}
				if (LispNames.TCP_CONNECT.equals(qn.member()) || LispNames.TCP_LISTEN.equals(qn.member())
						|| LispNames.TCP_ACCEPT.equals(qn.member()) || LispNames.TCP_LOCAL_PORT.equals(qn.member())) {
					WasmTcpCompiler.compile(qn.member(), cons, ctx);
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
				case LispNames.WRITE_STRING -> WasmWriteStringCompiler.compileWriteString(cons, ctx);
				case LispNames.WRITE_TO_STRING -> WasmPrin1ToStringCompiler.compile(cons, ctx);
				case LispNames.MAKE_STRING_OUTPUT_STREAM -> WasmWriteStringCompiler.compileMakeOutputStream(cons, ctx);
				case LispNames.MAKE_STRING_INPUT_STREAM -> WasmWriteStringCompiler.compileMakeInputStream(cons, ctx);
				case LispNames.STRING_STREAM_CONTENTS -> WasmWriteStringCompiler.compileContents(cons, ctx);
				case LispNames.WITH_OUTPUT_TO_STRING ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithOutputToString(cons), ctx);
				case LispNames.WITH_INPUT_FROM_STRING ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithInputFromString(cons), ctx);
				case LispNames.PUSHNEW -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPushnew(cons), ctx);
				case LispNames.DEFTYPE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDeftype(cons), ctx);
				case LispNames.DEFINE_CONDITION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandDefineCondition(cons), ctx);
				case LispNames.DEFINE_COMPILER_MACRO ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandDefineCompilerMacro(cons), ctx);
				case LispNames.RESTART_CASE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandRestartCase(cons), ctx);
				case LispNames.MAKE_CONDITION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeCondition(cons), ctx);
				case LispNames.DOCUMENTATION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandDocumentation(cons), ctx);
				case LispNames.WITH_OPEN_FILE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWithOpenFile(cons), ctx);
				case LispNames.READ_BYTE -> WasmReadByteCompiler.compile(cons, ctx);
				case LispNames.WRITE_BYTE -> WasmWriteByteCompiler.compile(cons, ctx);
				case LispNames.READ_SEQUENCE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandReadSequence(cons), ctx);
				case LispNames.WRITE_SEQUENCE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandWriteSequence(cons), ctx);
				case LispNames.MAKE_STRING ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeString(cons), ctx);
				case LispNames.REPLACE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandReplace(cons), ctx);
				case LispNames.LOWER_CASE_P ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandLowerCaseP(cons), ctx);
				case LispNames.UPPER_CASE_P ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandUpperCaseP(cons), ctx);
				case LispNames.CONSTANTP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandConstantp(cons), ctx);
				case LispNames.STREAMP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandStreamp(cons), ctx);
				case LispNames.STRING_UPCASE -> WasmStringUpcaseCompiler.compileUpcase(cons, ctx);
				case LispNames.STRING_DOWNCASE -> WasmStringUpcaseCompiler.compileDowncase(cons, ctx);
				case LispNames.STRING_CAPITALIZE -> WasmStringCapitalizeCompiler.compile(cons, ctx);
				case LispNames.SUBSEQ -> WasmSubseqCompiler.compile(cons, ctx);
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
				case LispNames.PARSE_INTEGER ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandParseInteger(cons), ctx);
				case LispNames.VALUES_LIST ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandValuesList(cons), ctx);
				case LispNames.COMPLEX -> WasmExprCompiler.compileExpr(LispMacroExpander.expandComplexLite(cons), ctx);
				case LispNames.NE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNumericNotEqual(cons), ctx);
				case LispNames.READ_FROM_STRING -> WasmReadFromStringCompiler.compile(cons, ctx);
				case LispNames.STRING_EQ -> WasmStringEqCompiler.compileEq(cons, ctx);
				case LispNames.STRING_EQUAL -> WasmStringEqCompiler.compileEqual(cons, ctx);
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
				case LispNames.PROGN -> WasmPrognCompiler.compile(cons, ctx);
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
				case LispNames.MAKE_INSTANCE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandMakeInstance(cons, ctx.closRegistry), ctx);
				case LispNames.SLOT_VALUE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandSlotValue(cons, ctx.closRegistry), ctx);
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
				case LispNames.ARRAY_ELEMENT_TYPE ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandArrayElementType(cons), ctx);
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
					// A string sequence sorts as a list of its characters and is coerced
					// back to a string; null when the call is already the inner sort.
					LispVal wrappedSort = LispMacroExpander.wrapSortForStringSeq(cons);
					if (wrappedSort != null) {
						WasmExprCompiler.compileExpr(wrappedSort, ctx);
					}
					else {
						WasmSortCompiler.compile(cons, ctx);
					}
				}
				case LispNames.STABLE_SORT ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandStableSort(cons), ctx);
				case LispNames.COPY_SEQ -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCopySeq(cons), ctx);
				case LispNames.VECTORP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandVectorp(cons), ctx);
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
				case LispNames.FUNCTIONP -> WasmFunctionpCompiler.compile(cons, ctx);
				case LispNames.ARRAYP_INTERNAL -> WasmArraypCompiler.compile(cons, ctx);
				case LispNames.KEYWORDP -> WasmKeywordpCompiler.compile(cons, ctx);
				case LispNames.FLOAT -> WasmFloatConvCompiler.compile(cons, ctx);
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
				case LispNames.ERROR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandError(cons), ctx);
				case LispNames.ERROR_INTERNAL -> WasmErrorCompiler.compile(cons, ctx);
				case LispNames.WARN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandWarn(cons), ctx);
				case LispNames.WARN_INTERNAL -> WasmWarnCompiler.compile(cons, ctx);
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
				case LispNames.NOTANY -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNotany(cons), ctx);
				case LispNames.NOTEVERY -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNotevery(cons), ctx);
				case LispNames.PROG2 -> WasmExprCompiler.compileExpr(LispMacroExpander.expandProg2(cons), ctx);
				case LispNames.PSETQ -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPsetq(cons), ctx);
				case LispNames.TYPECASE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandTypecase(cons), ctx);
				case LispNames.ETYPECASE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandEtypecase(cons), ctx);
				case LispNames.CHECK_TYPE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCheckType(cons), ctx);
				case LispNames.ASSERT -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAssert(cons), ctx);
				case LispNames.DECLARE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDeclare(cons), ctx);
				case LispNames.DECLAIM -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDeclaim(cons), ctx);
				case LispNames.PROCLAIM -> WasmExprCompiler.compileExpr(LispMacroExpander.expandProclaim(cons), ctx);
				case LispNames.THE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandThe(cons), ctx);
				case LispNames.EVAL_WHEN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandEvalWhen(cons), ctx);
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
				case LispNames.ROTATEF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandRotatef(cons), ctx);
				case LispNames.BYTE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandByte(cons), ctx);
				case LispNames.BYTE_SIZE -> WasmExprCompiler.compileExpr(LispMacroExpander.expandByteSize(cons), ctx);
				case LispNames.BYTE_POSITION ->
					WasmExprCompiler.compileExpr(LispMacroExpander.expandBytePosition(cons), ctx);
				case LispNames.LDB -> WasmExprCompiler.compileExpr(LispMacroExpander.expandLdb(cons), ctx);
				case LispNames.DPB -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDpb(cons), ctx);
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
