package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.ConcatenateForms;

import am.ik.jvm.Opcode;

/**
 * Compiles Lisp expressions to JVM bytecode. Serves as the entry point and dispatcher,
 * delegating to specialized compiler classes for each built-in function and special form.
 */
final class JvmExprCompiler {

	private JvmExprCompiler() {
	}

	static void compileExpr(LispVal expr, JvmLispCompiler.Ctx ctx, String className) {
		switch (expr) {
			case LispInteger i -> JvmEmitHelper.compileLong(i.value(), ctx);
			case LispBigInteger b -> JvmEmitHelper.compileBigInteger(b.value(), ctx);
			case LispRatio r -> JvmEmitHelper.compileRatio(r, ctx);
			case LispDouble d -> JvmEmitHelper.compileDouble(d.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> JvmEmitHelper.compileTrue(ctx);
			case LispString s -> JvmEmitHelper.compileStringLiteral(s.print(), ctx);
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
			// A packed #d(...) double-float literal compiles to a native double[] with a
			// dimension header (the packed representation), disjoint from the general
			// array.
			case am.ik.rontolisp.LispDoubleFloatArray fa -> JvmQuoteCompiler.compilePackedLiteral(fa, ctx);
			// #f(...) single-float packed arrays are not yet supported on the JVM backend
			// (todo 95 Phase 2); use #d for double-float.
			case am.ik.rontolisp.LispSingleFloatArray ignored -> throw new UnsupportedOperationException(
					"single-float packed arrays (#f) are not yet supported on the JVM backend; use #d for double-float");
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	static void compileSymbolRef(LispSymbol sym, JvmLispCompiler.Ctx ctx) {
		String name = sym.name();
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
			// defvar/defparameter global.
			ctx.emit(Opcode.GETSTATIC);
			ctx.emitU2(java.util.Objects.requireNonNull(ctx.globalFields.get(name)).index());
		}
		else if (ctx.dynamic) {
			JvmDynamicCallCompiler.compileVarRef(name, ctx);
		}
		else {
			// Lisp-2: a bare symbol is a variable reference only; functions must be
			// referenced via (function name) / #'name.
			throw new UnsupportedOperationException("Cannot compile symbol reference: " + name);
		}
	}

	private static void compileCons(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
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
				if (LispNames.LIST_FUNCTIONS.equals(qn.member()) || LispNames.LIST_MACROS.equals(qn.member())
						|| LispNames.LIST_SPECIAL_FORMS.equals(qn.member())) {
					JvmIntrospectionCompiler.compile(qn.member(), cons, ctx, className);
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
				if (LispNames.THEN.equals(qn.member())) {
					JvmThenCompiler.compile(cons, ctx, className);
					return;
				}
				if (LispNames.PROMISEP.equals(qn.member())) {
					JvmPromisepCompiler.compile(cons, ctx, className);
					return;
				}
				if (LispNames.TCP_CONNECT.equals(qn.member()) || LispNames.TCP_LISTEN.equals(qn.member())
						|| LispNames.TCP_ACCEPT.equals(qn.member()) || LispNames.TCP_LOCAL_PORT.equals(qn.member())
						|| LispNames.TLS_CONNECT.equals(qn.member()) || LispNames.TLS_LISTEN.equals(qn.member())
						|| LispNames.TLS_LISTEN_P12.equals(qn.member())) {
					JvmTcpCompiler.compile(qn.member(), cons, ctx, className);
					return;
				}
				if (LispNames.HTTP_HANDLER.equals(qn.member())) {
					JvmHttpHandlerCompiler.compile(cons, ctx, className);
					return;
				}
				if (LispNames.WASM_EXPORT.equals(qn.member())) {
					// rontolisp:wasm-export marks a function for direct WASM export; the
					// JVM
					// backend has no notion of it, so it is a no-op that yields nil.
					ctx.emit(Opcode.ACONST_NULL);
					return;
				}
				if (LispNames.WASM_IMPORT.equals(qn.member())) {
					// rontolisp:wasm-import declares a host function imported into WASM
					// output; on the JVM the error-signalling stub defun was registered
					// in pass 1, so the directive itself is a no-op that yields nil.
					ctx.emit(Opcode.ACONST_NULL);
					return;
				}
				// Other rontolisp: members (user defuns in that package) fall through.
			}
			if (qn != null && LispNames.JAVA_PKG.equals(qn.pkg()) && JvmJavaInteropCompiler.handles(qn.member())) {
				JvmJavaInteropCompiler.compile(qn.member(), cons, ctx, className);
				return;
			}
			// --simd: route the six vectorizable simd: kernels to the embedded Vector API
			// bridge instead of the scalar simd.lisp defun. Only when the runtime was
			// emitted (ctx.simdOps != null); otherwise this falls through to the ordinary
			// qualified-call path and runs the spliced scalar reference.
			if (qn != null && LispNames.SIMD_PKG.equals(qn.pkg()) && ctx.simdOps != null
					&& JvmSimdCompiler.handles(qn.member())) {
				JvmSimdCompiler.compile(qn.member(), cons, ctx, className);
				return;
			}
			switch (sym.name()) {
				case LispNames.ADD ->
					JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.ADD, Opcode.DADD, className);
				case LispNames.SUB ->
					JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.SUB, Opcode.DSUB, className);
				case LispNames.MUL ->
					JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.MUL, Opcode.DMUL, className);
				case LispNames.DIV ->
					JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.DIV, Opcode.DDIV, className);
				case LispNames.MOD ->
					JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.MOD, Opcode.DREM, className);
				case LispNames.REM ->
					JvmArithCompiler.compile(cons, ctx, JvmNumericRuntimeBuilder.REM, Opcode.DREM, className);
				case LispNames.EQ -> compileComparison(cons, ctx, className, Opcode.IFEQ);
				case LispNames.LT -> compileComparison(cons, ctx, className, Opcode.IFLT);
				case LispNames.GT -> compileComparison(cons, ctx, className, Opcode.IFGT);
				case LispNames.LE -> compileComparison(cons, ctx, className, Opcode.IFLE);
				case LispNames.GE -> compileComparison(cons, ctx, className, Opcode.IFGE);
				case LispNames.PRINT -> JvmPrintCompiler.compile(cons, ctx, className);
				case LispNames.PRIN1 -> JvmPrin1Compiler.compile(cons, ctx, className);
				case LispNames.PRINC -> JvmPrincCompiler.compile(cons, ctx, className);
				case LispNames.TERPRI -> JvmTerpriCompiler.compile(cons, ctx, className);
				case LispNames.FRESH_LINE -> JvmFreshLineCompiler.compile(cons, ctx, className);
				case LispNames.PRINC_TO_STRING -> JvmPrincToStringCompiler.compile(cons, ctx, className);
				case LispNames.PRIN1_TO_STRING -> JvmPrin1ToStringCompiler.compile(cons, ctx, className);
				case LispNames.STRING_CONCAT -> JvmStringConcatCompiler.compile(cons, ctx, className);
				case LispNames.GENSYM -> JvmGensymCompiler.compile(cons, ctx, className);
				case LispNames.STRING -> JvmSymbolApiCompiler.compileString(cons, ctx, className);
				case LispNames.SYMBOL_NAME -> JvmSymbolApiCompiler.compileSymbolName(cons, ctx, className);
				case LispNames.INTERN -> JvmSymbolApiCompiler.compileIntern(cons, ctx, className);
				case LispNames.FIND_SYMBOL -> JvmSymbolApiCompiler.compileFindSymbol(cons, ctx, className);
				case LispNames.MAKE_SYMBOL -> JvmSymbolApiCompiler.compileMakeSymbol(cons, ctx, className);
				case LispNames.BOUNDP -> JvmSymbolApiCompiler.compileBoundp(cons, ctx, className);
				case LispNames.FBOUNDP -> JvmSymbolApiCompiler.compileFboundp(cons, ctx, className);
				case LispNames.SYMBOL_VALUE -> JvmSymbolApiCompiler.compileSymbolValue(cons, ctx, className);
				case LispNames.CONCATENATE ->
					JvmExprCompiler.compileExpr(ConcatenateForms.expand(cons), ctx, className);
				case LispNames.READ_LINE -> JvmReadLineCompiler.compile(cons, ctx, className);
				case LispNames.READ_CHAR -> JvmReadCharCompiler.compile(cons, ctx, className);
				case LispNames.OPEN -> JvmOpenCompiler.compile(cons, ctx, className);
				case LispNames.CLOSE -> JvmCloseCompiler.compile(cons, ctx, className);
				case LispNames.WRITE_LINE -> JvmWriteLineCompiler.compile(cons, ctx, className);
				case LispNames.WRITE_STRING -> JvmStringStreamCompiler.compileWriteString(cons, ctx, className);
				case LispNames.WRITE_TO_STRING -> JvmPrin1ToStringCompiler.compile(cons, ctx, className);
				case LispNames.MAKE_STRING_OUTPUT_STREAM ->
					JvmStringStreamCompiler.compileMakeOutputStream(cons, ctx, className);
				case LispNames.MAKE_STRING_INPUT_STREAM ->
					JvmStringStreamCompiler.compileMakeInputStream(cons, ctx, className);
				case LispNames.STRING_STREAM_CONTENTS -> JvmStringStreamCompiler.compileContents(cons, ctx, className);
				case LispNames.WITH_OUTPUT_TO_STRING ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithOutputToString(cons), ctx, className);
				case LispNames.WITH_INPUT_FROM_STRING ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithInputFromString(cons), ctx, className);
				case LispNames.PUSHNEW ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPushnew(cons), ctx, className);
				case LispNames.DEFTYPE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDeftype(cons), ctx, className);
				case LispNames.DEFINE_CONDITION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDefineCondition(cons), ctx, className);
				case LispNames.DEFINE_SETF_EXPANDER ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDefineSetfExpander(cons), ctx, className);
				case LispNames.DEFINE_COMPILER_MACRO ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDefineCompilerMacro(cons), ctx, className);
				case LispNames.RESTART_CASE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRestartCase(cons), ctx, className);
				case LispNames.MAKE_CONDITION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMakeCondition(cons), ctx, className);
				case LispNames.DOCUMENTATION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDocumentation(cons), ctx, className);
				case LispNames.WITH_OPEN_FILE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWithOpenFile(cons), ctx, className);
				case LispNames.READ_BYTE -> JvmReadByteCompiler.compile(cons, ctx, className);
				case LispNames.WRITE_BYTE -> JvmWriteByteCompiler.compile(cons, ctx, className);
				case LispNames.READ_SEQUENCE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandReadSequence(cons), ctx, className);
				case LispNames.WRITE_SEQUENCE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandWriteSequence(cons), ctx, className);
				case LispNames.MAKE_STRING ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMakeString(cons), ctx, className);
				case LispNames.REPLACE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandReplace(cons), ctx, className);
				case LispNames.LOWER_CASE_P ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandLowerCaseP(cons), ctx, className);
				case LispNames.UPPER_CASE_P ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandUpperCaseP(cons), ctx, className);
				case LispNames.CONSTANTP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandConstantp(cons), ctx, className);
				case LispNames.STREAMP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandStreamp(cons), ctx, className);
				case LispNames.STRING_UPCASE -> JvmStringUpcaseCompiler.compileUpcase(cons, ctx, className);
				case LispNames.STRING_DOWNCASE -> JvmStringUpcaseCompiler.compileDowncase(cons, ctx, className);
				case LispNames.STRING_CAPITALIZE -> JvmStringCapitalizeCompiler.compile(cons, ctx, className);
				case LispNames.SUBSEQ -> JvmSubseqCompiler.compile(cons, ctx, className);
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
				case LispNames.PARSE_INTEGER ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandParseInteger(cons), ctx, className);
				case LispNames.VALUES_LIST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandValuesList(cons), ctx, className);
				case LispNames.COMPLEX ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandComplexLite(cons), ctx, className);
				case LispNames.NE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNumericNotEqual(cons), ctx, className);
				case LispNames.READ_FROM_STRING -> JvmReadFromStringCompiler.compile(cons, ctx, className);
				case LispNames.STRING_EQ -> JvmStringEqCompiler.compileEq(cons, ctx, className);
				case LispNames.STRING_EQUAL -> JvmStringEqCompiler.compileEqual(cons, ctx, className);
				case LispNames.STRING_TRIM -> JvmStringTrimCompiler.compileTrim(cons, ctx, className);
				case LispNames.STRING_LEFT_TRIM -> JvmStringTrimCompiler.compileLeft(cons, ctx, className);
				case LispNames.STRING_RIGHT_TRIM -> JvmStringTrimCompiler.compileRight(cons, ctx, className);
				case LispNames.QUOTE -> JvmQuoteCompiler.compile(cons, ctx, className);
				case LispNames.IF -> JvmIfCompiler.compile(cons, ctx, className);
				case LispNames.WHILE -> JvmWhileCompiler.compile(cons, ctx, className);
				case LispNames.LET -> JvmLetCompiler.compile(cons, ctx, className);
				case LispNames.PROGV ->
					// progv binds a runtime-computed list of symbols; the compiler cannot
					// name the static fields to save/restore. Interpreter only for now.
					throw new UnsupportedOperationException(
							LispNames.PROGV + " is not supported on the JVM backend (interpreter only)");
				case LispNames.PROGN -> JvmPrognCompiler.compile(cons, ctx, className);
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
					.compileExpr(LispMacroExpander.expandMakeInstance(cons, ctx.closRegistry), ctx, className);
				case LispNames.SLOT_VALUE -> JvmExprCompiler
					.compileExpr(LispMacroExpander.expandSlotValue(cons, ctx.closRegistry), ctx, className);
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
				case LispNames.RETURN -> JvmReturnCompiler.compile(cons, ctx, className);
				case LispNames.INCF -> JvmExprCompiler.compileExpr(LispMacroExpander.expandIncf(cons), ctx, className);
				case LispNames.DECF -> JvmExprCompiler.compileExpr(LispMacroExpander.expandDecf(cons), ctx, className);
				case LispNames.FORMAT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandFormat(cons), ctx, className);
				case LispNames.LENGTH -> JvmLengthCompiler.compile(cons, ctx, className);
				case LispNames.REVERSE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandReverse(cons), ctx, className);
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
				case LispNames.GETF -> JvmExprCompiler.compileExpr(LispMacroExpander.expandGetf(cons), ctx, className);
				case LispNames.EVERY ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandEvery(cons), ctx, className);
				case LispNames.SOME -> JvmExprCompiler.compileExpr(LispMacroExpander.expandSome(cons), ctx, className);
				case LispNames.REMOVE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRemove(cons), ctx, className);
				case LispNames.REMOVE_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRemoveIf(cons), ctx, className);
				case LispNames.REMOVE_IF_NOT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRemoveIfNot(cons), ctx, className);
				case LispNames.DELETE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDelete(cons), ctx, className);
				case LispNames.DELETE_IF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDeleteIf(cons), ctx, className);
				case LispNames.DELETE_IF_NOT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDeleteIfNot(cons), ctx, className);
				case LispNames.SUBSTITUTE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandSubstitute(cons), ctx, className);
				case LispNames.NSUBSTITUTE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandNsubstitute(cons), ctx, className);
				case LispNames.REMOVE_DUPLICATES ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRemoveDuplicates(cons), ctx, className);
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
				case LispNames.HASH_TABLE_P -> JvmHashTableCompiler.compileP(cons, ctx, className);
				case LispNames.MAPHASH -> JvmHashTableCompiler.compileMaphash(cons, ctx, className);
				case LispNames.MAKE_ARRAY -> JvmArrayCompiler.compileMake(cons, ctx, className);
				case LispNames.AREF -> JvmArrayCompiler.compileAref(cons, ctx, className);
				case LispNames.ASET -> JvmArrayCompiler.compileAset(cons, ctx, className);
				case LispNames.ARRAY_DIMENSIONS -> JvmArrayCompiler.compileDims(cons, ctx, className);
				case LispNames.ROW_MAJOR_AREF -> JvmArrayCompiler.compileRowMajorAref(cons, ctx, className);
				case LispNames.ROW_MAJOR_ASET -> JvmArrayCompiler.compileRowMajorAset(cons, ctx, className);
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
					if (ctx.usesFloatArray) {
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
				case LispNames.ARRAY_DISPLACEMENT ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandArrayDisplacement(cons), ctx, className);
				case LispNames.ARRAY_DISP_TARGET -> JvmArrayCompiler.compileDispTarget(cons, ctx, className);
				case LispNames.ARRAY_DISP_OFFSET -> JvmArrayCompiler.compileDispOffset(cons, ctx, className);
				case LispNames.COERCE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandCoerce(cons), ctx, className);
				case LispNames.MAP_INTO ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMapInto(cons), ctx, className);
				case LispNames.APPEND -> JvmAppendCompiler.compile(cons, ctx, className);
				case LispNames.EVAL -> JvmEvalCompiler.compile(cons, ctx, className);
				case LispNames.READ -> JvmReadCompiler.compile(cons, ctx, className);
				case LispNames.LOAD -> JvmLoadCompiler.compile(cons, ctx, className);
				// A literal top-level require/provide (and the asdf directives) was
				// consumed by the compile-time LoadInliner pass; anything left is nested
				// or non-literal, which the compiled runtime reader cannot execute
				// (unlike a runtime load).
				case LispNames.REQUIRE, LispNames.PROVIDE, LispNames.ASDF_LOAD_SYSTEM, LispNames.ASDF_DEFSYSTEM,
						LispNames.QL_QUICKLOAD ->
					throw new UnsupportedOperationException(
							sym.name() + " is only supported as a literal top-level form on the compile path");
				case LispNames.FUNCALL -> JvmFunctionCallCompiler.compileFuncall(cons, ctx, className);
				case LispNames.FUNCTION -> JvmFunctionFormCompiler.compile(cons, ctx, className);
				case LispNames.SYMBOL_FUNCTION -> JvmFunctionFormCompiler.compileSymbolFunction(cons, ctx, className);
				case LispNames.MAP -> JvmExprCompiler.compileExpr(LispMacroExpander.expandMap(cons), ctx, className);
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
						LispVal wrappedSort = LispMacroExpander.wrapSortForStringSeq(cons);
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
				case LispNames.FUNCTIONP -> JvmFunctionpCompiler.compile(cons, ctx, className);
				case LispNames.ARRAYP_INTERNAL -> JvmArraypCompiler.compile(cons, ctx, className);
				case LispNames.KEYWORDP -> JvmKeywordpCompiler.compile(cons, ctx, className);
				case LispNames.FLOAT -> JvmFloatConvCompiler.compile(cons, ctx, className);
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
				case LispNames.ERROR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandError(cons), ctx, className);
				case LispNames.ERROR_INTERNAL -> JvmErrorCompiler.compile(cons, ctx, className);
				case LispNames.WARN -> JvmExprCompiler.compileExpr(LispMacroExpander.expandWarn(cons), ctx, className);
				case LispNames.WARN_INTERNAL -> JvmWarnCompiler.compile(cons, ctx, className);
				case LispNames.AND -> JvmExprCompiler.compileExpr(LispMacroExpander.expandAnd(cons), ctx, className);
				case LispNames.OR -> JvmExprCompiler.compileExpr(LispMacroExpander.expandOr(cons), ctx, className);
				case LispNames.WHEN -> JvmExprCompiler.compileExpr(LispMacroExpander.expandWhen(cons), ctx, className);
				case LispNames.DOTIMES ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDotimes(cons), ctx, className);
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
				case LispNames.RANDOM -> JvmRandomCompiler.compile(cons, ctx, className);
				case LispNames.GET_UNIVERSAL_TIME, LispNames.GET_INTERNAL_REAL_TIME, LispNames.GET_INTERNAL_RUN_TIME ->
					JvmTimeCompiler.compile(cons, ctx, sym.name());
				case LispNames.GETENV -> JvmGetenvCompiler.compile(cons, ctx, className);
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
					if (isBinaryCall(cons)) {
						JvmBitwiseCompiler.compileLogand(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx, className);
					}
				}
				case LispNames.LOGIOR -> {
					if (isBinaryCall(cons)) {
						JvmBitwiseCompiler.compileLogior(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx, className);
					}
				}
				case LispNames.LOGXOR -> {
					if (isBinaryCall(cons)) {
						JvmBitwiseCompiler.compileLogxor(cons, ctx, className);
					}
					else {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandReduction(cons), ctx, className);
					}
				}
				case LispNames.LOGNOT -> JvmBitwiseCompiler.compileLognot(cons, ctx, className);
				case LispNames.ASH -> JvmBitwiseCompiler.compileAsh(cons, ctx, className);
				case LispNames.INTEGER_LENGTH -> JvmBitwiseCompiler.compileIntegerLength(cons, ctx, className);
				case LispNames.LOGBITP -> JvmBitwiseCompiler.compileLogbitp(cons, ctx, className);
				case LispNames.LIST_STAR ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandListStar(cons), ctx, className);
				case LispNames.ACONS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandAcons(cons), ctx, className);
				case LispNames.ENDP -> JvmExprCompiler.compileExpr(LispMacroExpander.expandEndp(cons), ctx, className);
				case LispNames.ELT -> JvmExprCompiler.compileExpr(LispMacroExpander.expandElt(cons), ctx, className);
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
				case LispNames.TYPECASE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandTypecase(cons), ctx, className);
				case LispNames.ETYPECASE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandEtypecase(cons), ctx, className);
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
				case LispNames.ROTATEF ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandRotatef(cons), ctx, className);
				case LispNames.BYTE -> JvmExprCompiler.compileExpr(LispMacroExpander.expandByte(cons), ctx, className);
				case LispNames.BYTE_SIZE ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandByteSize(cons), ctx, className);
				case LispNames.BYTE_POSITION ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandBytePosition(cons), ctx, className);
				case LispNames.LDB -> JvmExprCompiler.compileExpr(LispMacroExpander.expandLdb(cons), ctx, className);
				case LispNames.DPB -> JvmExprCompiler.compileExpr(LispMacroExpander.expandDpb(cons), ctx, className);
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
					if (LispMacroExpander.isCarCdrComposition(sym.name())) {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandCarCdrComposition(cons), ctx, className);
					}
					else {
						JvmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx, className);
					}
				}
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
	 * Compiles a numeric comparison. The binary form uses the dedicated comparison
	 * compiler; any other arity is desugared into nested binary comparisons.
	 */
	private static void compileComparison(LispCons cons, JvmLispCompiler.Ctx ctx, String className, int branchOpcode) {
		if (isBinaryCall(cons)) {
			JvmComparisonCompiler.compile(cons, ctx, branchOpcode, className);
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

}
