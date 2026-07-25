package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.EmittedReaderInitforms;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
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

	/**
	 * The static field holding the runtime struct-layout directory for {@code #S(...)}:
	 * one {@code Object[]} entry per registered layout, {@code {String pkg, String
	 * member, String[] layout, String[] initTexts}} -- {@code layout}/{@code initTexts}
	 * are null for a CLOS class entry (present only so the "it names a class" hint can
	 * fire). Emitted only when the reader is present AND the program may hold instances.
	 */
	static final String STRUCT_TABLE_FIELD = "_rdStructs";

	static final String STRUCT_TABLE_DESC = "[[Ljava/lang/Object;";

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

	// === # dispatch (the frontend lexer's dispatch set, mirrored) ===

	private final MethodrefConstant readHash;

	private final MethodrefConstant readCharLit;

	private final MethodrefConstant readRadix;

	private final MethodrefConstant readBits;

	private final MethodrefConstant readArrayN;

	private final MethodrefConstant readPacked;

	private final MethodrefConstant readStruct;

	private final MethodrefConstant rdLen;

	private final MethodrefConstant rdConsp;

	private final MethodrefConstant rdLevel;

	private final MethodrefConstant rdDims;

	private final MethodrefConstant rdFlat;

	private final MethodrefConstant rdErr;

	private final MethodrefConstant rdName;

	private final MethodrefConstant rdF;

	private final MethodrefConstant rdInferRank;

	private final MethodrefConstant lispToString;

	private final MethodrefConstant ratMethod;

	private final ClassConstant intArrayClass;

	private final ClassConstant bigIntegerArrayClass;

	private final ClassConstant stringArrayClass;

	private final ClassConstant integerClass;

	private final ClassConstant longClass;

	private final ClassConstant doubleClass;

	private final ClassConstant arrayListClass;

	private final ClassConstant rtExClass;

	private final MethodrefConstant rtExInit;

	private final MethodrefConstant alInit;

	private final MethodrefConstant alAdd;

	private final MethodrefConstant alSet;

	private final MethodrefConstant alSize;

	private final MethodrefConstant alGet;

	private final MethodrefConstant charIsLetter;

	private final MethodrefConstant charDigit;

	private final MethodrefConstant bigIntegerInitRadix;

	private final MethodrefConstant bigIntegerSignum;

	private final MethodrefConstant bigIntegerToString;

	private final MethodrefConstant bigIntegerDoubleValue;

	private final MethodrefConstant longLongValue;

	private final MethodrefConstant doubleDoubleValue;

	private final MethodrefConstant sbInitEmpty;

	private final MethodrefConstant sbAppendStr;

	private final MethodrefConstant sbAppendInt;

	private final MethodrefConstant sbAppendLong;

	private final MethodrefConstant stringEqualsIgnoreCase;

	private final MethodrefConstant stringIndexOf;

	private final MethodrefConstant stringLastIndexOf;

	private final MethodrefConstant stringSubstringFrom;

	private final MethodrefConstant stringStartsWith;

	private final @Nullable FieldrefConstant rdStructs;

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
			boolean emitLoad, boolean instances) {
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

		this.readHash = methodref("_readHash", "()Ljava/lang/Object;");
		this.readCharLit = methodref("_readCharLit", "()Ljava/lang/Object;");
		this.readRadix = methodref("_readRadix", "(II)Ljava/lang/Object;");
		this.readBits = methodref("_readBits", "()Ljava/lang/Object;");
		this.readArrayN = methodref("_readArrayN", "(I)Ljava/lang/Object;");
		this.readPacked = methodref("_readPacked", "(I)Ljava/lang/Object;");
		this.readStruct = methodref("_readStruct", "()Ljava/lang/Object;");
		this.rdLen = methodref("_rdLen", "(Ljava/lang/Object;Ljava/lang/String;)I");
		this.rdConsp = methodref("_rdConsp", "(Ljava/lang/Object;)Z");
		this.rdLevel = methodref("_rdLevel", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
		this.rdDims = methodref("_rdDims", "(Ljava/lang/Object;ILjava/lang/String;)[Ljava/lang/Object;");
		this.rdFlat = methodref("_rdFlat",
				"(Ljava/lang/Object;I[Ljava/lang/Object;Ljava/util/ArrayList;Ljava/lang/String;)V");
		this.rdErr = methodref("_rdErr", "(Ljava/lang/String;)V");
		this.rdName = methodref("_rdName", "(Ljava/lang/String;)Ljava/lang/String;");
		this.rdF = methodref("_rdF", "(Ljava/lang/Object;)D");
		this.rdInferRank = methodref("_rdInferRank", "(Ljava/lang/Object;)I");
		this.lispToString = methodref("_lispToString", "(Ljava/lang/Object;)Ljava/lang/String;");
		this.ratMethod = methodref("_rat", "(Ljava/math/BigInteger;Ljava/math/BigInteger;)Ljava/lang/Object;");
		this.intArrayClass = cp.addClass(cp.addUtf8("[I"));
		this.bigIntegerArrayClass = cp.addClass(cp.addUtf8("[Ljava/math/BigInteger;"));
		this.stringArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/String;"));
		this.integerClass = cp.addClass(cp.addUtf8("java/lang/Integer"));
		this.longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		this.doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		this.arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		this.rtExClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		this.rtExInit = cp.addMethodref(this.rtExClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		this.alInit = cp.addMethodref(this.arrayListClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		this.alAdd = cp.addMethodref(this.arrayListClass,
				cp.addNameAndType(cp.addUtf8("add"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		this.alSet = cp.addMethodref(this.arrayListClass,
				cp.addNameAndType(cp.addUtf8("set"), cp.addUtf8("(ILjava/lang/Object;)Ljava/lang/Object;")));
		this.alSize = cp.addMethodref(this.arrayListClass, cp.addNameAndType(cp.addUtf8("size"), cp.addUtf8("()I")));
		this.alGet = cp.addMethodref(this.arrayListClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(I)Ljava/lang/Object;")));
		ClassConstant characterCls = cp.addClass(cp.addUtf8("java/lang/Character"));
		this.charIsLetter = cp.addMethodref(characterCls,
				cp.addNameAndType(cp.addUtf8("isLetter"), cp.addUtf8("(C)Z")));
		this.charDigit = cp.addMethodref(characterCls, cp.addNameAndType(cp.addUtf8("digit"), cp.addUtf8("(II)I")));
		this.bigIntegerInitRadix = cp.addMethodref(this.bigIntegerClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;I)V")));
		this.bigIntegerSignum = cp.addMethodref(this.bigIntegerClass,
				cp.addNameAndType(cp.addUtf8("signum"), cp.addUtf8("()I")));
		this.bigIntegerToString = cp.addMethodref(this.bigIntegerClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		this.bigIntegerDoubleValue = cp.addMethodref(this.bigIntegerClass,
				cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D")));
		this.longLongValue = cp.addMethodref(this.longClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));
		this.doubleDoubleValue = cp.addMethodref(this.doubleClass,
				cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D")));
		this.sbInitEmpty = cp.addMethodref(this.stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		this.sbAppendStr = cp.addMethodref(this.stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		this.sbAppendInt = cp.addMethodref(this.stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(I)Ljava/lang/StringBuilder;")));
		this.sbAppendLong = cp.addMethodref(this.stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(J)Ljava/lang/StringBuilder;")));
		this.stringEqualsIgnoreCase = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("equalsIgnoreCase"), cp.addUtf8("(Ljava/lang/String;)Z")));
		this.stringIndexOf = cp.addMethodref(stringClass, cp.addNameAndType(cp.addUtf8("indexOf"), cp.addUtf8("(I)I")));
		this.stringLastIndexOf = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("lastIndexOf"), cp.addUtf8("(I)I")));
		this.stringSubstringFrom = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(I)Ljava/lang/String;")));
		this.stringStartsWith = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("startsWith"), cp.addUtf8("(Ljava/lang/String;)Z")));
		this.rdStructs = instances ? cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8(STRUCT_TABLE_FIELD), cp.addUtf8(STRUCT_TABLE_DESC))) : null;

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
			boolean emitLoad, boolean instances) {
		return new JvmReadRuntimeBuilder(cp, thisClass, objectClass, objectArrayClass, stringClass, longValueOf,
				doubleValueOf, stringCharAt, stringLength, stringSubstring, objectEquals, readLineHelper, emitLoad,
				instances);
	}

	private MethodrefConstant methodref(String name, String desc) {
		return this.cp.addMethodref(this.thisClass,
				this.cp.addNameAndType(this.cp.addUtf8(name), this.cp.addUtf8(desc)));
	}

	/**
	 * Builds the {@code <clinit>} chunk filling {@link #STRUCT_TABLE_FIELD}: one
	 * {@code Object[]{pkg, member, layout, initTexts}} entry per registered layout, in
	 * registration order (classes carry null layout/initTexts -- they exist only for the
	 * "it names a class" hint). The struct layouts must already be interned in the layout
	 * pool. The chunk's operand stack peaks at 9; the caller's declared {@code <clinit>}
	 * max_stack must cover it.
	 * @param cp the constant pool (still open -- this mints CONSTANT_String entries)
	 * @param thisClass the generated class
	 * @param pool the layout pool holding the interned struct layout fields
	 * @param registry the layout registry
	 * @param objectClass {@code java/lang/Object}
	 * @param objectArrayClass {@code [Ljava/lang/Object;}
	 * @param stringClass {@code java/lang/String}
	 * @return the code chunk (no trailing RETURN)
	 */
	static List<Integer> structTableClinit(ConstantPool cp, ClassConstant thisClass, JvmLispCompiler.LayoutPool pool,
			ClosRegistry registry, ClassConstant objectClass, ClassConstant objectArrayClass,
			ClassConstant stringClass) {
		FieldrefConstant field = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8(STRUCT_TABLE_FIELD), cp.addUtf8(STRUCT_TABLE_DESC)));
		List<LispLayout> layouts = new ArrayList<>(registry.layouts().values());
		JvmAsm a = new JvmAsm();
		a.iconst(layouts.size());
		a.anewarray(objectArrayClass);
		for (int i = 0; i < layouts.size(); i++) {
			LispLayout layout = layouts.get(i);
			boolean isStruct = layout.kind() == LispLayout.Kind.STRUCT;
			String prefix = isStruct ? LispLayout.STRUCT_TAG_PREFIX : LispLayout.CLASS_TAG_PREFIX;
			String registered = layout.tag().substring(prefix.length());
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(registered);
			String pkg = qn == null ? "" : qn.pkg();
			String member = qn == null ? registered : qn.member();
			a.dup();
			a.iconst(i);
			a.iconst(4);
			a.anewarray(objectClass);
			a.dup();
			a.iconst(0);
			a.ldcString(cp.addString(pkg));
			a.aastore();
			a.dup();
			a.iconst(1);
			a.ldcString(cp.addString(member));
			a.aastore();
			if (isStruct) {
				FieldrefConstant layoutField = layoutFieldFor(pool, layout.tag());
				a.dup();
				a.iconst(2);
				a.getstatic(layoutField);
				a.aastore();
				@Nullable String[] inits = EmittedReaderInitforms.initTexts(layout, false);
				a.dup();
				a.iconst(3);
				a.iconst(inits.length);
				a.anewarray(stringClass);
				for (int k = 0; k < inits.length; k++) {
					if (inits[k] != null) {
						a.dup();
						a.iconst(k);
						a.ldcString(cp.addString(inits[k]));
						a.aastore();
					}
				}
				a.aastore();
			}
			a.aastore();
		}
		a.putstatic(field);
		return a.finish();
	}

	private static FieldrefConstant layoutFieldFor(JvmLispCompiler.LayoutPool pool, String tag) {
		for (JvmLispCompiler.LayoutPool.LayoutField lf : pool.fields()) {
			if (lf.layout().tag().equals(tag)) {
				return lf.ref();
			}
		}
		throw new IllegalStateException("Struct layout not interned for the reader directory: " + tag);
	}

	/** Returns all reader method bodies to emit. */
	List<ReadMethod> methods() {
		List<ReadMethod> ms = new ArrayList<>();
		ms.add(new ReadMethod(this.cp.addUtf8("_readSkipWs"), this.cp.addUtf8("()V"), 4, 2, buildSkipWs()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readExpr"), this.cp.addUtf8("()Ljava/lang/Object;"), 8, 2,
				buildReadExpr()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readList"), this.cp.addUtf8("()Ljava/lang/Object;"), 6, 2,
				buildReadList()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readAtom"), this.cp.addUtf8("()Ljava/lang/Object;"), 4, 2,
				buildReadAtom()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readStr"), this.cp.addUtf8("()Ljava/lang/Object;"), 4, 3,
				buildReadStr()));
		ms.add(new ReadMethod(this.cp.addUtf8("_classify"), this.cp.addUtf8("(Ljava/lang/String;)Ljava/lang/Object;"),
				6, 10, buildClassify()));
		ms.add(new ReadMethod(this.cp.addUtf8("_read"), this.cp.addUtf8("()Ljava/lang/Object;"), 4, 1, buildRead()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readStream"), this.cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;"),
				4, 2, buildReadStream()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readFromString"),
				this.cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;"), 4, 2, buildReadFromString()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readHash"), this.cp.addUtf8("()Ljava/lang/Object;"), 8, 4,
				buildReadHash()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readCharLit"), this.cp.addUtf8("()Ljava/lang/Object;"), 6, 4,
				buildReadCharLit()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readRadix"), this.cp.addUtf8("(II)Ljava/lang/Object;"), 6, 7,
				buildReadRadix()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readBits"), this.cp.addUtf8("()Ljava/lang/Object;"), 10, 3,
				buildReadBits()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readArrayN"), this.cp.addUtf8("(I)Ljava/lang/Object;"), 8, 5,
				buildReadArrayN()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readPacked"), this.cp.addUtf8("(I)Ljava/lang/Object;"), 8, 12,
				buildReadPacked()));
		ms.add(new ReadMethod(this.cp.addUtf8("_readStruct"), this.cp.addUtf8("()Ljava/lang/Object;"), 8, 18,
				buildReadStruct()));
		ms.add(new ReadMethod(this.cp.addUtf8("_rdLen"), this.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/String;)I"), 4,
				4, buildRdLen()));
		ms.add(new ReadMethod(this.cp.addUtf8("_rdConsp"), this.cp.addUtf8("(Ljava/lang/Object;)Z"), 3, 2,
				buildRdConsp()));
		ms.add(new ReadMethod(this.cp.addUtf8("_rdLevel"),
				this.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"), 5, 2, buildRdLevel()));
		ms.add(new ReadMethod(this.cp.addUtf8("_rdDims"),
				this.cp.addUtf8("(Ljava/lang/Object;ILjava/lang/String;)[Ljava/lang/Object;"), 6, 6, buildRdDims()));
		ms.add(new ReadMethod(this.cp.addUtf8("_rdFlat"),
				this.cp.addUtf8("(Ljava/lang/Object;I[Ljava/lang/Object;Ljava/util/ArrayList;Ljava/lang/String;)V"), 7,
				8, buildRdFlat()));
		ms.add(new ReadMethod(this.cp.addUtf8("_rdErr"), this.cp.addUtf8("(Ljava/lang/String;)V"), 3, 1, buildRdErr()));
		ms.add(new ReadMethod(this.cp.addUtf8("_rdName"), this.cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;"), 3,
				2, buildRdName()));
		ms.add(new ReadMethod(this.cp.addUtf8("_rdF"), this.cp.addUtf8("(Ljava/lang/Object;)D"), 6, 2, buildRdF()));
		ms.add(new ReadMethod(this.cp.addUtf8("_rdInferRank"), this.cp.addUtf8("(Ljava/lang/Object;)I"), 3, 3,
				buildRdInferRank()));
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
		int notSemi = a.label();
		int bloop = a.label();
		int bNotClose = a.label();
		int bNotOpen = a.label();
		int bPlain = a.label();
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
		a.branch(Opcode.IF_ICMPNE, notSemi);
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
		a.bind(notSemi);
		// "#|" block comment, honoring nesting like the frontend lexer
		a.iload(0);
		a.iconst('#');
		a.branch(Opcode.IF_ICMPNE, end);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, end);
		a.getstatic(this.readSrc);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringCharAt);
		a.iconst('|');
		a.branch(Opcode.IF_ICMPNE, end);
		advance(a); // consume '#'
		advance(a); // consume '|'
		a.iconst(1);
		a.istore(1); // depth
		a.bind(bloop);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPLT, bNotClose);
		// input exhausted inside the comment: unterminated
		ldc(a, "Unterminated block comment");
		a.invokestatic(this.rdErr);
		a.op(Opcode.RETURN);
		a.bind(bNotClose);
		// "|#" -> depth--, back to whitespace skipping at 0
		charAtPos(a);
		a.iconst('|');
		a.branch(Opcode.IF_ICMPNE, bNotOpen);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, bNotOpen);
		a.getstatic(this.readSrc);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringCharAt);
		a.iconst('#');
		a.branch(Opcode.IF_ICMPNE, bNotOpen);
		advance(a); // consume '|'
		advance(a); // consume '#'
		a.iinc(1, -1);
		a.iload(1);
		a.branch(Opcode.IFEQ, loop); // depth 0: back to whitespace skipping
		a.branch(Opcode.GOTO, bloop);
		a.bind(bNotOpen);
		// "#|" -> depth++
		charAtPos(a);
		a.iconst('#');
		a.branch(Opcode.IF_ICMPNE, bPlain);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, bPlain);
		a.getstatic(this.readSrc);
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringCharAt);
		a.iconst('|');
		a.branch(Opcode.IF_ICMPNE, bPlain);
		advance(a); // consume '#'
		advance(a); // consume '|'
		a.iinc(1, 1);
		a.branch(Opcode.GOTO, bloop);
		a.bind(bPlain);
		advance(a);
		a.branch(Opcode.GOTO, bloop);
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
		wrapWithSymbol(a, LispNames.QUOTE, 1);
		a.areturn();
		a.bind(notQuote);
		// '#' -> the dispatch mirror of the frontend lexer (chars, vectors, arrays,
		// structs, radix ints, packed floats, bit vectors); a token no dispatch claims
		// falls back to the atom path inside _readHash, like the frontend's readSymbol.
		a.iload(0);
		a.iconst('#');
		a.branch(Opcode.IF_ICMPNE, notSharp);
		a.invokestatic(this.readHash);
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
	 * where {@code inner} is in local slot {@code innerSlot}. Leaves the result on the
	 * stack.
	 */
	private void wrapWithSymbol(JvmAsm a, String sym, int innerSlot) {
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
		a.aload(innerSlot);
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
		// Leading '+': an explicitly positive number literal (+347, +2.5, +1/3) drops
		// the sign when a digit follows, like the frontend tokenizer; any other '+'
		// token stays a symbol.
		int noPlus = a.label();
		a.aload(0);
		a.invokevirtual(this.stringLength);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPLT, noPlus);
		a.aload(0);
		a.iconst(0);
		a.invokevirtual(this.stringCharAt);
		a.iconst('+');
		a.branch(Opcode.IF_ICMPNE, noPlus);
		a.aload(0);
		a.iconst(1);
		a.invokevirtual(this.stringCharAt);
		a.iconst('0');
		a.branch(Opcode.IF_ICMPLT, noPlus);
		a.aload(0);
		a.iconst(1);
		a.invokevirtual(this.stringCharAt);
		a.iconst('9');
		a.branch(Opcode.IF_ICMPGT, noPlus);
		a.aload(0);
		a.iconst(1);
		a.invokevirtual(this.stringSubstringFrom);
		a.astore(0);
		a.bind(noPlus);
		// n = token.length(); slot2
		a.aload(0);
		a.invokevirtual(this.stringLength);
		a.istore(2);
		a.iload(2);
		a.branch(Opcode.IFEQ, sym); // empty -> symbol
		// Ratio N/D: exactly one '/', digits (with grouping commas) on both sides, an
		// optional leading '-'; built through _rat so normalization (2/4 -> 1/2) and the
		// integer demotion (4/2 -> 2) match the frontend. Any other '/'-bearing token
		// falls through to the symbol path.
		int noRatio = a.label();
		int rnumLoop = a.label();
		int rnumNext = a.label();
		int rnumDone = a.label();
		int rdenLoop = a.label();
		int rdenNext = a.label();
		int rdenDone = a.label();
		int rDivZero = a.label();
		a.aload(0);
		a.iconst('/');
		a.invokevirtual(this.stringIndexOf);
		a.istore(7); // si
		a.iload(7);
		a.branch(Opcode.IFLT, noRatio);
		// numerator scan from j = ('-' prefix ? 1 : 0) to si-1
		a.iconst(0);
		a.istore(8); // j
		a.aload(0);
		a.iconst(0);
		a.invokevirtual(this.stringCharAt);
		a.iconst('-');
		a.branch(Opcode.IF_ICMPNE, rnumLoop);
		a.iconst(1);
		a.istore(8);
		a.bind(rnumLoop);
		a.iconst(0);
		a.istore(9); // sawNumDigit
		int rnumScan = a.label();
		a.bind(rnumScan);
		a.iload(8);
		a.iload(7);
		a.branch(Opcode.IF_ICMPGE, rnumDone);
		a.aload(0);
		a.iload(8);
		a.invokevirtual(this.stringCharAt);
		a.istore(6);
		a.iload(6);
		a.iconst(',');
		a.branch(Opcode.IF_ICMPEQ, rnumNext);
		a.iload(6);
		a.iconst('0');
		a.branch(Opcode.IF_ICMPLT, sym);
		a.iload(6);
		a.iconst('9');
		a.branch(Opcode.IF_ICMPGT, sym);
		a.iconst(1);
		a.istore(9);
		a.bind(rnumNext);
		a.iinc(8, 1);
		a.branch(Opcode.GOTO, rnumScan);
		a.bind(rnumDone);
		a.iload(9);
		a.branch(Opcode.IFEQ, sym);
		// denominator scan from si+1 to n-1 (no sign allowed)
		a.iload(7);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.iload(2);
		a.branch(Opcode.IF_ICMPGE, sym); // empty denominator
		a.iload(7);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.istore(8);
		a.iconst(0);
		a.istore(9); // sawDenDigit
		a.bind(rdenLoop);
		a.iload(8);
		a.iload(2);
		a.branch(Opcode.IF_ICMPGE, rdenDone);
		a.aload(0);
		a.iload(8);
		a.invokevirtual(this.stringCharAt);
		a.istore(6);
		a.iload(6);
		a.iconst(',');
		a.branch(Opcode.IF_ICMPEQ, rdenNext);
		a.iload(6);
		a.iconst('0');
		a.branch(Opcode.IF_ICMPLT, sym);
		a.iload(6);
		a.iconst('9');
		a.branch(Opcode.IF_ICMPGT, sym);
		a.iconst(1);
		a.istore(9);
		a.bind(rdenNext);
		a.iinc(8, 1);
		a.branch(Opcode.GOTO, rdenLoop);
		a.bind(rdenDone);
		a.iload(9);
		a.branch(Opcode.IFEQ, sym);
		// numStr = token.substring(0, si).replace(",", "") (kept for the /0 message)
		a.aload(0);
		a.iconst(0);
		a.iload(7);
		a.invokevirtual(this.stringSubstring);
		ldc(a, ",");
		ldc(a, "");
		a.invokevirtual(this.stringReplace);
		a.astore(5);
		// num, den on the stack, den checked for zero before _rat
		a.anew(this.bigIntegerClass);
		a.dup();
		a.aload(5);
		a.invokespecial(this.bigIntegerInit);
		a.anew(this.bigIntegerClass);
		a.dup();
		a.aload(0);
		a.iload(7);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringSubstringFrom);
		ldc(a, ",");
		ldc(a, "");
		a.invokevirtual(this.stringReplace);
		a.invokespecial(this.bigIntegerInit);
		a.dup();
		a.invokevirtual(this.bigIntegerSignum);
		a.branch(Opcode.IFEQ, rDivZero);
		a.invokestatic(this.ratMethod);
		a.areturn();
		a.bind(rDivZero);
		a.pop();
		a.pop();
		sbNew(a, "Division by zero in ratio literal: ");
		a.aload(5);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, "/0");
		sbThrow(a);
		a.aconstNull();
		a.areturn();
		a.bind(noRatio);
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

	// === # dispatch bodies ===

	/** Emits {@code new StringBuilder(head)} leaving the builder on the stack. */
	private void sbNew(JvmAsm a, String head) {
		a.anew(this.stringBuilderClass);
		a.dup();
		ldc(a, head);
		a.invokespecial(this.sbInitStr);
	}

	/** Appends the constant {@code text} to the StringBuilder on the stack. */
	private void sbText(JvmAsm a, String text) {
		ldc(a, text);
		a.invokevirtual(this.sbAppendStr);
	}

	/** Finishes the StringBuilder on the stack and throws it via {@code _rdErr}. */
	private void sbThrow(JvmAsm a) {
		a.invokevirtual(this.sbToString);
		a.invokestatic(this.rdErr);
	}

	/** Emits a static-message throw via {@code _rdErr}. */
	private void err(JvmAsm a, String message) {
		ldc(a, message);
		a.invokestatic(this.rdErr);
	}

	/** Pushes {@code _readSrc.charAt(_readPos + offset)}. */
	private void charAtPosPlus(JvmAsm a, int offset) {
		a.getstatic(this.readSrc);
		a.getstatic(this.readPos);
		a.iconst(offset);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringCharAt);
	}

	/** Branches to {@code target} when {@code _readPos + offset >= len}. */
	private void branchIfPosPlusGeLen(JvmAsm a, int offset, int target) {
		a.getstatic(this.readPos);
		a.iconst(offset);
		a.op(Opcode.IADD);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, target);
	}

	// _readHash: the '#' dispatcher, mirroring the frontend lexer's dispatch set. The
	// cursor is still AT the '#'; any token no dispatch claims falls back to the atom
	// path, so #foo / #:g / #16r1f read as the symbols the frontend reads them as.
	private List<Integer> buildReadHash() {
		JvmAsm a = new JvmAsm();
		int atom = a.label();
		int notFn = a.label();
		int notChar = a.label();
		int notVec = a.label();
		int notStructS = a.label();
		int structOpen = a.label();
		int notBits = a.label();
		int notSingle = a.label();
		int singleOpen = a.label();
		int notDouble = a.label();
		int doubleOpen = a.label();
		int notDigit = a.label();
		int dloop = a.label();
		int dend = a.label();
		int rankOverflow = a.label();
		int notArrA = a.label();
		int arrOpen = a.label();
		int labelErr = a.label();
		int notX = a.label();
		int notO = a.label();
		int notB = a.label();
		int radix16 = a.label();
		int radix8 = a.label();
		int radix2 = a.label();
		int notDot = a.label();
		int notFeature = a.label();
		// if pos+1 >= len -> atom ("#" at end of input reads as the symbol #)
		branchIfPosPlusGeLen(a, 1, atom);
		// c2 = charAt(pos+1)
		charAtPosPlus(a, 1);
		a.istore(0);
		// "#'" -> (function inner)
		a.iload(0);
		a.iconst('\'');
		a.branch(Opcode.IF_ICMPNE, notFn);
		advance(a);
		advance(a);
		a.invokestatic(this.readExpr);
		a.astore(3);
		wrapWithSymbol(a, LispNames.FUNCTION, 3);
		a.areturn();
		a.bind(notFn);
		// "#\" -> character literal
		a.iload(0);
		a.iconst('\\');
		a.branch(Opcode.IF_ICMPNE, notChar);
		advance(a);
		advance(a);
		a.invokestatic(this.readCharLit);
		a.areturn();
		a.bind(notChar);
		// "#(" -> rank-1 vector
		a.iload(0);
		a.iconst('(');
		a.branch(Opcode.IF_ICMPNE, notVec);
		advance(a);
		advance(a);
		a.iconst(1);
		a.invokestatic(this.readArrayN);
		a.areturn();
		a.bind(notVec);
		// "#S(" / "#s(" -> structure literal ("#S" without the paren is a symbol)
		a.iload(0);
		a.iconst('S');
		a.branch(Opcode.IF_ICMPEQ, structOpen);
		a.iload(0);
		a.iconst('s');
		a.branch(Opcode.IF_ICMPNE, notStructS);
		a.bind(structOpen);
		branchIfPosPlusGeLen(a, 2, atom);
		charAtPosPlus(a, 2);
		a.iconst('(');
		a.branch(Opcode.IF_ICMPNE, atom);
		advance(a);
		advance(a);
		advance(a);
		a.invokestatic(this.readStruct);
		a.areturn();
		a.bind(notStructS);
		// "#*" -> bit vector (a general vector of 0/1, like the frontend)
		a.iload(0);
		a.iconst('*');
		a.branch(Opcode.IF_ICMPNE, notBits);
		advance(a);
		advance(a);
		a.invokestatic(this.readBits);
		a.areturn();
		a.bind(notBits);
		// "#f(" / "#F(" -> packed single-float array
		a.iload(0);
		a.iconst('f');
		a.branch(Opcode.IF_ICMPEQ, singleOpen);
		a.iload(0);
		a.iconst('F');
		a.branch(Opcode.IF_ICMPNE, notSingle);
		a.bind(singleOpen);
		branchIfPosPlusGeLen(a, 2, atom);
		charAtPosPlus(a, 2);
		a.iconst('(');
		a.branch(Opcode.IF_ICMPNE, atom);
		advance(a);
		advance(a);
		advance(a);
		a.iconst(1);
		a.invokestatic(this.readPacked);
		a.areturn();
		a.bind(notSingle);
		// "#d(" / "#D(" -> packed double-float array
		a.iload(0);
		a.iconst('d');
		a.branch(Opcode.IF_ICMPEQ, doubleOpen);
		a.iload(0);
		a.iconst('D');
		a.branch(Opcode.IF_ICMPNE, notDouble);
		a.bind(doubleOpen);
		branchIfPosPlusGeLen(a, 2, atom);
		charAtPosPlus(a, 2);
		a.iconst('(');
		a.branch(Opcode.IF_ICMPNE, atom);
		advance(a);
		advance(a);
		advance(a);
		a.iconst(0);
		a.invokestatic(this.readPacked);
		a.areturn();
		a.bind(notDouble);
		// "#<digits>" -> #nA( array, #n=/#n# labels (signaled), or a symbol
		a.iload(0);
		a.iconst('0');
		a.branch(Opcode.IF_ICMPLT, notDigit);
		a.iload(0);
		a.iconst('9');
		a.branch(Opcode.IF_ICMPGT, notDigit);
		a.getstatic(this.readPos);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.istore(1); // probe
		a.iconst(0);
		a.istore(2); // rank
		a.bind(dloop);
		a.iload(1);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, dend);
		a.getstatic(this.readSrc);
		a.iload(1);
		a.invokevirtual(this.stringCharAt);
		a.istore(0);
		a.iload(0);
		a.iconst('0');
		a.branch(Opcode.IF_ICMPLT, dend);
		a.iload(0);
		a.iconst('9');
		a.branch(Opcode.IF_ICMPGT, dend);
		a.iload(2);
		a.iconst(10);
		a.op(Opcode.IMUL);
		a.iload(0);
		a.iconst('0');
		a.op(Opcode.ISUB);
		a.op(Opcode.IADD);
		a.istore(2);
		// A rank this large cannot denote a real array; stopping here keeps the int
		// accumulator from wrapping around into a small (wrong) rank.
		a.iload(2);
		a.iconst(20000);
		a.branch(Opcode.IF_ICMPGT, rankOverflow);
		a.iinc(1, 1);
		a.branch(Opcode.GOTO, dloop);
		a.bind(rankOverflow);
		sbNew(a, "Invalid array rank: ");
		a.getstatic(this.readSrc);
		a.getstatic(this.readPos);
		a.iload(1);
		a.invokevirtual(this.stringSubstring);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconstNull();
		a.areturn();
		a.bind(dend);
		a.iload(1);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, atom);
		a.getstatic(this.readSrc);
		a.iload(1);
		a.invokevirtual(this.stringCharAt);
		a.istore(0);
		a.iload(0);
		a.iconst('A');
		a.branch(Opcode.IF_ICMPEQ, arrOpen);
		a.iload(0);
		a.iconst('a');
		a.branch(Opcode.IF_ICMPNE, notArrA);
		a.bind(arrOpen);
		a.iload(1);
		a.iconst(1);
		a.op(Opcode.IADD);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, atom);
		a.getstatic(this.readSrc);
		a.iload(1);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringCharAt);
		a.iconst('(');
		a.branch(Opcode.IF_ICMPNE, atom);
		// pos = probe + 2 (past "A(")
		a.iload(1);
		a.iconst(2);
		a.op(Opcode.IADD);
		a.putstatic(this.readPos);
		a.iload(2);
		a.invokestatic(this.readArrayN);
		a.areturn();
		a.bind(notArrA);
		a.iload(0);
		a.iconst('=');
		a.branch(Opcode.IF_ICMPEQ, labelErr);
		a.iload(0);
		a.iconst('#');
		a.branch(Opcode.IF_ICMPNE, atom);
		a.bind(labelErr);
		err(a, "reader labels (#N=/#N#) are not supported by the compiled runtime reader");
		a.aconstNull();
		a.areturn();
		a.bind(notDigit);
		// "#x" / "#o" / "#b" -> radix integer
		a.iload(0);
		a.iconst('x');
		a.branch(Opcode.IF_ICMPEQ, radix16);
		a.iload(0);
		a.iconst('X');
		a.branch(Opcode.IF_ICMPNE, notX);
		a.bind(radix16);
		advance(a);
		advance(a);
		a.iconst(16);
		a.iload(0);
		a.invokestatic(this.readRadix);
		a.areturn();
		a.bind(notX);
		a.iload(0);
		a.iconst('o');
		a.branch(Opcode.IF_ICMPEQ, radix8);
		a.iload(0);
		a.iconst('O');
		a.branch(Opcode.IF_ICMPNE, notO);
		a.bind(radix8);
		advance(a);
		advance(a);
		a.iconst(8);
		a.iload(0);
		a.invokestatic(this.readRadix);
		a.areturn();
		a.bind(notO);
		a.iload(0);
		a.iconst('b');
		a.branch(Opcode.IF_ICMPEQ, radix2);
		a.iload(0);
		a.iconst('B');
		a.branch(Opcode.IF_ICMPNE, notB);
		a.bind(radix2);
		advance(a);
		advance(a);
		a.iconst(2);
		a.iload(0);
		a.invokestatic(this.readRadix);
		a.areturn();
		a.bind(notB);
		// "#." needs an evaluator at read time; a compiled artifact has none, so it is a
		// permanent limit that SIGNALS instead of misreading (the frontend evaluates it).
		a.iload(0);
		a.iconst('.');
		a.branch(Opcode.IF_ICMPNE, notDot);
		err(a, "#. read-time evaluation is not supported");
		a.aconstNull();
		a.areturn();
		a.bind(notDot);
		// "#+" / "#-" need the feature set at read time; same permanent limit.
		a.iload(0);
		a.iconst('+');
		a.branch(Opcode.IF_ICMPEQ, notFeature);
		a.iload(0);
		a.iconst('-');
		a.branch(Opcode.IF_ICMPNE, atom);
		a.bind(notFeature);
		err(a, "#+/#- feature conditionals are not supported by the compiled runtime reader");
		a.aconstNull();
		a.areturn();
		// fallthrough: the token reads as a symbol, like the frontend's readSymbol
		a.bind(atom);
		a.invokestatic(this.readAtom);
		a.areturn();
		return a.finish();
	}

	// _readCharLit: cursor just past "#\". The first character is taken literally; a
	// letter starts a name scan; a length-1 token is that character verbatim; a longer
	// token resolves through the (case-insensitive) frontend name table.
	private List<Integer> buildReadCharLit() {
		JvmAsm a = new JvmAsm();
		int ok = a.label();
		int box = a.label();
		int scan = a.label();
		int loop = a.label();
		int scanEnd = a.label();
		int named = a.label();
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPLT, ok);
		err(a, "Unexpected end of input after #\\");
		a.aconstNull();
		a.areturn();
		a.bind(ok);
		pos(a);
		a.istore(1); // start
		charAtPos(a);
		a.istore(0); // first
		advance(a);
		a.iload(0);
		a.invokestatic(this.charIsLetter);
		a.branch(Opcode.IFNE, scan);
		a.bind(box);
		a.iconst(1);
		a.newarrayInt();
		a.dup();
		a.iconst(0);
		a.iload(0);
		a.iastore();
		a.areturn();
		a.bind(scan);
		a.bind(loop);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, scanEnd);
		charAtPos(a);
		a.istore(2);
		a.iload(2);
		a.invokestatic(this.isWhitespace);
		a.branch(Opcode.IFNE, scanEnd);
		stopIfSlot(a, 2, '(', scanEnd);
		stopIfSlot(a, 2, ')', scanEnd);
		stopIfSlot(a, 2, '\'', scanEnd);
		stopIfSlot(a, 2, '"', scanEnd);
		stopIfSlot(a, 2, ';', scanEnd);
		stopIfSlot(a, 2, ',', scanEnd);
		stopIfSlot(a, 2, '`', scanEnd);
		advance(a);
		a.branch(Opcode.GOTO, loop);
		a.bind(scanEnd);
		a.getstatic(this.readSrc);
		a.iload(1);
		pos(a);
		a.invokevirtual(this.stringSubstring);
		a.astore(3); // token
		a.aload(3);
		a.invokevirtual(this.stringLength);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPNE, named);
		a.branch(Opcode.GOTO, box);
		a.bind(named);
		charName(a, "space", 32);
		charName(a, "newline", 10);
		charName(a, "linefeed", 10);
		charName(a, "lf", 10);
		charName(a, "tab", 9);
		charName(a, "return", 13);
		charName(a, "cr", 13);
		charName(a, "page", 12);
		charName(a, "backspace", 8);
		charName(a, "nul", 0);
		charName(a, "null", 0);
		charName(a, "rubout", 127);
		charName(a, "delete", 127);
		charName(a, "del", 127);
		charName(a, "escape", 27);
		charName(a, "altmode", 27);
		charName(a, "esc", 27);
		sbNew(a, "Unknown character name: #\\");
		a.aload(3);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconstNull();
		a.areturn();
		// the named branches jump back to `box` with the code point in slot 0
		return a.finish();
	}

	/** One case-insensitive named-character branch; on a match boxes {@code code}. */
	private void charName(JvmAsm a, String name, int code) {
		int next = a.label();
		a.aload(3);
		ldc(a, name);
		a.invokevirtual(this.stringEqualsIgnoreCase);
		a.branch(Opcode.IFEQ, next);
		a.iconst(1);
		a.newarrayInt();
		a.dup();
		a.iconst(0);
		a.iconst(code);
		a.iastore();
		a.areturn();
		a.bind(next);
	}

	private void stopIfSlot(JvmAsm a, int slot, char ch, int target) {
		a.iload(slot);
		a.iconst(ch);
		a.branch(Opcode.IF_ICMPEQ, target);
	}

	// _readRadix(radix, marker): cursor just past "#x"/"#o"/"#b". An optional leading
	// '-', then digits of the radix; bad digits (or a trailing symbol character) signal
	// the frontend's "Invalid digits after #x: ..." message. Values past 63 bits stay
	// BigInteger, matching the decimal classifier.
	private List<Integer> buildReadRadix() {
		JvmAsm a = new JvmAsm();
		int noNeg = a.label();
		int dloop = a.label();
		int dend = a.label();
		int hasDigits = a.label();
		int good = a.label();
		int noStrNeg = a.label();
		int bigRet = a.label();
		int errL = a.label();
		int useEnd = a.label();
		pos(a);
		a.istore(2); // start
		a.iconst(0);
		a.istore(3); // neg
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, noNeg);
		charAtPos(a);
		a.iconst('-');
		a.branch(Opcode.IF_ICMPNE, noNeg);
		a.iconst(1);
		a.istore(3);
		advance(a);
		a.bind(noNeg);
		pos(a);
		a.istore(4); // digitsStart
		a.bind(dloop);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, dend);
		charAtPos(a);
		a.iload(0);
		a.invokestatic(this.charDigit);
		a.branch(Opcode.IFLT, dend);
		advance(a);
		a.branch(Opcode.GOTO, dloop);
		a.bind(dend);
		pos(a);
		a.iload(4);
		a.branch(Opcode.IF_ICMPGT, hasDigits);
		a.branch(Opcode.GOTO, errL);
		a.bind(hasDigits);
		// a symbol character right after the digits invalidates the whole token
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, good);
		charAtPos(a);
		a.istore(6);
		a.iload(6);
		a.invokestatic(this.isWhitespace);
		a.branch(Opcode.IFNE, good);
		stopIfSlot(a, 6, '(', good);
		stopIfSlot(a, 6, ')', good);
		stopIfSlot(a, 6, '\'', good);
		stopIfSlot(a, 6, '"', good);
		stopIfSlot(a, 6, ';', good);
		stopIfSlot(a, 6, ',', good);
		stopIfSlot(a, 6, '`', good);
		a.branch(Opcode.GOTO, errL);
		a.bind(good);
		a.getstatic(this.readSrc);
		a.iload(4);
		pos(a);
		a.invokevirtual(this.stringSubstring);
		a.astore(5); // digits
		a.iload(3);
		a.branch(Opcode.IFEQ, noStrNeg);
		sbNew(a, "-");
		a.aload(5);
		a.invokevirtual(this.sbAppendStr);
		a.invokevirtual(this.sbToString);
		a.astore(5);
		a.bind(noStrNeg);
		a.anew(this.bigIntegerClass);
		a.dup();
		a.aload(5);
		a.iload(0);
		a.invokespecial(this.bigIntegerInitRadix);
		a.dup();
		a.invokevirtual(this.bigIntegerBitLength);
		a.iconst(64);
		a.branch(Opcode.IF_ICMPGE, bigRet);
		a.invokevirtual(this.bigIntegerLongValue);
		a.invokestatic(this.longValueOf);
		a.areturn();
		a.bind(bigRet);
		a.areturn();
		a.bind(errL);
		sbNew(a, "Invalid digits after #");
		a.iload(1);
		a.invokevirtual(this.sbAppendChar);
		sbText(a, ": ");
		// substring(start, min(pos + 1, len))
		pos(a);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.istore(6);
		a.iload(6);
		srcLen(a);
		a.branch(Opcode.IF_ICMPLE, useEnd);
		srcLen(a);
		a.istore(6);
		a.bind(useEnd);
		a.getstatic(this.readSrc);
		a.iload(2);
		a.iload(6);
		a.invokevirtual(this.stringSubstring);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconstNull();
		a.areturn();
		return a.finish();
	}

	// _readBits: cursor just past "#*"; consumes 0/1 characters into the general-array
	// runtime shape (an ArrayList with a {dims, nil, nil} header), like the frontend's
	// bit-vector lowering -- there is no packed bit representation.
	private List<Integer> buildReadBits() {
		JvmAsm a = new JvmAsm();
		int loop = a.label();
		int ok01 = a.label();
		int done = a.label();
		a.anew(this.arrayListClass);
		a.dup();
		a.invokespecial(this.alInit);
		a.astore(0);
		a.aload(0);
		a.aconstNull();
		a.invokevirtual(this.alAdd);
		a.pop();
		a.iconst(0);
		a.istore(1); // count
		a.bind(loop);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPGE, done);
		charAtPos(a);
		a.istore(2);
		a.iload(2);
		a.iconst('0');
		a.branch(Opcode.IF_ICMPEQ, ok01);
		a.iload(2);
		a.iconst('1');
		a.branch(Opcode.IF_ICMPNE, done);
		a.bind(ok01);
		a.aload(0);
		a.iload(2);
		a.iconst('0');
		a.op(Opcode.ISUB);
		a.op(Opcode.I2L);
		a.invokestatic(this.longValueOf);
		a.invokevirtual(this.alAdd);
		a.pop();
		a.iinc(1, 1);
		advance(a);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		// header = Object[]{ Object[]{Long(count)}, null, null } into slot 0 of the list
		a.aload(0);
		a.iconst(0);
		a.iconst(3);
		a.anewarray(this.objectClass);
		a.dup();
		a.iconst(0);
		a.iconst(1);
		a.anewarray(this.objectClass);
		a.dup();
		a.iconst(0);
		a.iload(1);
		a.op(Opcode.I2L);
		a.invokestatic(this.longValueOf);
		a.aastore();
		a.aastore();
		a.invokevirtual(this.alSet);
		a.pop();
		a.aload(0);
		a.areturn();
		return a.finish();
	}

	// _readArrayN(rank): cursor just past the opening '('; reads the grouped contents
	// as a list, computes/validates dims like the frontend, and builds the general
	// runtime array (ArrayList + {dims, nil, nil} header). #( is rank 1.
	private List<Integer> buildReadArrayN() {
		JvmAsm a = new JvmAsm();
		int okRank = a.label();
		a.iload(0);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPGE, okRank);
		sbNew(a, "#");
		a.iload(0);
		a.invokevirtual(this.sbAppendInt);
		sbText(a, "A: array rank must be >= 1");
		sbThrow(a);
		a.aconstNull();
		a.areturn();
		a.bind(okRank);
		a.invokestatic(this.readList);
		a.astore(1); // rows
		sbNew(a, "#");
		a.iload(0);
		a.invokevirtual(this.sbAppendInt);
		sbText(a, "A");
		a.invokevirtual(this.sbToString);
		a.astore(2); // label
		a.aload(1);
		a.iload(0);
		a.aload(2);
		a.invokestatic(this.rdDims);
		a.astore(3); // dims
		a.anew(this.arrayListClass);
		a.dup();
		a.invokespecial(this.alInit);
		a.astore(4); // out
		a.aload(4);
		a.iconst(3);
		a.anewarray(this.objectClass);
		a.dup();
		a.iconst(0);
		a.aload(3);
		a.aastore();
		a.invokevirtual(this.alAdd);
		a.pop();
		a.aload(1);
		a.iconst(0);
		a.aload(3);
		a.aload(4);
		a.aload(2);
		a.invokestatic(this.rdFlat);
		a.aload(4);
		a.areturn();
		return a.finish();
	}

	// _readPacked(single): cursor just past "#f(" / "#d("; rank is inferred from the
	// nesting depth, leaves are coerced to double (narrowed for #f), and the value is
	// the packed float[]/double[] with the [rank, dims..., elements...] header.
	private List<Integer> buildReadPacked() {
		JvmAsm a = new JvmAsm();
		int dlab = a.label();
		int lab = a.label();
		int dbl = a.label();
		int floop1 = a.label();
		int fdone1 = a.label();
		int floop2 = a.label();
		int fdone2 = a.label();
		int dloop1 = a.label();
		int ddone1 = a.label();
		int dloop2 = a.label();
		int ddone2 = a.label();
		a.iload(0);
		a.branch(Opcode.IFEQ, dlab);
		ldc(a, "#f");
		a.astore(2);
		a.branch(Opcode.GOTO, lab);
		a.bind(dlab);
		ldc(a, "#d");
		a.astore(2);
		a.bind(lab);
		a.invokestatic(this.readList);
		a.astore(1); // rows
		a.aload(1);
		a.invokestatic(this.rdInferRank);
		a.istore(3); // rank
		a.aload(1);
		a.iload(3);
		a.aload(2);
		a.invokestatic(this.rdDims);
		a.astore(4); // dims
		a.anew(this.arrayListClass);
		a.dup();
		a.invokespecial(this.alInit);
		a.astore(5); // out (data only)
		a.aload(1);
		a.iconst(0);
		a.aload(4);
		a.aload(5);
		a.aload(2);
		a.invokestatic(this.rdFlat);
		a.aload(5);
		a.invokevirtual(this.alSize);
		a.istore(6); // n
		a.iconst(1);
		a.iload(3);
		a.op(Opcode.IADD);
		a.istore(9); // base = 1 + rank
		a.iload(0);
		a.branch(Opcode.IFEQ, dbl);
		// single: float[base + n]
		a.iload(9);
		a.iload(6);
		a.op(Opcode.IADD);
		a.newarrayFloat();
		a.astore(8);
		a.aload(8);
		a.iconst(0);
		a.iload(3);
		a.i2f();
		a.fastore();
		a.iconst(0);
		a.istore(7);
		a.bind(floop1);
		a.iload(7);
		a.iload(3);
		a.branch(Opcode.IF_ICMPGE, fdone1);
		a.aload(8);
		a.iconst(1);
		a.iload(7);
		a.op(Opcode.IADD);
		a.aload(4);
		a.iload(7);
		a.aaload();
		a.checkcast(this.longClass);
		a.invokevirtual(this.longLongValue);
		a.op(Opcode.L2F);
		a.fastore();
		a.iinc(7, 1);
		a.branch(Opcode.GOTO, floop1);
		a.bind(fdone1);
		a.iconst(0);
		a.istore(7);
		a.bind(floop2);
		a.iload(7);
		a.iload(6);
		a.branch(Opcode.IF_ICMPGE, fdone2);
		a.aload(8);
		a.iload(9);
		a.iload(7);
		a.op(Opcode.IADD);
		a.aload(5);
		a.iload(7);
		a.invokevirtual(this.alGet);
		a.invokestatic(this.rdF);
		a.d2f();
		a.fastore();
		a.iinc(7, 1);
		a.branch(Opcode.GOTO, floop2);
		a.bind(fdone2);
		a.aload(8);
		a.areturn();
		a.bind(dbl);
		// double: double[base + n]
		a.iload(9);
		a.iload(6);
		a.op(Opcode.IADD);
		a.newarrayDouble();
		a.astore(8);
		a.aload(8);
		a.iconst(0);
		a.iload(3);
		a.i2d();
		a.dastore();
		a.iconst(0);
		a.istore(7);
		a.bind(dloop1);
		a.iload(7);
		a.iload(3);
		a.branch(Opcode.IF_ICMPGE, ddone1);
		a.aload(8);
		a.iconst(1);
		a.iload(7);
		a.op(Opcode.IADD);
		a.aload(4);
		a.iload(7);
		a.aaload();
		a.checkcast(this.longClass);
		a.invokevirtual(this.longLongValue);
		a.l2d();
		a.dastore();
		a.iinc(7, 1);
		a.branch(Opcode.GOTO, dloop1);
		a.bind(ddone1);
		a.iconst(0);
		a.istore(7);
		a.bind(dloop2);
		a.iload(7);
		a.iload(6);
		a.branch(Opcode.IF_ICMPGE, ddone2);
		a.aload(8);
		a.iload(9);
		a.iload(7);
		a.op(Opcode.IADD);
		a.aload(5);
		a.iload(7);
		a.invokevirtual(this.alGet);
		a.invokestatic(this.rdF);
		a.dastore();
		a.iinc(7, 1);
		a.branch(Opcode.GOTO, dloop2);
		a.bind(ddone2);
		a.aload(8);
		a.areturn();
		return a.finish();
	}

	// _readStruct: cursor just past "#S(". Parses the type name and the slot name/value
	// pairs, resolves the layout in the baked _rdStructs directory, applies the fold's
	// rules (leftmost repeated slot wins, an omitted slot takes its nil/baked-constant
	// initform or signals), and builds the Object[]{layout, v1..vn} instance -- the
	// exact shape %obj-new emits. Without the instance gate no defstruct exists, so any
	// #S(...) resolves to the "not a defined structure type" error.
	private List<Integer> buildReadStruct() {
		JvmAsm a = new JvmAsm();
		int ne1 = a.label();
		int ne2 = a.label();
		int goodName = a.label();
		int badName = a.label();
		int unq = a.label();
		int splitDone = a.label();
		int errNoType = a.label();
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPLT, ne1);
		err(a, "Unexpected end of input, expected ')'");
		a.aconstNull();
		a.areturn();
		a.bind(ne1);
		charAtPos(a);
		a.iconst(')');
		a.branch(Opcode.IF_ICMPNE, ne2);
		advance(a);
		err(a, "#S(): a structure literal needs a type name");
		a.aconstNull();
		a.areturn();
		a.bind(ne2);
		a.invokestatic(this.readExpr);
		a.astore(16); // nmObj
		a.aload(16);
		a.instanceOf(this.stringClass);
		a.branch(Opcode.IFEQ, badName);
		a.aload(16);
		a.checkcast(this.stringClass);
		ldc(a, "\"");
		a.invokevirtual(this.stringStartsWith);
		a.branch(Opcode.IFEQ, goodName);
		a.bind(badName);
		sbNew(a, "#S: expected a structure type name, got ");
		a.aload(16);
		a.invokestatic(this.lispToString);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconstNull();
		a.areturn();
		a.bind(goodName);
		a.aload(16);
		a.checkcast(this.stringClass);
		a.astore(0); // name
		a.aload(0);
		a.iconst(':');
		a.invokevirtual(this.stringIndexOf);
		a.istore(17); // ci
		a.iload(17);
		a.branch(Opcode.IFLT, unq);
		a.aload(0);
		a.iconst(0);
		a.iload(17);
		a.invokevirtual(this.stringSubstring);
		a.astore(2); // tp
		a.aload(0);
		a.aload(0);
		a.iconst(':');
		a.invokevirtual(this.stringLastIndexOf);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringSubstringFrom);
		a.astore(1); // tm
		a.branch(Opcode.GOTO, splitDone);
		a.bind(unq);
		a.aconstNull();
		a.astore(2);
		a.aload(0);
		a.astore(1);
		a.bind(splitDone);
		if (this.rdStructs == null) {
			a.branch(Opcode.GOTO, errNoType);
			a.bind(errNoType);
			emitNoTypeError(a, "");
			return a.finish();
		}
		int found = a.label();
		int errClassHint = a.label();
		// pass 1: exact struct match (a qualified spelling against its qualified entry,
		// or an unqualified spelling against an unqualified entry)
		int p1loop = a.label();
		int p1next = a.label();
		int p1end = a.label();
		int p1qual = a.label();
		a.iconst(0);
		a.istore(4);
		a.bind(p1loop);
		a.iload(4);
		a.getstatic(this.rdStructs);
		a.arraylength();
		a.branch(Opcode.IF_ICMPGE, p1end);
		a.getstatic(this.rdStructs);
		a.iload(4);
		a.aaload();
		a.astore(3); // entry
		a.aload(3);
		a.iconst(1);
		a.aaload();
		a.aload(1);
		a.invokevirtual(this.objectEquals);
		a.branch(Opcode.IFEQ, p1next);
		a.aload(3);
		a.iconst(2);
		a.aaload();
		a.branch(Opcode.IFNULL, p1next); // class entry
		a.aload(3);
		a.iconst(0);
		a.aaload();
		a.checkcast(this.stringClass);
		a.astore(16); // epkg
		a.aload(16);
		a.invokevirtual(this.stringLength);
		a.branch(Opcode.IFNE, p1qual);
		a.aload(2);
		a.branch(Opcode.IFNONNULL, p1next);
		a.branch(Opcode.GOTO, found);
		a.bind(p1qual);
		a.aload(2);
		a.branch(Opcode.IFNULL, p1next);
		a.aload(16);
		a.aload(2);
		a.invokevirtual(this.objectEquals);
		a.branch(Opcode.IFEQ, p1next);
		a.branch(Opcode.GOTO, found);
		a.bind(p1next);
		a.iinc(4, 1);
		a.branch(Opcode.GOTO, p1loop);
		a.bind(p1end);
		// pass 2: a qualified spelling falls back to an unqualified entry of the same
		// member name (findStructTag's splitQualified fallback)
		int passClass = a.label();
		int p2loop = a.label();
		int p2next = a.label();
		int p2end = a.label();
		a.aload(2);
		a.branch(Opcode.IFNULL, passClass);
		a.iconst(0);
		a.istore(4);
		a.bind(p2loop);
		a.iload(4);
		a.getstatic(this.rdStructs);
		a.arraylength();
		a.branch(Opcode.IF_ICMPGE, p2end);
		a.getstatic(this.rdStructs);
		a.iload(4);
		a.aaload();
		a.astore(3);
		a.aload(3);
		a.iconst(1);
		a.aaload();
		a.aload(1);
		a.invokevirtual(this.objectEquals);
		a.branch(Opcode.IFEQ, p2next);
		a.aload(3);
		a.iconst(2);
		a.aaload();
		a.branch(Opcode.IFNULL, p2next);
		a.aload(3);
		a.iconst(0);
		a.aaload();
		a.checkcast(this.stringClass);
		a.invokevirtual(this.stringLength);
		a.branch(Opcode.IFNE, p2next);
		a.branch(Opcode.GOTO, found);
		a.bind(p2next);
		a.iinc(4, 1);
		a.branch(Opcode.GOTO, p2loop);
		a.bind(p2end);
		a.bind(passClass);
		// pass 3: a class of that name exists -> the "#S reads defstruct types only"
		// hint, matching the fold's error
		int p3loop = a.label();
		int p3next = a.label();
		int p3end = a.label();
		a.iconst(0);
		a.istore(4);
		a.bind(p3loop);
		a.iload(4);
		a.getstatic(this.rdStructs);
		a.arraylength();
		a.branch(Opcode.IF_ICMPGE, p3end);
		a.getstatic(this.rdStructs);
		a.iload(4);
		a.aaload();
		a.astore(3);
		a.aload(3);
		a.iconst(1);
		a.aaload();
		a.aload(1);
		a.invokevirtual(this.objectEquals);
		a.branch(Opcode.IFEQ, p3next);
		a.aload(3);
		a.iconst(2);
		a.aaload();
		a.branch(Opcode.IFNONNULL, p3next);
		a.branch(Opcode.GOTO, errClassHint);
		a.bind(p3next);
		a.iinc(4, 1);
		a.branch(Opcode.GOTO, p3loop);
		a.bind(p3end);
		a.branch(Opcode.GOTO, errNoType);
		a.bind(found);
		a.aload(3);
		a.iconst(2);
		a.aaload();
		a.checkcast(this.stringArrayClass);
		a.astore(5); // layout
		a.aload(3);
		a.iconst(3);
		a.aaload();
		a.checkcast(this.stringArrayClass);
		a.astore(12); // initTexts
		a.aload(5);
		a.arraylength();
		a.iconst(3);
		a.op(Opcode.ISUB);
		a.istore(6); // slotCount
		a.iload(6);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.anewarray(this.objectClass);
		a.astore(7); // inst
		a.aload(7);
		a.iconst(0);
		a.aload(5);
		a.aastore();
		// fill every slot with the layout as an "unset" sentinel: nil values are null,
		// so unset needs a marker no read datum can be (identity to the layout array)
		int sloop = a.label();
		int sdone = a.label();
		a.iconst(0);
		a.istore(11);
		a.bind(sloop);
		a.iload(11);
		a.iload(6);
		a.branch(Opcode.IF_ICMPGE, sdone);
		a.aload(7);
		a.iconst(1);
		a.iload(11);
		a.op(Opcode.IADD);
		a.aload(5);
		a.aastore();
		a.iinc(11, 1);
		a.branch(Opcode.GOTO, sloop);
		a.bind(sdone);
		// slot name/value pairs
		int pairLoop = a.label();
		int pl1 = a.label();
		int pl2 = a.label();
		int badSlot = a.label();
		int goodSlot = a.label();
		int pv1 = a.label();
		int pv2 = a.label();
		int kloop = a.label();
		int knext = a.label();
		int kdone = a.label();
		int haveIdx = a.label();
		int fillDefaults = a.label();
		a.bind(pairLoop);
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPLT, pl1);
		err(a, "Unexpected end of input, expected ')'");
		a.aconstNull();
		a.areturn();
		a.bind(pl1);
		charAtPos(a);
		a.iconst(')');
		a.branch(Opcode.IF_ICMPNE, pl2);
		advance(a);
		a.branch(Opcode.GOTO, fillDefaults);
		a.bind(pl2);
		a.invokestatic(this.readExpr);
		a.astore(8); // snObj
		a.aload(8);
		a.instanceOf(this.stringClass);
		a.branch(Opcode.IFEQ, badSlot);
		a.aload(8);
		a.checkcast(this.stringClass);
		ldc(a, "\"");
		a.invokevirtual(this.stringStartsWith);
		a.branch(Opcode.IFEQ, goodSlot);
		a.bind(badSlot);
		sbNew(a, "#S(");
		a.aload(0);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " ...): expected a slot name, got ");
		a.aload(8);
		a.invokestatic(this.lispToString);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconstNull();
		a.areturn();
		a.bind(goodSlot);
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.branch(Opcode.IF_ICMPLT, pv1);
		err(a, "Unexpected end of input, expected ')'");
		a.aconstNull();
		a.areturn();
		a.bind(pv1);
		charAtPos(a);
		a.iconst(')');
		a.branch(Opcode.IF_ICMPNE, pv2);
		sbNew(a, "#S(");
		a.aload(0);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " ...): odd number of slot name/value items in a structure literal");
		sbThrow(a);
		a.aconstNull();
		a.areturn();
		a.bind(pv2);
		a.invokestatic(this.readExpr);
		a.astore(9); // value
		a.aload(8);
		a.checkcast(this.stringClass);
		a.invokestatic(this.rdName);
		a.astore(13); // base name
		a.iconst(-1);
		a.istore(10); // idx
		a.iconst(0);
		a.istore(11);
		a.bind(kloop);
		a.iload(11);
		a.iload(6);
		a.branch(Opcode.IF_ICMPGE, kdone);
		a.aload(5);
		a.iconst(3);
		a.iload(11);
		a.op(Opcode.IADD);
		a.aaload();
		a.aload(13);
		a.invokevirtual(this.objectEquals);
		a.branch(Opcode.IFEQ, knext);
		a.iload(11);
		a.istore(10);
		a.branch(Opcode.GOTO, kdone);
		a.bind(knext);
		a.iinc(11, 1);
		a.branch(Opcode.GOTO, kloop);
		a.bind(kdone);
		a.iload(10);
		a.branch(Opcode.IFGE, haveIdx);
		sbNew(a, "#S(");
		a.aload(0);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " ...): ");
		a.aload(5);
		a.iconst(1);
		a.aaload();
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " has no slot named ");
		a.aload(8);
		a.checkcast(this.stringClass);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconstNull();
		a.areturn();
		a.bind(haveIdx);
		// leftmost wins: store only while the slot still holds the sentinel
		a.aload(7);
		a.iconst(1);
		a.iload(10);
		a.op(Opcode.IADD);
		a.aaload();
		a.aload(5);
		a.branch(Opcode.IF_ACMPNE, pairLoop);
		a.aload(7);
		a.iconst(1);
		a.iload(10);
		a.op(Opcode.IADD);
		a.aload(9);
		a.aastore();
		a.branch(Opcode.GOTO, pairLoop);
		a.bind(fillDefaults);
		int floop = a.label();
		int fnext = a.label();
		int fdone = a.label();
		int setNil = a.label();
		int parseText = a.label();
		a.iconst(0);
		a.istore(11);
		a.bind(floop);
		a.iload(11);
		a.iload(6);
		a.branch(Opcode.IF_ICMPGE, fdone);
		a.aload(7);
		a.iconst(1);
		a.iload(11);
		a.op(Opcode.IADD);
		a.aaload();
		a.aload(5);
		a.branch(Opcode.IF_ACMPNE, fnext);
		a.aload(12);
		a.branch(Opcode.IFNULL, setNil);
		a.aload(12);
		a.iload(11);
		a.aaload();
		a.astore(13); // initform action
		a.aload(13);
		a.branch(Opcode.IFNULL, setNil);
		a.aload(13);
		a.iconst(0);
		a.invokevirtual(this.stringCharAt);
		a.iconst(EmittedReaderInitforms.SIGNAL_MARKER);
		a.branch(Opcode.IF_ICMPNE, parseText);
		a.aload(13);
		a.iconst(1);
		a.invokevirtual(this.stringSubstringFrom);
		a.invokestatic(this.rdErr);
		a.aconstNull();
		a.areturn();
		a.bind(parseText);
		// re-read the baked constant text in place (save/restore the reader state)
		a.getstatic(this.readSrc);
		a.astore(14);
		a.getstatic(this.readPos);
		a.istore(15);
		a.aload(13);
		a.putstatic(this.readSrc);
		a.iconst(0);
		a.putstatic(this.readPos);
		a.invokestatic(this.readExpr);
		a.astore(9);
		a.aload(14);
		a.putstatic(this.readSrc);
		a.iload(15);
		a.putstatic(this.readPos);
		a.aload(7);
		a.iconst(1);
		a.iload(11);
		a.op(Opcode.IADD);
		a.aload(9);
		a.aastore();
		a.branch(Opcode.GOTO, fnext);
		a.bind(setNil);
		a.aload(7);
		a.iconst(1);
		a.iload(11);
		a.op(Opcode.IADD);
		a.aconstNull();
		a.aastore();
		a.bind(fnext);
		a.iinc(11, 1);
		a.branch(Opcode.GOTO, floop);
		a.bind(fdone);
		a.aload(7);
		a.areturn();
		a.bind(errClassHint);
		emitNoTypeError(a, " (it names a class; #S reads defstruct types only)");
		a.bind(errNoType);
		emitNoTypeError(a, "");
		return a.finish();
	}

	/** Emits the "#S(NAME ...): NAME is not a defined structure type" throw. */
	private void emitNoTypeError(JvmAsm a, String suffix) {
		sbNew(a, "#S(");
		a.aload(0);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " ...): ");
		a.aload(0);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " is not a defined structure type" + suffix);
		sbThrow(a);
		a.aconstNull();
		a.areturn();
	}

	// _rdLen(list, label): proper-list length; an improper tail signals.
	private List<Integer> buildRdLen() {
		JvmAsm a = new JvmAsm();
		int loop = a.label();
		int done = a.label();
		int isCons = a.label();
		a.iconst(0);
		a.istore(2);
		a.aload(0);
		a.astore(3);
		a.bind(loop);
		a.aload(3);
		a.branch(Opcode.IFNULL, done);
		a.aload(3);
		a.invokestatic(this.rdConsp);
		a.branch(Opcode.IFNE, isCons);
		a.anew(this.stringBuilderClass);
		a.dup();
		a.aload(1);
		a.invokespecial(this.sbInitStr);
		sbText(a, ": contents must be proper lists");
		sbThrow(a);
		a.iconst(0);
		a.ireturn();
		a.bind(isCons);
		a.iinc(2, 1);
		a.aload(3);
		a.checkcast(this.objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(3);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.iload(2);
		a.ireturn();
		return a.finish();
	}

	// _rdConsp: the runtime cons test (an Object[] that is not a ratio, closure or
	// instance), mirroring the consp predicate's discriminators.
	private List<Integer> buildRdConsp() {
		JvmAsm a = new JvmAsm();
		int no = a.label();
		a.aload(0);
		a.instanceOf(this.objectArrayClass);
		a.branch(Opcode.IFEQ, no);
		a.aload(0);
		a.instanceOf(this.bigIntegerArrayClass);
		a.branch(Opcode.IFNE, no);
		a.aload(0);
		a.checkcast(this.objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.instanceOf(this.integerClass);
		a.branch(Opcode.IFNE, no);
		a.aload(0);
		a.checkcast(this.objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.instanceOf(this.stringArrayClass);
		a.branch(Opcode.IFNE, no);
		a.iconst(1);
		a.ireturn();
		a.bind(no);
		a.iconst(0);
		a.ireturn();
		return a.finish();
	}

	// _rdLevel(v, label): one nested level of array contents -- nil or a proper list;
	// anything else is the frontend's "expected a nested list" error.
	private List<Integer> buildRdLevel() {
		JvmAsm a = new JvmAsm();
		int nn = a.label();
		int bad = a.label();
		a.aload(0);
		a.branch(Opcode.IFNONNULL, nn);
		a.aconstNull();
		a.areturn();
		a.bind(nn);
		a.aload(0);
		a.invokestatic(this.rdConsp);
		a.branch(Opcode.IFEQ, bad);
		a.aload(0);
		a.areturn();
		a.bind(bad);
		a.anew(this.stringBuilderClass);
		a.dup();
		a.aload(1);
		a.invokespecial(this.sbInitStr);
		sbText(a, ": expected a nested list, got ");
		a.aload(0);
		a.invokestatic(this.lispToString);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconstNull();
		a.areturn();
		return a.finish();
	}

	// _rdDims(rows, rank, label): dimension sizes from the first-element chain, as the
	// Object[]-of-Long shape the array header stores.
	private List<Integer> buildRdDims() {
		JvmAsm a = new JvmAsm();
		int loop = a.label();
		int done = a.label();
		int lvlNull = a.label();
		int lvlSet = a.label();
		a.iload(1);
		a.anewarray(this.objectClass);
		a.astore(3);
		a.aload(3);
		a.iconst(0);
		a.aload(0);
		a.aload(2);
		a.invokestatic(this.rdLen);
		a.op(Opcode.I2L);
		a.invokestatic(this.longValueOf);
		a.aastore();
		a.aload(0);
		a.astore(5);
		a.iconst(1);
		a.istore(4);
		a.bind(loop);
		a.iload(4);
		a.iload(1);
		a.branch(Opcode.IF_ICMPGE, done);
		a.aload(5);
		a.branch(Opcode.IFNULL, lvlNull);
		a.aload(5);
		a.checkcast(this.objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.aload(2);
		a.invokestatic(this.rdLevel);
		a.astore(5);
		a.branch(Opcode.GOTO, lvlSet);
		a.bind(lvlNull);
		a.aconstNull();
		a.astore(5);
		a.bind(lvlSet);
		a.aload(3);
		a.iload(4);
		a.aload(5);
		a.aload(2);
		a.invokestatic(this.rdLen);
		a.op(Opcode.I2L);
		a.invokestatic(this.longValueOf);
		a.aastore();
		a.iinc(4, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.aload(3);
		a.areturn();
		return a.finish();
	}

	// _rdFlat(items, depth, dims, out, label): validates one level against dims and
	// appends the leaves to `out` in row-major order, recursing into nested levels.
	private List<Integer> buildRdFlat() {
		JvmAsm a = new JvmAsm();
		int okCount = a.label();
		int deeper = a.label();
		int aloop = a.label();
		int retv = a.label();
		int bloop = a.label();
		int retv2 = a.label();
		a.aload(0);
		a.aload(4);
		a.invokestatic(this.rdLen);
		a.istore(5);
		a.aload(2);
		a.iload(1);
		a.aaload();
		a.checkcast(this.longClass);
		a.invokevirtual(this.longLongValue);
		a.iload(5);
		a.op(Opcode.I2L);
		a.op(Opcode.LCMP);
		a.branch(Opcode.IFEQ, okCount);
		a.anew(this.stringBuilderClass);
		a.dup();
		a.aload(4);
		a.invokespecial(this.sbInitStr);
		sbText(a, ": ragged contents, expected ");
		a.aload(2);
		a.iload(1);
		a.aaload();
		a.checkcast(this.longClass);
		a.invokevirtual(this.longLongValue);
		a.invokevirtual(this.sbAppendLong);
		sbText(a, " elements, got ");
		a.iload(5);
		a.invokevirtual(this.sbAppendInt);
		sbThrow(a);
		a.op(Opcode.RETURN);
		a.bind(okCount);
		a.iload(1);
		a.aload(2);
		a.arraylength();
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.branch(Opcode.IF_ICMPNE, deeper);
		a.aload(0);
		a.astore(6);
		a.bind(aloop);
		a.aload(6);
		a.branch(Opcode.IFNULL, retv);
		a.aload(3);
		a.aload(6);
		a.checkcast(this.objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.invokevirtual(this.alAdd);
		a.pop();
		a.aload(6);
		a.checkcast(this.objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(6);
		a.branch(Opcode.GOTO, aloop);
		a.bind(retv);
		a.op(Opcode.RETURN);
		a.bind(deeper);
		a.aload(0);
		a.astore(6);
		a.bind(bloop);
		a.aload(6);
		a.branch(Opcode.IFNULL, retv2);
		a.aload(6);
		a.checkcast(this.objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.aload(4);
		a.invokestatic(this.rdLevel);
		a.iload(1);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.aload(2);
		a.aload(3);
		a.aload(4);
		a.invokestatic(this.rdFlat);
		a.aload(6);
		a.checkcast(this.objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(6);
		a.branch(Opcode.GOTO, bloop);
		a.bind(retv2);
		a.op(Opcode.RETURN);
		return a.finish();
	}

	// _rdErr(message): throw the reader error; a RuntimeException is what every emitted
	// runtime helper throws, so handler-case catches it as a simple-error.
	private List<Integer> buildRdErr() {
		JvmAsm a = new JvmAsm();
		a.anew(this.rtExClass);
		a.dup();
		a.aload(0);
		a.invokespecial(this.rtExInit);
		a.op(Opcode.ATHROW);
		return a.finish();
	}

	// _rdName(spelled): the package-stripped base name with the keyword marker dropped
	// (:X, X and PKG::X all name slot X), mirroring the fold's slotIndexOf.
	private List<Integer> buildRdName() {
		JvmAsm a = new JvmAsm();
		int n1 = a.label();
		int strip = a.label();
		int retn = a.label();
		a.aload(0);
		ldc(a, "#:");
		a.invokevirtual(this.stringStartsWith);
		a.branch(Opcode.IFEQ, n1);
		a.aload(0);
		a.iconst(2);
		a.invokevirtual(this.stringSubstringFrom);
		a.astore(0);
		a.branch(Opcode.GOTO, strip);
		a.bind(n1);
		a.aload(0);
		a.invokevirtual(this.stringLength);
		a.branch(Opcode.IFEQ, retn);
		a.aload(0);
		a.iconst(0);
		a.invokevirtual(this.stringCharAt);
		a.iconst(':');
		a.branch(Opcode.IF_ICMPNE, strip);
		a.aload(0);
		a.iconst(1);
		a.invokevirtual(this.stringSubstringFrom);
		a.astore(0);
		a.bind(strip);
		a.aload(0);
		a.iconst(':');
		a.invokevirtual(this.stringLastIndexOf);
		a.istore(1);
		a.iload(1);
		a.branch(Opcode.IFLT, retn);
		a.aload(0);
		a.iload(1);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(this.stringSubstringFrom);
		a.astore(0);
		a.bind(retn);
		a.aload(0);
		a.areturn();
		return a.finish();
	}

	// _rdF(leaf): coerce a packed-float leaf to double (integer, double, big integer or
	// ratio), or the frontend's "expected a number" error.
	private List<Integer> buildRdF() {
		JvmAsm a = new JvmAsm();
		int f1 = a.label();
		int f2 = a.label();
		int f3 = a.label();
		int f4 = a.label();
		a.aload(0);
		a.instanceOf(this.longClass);
		a.branch(Opcode.IFEQ, f1);
		a.aload(0);
		a.checkcast(this.longClass);
		a.invokevirtual(this.longLongValue);
		a.l2d();
		a.dreturn();
		a.bind(f1);
		a.aload(0);
		a.instanceOf(this.doubleClass);
		a.branch(Opcode.IFEQ, f2);
		a.aload(0);
		a.checkcast(this.doubleClass);
		a.invokevirtual(this.doubleDoubleValue);
		a.dreturn();
		a.bind(f2);
		a.aload(0);
		a.instanceOf(this.bigIntegerClass);
		a.branch(Opcode.IFEQ, f3);
		a.aload(0);
		a.checkcast(this.bigIntegerClass);
		a.invokevirtual(this.bigIntegerDoubleValue);
		a.dreturn();
		a.bind(f3);
		a.aload(0);
		a.instanceOf(this.bigIntegerArrayClass);
		a.branch(Opcode.IFEQ, f4);
		a.aload(0);
		a.checkcast(this.bigIntegerArrayClass);
		a.iconst(0);
		a.aaload();
		a.invokevirtual(this.bigIntegerDoubleValue);
		a.aload(0);
		a.checkcast(this.bigIntegerArrayClass);
		a.iconst(1);
		a.aaload();
		a.invokevirtual(this.bigIntegerDoubleValue);
		a.op(Opcode.DDIV);
		a.dreturn();
		a.bind(f4);
		sbNew(a, "packed float array: expected a number, got ");
		a.aload(0);
		a.invokestatic(this.lispToString);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.op(Opcode.DCONST_0);
		a.dreturn();
		return a.finish();
	}

	// _rdInferRank(rows): 1 + the depth of the first-element chain, numpy style,
	// mirroring the frontend's inferFloatArrayRank.
	private List<Integer> buildRdInferRank() {
		JvmAsm a = new JvmAsm();
		int nn = a.label();
		int loop = a.label();
		int notNil = a.label();
		int done = a.label();
		a.aload(0);
		a.branch(Opcode.IFNONNULL, nn);
		a.iconst(1);
		a.ireturn();
		a.bind(nn);
		a.aload(0);
		a.checkcast(this.objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.astore(1); // probe
		a.iconst(1);
		a.istore(2); // rank
		a.bind(loop);
		a.aload(1);
		a.branch(Opcode.IFNONNULL, notNil);
		a.iinc(2, 1);
		a.branch(Opcode.GOTO, done);
		a.bind(notNil);
		a.aload(1);
		a.invokestatic(this.rdConsp);
		a.branch(Opcode.IFEQ, done);
		a.iinc(2, 1);
		a.aload(1);
		a.checkcast(this.objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.astore(1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.iload(2);
		a.ireturn();
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
