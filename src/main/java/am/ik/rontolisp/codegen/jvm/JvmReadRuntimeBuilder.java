package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispNames;
import org.jspecify.annotations.Nullable;

/**
 * Builds the JVM bytecode for the runtime Lisp reader used by the {@code read} and
 * {@code load} built-ins. The generated {@code .class} is standalone, so the reader is
 * emitted directly into it (mirroring how {@link JvmEvalRuntimeBuilder} emits
 * {@code eval}).
 *
 * <p>
 * A combined lexer/parser walks the characters of the static field {@code _readSrc}
 * starting at {@code _readPos}, producing values in the shared runtime representation:
 * {@code null} for nil, {@code Long} for integers, {@code BigInteger} for big integers,
 * {@code Double} for floats, a plain {@code String} for symbols, a quote-wrapped
 * {@code String} for string literals, {@code Long(1)} for {@code t}, and an
 * {@code Object[2]} for cons cells. {@code read} parses one datum from a line of stdin;
 * {@code load} reads a file and evaluates every top-level datum in the global environment
 * via the {@code _eval} runtime.
 */
final class JvmReadRuntimeBuilder {

	/** A reader method body ready to be emitted into the generated class. */
	record ReadMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	private final ConstantPool cp;

	private final ClassConstant thisClass;

	private final ClassConstant objectClass;

	private final ClassConstant objectArrayClass;

	private final ClassConstant stringClass;

	private final MethodrefConstant longValueOf;

	private final MethodrefConstant doubleValueOf;

	private final MethodrefConstant stringCharAt;

	private final MethodrefConstant stringLength;

	private final MethodrefConstant stringSubstring;

	private final MethodrefConstant objectEquals;

	private final MethodrefConstant readLineHelper;

	private final boolean emitLoad;

	// CP entries created internally
	private final FieldrefConstant readSrc;

	private final FieldrefConstant readPos;

	private final MethodrefConstant isWhitespace;

	private final MethodrefConstant doubleParse;

	private final MethodrefConstant stringReplace;

	private final ClassConstant bigIntegerClass;

	private final MethodrefConstant bigIntegerInit;

	private final MethodrefConstant bigIntegerBitLength;

	private final MethodrefConstant bigIntegerLongValue;

	private final ClassConstant stringBuilderClass;

	private final MethodrefConstant sbInitStr;

	private final MethodrefConstant sbAppendChar;

	private final MethodrefConstant sbToString;

	private final MethodrefConstant readLineStream;

	private final MethodrefConstant readSkipWs;

	private final MethodrefConstant readExpr;

	private final MethodrefConstant readList;

	private final MethodrefConstant readAtom;

	private final MethodrefConstant readStr;

	private final MethodrefConstant classify;

	// _classify upcases an atom token like CL's :upcase readtable case (the uppercase
	// name is canonical -- there is no fold). See .kb/reader-case-upcase.md.
	private final MethodrefConstant stringToUpperCase;

	private final FieldrefConstant localeRoot;

	private final @Nullable MethodrefConstant evalRef;

	private final @Nullable MethodrefConstant pathsGet;

	private final @Nullable MethodrefConstant filesReadString;

	private JvmReadRuntimeBuilder(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant objectArrayClass, ClassConstant stringClass, MethodrefConstant longValueOf,
			MethodrefConstant doubleValueOf, MethodrefConstant stringCharAt, MethodrefConstant stringLength,
			MethodrefConstant stringSubstring, MethodrefConstant objectEquals, MethodrefConstant readLineHelper,
			boolean emitLoad) {
		this.cp = cp;
		this.thisClass = thisClass;
		this.objectClass = objectClass;
		this.objectArrayClass = objectArrayClass;
		this.stringClass = stringClass;
		this.longValueOf = longValueOf;
		this.doubleValueOf = doubleValueOf;
		this.stringCharAt = stringCharAt;
		this.stringLength = stringLength;
		this.stringSubstring = stringSubstring;
		this.objectEquals = objectEquals;
		this.readLineHelper = readLineHelper;
		this.emitLoad = emitLoad;

		this.readSrc = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8("_readSrc"), cp.addUtf8("Ljava/lang/String;")));
		this.readPos = cp.addFieldref(thisClass, cp.addNameAndType(cp.addUtf8("_readPos"), cp.addUtf8("I")));

		ClassConstant characterClass = cp.addClass(cp.addUtf8("java/lang/Character"));
		this.isWhitespace = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("isWhitespace"), cp.addUtf8("(C)Z")));
		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		this.doubleParse = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("parseDouble"), cp.addUtf8("(Ljava/lang/String;)D")));
		this.stringReplace = cp.addMethodref(stringClass, cp.addNameAndType(cp.addUtf8("replace"),
				cp.addUtf8("(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;")));
		this.bigIntegerClass = cp.addClass(cp.addUtf8("java/math/BigInteger"));
		this.bigIntegerInit = cp.addMethodref(this.bigIntegerClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.bigIntegerBitLength = cp.addMethodref(this.bigIntegerClass,
				cp.addNameAndType(cp.addUtf8("bitLength"), cp.addUtf8("()I")));
		this.bigIntegerLongValue = cp.addMethodref(this.bigIntegerClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));
		this.stringBuilderClass = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		this.sbInitStr = cp.addMethodref(this.stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.sbAppendChar = cp.addMethodref(this.stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(C)Ljava/lang/StringBuilder;")));
		this.sbToString = cp.addMethodref(this.stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));

		this.readLineStream = methodref("_readLineStream", "(Ljava/lang/Object;)Ljava/lang/Object;");
		this.readSkipWs = methodref("_readSkipWs", "()V");
		this.readExpr = methodref("_readExpr", "()Ljava/lang/Object;");
		this.readList = methodref("_readList", "()Ljava/lang/Object;");
		this.readAtom = methodref("_readAtom", "()Ljava/lang/Object;");
		this.readStr = methodref("_readStr", "()Ljava/lang/Object;");
		this.classify = methodref("_classify", "(Ljava/lang/String;)Ljava/lang/Object;");
		ClassConstant localeClass = cp.addClass(cp.addUtf8("java/util/Locale"));
		this.localeRoot = cp.addFieldref(localeClass,
				cp.addNameAndType(cp.addUtf8("ROOT"), cp.addUtf8("Ljava/util/Locale;")));
		this.stringToUpperCase = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("toUpperCase"), cp.addUtf8("(Ljava/util/Locale;)Ljava/lang/String;")));

		if (emitLoad) {
			this.evalRef = methodref("_eval", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
			ClassConstant pathsClass = cp.addClass(cp.addUtf8("java/nio/file/Paths"));
			this.pathsGet = cp.addMethodref(pathsClass, cp.addNameAndType(cp.addUtf8("get"),
					cp.addUtf8("(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;")));
			ClassConstant filesClass = cp.addClass(cp.addUtf8("java/nio/file/Files"));
			this.filesReadString = cp.addMethodref(filesClass, cp.addNameAndType(cp.addUtf8("readString"),
					cp.addUtf8("(Ljava/nio/file/Path;)Ljava/lang/String;")));
		}
		else {
			this.evalRef = null;
			this.pathsGet = null;
			this.filesReadString = null;
		}
	}

	static JvmReadRuntimeBuilder create(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant objectArrayClass, ClassConstant stringClass, MethodrefConstant longValueOf,
			MethodrefConstant doubleValueOf, MethodrefConstant stringCharAt, MethodrefConstant stringLength,
			MethodrefConstant stringSubstring, MethodrefConstant objectEquals, MethodrefConstant readLineHelper,
			boolean emitLoad) {
		return new JvmReadRuntimeBuilder(cp, thisClass, objectClass, objectArrayClass, stringClass, longValueOf,
				doubleValueOf, stringCharAt, stringLength, stringSubstring, objectEquals, readLineHelper, emitLoad);
	}

	private MethodrefConstant methodref(String name, String desc) {
		return this.cp.addMethodref(this.thisClass,
				this.cp.addNameAndType(this.cp.addUtf8(name), this.cp.addUtf8(desc)));
	}

	/** Returns all reader method bodies to emit. */
	List<ReadMethod> methods() {
		List<ReadMethod> ms = new ArrayList<>();
		ms.add(new ReadMethod(this.cp.addUtf8("_readSkipWs"), this.cp.addUtf8("()V"), 4, 1, buildSkipWs()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readExpr"), this.cp.addUtf8("()Ljava/lang/Object;"), 8, 2,
				buildReadExpr()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readList"), this.cp.addUtf8("()Ljava/lang/Object;"), 6, 2,
				buildReadList()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readAtom"), this.cp.addUtf8("()Ljava/lang/Object;"), 4, 2,
				buildReadAtom()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readStr"), this.cp.addUtf8("()Ljava/lang/Object;"), 4, 3,
				buildReadStr()));
		ms.add(new ReadMethod(this.cp.addUtf8("_classify"), this.cp.addUtf8("(Ljava/lang/String;)Ljava/lang/Object;"),
				4, 7, buildClassify()));
		ms.add(new ReadMethod(this.cp.addUtf8("_read"), this.cp.addUtf8("()Ljava/lang/Object;"), 4, 1, buildRead()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readStream"), this.cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;"),
				4, 2, buildReadStream()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readFromString"),
				this.cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;"), 4, 2, buildReadFromString()));
		if (this.emitLoad) {
			ms.add(new ReadMethod(this.cp.addUtf8("_load"), this.cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;"),
					4, 3, buildLoad()));
		}
		return ms;
	}

	// === per-method bodies ===

	private void ldc(JvmAsm a, String value) {
		ConstantPool.StringConstant sc = this.cp.addString(value);
		if (sc.index() <= 255) {
			a.op(Opcode.LDC);
			a.code.add(sc.index());
		}
		else {
			a.op(Opcode.LDC_W);
			a.u2(sc.index());
		}
	}

	/** Pushes {@code _readPos}. */
	private void pos(JvmAsm a) {
		a.getstatic(this.readPos);
	}

	/** Pushes {@code _readSrc.length()}. */
	private void srcLen(JvmAsm a) {
		a.getstatic(this.readSrc);
		a.invokevirtual(this.stringLength);
	}

	/** Pushes {@code _readSrc.charAt(_readPos)}. */
	private void charAtPos(JvmAsm a) {
		a.getstatic(this.readSrc);
		a.getstatic(this.readPos);
		a.invokevirtual(this.stringCharAt);
	}

	/** Emits {@code _readPos += 1}. */
	private void advance(JvmAsm a) {
		a.getstatic(this.readPos);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.putstatic(this.readPos);
	}

	private List<Integer> buildSkipWs() {
		JvmAsm a = new JvmAsm();
		int loop = a.label();
		int notWs = a.label();
		int cloop = a.label();
		int end = a.label();
		a.bind(loop);
		// if pos >= len return
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, end);
		// c = charAt(pos)
		charAtPos(a);
		a.istore(0);
		a.iload(0);
		a.invokestatic(this.isWhitespace);
		a.branch(Opcode.IFEQ, notWs);
		// whitespace: pos++
		advance(a);
		a.branch(Opcode.GOTO, loop);
		// not whitespace: comment?
		a.bind(notWs);
		a.iload(0);
		a.iconst(';');
		a.branch(Opcode.IF_ICMPNE, end);
		// comment: skip to end of line
		a.bind(cloop);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, end);
		charAtPos(a);
		a.iconst('\n');
		a.branch(Opcode.IF_ICMPEQ, loop); // newline consumed as whitespace next iteration
		advance(a);
		a.branch(Opcode.GOTO, cloop);
		a.bind(end);
		a.op(Opcode.RETURN);
		return a.finish();
	}

	private List<Integer> buildReadExpr() {
		JvmAsm a = new JvmAsm();
		int retNull = a.label();
		int notLp = a.label();
		int notQuote = a.label();
		int notSharp = a.label();
		int notStr = a.label();
		int atom = a.label();
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, retNull);
		charAtPos(a);
		a.istore(0); // c
		// '('
		a.iload(0);
		a.iconst('(');
		a.branch(Opcode.IF_ICMPNE, notLp);
		advance(a);
		a.invokestatic(this.readList);
		a.areturn();
		a.bind(notLp);
		// '\''
		a.iload(0);
		a.iconst('\'');
		a.branch(Opcode.IF_ICMPNE, notQuote);
		advance(a);
		a.invokestatic(this.readExpr);
		a.astore(1); // inner
		wrapWithSymbol(a, LispNames.QUOTE);
		a.areturn();
		a.bind(notQuote);
		// "#'" -> (function inner)
		a.iload(0);
		a.iconst('#');
		a.branch(Opcode.IF_ICMPNE, notSharp);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, notSharp);
		a.getstatic(this.readSrc);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringCharAt);
		a.iconst('\'');
		a.branch(Opcode.IF_ICMPNE, notSharp);
		advance(a); // consume '#'
		advance(a); // consume '\''
		a.invokestatic(this.readExpr);
		a.astore(1); // inner
		wrapWithSymbol(a, LispNames.FUNCTION);
		a.areturn();
		a.bind(notSharp);
		// '"'
		a.iload(0);
		a.iconst('"');
		a.branch(Opcode.IF_ICMPNE, notStr);
		a.invokestatic(this.readStr);
		a.areturn();
		a.bind(notStr);
		// ')'
		a.iload(0);
		a.iconst(')');
		a.branch(Opcode.IF_ICMPNE, atom);
		advance(a);
		a.aconstNull();
		a.areturn();
		a.bind(atom);
		a.invokestatic(this.readAtom);
		a.areturn();
		a.bind(retNull);
		a.aconstNull();
		a.areturn();
		return a.finish();
	}

	/**
	 * Emits {@code (sym inner)} = {@code new Object[]{sym, new Object[]{inner, null}}}
	 * where {@code inner} is in local slot 1. Leaves the result on the stack.
	 */
	private void wrapWithSymbol(JvmAsm a, String sym) {
		a.iconst(2);
		a.anewarray(this.objectClass);
		a.dup();
		a.iconst(0);
		ldc(a, sym);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.iconst(2);
		a.anewarray(this.objectClass);
		a.dup();
		a.iconst(0);
		a.aload(1);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aconstNull();
		a.aastore();
		a.aastore();
	}

	private List<Integer> buildReadList() {
		JvmAsm a = new JvmAsm();
		int retNull = a.label();
		int cont = a.label();
		int notDot = a.label();
		int isDot = a.label();
		int build = a.label();
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, retNull);
		charAtPos(a);
		a.iconst(')');
		a.branch(Opcode.IF_ICMPNE, cont);
		advance(a);
		a.aconstNull();
		a.areturn();
		a.bind(cont);
		a.invokestatic(this.readExpr);
		a.astore(0); // car
		// Dotted pair: a standalone '.' token puts the next datum directly in the
		// final cdr, mirroring the compile-time reader: (a . b). The '.' counts as a
		// token of its own only when followed by a delimiter or the end of input, so
		// symbols and floats containing '.' are untouched.
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, notDot);
		charAtPos(a);
		a.iconst('.');
		a.branch(Opcode.IF_ICMPNE, notDot);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, isDot);
		a.getstatic(this.readSrc);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringCharAt);
		a.istore(1); // ch2 (slot reused; overwritten by the cdr below)
		a.iload(1);
		a.invokestatic(this.isWhitespace);
		a.branch(Opcode.IFNE, isDot);
		a.iload(1);
		a.iconst(')');
		a.branch(Opcode.IF_ICMPEQ, isDot);
		a.iload(1);
		a.iconst('(');
		a.branch(Opcode.IF_ICMPEQ, isDot);
		a.iload(1);
		a.iconst('\'');
		a.branch(Opcode.IF_ICMPEQ, isDot);
		a.iload(1);
		a.iconst('"');
		a.branch(Opcode.IF_ICMPEQ, isDot);
		a.iload(1);
		a.iconst(';');
		a.branch(Opcode.IF_ICMPEQ, isDot);
		a.branch(Opcode.GOTO, notDot);
		a.bind(isDot);
		advance(a); // consume '.'
		a.invokestatic(this.readExpr);
		a.astore(1); // cdr = the dotted tail
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, build);
		charAtPos(a);
		a.iconst(')');
		a.branch(Opcode.IF_ICMPNE, build);
		advance(a); // consume ')'
		a.branch(Opcode.GOTO, build);
		a.bind(notDot);
		a.invokestatic(this.readList);
		a.astore(1); // cdr
		a.bind(build);
		a.iconst(2);
		a.anewarray(this.objectClass);
		a.dup();
		a.iconst(0);
		a.aload(0);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aload(1);
		a.aastore();
		a.areturn();
		a.bind(retNull);
		a.aconstNull();
		a.areturn();
		return a.finish();
	}

	private List<Integer> buildReadAtom() {
		JvmAsm a = new JvmAsm();
		int aloop = a.label();
		int aend = a.label();
		pos(a);
		a.istore(0); // start
		a.bind(aloop);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, aend);
		charAtPos(a);
		a.istore(1); // ch
		a.iload(1);
		a.invokestatic(this.isWhitespace);
		a.branch(Opcode.IFNE, aend);
		stopIf(a, '(', aend);
		stopIf(a, ')', aend);
		stopIf(a, '\'', aend);
		stopIf(a, '"', aend);
		stopIf(a, ';', aend);
		advance(a);
		a.branch(Opcode.GOTO, aloop);
		a.bind(aend);
		// token = src.substring(start, pos)
		a.getstatic(this.readSrc);
		a.iload(0);
		pos(a);
		a.invokevirtual(this.stringSubstring);
		a.invokestatic(this.classify);
		a.areturn();
		return a.finish();
	}

	private void stopIf(JvmAsm a, char ch, int target) {
		a.iload(1);
		a.iconst(ch);
		a.branch(Opcode.IF_ICMPEQ, target);
	}

	private List<Integer> buildReadStr() {
		JvmAsm a = new JvmAsm();
		int loop = a.label();
		int close = a.label();
		int done = a.label();
		int plain = a.label();
		int plainBs = a.label();
		int adv = a.label();
		int e1 = a.label();
		int e2 = a.label();
		int e3 = a.label();
		int e4 = a.label();
		// consume opening quote
		advance(a);
		// sb = new StringBuilder("\"")
		a.anew(this.stringBuilderClass);
		a.dup();
		ldc(a, "\"");
		a.invokespecial(this.sbInitStr);
		a.astore(0);
		a.bind(loop);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, done);
		charAtPos(a);
		a.istore(1); // ch
		a.iload(1);
		a.iconst('"');
		a.branch(Opcode.IF_ICMPEQ, close);
		a.iload(1);
		a.iconst('\\');
		a.branch(Opcode.IF_ICMPNE, plain);
		// backslash: check pos+1 < len
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, plainBs);
		// esc = charAt(pos+1)
		a.getstatic(this.readSrc);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringCharAt);
		a.istore(2); // esc
		// consume the backslash (esc char consumed by ADV)
		advance(a);
		// switch esc
		a.iload(2);
		a.iconst('n');
		a.branch(Opcode.IF_ICMPNE, e1);
		appendChar(a, '\n');
		a.branch(Opcode.GOTO, adv);
		a.bind(e1);
		a.iload(2);
		a.iconst('t');
		a.branch(Opcode.IF_ICMPNE, e2);
		appendChar(a, '\t');
		a.branch(Opcode.GOTO, adv);
		a.bind(e2);
		a.iload(2);
		a.iconst('\\');
		a.branch(Opcode.IF_ICMPNE, e3);
		appendChar(a, '\\');
		a.branch(Opcode.GOTO, adv);
		a.bind(e3);
		a.iload(2);
		a.iconst('"');
		a.branch(Opcode.IF_ICMPNE, e4);
		appendChar(a, '"');
		a.branch(Opcode.GOTO, adv);
		a.bind(e4);
		// default: append '\\' then esc
		a.aload(0);
		a.iconst('\\');
		a.invokevirtual(this.sbAppendChar);
		a.pop();
		a.aload(0);
		a.iload(2);
		a.invokevirtual(this.sbAppendChar);
		a.pop();
		a.branch(Opcode.GOTO, adv);
		a.bind(plainBs);
		a.aload(0);
		a.iload(1);
		a.invokevirtual(this.sbAppendChar);
		a.pop();
		a.branch(Opcode.GOTO, adv);
		a.bind(plain);
		a.aload(0);
		a.iload(1);
		a.invokevirtual(this.sbAppendChar);
		a.pop();
		a.branch(Opcode.GOTO, adv);
		a.bind(adv);
		advance(a);
		a.branch(Opcode.GOTO, loop);
		a.bind(close);
		advance(a); // consume closing quote
		a.bind(done);
		a.aload(0);
		a.iconst('"');
		a.invokevirtual(this.sbAppendChar);
		a.pop();
		a.aload(0);
		a.invokevirtual(this.sbToString);
		a.areturn();
		return a.finish();
	}

	private void appendChar(JvmAsm a, char ch) {
		a.aload(0);
		a.iconst(ch);
		a.invokevirtual(this.sbAppendChar);
		a.pop();
	}

	private List<Integer> buildClassify() {
		JvmAsm a = new JvmAsm();
		int notNil = a.label();
		int notT = a.label();
		int vloop = a.label();
		int notDigit = a.label();
		int notComma = a.label();
		int vnext = a.label();
		int vend = a.label();
		int sym = a.label();
		int intPath = a.label();
		int big = a.label();
		// Upcase the token to its canonical spelling first (uppercase-canonical: the
		// reader upcases every unescaped symbol character like CL's :upcase readtable
		// case, with no fold back to a lowercase form). So (read "foo") is FOO, (read
		// "car") is CAR and (read "&optional") is &OPTIONAL -- matching the frontend
		// reader. A numeric token has no letters, so the upcase is a no-op and the
		// classifier below still parses it; NIL/T are recognized on the upcased name.
		a.aload(0);
		a.getstatic(this.localeRoot);
		a.invokevirtual(this.stringToUpperCase);
		a.astore(0);
		// nil?
		a.aload(0);
		ldc(a, "NIL");
		a.invokevirtual(this.objectEquals);
		a.branch(Opcode.IFEQ, notNil);
		a.aconstNull();
		a.areturn();
		a.bind(notNil);
		// t? -> the symbol t (the bare String "t", like the interpreter's reader), so it
		// prints as t and is eq to a quoted 't.
		a.aload(0);
		ldc(a, "T");
		a.invokevirtual(this.objectEquals);
		a.branch(Opcode.IFEQ, notT);
		ldc(a, "T");
		a.areturn();
		a.bind(notT);
		// n = token.length(); slot2
		a.aload(0);
		a.invokevirtual(this.stringLength);
		a.istore(2);
		a.iload(2);
		a.branch(Opcode.IFEQ, sym); // empty -> symbol
		a.iconst(0);
		a.istore(1); // i
		a.iconst(0);
		a.istore(3); // sawDigit
		a.iconst(0);
		a.istore(4); // sawDot
		// if token[0]=='-': i=1
		a.aload(0);
		a.iconst(0);
		a.invokevirtual(this.stringCharAt);
		a.iconst('-');
		a.branch(Opcode.IF_ICMPNE, vloop);
		a.iconst(1);
		a.istore(1);
		a.bind(vloop);
		a.iload(1);
		a.iload(2);
		a.branch(Opcode.IF_ICMPGE, vend);
		a.aload(0);
		a.iload(1);
		a.invokevirtual(this.stringCharAt);
		a.istore(6); // ch
		// digit?
		a.iload(6);
		a.iconst('0');
		a.branch(Opcode.IF_ICMPLT, notDigit);
		a.iload(6);
		a.iconst('9');
		a.branch(Opcode.IF_ICMPGT, notDigit);
		a.iconst(1);
		a.istore(3);
		a.branch(Opcode.GOTO, vnext);
		a.bind(notDigit);
		a.iload(6);
		a.iconst(',');
		a.branch(Opcode.IF_ICMPNE, notComma);
		a.branch(Opcode.GOTO, vnext); // grouping comma: skip
		a.bind(notComma);
		a.iload(6);
		a.iconst('.');
		a.branch(Opcode.IF_ICMPNE, sym); // any other char -> symbol
		a.iload(4);
		a.branch(Opcode.IFNE, sym); // second dot -> symbol
		a.iconst(1);
		a.istore(4);
		a.branch(Opcode.GOTO, vnext);
		a.bind(vnext);
		a.iinc(1, 1);
		a.branch(Opcode.GOTO, vloop);
		a.bind(vend);
		a.iload(3);
		a.branch(Opcode.IFEQ, sym); // no digit -> symbol
		// stripped = token.replace(",", "") ; slot5
		a.aload(0);
		ldc(a, ",");
		ldc(a, "");
		a.invokevirtual(this.stringReplace);
		a.astore(5);
		// double?
		a.iload(4);
		a.branch(Opcode.IFEQ, intPath);
		a.aload(5);
		a.invokestatic(this.doubleParse);
		a.invokestatic(this.doubleValueOf);
		a.areturn();
		a.bind(intPath);
		// bi = new BigInteger(stripped)
		a.anew(this.bigIntegerClass);
		a.dup();
		a.aload(5);
		a.invokespecial(this.bigIntegerInit);
		// if bi.bitLength() < 64 -> Long.valueOf(bi.longValue()) else bi
		a.dup();
		a.invokevirtual(this.bigIntegerBitLength);
		a.iconst(64);
		a.branch(Opcode.IF_ICMPGE, big);
		a.invokevirtual(this.bigIntegerLongValue);
		a.invokestatic(this.longValueOf);
		a.areturn();
		a.bind(big);
		a.areturn(); // bi (BigInteger) still on stack
		a.bind(sym);
		a.aload(0);
		a.areturn();
		return a.finish();
	}

	private List<Integer> buildRead() {
		JvmAsm a = new JvmAsm();
		int loop = a.label();
		int rnull = a.label();
		// Keep reading lines until one contains a datum (blank and comment-only lines
		// are skipped) or stdin is exhausted (EOF -> nil).
		a.bind(loop);
		a.invokestatic(this.readLineHelper);
		a.dup();
		a.branch(Opcode.IFNULL, rnull);
		a.checkcast(this.stringClass);
		a.astore(0);
		// raw = s.substring(1, s.length()-1)
		a.aload(0);
		a.iconst(1);
		a.aload(0);
		a.invokevirtual(this.stringLength);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(this.stringSubstring);
		a.putstatic(this.readSrc);
		a.iconst(0);
		a.putstatic(this.readPos);
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, loop);
		a.invokestatic(this.readExpr);
		a.areturn();
		a.bind(rnull);
		a.areturn(); // null already on stack
		return a.finish();
	}

	// _readStream(Object handle): like _read but reads lines from an open input stream
	// via _readLineStream(handle); returns one datum per call, or null at end of stream.
	private List<Integer> buildReadStream() {
		JvmAsm a = new JvmAsm();
		int loop = a.label();
		int rnull = a.label();
		a.bind(loop);
		a.aload(0); // handle
		a.invokestatic(this.readLineStream);
		a.dup();
		a.branch(Opcode.IFNULL, rnull);
		a.checkcast(this.stringClass);
		a.astore(1);
		// raw = s.substring(1, s.length()-1)
		a.aload(1);
		a.iconst(1);
		a.aload(1);
		a.invokevirtual(this.stringLength);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(this.stringSubstring);
		a.putstatic(this.readSrc);
		a.iconst(0);
		a.putstatic(this.readPos);
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, loop);
		a.invokestatic(this.readExpr);
		a.areturn();
		a.bind(rnull);
		a.areturn(); // null already on stack
		return a.finish();
	}

	// _readFromString(Object strObj): parse the first datum from a string (the quotes of
	// the runtime string representation are stripped first); returns null when empty.
	private List<Integer> buildReadFromString() {
		JvmAsm a = new JvmAsm();
		int retNull = a.label();
		a.aload(0);
		a.checkcast(this.stringClass);
		a.astore(1);
		// raw = s.substring(1, s.length()-1)
		a.aload(1);
		a.iconst(1);
		a.aload(1);
		a.invokevirtual(this.stringLength);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(this.stringSubstring);
		a.putstatic(this.readSrc);
		a.iconst(0);
		a.putstatic(this.readPos);
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, retNull);
		a.invokestatic(this.readExpr);
		a.areturn();
		a.bind(retNull);
		a.aconstNull();
		a.areturn();
		return a.finish();
	}

	private List<Integer> buildLoad() {
		JvmAsm a = new JvmAsm();
		int loop = a.label();
		int done = a.label();
		MethodrefConstant eval = java.util.Objects.requireNonNull(this.evalRef);
		MethodrefConstant paths = java.util.Objects.requireNonNull(this.pathsGet);
		MethodrefConstant files = java.util.Objects.requireNonNull(this.filesReadString);
		// path = ((String) pathVal).substring(1, len-1)
		a.aload(0);
		a.checkcast(this.stringClass);
		a.astore(1);
		a.aload(1);
		a.iconst(1);
		a.aload(1);
		a.invokevirtual(this.stringLength);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(this.stringSubstring);
		a.astore(2); // path
		// content = Files.readString(Paths.get(path, new String[0]))
		a.aload(2);
		a.iconst(0);
		a.anewarray(this.stringClass);
		a.invokestatic(paths);
		a.invokestatic(files);
		a.putstatic(this.readSrc);
		a.iconst(0);
		a.putstatic(this.readPos);
		a.bind(loop);
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, done);
		a.invokestatic(this.readExpr);
		a.aconstNull();
		a.invokestatic(eval);
		a.pop();
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.op(Opcode.LCONST_1);
		a.invokestatic(this.longValueOf);
		a.areturn();
		return a.finish();
	}

}
