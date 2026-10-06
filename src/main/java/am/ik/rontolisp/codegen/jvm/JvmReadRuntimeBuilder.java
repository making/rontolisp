package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.EmittedReaderInitforms;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.ReadFailure;
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
	record ReadMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	/**
	 * The static field holding the runtime struct-layout directory for {@code #S(...)}:
	 * one {@code Object[]} entry per registered layout, {@code {String pkg, String
	 * member, String[] layout, String[] initTexts}} -- {@code layout}/{@code initTexts}
	 * are null for a CLOS class entry (present only so the "it names a class" hint can
	 * fire). Emitted only when the reader is present AND the program may hold instances.
	 */
	static final String STRUCT_TABLE_FIELD = "_rdStructs";

	/**
	 * The static int field recording how the last {@code _readFromString} parse ended
	 * ({@link ReadFailure}): 0 with a datum; bit 0 set when the text ran out before a
	 * datum was complete (or held none); an error otherwise -- a {@code )} that closed
	 * nothing, a {@code .} with nothing before it in its list, a dotted tail followed by
	 * more than one object -- which also ends the parse by moving {@code _readPos} to the
	 * end. The readers never throw for any of them: a {@code read-from-string} call reads
	 * the field after the parse ({@code %read-failure}) and signals the typed condition,
	 * and {@code _load} throws.
	 */
	static final String FAIL_FIELD = "_readFail";

	static final String STRUCT_TABLE_DESC = "[[Ljava/lang/Object;";

	private final ConstantPool cp;

	private final ClassEntry thisClass;

	private final ClassEntry objectClass;

	private final ClassEntry objectArrayClass;

	private final ClassEntry stringClass;

	private final MethodRefEntry longValueOf;

	private final MethodRefEntry doubleValueOf;

	private final MethodRefEntry stringCharAt;

	private final MethodRefEntry stringLength;

	private final MethodRefEntry stringSubstring;

	private final MethodRefEntry objectEquals;

	private final boolean emitLoad;

	// CP entries created internally
	private final FieldRefEntry readSrc;

	private final FieldRefEntry readPos;

	private final FieldRefEntry readFail;

	private final MethodRefEntry isWhitespace;

	private final MethodRefEntry doubleParse;

	private final MethodRefEntry stringReplace;

	private final ClassEntry bigIntegerClass;

	private final MethodRefEntry bigIntegerInit;

	private final MethodRefEntry bigIntegerBitLength;

	private final MethodRefEntry bigIntegerLongValue;

	private final ClassEntry stringBuilderClass;

	private final MethodRefEntry sbInitStr;

	private final MethodRefEntry sbAppendChar;

	private final MethodRefEntry sbToString;

	private final MethodRefEntry readSkipWs;

	private final MethodRefEntry readExpr;

	private final MethodRefEntry readList;

	private final MethodRefEntry readAtom;

	private final MethodRefEntry readStr;

	private final MethodRefEntry classify;

	// === # dispatch (the frontend lexer's dispatch set, mirrored) ===

	private final MethodRefEntry readHash;

	private final MethodRefEntry readCharLit;

	private final MethodRefEntry readRadix;

	private final MethodRefEntry readBits;

	private final MethodRefEntry readArrayN;

	private final MethodRefEntry readPacked;

	// The _fv* tier's bfloat16 narrowing (JvmFloatArrayRuntimeBuilder); the reader
	// forces that tier on (usesRead -> usesFloatArray), so it is always emitted here.
	private final MethodRefEntry bf16Bits;

	private final MethodRefEntry readStruct;

	private final MethodRefEntry rdLen;

	private final MethodRefEntry rdConsp;

	private final MethodRefEntry rdLevel;

	private final MethodRefEntry rdDims;

	private final MethodRefEntry rdFlat;

	private final MethodRefEntry rdErr;

	private final MethodRefEntry rdName;

	private final MethodRefEntry rdF;

	private final MethodRefEntry rdInferRank;

	private final MethodRefEntry lispToString;

	private final MethodRefEntry ratMethod;

	private final ClassEntry intArrayClass;

	private final ClassEntry bigIntegerArrayClass;

	private final ClassEntry stringArrayClass;

	private final ClassEntry integerClass;

	private final ClassEntry longClass;

	private final ClassEntry doubleClass;

	private final ClassEntry arrayListClass;

	private final ClassEntry rtExClass;

	private final MethodRefEntry rtExInit;

	private final MethodRefEntry alInit;

	private final MethodRefEntry alAdd;

	private final MethodRefEntry alSet;

	private final MethodRefEntry alSize;

	private final MethodRefEntry alGet;

	private final MethodRefEntry charIsLetter;

	private final MethodRefEntry charDigit;

	private final MethodRefEntry charToString;

	private final MethodRefEntry bigIntegerInitRadix;

	private final MethodRefEntry bigIntegerSignum;

	private final MethodRefEntry bigIntegerToString;

	private final MethodRefEntry bigIntegerDoubleValue;

	private final MethodRefEntry longLongValue;

	private final MethodRefEntry doubleDoubleValue;

	private final MethodRefEntry sbInitEmpty;

	private final MethodRefEntry sbAppendStr;

	private final MethodRefEntry sbAppendInt;

	private final MethodRefEntry sbAppendLong;

	private final MethodRefEntry stringEqualsIgnoreCase;

	private final MethodRefEntry stringIndexOf;

	private final MethodRefEntry stringLastIndexOf;

	private final MethodRefEntry stringSubstringFrom;

	private final MethodRefEntry stringStartsWith;

	private final @Nullable FieldRefEntry rdStructs;

	// _classify upcases an atom token like CL's :upcase readtable case (the uppercase
	// name is canonical -- there is no fold). See .kb/reader-case-upcase.md.
	private final MethodRefEntry stringToUpperCase;

	private final FieldRefEntry localeRoot;

	private final @Nullable MethodRefEntry evalRef;

	private final @Nullable MethodRefEntry pathsGet;

	private final @Nullable MethodRefEntry filesReadString;

	/**
	 * The interned {@code LispLayout.PATHNAME} layout field, present exactly when the
	 * instance machinery is (the {@code #P} arm builds {@code Object[]{layout, ns}} from
	 * it); null with the gate off, where the arm signals instead.
	 */
	private final @Nullable FieldRefEntry pathnameLayout;

	private JvmReadRuntimeBuilder(ConstantPool cp, ClassEntry thisClass, ClassEntry objectClass,
			ClassEntry objectArrayClass, ClassEntry stringClass, MethodRefEntry longValueOf,
			MethodRefEntry doubleValueOf, MethodRefEntry stringCharAt, MethodRefEntry stringLength,
			MethodRefEntry stringSubstring, MethodRefEntry objectEquals, boolean emitLoad, boolean instances,
			@Nullable FieldRefEntry pathnameLayout) {
		this.pathnameLayout = pathnameLayout;
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
		this.emitLoad = emitLoad;

		this.readSrc = cp.fieldRef(thisClass, "_readSrc", "Ljava/lang/String;");
		this.readPos = cp.fieldRef(thisClass, "_readPos", "I");
		this.readFail = cp.fieldRef(thisClass, FAIL_FIELD, "I");

		ClassEntry characterClass = cp.classEntry("java/lang/Character");
		this.isWhitespace = cp.methodRef(characterClass, "isWhitespace", "(C)Z");
		ClassEntry doubleClass = cp.classEntry("java/lang/Double");
		this.doubleParse = cp.methodRef(doubleClass, "parseDouble", "(Ljava/lang/String;)D");
		this.stringReplace = cp.methodRef(stringClass, "replace",
				"(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;");
		this.bigIntegerClass = cp.classEntry("java/math/BigInteger");
		this.bigIntegerInit = cp.methodRef(this.bigIntegerClass, "<init>", "(Ljava/lang/String;)V");
		this.bigIntegerBitLength = cp.methodRef(this.bigIntegerClass, "bitLength", "()I");
		this.bigIntegerLongValue = cp.methodRef(this.bigIntegerClass, "longValue", "()J");
		this.stringBuilderClass = cp.classEntry("java/lang/StringBuilder");
		this.sbInitStr = cp.methodRef(this.stringBuilderClass, "<init>", "(Ljava/lang/String;)V");
		this.sbAppendChar = cp.methodRef(this.stringBuilderClass, "append", "(C)Ljava/lang/StringBuilder;");
		this.sbToString = cp.methodRef(this.stringBuilderClass, "toString", "()Ljava/lang/String;");

		this.readSkipWs = methodref("_readSkipWs", "()V");
		this.readExpr = methodref("_readExpr", "()Ljava/lang/Object;");
		this.readList = methodref("_readList", "()Ljava/lang/Object;");
		this.readAtom = methodref("_readAtom", "()Ljava/lang/Object;");
		this.readStr = methodref("_readStr", "()Ljava/lang/Object;");
		this.classify = methodref("_classify", "(Ljava/lang/String;)Ljava/lang/Object;");
		ClassEntry localeClass = cp.classEntry("java/util/Locale");
		this.localeRoot = cp.fieldRef(localeClass, "ROOT", "Ljava/util/Locale;");
		this.stringToUpperCase = cp.methodRef(stringClass, "toUpperCase", "(Ljava/util/Locale;)Ljava/lang/String;");

		this.readHash = methodref("_readHash", "()Ljava/lang/Object;");
		this.readCharLit = methodref("_readCharLit", "()Ljava/lang/Object;");
		this.readRadix = methodref("_readRadix", "(II)Ljava/lang/Object;");
		this.readBits = methodref("_readBits", "()Ljava/lang/Object;");
		this.readArrayN = methodref("_readArrayN", "(I)Ljava/lang/Object;");
		this.readPacked = methodref("_readPacked", "(I)Ljava/lang/Object;");
		this.bf16Bits = methodref(JvmFloatArrayRuntimeBuilder.BF16_BITS, JvmFloatArrayRuntimeBuilder.BF16_BITS_DESC);
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
		this.intArrayClass = cp.classEntry("[I");
		this.bigIntegerArrayClass = cp.classEntry("[Ljava/math/BigInteger;");
		this.stringArrayClass = cp.classEntry("[Ljava/lang/String;");
		this.integerClass = cp.classEntry("java/lang/Integer");
		this.longClass = cp.classEntry("java/lang/Long");
		this.doubleClass = cp.classEntry("java/lang/Double");
		this.arrayListClass = cp.classEntry("java/util/ArrayList");
		this.rtExClass = cp.classEntry("java/lang/RuntimeException");
		this.rtExInit = cp.methodRef(this.rtExClass, "<init>", "(Ljava/lang/String;)V");
		this.alInit = cp.methodRef(this.arrayListClass, "<init>", "()V");
		this.alAdd = cp.methodRef(this.arrayListClass, "add", "(Ljava/lang/Object;)Z");
		this.alSet = cp.methodRef(this.arrayListClass, "set", "(ILjava/lang/Object;)Ljava/lang/Object;");
		this.alSize = cp.methodRef(this.arrayListClass, "size", "()I");
		this.alGet = cp.methodRef(this.arrayListClass, "get", "(I)Ljava/lang/Object;");
		ClassEntry characterCls = cp.classEntry("java/lang/Character");
		this.charIsLetter = cp.methodRef(characterCls, "isLetter", "(C)Z");
		this.charDigit = cp.methodRef(characterCls, "digit", "(II)I");
		this.charToString = cp.methodRef(characterCls, "toString", "(I)Ljava/lang/String;");
		this.bigIntegerInitRadix = cp.methodRef(this.bigIntegerClass, "<init>", "(Ljava/lang/String;I)V");
		this.bigIntegerSignum = cp.methodRef(this.bigIntegerClass, "signum", "()I");
		this.bigIntegerToString = cp.methodRef(this.bigIntegerClass, "toString", "()Ljava/lang/String;");
		this.bigIntegerDoubleValue = cp.methodRef(this.bigIntegerClass, "doubleValue", "()D");
		this.longLongValue = cp.methodRef(this.longClass, "longValue", "()J");
		this.doubleDoubleValue = cp.methodRef(this.doubleClass, "doubleValue", "()D");
		this.sbInitEmpty = cp.methodRef(this.stringBuilderClass, "<init>", "()V");
		this.sbAppendStr = cp.methodRef(this.stringBuilderClass, "append",
				"(Ljava/lang/String;)Ljava/lang/StringBuilder;");
		this.sbAppendInt = cp.methodRef(this.stringBuilderClass, "append", "(I)Ljava/lang/StringBuilder;");
		this.sbAppendLong = cp.methodRef(this.stringBuilderClass, "append", "(J)Ljava/lang/StringBuilder;");
		this.stringEqualsIgnoreCase = cp.methodRef(stringClass, "equalsIgnoreCase", "(Ljava/lang/String;)Z");
		this.stringIndexOf = cp.methodRef(stringClass, "indexOf", "(I)I");
		this.stringLastIndexOf = cp.methodRef(stringClass, "lastIndexOf", "(I)I");
		this.stringSubstringFrom = cp.methodRef(stringClass, "substring", "(I)Ljava/lang/String;");
		this.stringStartsWith = cp.methodRef(stringClass, "startsWith", "(Ljava/lang/String;)Z");
		this.rdStructs = instances ? cp.fieldRef(thisClass, STRUCT_TABLE_FIELD, STRUCT_TABLE_DESC) : null;

		if (emitLoad) {
			this.evalRef = methodref("_eval", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
			ClassEntry pathsClass = cp.classEntry("java/nio/file/Paths");
			this.pathsGet = cp.methodRef(pathsClass, "get",
					"(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;");
			ClassEntry filesClass = cp.classEntry("java/nio/file/Files");
			this.filesReadString = cp.methodRef(filesClass, "readString", "(Ljava/nio/file/Path;)Ljava/lang/String;");
		}
		else {
			this.evalRef = null;
			this.pathsGet = null;
			this.filesReadString = null;
		}
	}

	static JvmReadRuntimeBuilder create(ConstantPool cp, ClassEntry thisClass, ClassEntry objectClass,
			ClassEntry objectArrayClass, ClassEntry stringClass, MethodRefEntry longValueOf,
			MethodRefEntry doubleValueOf, MethodRefEntry stringCharAt, MethodRefEntry stringLength,
			MethodRefEntry stringSubstring, MethodRefEntry objectEquals, boolean emitLoad, boolean instances,
			@Nullable FieldRefEntry pathnameLayout) {
		return new JvmReadRuntimeBuilder(cp, thisClass, objectClass, objectArrayClass, stringClass, longValueOf,
				doubleValueOf, stringCharAt, stringLength, stringSubstring, objectEquals, emitLoad, instances,
				pathnameLayout);
	}

	private MethodRefEntry methodref(String name, String desc) {
		return this.cp.methodRef(this.thisClass, name, desc);
	}

	/**
	 * Builds the {@code <clinit>} chunk filling {@link #STRUCT_TABLE_FIELD}: one
	 * {@code Object[]{pkg, member, layout, initTexts}} entry per registered layout, in
	 * registration order (classes carry null layout/initTexts -- they exist only for the
	 * "it names a class" hint). The struct layouts must already be interned in the layout
	 * pool.
	 * @param cp the constant pool (still open -- this mints CONSTANT_String entries)
	 * @param thisClass the generated class
	 * @param pool the layout pool holding the interned struct layout fields
	 * @param registry the layout registry
	 * @param objectClass {@code java/lang/Object}
	 * @param objectArrayClass {@code [Ljava/lang/Object;}
	 * @param stringClass {@code java/lang/String}
	 * @return the code chunk (no trailing RETURN)
	 */
	static MethodCode structTableClinit(ConstantPool cp, ClassEntry thisClass, JvmLispCompiler.LayoutPool pool,
			ClosRegistry registry, ClassEntry objectClass, ClassEntry objectArrayClass, ClassEntry stringClass) {
		FieldRefEntry field = cp.fieldRef(thisClass, STRUCT_TABLE_FIELD, STRUCT_TABLE_DESC);
		// The PATHNAME layout is not a #S-readable type (its literal syntax is #P,
		// which resolves through the fixed layout, never through this directory) --
		// and its %PATHNAME tag carries neither name prefix, so it must not be
		// shoehorned into the pkg/member split below.
		List<LispLayout> layouts = new ArrayList<>();
		for (LispLayout layout : registry.layouts().values()) {
			if (layout.kind() != LispLayout.Kind.PATHNAME) {
				layouts.add(layout);
			}
		}
		MethodCode a = new MethodCode();
		a.loadConstant(layouts.size());
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
			a.loadConstant(i);
			a.loadConstant(4);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.ldc(cp.stringEntry(pkg));
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.ldc(cp.stringEntry(member));
			a.aastore();
			if (isStruct) {
				FieldRefEntry layoutField = layoutFieldFor(pool, layout.tag());
				a.dup();
				a.loadConstant(2);
				a.getstatic(layoutField);
				a.aastore();
				@Nullable String[] inits = EmittedReaderInitforms.initTexts(layout, false);
				a.dup();
				a.loadConstant(3);
				a.loadConstant(inits.length);
				a.anewarray(stringClass);
				for (int k = 0; k < inits.length; k++) {
					if (inits[k] != null) {
						a.dup();
						a.loadConstant(k);
						a.ldc(cp.stringEntry(inits[k]));
						a.aastore();
					}
				}
				a.aastore();
			}
			a.aastore();
		}
		a.putstatic(field);
		return a;
	}

	private static FieldRefEntry layoutFieldFor(JvmLispCompiler.LayoutPool pool, String tag) {
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
		ms.add(new ReadMethod(this.cp.utf8Entry("_readSkipWs"), this.cp.utf8Entry("()V"), buildSkipWs()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readExpr"), this.cp.utf8Entry("()Ljava/lang/Object;"),
				buildReadExpr()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readList"), this.cp.utf8Entry("()Ljava/lang/Object;"),
				buildReadList()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readAtom"), this.cp.utf8Entry("()Ljava/lang/Object;"),
				buildReadAtom()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readStr"), this.cp.utf8Entry("()Ljava/lang/Object;"),
				buildReadStr()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_classify"),
				this.cp.utf8Entry("(Ljava/lang/String;)Ljava/lang/Object;"), buildClassify()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readFromString"),
				this.cp.utf8Entry("(Ljava/lang/Object;)Ljava/lang/Object;"), buildReadFromString()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readHash"), this.cp.utf8Entry("()Ljava/lang/Object;"),
				buildReadHash()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readCharLit"), this.cp.utf8Entry("()Ljava/lang/Object;"),
				buildReadCharLit()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readRadix"), this.cp.utf8Entry("(II)Ljava/lang/Object;"),
				buildReadRadix()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readBits"), this.cp.utf8Entry("()Ljava/lang/Object;"),
				buildReadBits()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readArrayN"), this.cp.utf8Entry("(I)Ljava/lang/Object;"),
				buildReadArrayN()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readPacked"), this.cp.utf8Entry("(I)Ljava/lang/Object;"),
				buildReadPacked()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_readStruct"), this.cp.utf8Entry("()Ljava/lang/Object;"),
				buildReadStruct()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_rdLen"), this.cp.utf8Entry("(Ljava/lang/Object;Ljava/lang/String;)I"),
				buildRdLen()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_rdConsp"), this.cp.utf8Entry("(Ljava/lang/Object;)Z"),
				buildRdConsp()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_rdLevel"),
				this.cp.utf8Entry("(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"), buildRdLevel()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_rdDims"),
				this.cp.utf8Entry("(Ljava/lang/Object;ILjava/lang/String;)[Ljava/lang/Object;"), buildRdDims()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_rdFlat"),
				this.cp.utf8Entry("(Ljava/lang/Object;I[Ljava/lang/Object;Ljava/util/ArrayList;Ljava/lang/String;)V"),
				buildRdFlat()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_rdErr"), this.cp.utf8Entry("(Ljava/lang/String;)V"), buildRdErr()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_rdName"), this.cp.utf8Entry("(Ljava/lang/String;)Ljava/lang/String;"),
				buildRdName()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_rdF"), this.cp.utf8Entry("(Ljava/lang/Object;)D"), buildRdF()));
		ms.add(new ReadMethod(this.cp.utf8Entry("_rdInferRank"), this.cp.utf8Entry("(Ljava/lang/Object;)I"),
				buildRdInferRank()));
		if (this.emitLoad) {
			ms.add(new ReadMethod(this.cp.utf8Entry("_load"),
					this.cp.utf8Entry("(Ljava/lang/Object;)Ljava/lang/Object;"), buildLoad()));
		}
		return ms;
	}

	// === per-method bodies ===

	private void ldc(MethodCode a, String value) {
		a.ldc(this.cp.stringEntry(value));
	}

	/** Pushes {@code _readPos}. */
	private void pos(MethodCode a) {
		a.getstatic(this.readPos);
	}

	/** Pushes {@code _readSrc.length()}. */
	private void srcLen(MethodCode a) {
		a.getstatic(this.readSrc);
		a.invokevirtual(this.stringLength);
	}

	/** Pushes {@code _readSrc.charAt(_readPos)}. */
	private void charAtPos(MethodCode a) {
		a.getstatic(this.readSrc);
		a.getstatic(this.readPos);
		a.invokevirtual(this.stringCharAt);
	}

	/** Emits {@code _readPos += 1}. */
	private void advance(MethodCode a) {
		a.getstatic(this.readPos);
		a.loadConstant(1);
		a.iadd();
		a.putstatic(this.readPos);
	}

	/**
	 * Emits {@code _readFail |= 1}: the text ran out where a datum (or the rest of one)
	 * was due. An OR, so a stray {@code )} recorded first stays visible.
	 */
	private void markEof(MethodCode a) {
		a.getstatic(this.readFail);
		a.loadConstant(1);
		a.ior();
		a.putstatic(this.readFail);
	}

	/**
	 * Emits the record of an error: {@code _readFail = failure}, and the cursor moved to
	 * the end so every enclosing reader unwinds through its end-of-input path.
	 */
	private void recordError(MethodCode a, int failure) {
		a.loadConstant(failure);
		a.putstatic(this.readFail);
		srcLen(a);
		a.putstatic(this.readPos);
	}

	private MethodCode buildSkipWs() {
		MethodCode a = new MethodCode();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label notWs = a.newLabel();
		MethodCode.Label cloop = a.newLabel();
		MethodCode.Label notSemi = a.newLabel();
		MethodCode.Label bloop = a.newLabel();
		MethodCode.Label bNotClose = a.newLabel();
		MethodCode.Label bNotOpen = a.newLabel();
		MethodCode.Label bPlain = a.newLabel();
		MethodCode.Label end = a.newLabel();
		a.labelBinding(loop);
		// if pos >= len return
		pos(a);
		srcLen(a);
		a.if_icmpge(end);
		// c = charAt(pos)
		charAtPos(a);
		a.istore(0);
		a.iload(0);
		a.invokestatic(this.isWhitespace);
		a.ifeq(notWs);
		// whitespace: pos++
		advance(a);
		a.goto_(loop);
		// not whitespace: comment?
		a.labelBinding(notWs);
		a.iload(0);
		a.loadConstant(';');
		a.if_icmpne(notSemi);
		// comment: skip to end of line
		a.labelBinding(cloop);
		pos(a);
		srcLen(a);
		a.if_icmpge(end);
		charAtPos(a);
		a.loadConstant('\n');
		a.if_icmpeq(loop); // newline consumed as whitespace next iteration
		advance(a);
		a.goto_(cloop);
		a.labelBinding(notSemi);
		// "#|" block comment, honoring nesting like the frontend lexer
		a.iload(0);
		a.loadConstant('#');
		a.if_icmpne(end);
		pos(a);
		a.loadConstant(1);
		a.iadd();
		srcLen(a);
		a.if_icmpge(end);
		a.getstatic(this.readSrc);
		pos(a);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('|');
		a.if_icmpne(end);
		advance(a); // consume '#'
		advance(a); // consume '|'
		a.loadConstant(1);
		a.istore(1); // depth
		a.labelBinding(bloop);
		pos(a);
		srcLen(a);
		a.if_icmplt(bNotClose);
		// input exhausted inside the comment: unterminated
		markEof(a);
		a.return_();
		a.labelBinding(bNotClose);
		// "|#" -> depth--, back to whitespace skipping at 0
		charAtPos(a);
		a.loadConstant('|');
		a.if_icmpne(bNotOpen);
		pos(a);
		a.loadConstant(1);
		a.iadd();
		srcLen(a);
		a.if_icmpge(bNotOpen);
		a.getstatic(this.readSrc);
		pos(a);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('#');
		a.if_icmpne(bNotOpen);
		advance(a); // consume '|'
		advance(a); // consume '#'
		a.iinc(1, -1);
		a.iload(1);
		a.ifeq(loop); // depth 0: back to whitespace skipping
		a.goto_(bloop);
		a.labelBinding(bNotOpen);
		// "#|" -> depth++
		charAtPos(a);
		a.loadConstant('#');
		a.if_icmpne(bPlain);
		pos(a);
		a.loadConstant(1);
		a.iadd();
		srcLen(a);
		a.if_icmpge(bPlain);
		a.getstatic(this.readSrc);
		pos(a);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('|');
		a.if_icmpne(bPlain);
		advance(a); // consume '#'
		advance(a); // consume '|'
		a.iinc(1, 1);
		a.goto_(bloop);
		a.labelBinding(bPlain);
		advance(a);
		a.goto_(bloop);
		a.labelBinding(end);
		a.return_();
		return a;
	}

	private MethodCode buildReadExpr() {
		MethodCode a = new MethodCode();
		MethodCode.Label retNull = a.newLabel();
		MethodCode.Label notLp = a.newLabel();
		MethodCode.Label notQuote = a.newLabel();
		MethodCode.Label notSharp = a.newLabel();
		MethodCode.Label notStr = a.newLabel();
		MethodCode.Label atom = a.newLabel();
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.if_icmpge(retNull);
		charAtPos(a);
		a.istore(0); // c
		// '('
		a.iload(0);
		a.loadConstant('(');
		a.if_icmpne(notLp);
		advance(a);
		a.invokestatic(this.readList);
		a.areturn();
		a.labelBinding(notLp);
		// '\''
		a.iload(0);
		a.loadConstant('\'');
		a.if_icmpne(notQuote);
		advance(a);
		a.invokestatic(this.readExpr);
		a.astore(1); // inner
		wrapWithSymbol(a, LispNames.QUOTE, 1);
		a.areturn();
		a.labelBinding(notQuote);
		// '#' -> the dispatch mirror of the frontend lexer (chars, vectors, arrays,
		// structs, radix ints, packed floats, bit vectors); a token no dispatch claims
		// falls back to the atom path inside _readHash, like the frontend's readSymbol.
		a.iload(0);
		a.loadConstant('#');
		a.if_icmpne(notSharp);
		a.invokestatic(this.readHash);
		a.areturn();
		a.labelBinding(notSharp);
		// '"'
		a.iload(0);
		a.loadConstant('"');
		a.if_icmpne(notStr);
		a.invokestatic(this.readStr);
		a.areturn();
		a.labelBinding(notStr);
		// ')'
		a.iload(0);
		a.loadConstant(')');
		a.if_icmpne(atom);
		// a ')' where a datum is due closes nothing: record it and end the parse
		recordError(a, ReadFailure.UNMATCHED_CLOSE);
		a.aconst_null();
		a.areturn();
		a.labelBinding(atom);
		a.invokestatic(this.readAtom);
		a.areturn();
		a.labelBinding(retNull);
		markEof(a);
		a.aconst_null();
		a.areturn();
		return a;
	}

	/**
	 * Emits {@code (sym inner)} = {@code new Object[]{sym, new Object[]{inner, null}}}
	 * where {@code inner} is in local slot {@code innerSlot}. Leaves the result on the
	 * stack.
	 */
	private void wrapWithSymbol(MethodCode a, String sym, int innerSlot) {
		a.loadConstant(2);
		a.anewarray(this.objectClass);
		a.dup();
		a.loadConstant(0);
		ldc(a, sym);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.loadConstant(2);
		a.anewarray(this.objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(innerSlot);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aconst_null();
		a.aastore();
		a.aastore();
	}

	/**
	 * Emits the test for a {@code .} token at the cursor, which the caller has moved to a
	 * non-whitespace character: the {@code .} counts as a token of its own only when
	 * followed by a delimiter or the end of input, so symbols and floats containing
	 * {@code .} are untouched. Jumps to {@code isDot} or {@code notDot}; neither label is
	 * bound here. Local 1 is the scratch.
	 */
	private void emitDotTokenTest(MethodCode a, MethodCode.Label isDot, MethodCode.Label notDot) {
		pos(a);
		srcLen(a);
		a.if_icmpge(notDot);
		charAtPos(a);
		a.loadConstant('.');
		a.if_icmpne(notDot);
		pos(a);
		a.loadConstant(1);
		a.iadd();
		srcLen(a);
		a.if_icmpge(isDot);
		a.getstatic(this.readSrc);
		pos(a);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(this.stringCharAt);
		a.istore(1);
		a.iload(1);
		a.invokestatic(this.isWhitespace);
		a.ifne(isDot);
		a.iload(1);
		a.loadConstant(')');
		a.if_icmpeq(isDot);
		a.iload(1);
		a.loadConstant('(');
		a.if_icmpeq(isDot);
		a.iload(1);
		a.loadConstant('\'');
		a.if_icmpeq(isDot);
		a.iload(1);
		a.loadConstant('"');
		a.if_icmpeq(isDot);
		a.iload(1);
		a.loadConstant(';');
		a.if_icmpeq(isDot);
		a.goto_(notDot);
	}

	private MethodCode buildReadList() {
		MethodCode a = new MethodCode();
		MethodCode.Label retNull = a.newLabel();
		MethodCode.Label cont = a.newLabel();
		MethodCode.Label firstIsDot = a.newLabel();
		MethodCode.Label firstNotDot = a.newLabel();
		MethodCode.Label notDot = a.newLabel();
		MethodCode.Label isDot = a.newLabel();
		MethodCode.Label tailEof = a.newLabel();
		MethodCode.Label tooMany = a.newLabel();
		MethodCode.Label build = a.newLabel();
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.if_icmpge(retNull);
		charAtPos(a);
		a.loadConstant(')');
		a.if_icmpne(cont);
		advance(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(cont);
		// A '.' token ahead of every element has no car to be the cdr of.
		emitDotTokenTest(a, firstIsDot, firstNotDot);
		a.labelBinding(firstIsDot);
		recordError(a, ReadFailure.NOTHING_BEFORE_DOT);
		a.aconst_null();
		a.areturn();
		a.labelBinding(firstNotDot);
		a.invokestatic(this.readExpr);
		a.astore(0); // car
		// Dotted pair: a standalone '.' token puts the next datum directly in the
		// final cdr, mirroring the compile-time reader: (a . b).
		a.invokestatic(this.readSkipWs);
		emitDotTokenTest(a, isDot, notDot);
		a.labelBinding(isDot);
		advance(a); // consume '.'
		a.invokestatic(this.readExpr);
		a.astore(1); // cdr = the dotted tail
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.if_icmpge(tailEof);
		charAtPos(a);
		a.loadConstant(')');
		a.if_icmpne(tooMany);
		advance(a); // consume ')'
		a.goto_(build);
		a.labelBinding(tooMany);
		// a second object where the dotted list's ')' was due
		recordError(a, ReadFailure.MORE_THAN_ONE_AFTER_DOT);
		a.goto_(build);
		a.labelBinding(tailEof);
		// the text ends before the dotted list's ')'
		markEof(a);
		a.goto_(build);
		a.labelBinding(notDot);
		a.invokestatic(this.readList);
		a.astore(1); // cdr
		a.labelBinding(build);
		a.loadConstant(2);
		a.anewarray(this.objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(0);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(1);
		a.aastore();
		a.areturn();
		a.labelBinding(retNull);
		// the text ends before the list's ')'
		markEof(a);
		a.aconst_null();
		a.areturn();
		return a;
	}

	private MethodCode buildReadAtom() {
		MethodCode a = new MethodCode();
		MethodCode.Label aloop = a.newLabel();
		MethodCode.Label aend = a.newLabel();
		pos(a);
		a.istore(0); // start
		a.labelBinding(aloop);
		pos(a);
		srcLen(a);
		a.if_icmpge(aend);
		charAtPos(a);
		a.istore(1); // ch
		a.iload(1);
		a.invokestatic(this.isWhitespace);
		a.ifne(aend);
		stopIf(a, '(', aend);
		stopIf(a, ')', aend);
		stopIf(a, '\'', aend);
		stopIf(a, '"', aend);
		stopIf(a, ';', aend);
		advance(a);
		a.goto_(aloop);
		a.labelBinding(aend);
		// token = src.substring(start, pos)
		a.getstatic(this.readSrc);
		a.iload(0);
		pos(a);
		a.invokevirtual(this.stringSubstring);
		a.invokestatic(this.classify);
		a.areturn();
		return a;
	}

	private void stopIf(MethodCode a, char ch, MethodCode.Label target) {
		a.iload(1);
		a.loadConstant(ch);
		a.if_icmpeq(target);
	}

	private MethodCode buildReadStr() {
		MethodCode a = new MethodCode();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label close = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label plain = a.newLabel();
		MethodCode.Label plainBs = a.newLabel();
		MethodCode.Label adv = a.newLabel();
		MethodCode.Label e1 = a.newLabel();
		MethodCode.Label e2 = a.newLabel();
		MethodCode.Label e3 = a.newLabel();
		MethodCode.Label e4 = a.newLabel();
		MethodCode.Label eof = a.newLabel();
		// consume opening quote
		advance(a);
		// sb = new StringBuilder("\"")
		a.new_(this.stringBuilderClass);
		a.dup();
		ldc(a, "\"");
		a.invokespecial(this.sbInitStr);
		a.astore(0);
		a.labelBinding(loop);
		pos(a);
		srcLen(a);
		a.if_icmpge(eof);
		charAtPos(a);
		a.istore(1); // ch
		a.iload(1);
		a.loadConstant('"');
		a.if_icmpeq(close);
		a.iload(1);
		a.loadConstant('\\');
		a.if_icmpne(plain);
		// backslash: check pos+1 < len
		pos(a);
		a.loadConstant(1);
		a.iadd();
		srcLen(a);
		a.if_icmpge(plainBs);
		// esc = charAt(pos+1)
		a.getstatic(this.readSrc);
		pos(a);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(this.stringCharAt);
		a.istore(2); // esc
		// consume the backslash (esc char consumed by ADV)
		advance(a);
		// switch esc
		a.iload(2);
		a.loadConstant('n');
		a.if_icmpne(e1);
		appendChar(a, '\n');
		a.goto_(adv);
		a.labelBinding(e1);
		a.iload(2);
		a.loadConstant('t');
		a.if_icmpne(e2);
		appendChar(a, '\t');
		a.goto_(adv);
		a.labelBinding(e2);
		a.iload(2);
		a.loadConstant('\\');
		a.if_icmpne(e3);
		appendChar(a, '\\');
		a.goto_(adv);
		a.labelBinding(e3);
		a.iload(2);
		a.loadConstant('"');
		a.if_icmpne(e4);
		appendChar(a, '"');
		a.goto_(adv);
		a.labelBinding(e4);
		// default: append '\\' then esc
		a.aload(0);
		a.loadConstant('\\');
		a.invokevirtual(this.sbAppendChar);
		a.pop();
		a.aload(0);
		a.iload(2);
		a.invokevirtual(this.sbAppendChar);
		a.pop();
		a.goto_(adv);
		a.labelBinding(plainBs);
		a.aload(0);
		a.iload(1);
		a.invokevirtual(this.sbAppendChar);
		a.pop();
		a.goto_(adv);
		a.labelBinding(plain);
		a.aload(0);
		a.iload(1);
		a.invokevirtual(this.sbAppendChar);
		a.pop();
		a.goto_(adv);
		a.labelBinding(adv);
		advance(a);
		a.goto_(loop);
		a.labelBinding(eof);
		// the text ends before the closing quote
		markEof(a);
		a.goto_(done);
		a.labelBinding(close);
		advance(a); // consume closing quote
		a.labelBinding(done);
		a.aload(0);
		a.loadConstant('"');
		a.invokevirtual(this.sbAppendChar);
		a.pop();
		a.aload(0);
		a.invokevirtual(this.sbToString);
		a.areturn();
		return a;
	}

	private void appendChar(MethodCode a, char ch) {
		a.aload(0);
		a.loadConstant(ch);
		a.invokevirtual(this.sbAppendChar);
		a.pop();
	}

	private MethodCode buildClassify() {
		MethodCode a = new MethodCode();
		MethodCode.Label notNil = a.newLabel();
		MethodCode.Label notT = a.newLabel();
		MethodCode.Label vloop = a.newLabel();
		MethodCode.Label notDigit = a.newLabel();
		MethodCode.Label notComma = a.newLabel();
		MethodCode.Label expState = a.newLabel();
		MethodCode.Label expBad = a.newLabel();
		MethodCode.Label expSignOk = a.newLabel();
		MethodCode.Label expPlus = a.newLabel();
		MethodCode.Label notMarker = a.newLabel();
		MethodCode.Label expMark = a.newLabel();
		MethodCode.Label expOk = a.newLabel();
		MethodCode.Label markersDone = a.newLabel();
		MethodCode.Label vnext = a.newLabel();
		MethodCode.Label vend = a.newLabel();
		MethodCode.Label sym = a.newLabel();
		MethodCode.Label intPath = a.newLabel();
		MethodCode.Label noTrailingDot = a.newLabel();
		MethodCode.Label big = a.newLabel();
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
		a.ifeq(notNil);
		a.aconst_null();
		a.areturn();
		a.labelBinding(notNil);
		// t? -> the symbol t (the bare String "t", like the interpreter's reader), so it
		// prints as t and is eq to a quoted 't.
		a.aload(0);
		ldc(a, "T");
		a.invokevirtual(this.objectEquals);
		a.ifeq(notT);
		ldc(a, "T");
		a.areturn();
		a.labelBinding(notT);
		// Leading '+': an explicitly positive number literal (+347, +2.5, +.5, +1/3)
		// drops
		// the sign when a digit, or a '.' and a digit, follows, like the frontend
		// tokenizer; any other '+' token stays a symbol. A token that then turns out not
		// to be a number (+5x) is the symbol spelled with its sign: slot 10 keeps it.
		MethodCode.Label noPlus = a.newLabel();
		MethodCode.Label plusDot = a.newLabel();
		MethodCode.Label plusStrip = a.newLabel();
		a.aload(0);
		a.astore(10);
		a.aload(0);
		a.invokevirtual(this.stringLength);
		a.loadConstant(2);
		a.if_icmplt(noPlus);
		a.aload(0);
		a.loadConstant(0);
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('+');
		a.if_icmpne(noPlus);
		a.aload(0);
		a.loadConstant(1);
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('.');
		a.if_icmpeq(plusDot);
		a.aload(0);
		a.loadConstant(1);
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('0');
		a.if_icmplt(noPlus);
		a.aload(0);
		a.loadConstant(1);
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('9');
		a.if_icmpgt(noPlus);
		a.goto_(plusStrip);
		a.labelBinding(plusDot);
		a.aload(0);
		a.invokevirtual(this.stringLength);
		a.loadConstant(3);
		a.if_icmplt(noPlus);
		a.aload(0);
		a.loadConstant(2);
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('0');
		a.if_icmplt(noPlus);
		a.aload(0);
		a.loadConstant(2);
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('9');
		a.if_icmpgt(noPlus);
		a.labelBinding(plusStrip);
		a.aload(0);
		a.loadConstant(1);
		a.invokevirtual(this.stringSubstringFrom);
		a.astore(0);
		a.labelBinding(noPlus);
		// n = token.length(); slot2
		a.aload(0);
		a.invokevirtual(this.stringLength);
		a.istore(2);
		a.iload(2);
		a.ifeq(sym); // empty -> symbol
		// Ratio N/D: exactly one '/', digits (with grouping commas) on both sides, an
		// optional leading '-'; built through _rat so normalization (2/4 -> 1/2) and the
		// integer demotion (4/2 -> 2) match the frontend. Any other '/'-bearing token
		// falls through to the symbol path.
		MethodCode.Label noRatio = a.newLabel();
		MethodCode.Label rnumLoop = a.newLabel();
		MethodCode.Label rnumNext = a.newLabel();
		MethodCode.Label rnumDone = a.newLabel();
		MethodCode.Label rdenLoop = a.newLabel();
		MethodCode.Label rdenNext = a.newLabel();
		MethodCode.Label rdenDone = a.newLabel();
		MethodCode.Label rDivZero = a.newLabel();
		a.aload(0);
		a.loadConstant('/');
		a.invokevirtual(this.stringIndexOf);
		a.istore(7); // si
		a.iload(7);
		a.iflt(noRatio);
		// numerator scan from j = ('-' prefix ? 1 : 0) to si-1
		a.loadConstant(0);
		a.istore(8); // j
		a.aload(0);
		a.loadConstant(0);
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('-');
		a.if_icmpne(rnumLoop);
		a.loadConstant(1);
		a.istore(8);
		a.labelBinding(rnumLoop);
		a.loadConstant(0);
		a.istore(9); // sawNumDigit
		MethodCode.Label rnumScan = a.newLabel();
		a.labelBinding(rnumScan);
		a.iload(8);
		a.iload(7);
		a.if_icmpge(rnumDone);
		a.aload(0);
		a.iload(8);
		a.invokevirtual(this.stringCharAt);
		a.istore(6);
		a.iload(6);
		a.loadConstant(',');
		a.if_icmpeq(rnumNext);
		a.iload(6);
		a.loadConstant('0');
		a.if_icmplt(sym);
		a.iload(6);
		a.loadConstant('9');
		a.if_icmpgt(sym);
		a.loadConstant(1);
		a.istore(9);
		a.labelBinding(rnumNext);
		a.iinc(8, 1);
		a.goto_(rnumScan);
		a.labelBinding(rnumDone);
		a.iload(9);
		a.ifeq(sym);
		// denominator scan from si+1 to n-1 (no sign allowed)
		a.iload(7);
		a.loadConstant(1);
		a.iadd();
		a.iload(2);
		a.if_icmpge(sym); // empty denominator
		a.iload(7);
		a.loadConstant(1);
		a.iadd();
		a.istore(8);
		a.loadConstant(0);
		a.istore(9); // sawDenDigit
		a.labelBinding(rdenLoop);
		a.iload(8);
		a.iload(2);
		a.if_icmpge(rdenDone);
		a.aload(0);
		a.iload(8);
		a.invokevirtual(this.stringCharAt);
		a.istore(6);
		a.iload(6);
		a.loadConstant(',');
		a.if_icmpeq(rdenNext);
		a.iload(6);
		a.loadConstant('0');
		a.if_icmplt(sym);
		a.iload(6);
		a.loadConstant('9');
		a.if_icmpgt(sym);
		a.loadConstant(1);
		a.istore(9);
		a.labelBinding(rdenNext);
		a.iinc(8, 1);
		a.goto_(rdenLoop);
		a.labelBinding(rdenDone);
		a.iload(9);
		a.ifeq(sym);
		// numStr = token.substring(0, si).replace(",", "") (kept for the /0 message)
		a.aload(0);
		a.loadConstant(0);
		a.iload(7);
		a.invokevirtual(this.stringSubstring);
		ldc(a, ",");
		ldc(a, "");
		a.invokevirtual(this.stringReplace);
		a.astore(5);
		// num, den on the stack, den checked for zero before _rat
		a.new_(this.bigIntegerClass);
		a.dup();
		a.aload(5);
		a.invokespecial(this.bigIntegerInit);
		a.new_(this.bigIntegerClass);
		a.dup();
		a.aload(0);
		a.iload(7);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(this.stringSubstringFrom);
		ldc(a, ",");
		ldc(a, "");
		a.invokevirtual(this.stringReplace);
		a.invokespecial(this.bigIntegerInit);
		a.dup();
		a.invokevirtual(this.bigIntegerSignum);
		a.ifeq(rDivZero);
		a.invokestatic(this.ratMethod);
		a.areturn();
		a.labelBinding(rDivZero);
		a.pop();
		a.pop();
		sbNew(a, "Division by zero in ratio literal: ");
		a.aload(5);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, "/0");
		sbThrow(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(noRatio);
		a.loadConstant(0);
		a.istore(1); // i
		a.loadConstant(0);
		a.istore(3); // sawDigit
		a.loadConstant(0);
		a.istore(4); // sawDot
		// Exponent state in slots 7/8/9 (free since the ratio scan above ended):
		// sawExponent = a CL exponent marker has been consumed, expSign = 0 none /
		// 1 '+' / 2 '-', expDigit = at least one digit followed the marker.
		a.loadConstant(0);
		a.istore(7);
		a.loadConstant(0);
		a.istore(8);
		a.loadConstant(0);
		a.istore(9);
		// if token[0]=='-': i=1
		a.aload(0);
		a.loadConstant(0);
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('-');
		a.if_icmpne(vloop);
		a.loadConstant(1);
		a.istore(1);
		a.labelBinding(vloop);
		a.iload(1);
		a.iload(2);
		a.if_icmpge(vend);
		a.aload(0);
		a.iload(1);
		a.invokevirtual(this.stringCharAt);
		a.istore(6); // ch
		// Past an exponent marker only an exponent remains: digits, and a sign only
		// directly after the marker. Anything else -- a second marker, a dot, a letter
		// ("1d0x") -- sends the whole token to the symbol path, like the frontend.
		a.iload(7);
		a.ifne(expState);
		// digit?
		a.iload(6);
		a.loadConstant('0');
		a.if_icmplt(notDigit);
		a.iload(6);
		a.loadConstant('9');
		a.if_icmpgt(notDigit);
		a.loadConstant(1);
		a.istore(3);
		a.goto_(vnext);
		a.labelBinding(notDigit);
		a.iload(6);
		a.loadConstant(',');
		a.if_icmpne(notComma);
		a.goto_(vnext); // grouping comma: skip
		a.labelBinding(notComma);
		a.iload(6);
		a.loadConstant('.');
		a.if_icmpne(notMarker);
		a.iload(4);
		a.ifne(sym); // second dot -> symbol
		a.loadConstant(1);
		a.istore(4);
		a.goto_(vnext);
		a.labelBinding(notMarker);
		// A CL exponent marker (the token is upcased, so only the uppercase spelling
		// can appear) starts the exponent part: "1E5", ".5E2", "1.E5".
		a.iload(6);
		a.loadConstant('E');
		a.if_icmpeq(expMark);
		a.iload(6);
		a.loadConstant('S');
		a.if_icmpeq(expMark);
		a.iload(6);
		a.loadConstant('F');
		a.if_icmpeq(expMark);
		a.iload(6);
		a.loadConstant('D');
		a.if_icmpeq(expMark);
		a.iload(6);
		a.loadConstant('L');
		a.if_icmpeq(expMark);
		a.goto_(sym); // any other char -> symbol
		a.labelBinding(expMark);
		a.loadConstant(1);
		a.istore(7);
		a.goto_(vnext);
		a.labelBinding(expState);
		// Past an exponent marker: digits, and a sign only directly after the marker.
		// Anything else -- a second marker, a dot, a letter ("1D0X") -- sends the whole
		// token to the symbol path, like the frontend's numberFallbackSymbol.
		a.iload(6);
		a.loadConstant('0');
		a.if_icmplt(expBad);
		a.iload(6);
		a.loadConstant('9');
		a.if_icmpgt(expBad);
		a.loadConstant(1);
		a.istore(9); // expDigit
		a.goto_(vnext);
		a.labelBinding(expBad);
		a.iload(6);
		a.loadConstant('+');
		a.if_icmpeq(expSignOk);
		a.iload(6);
		a.loadConstant('-');
		a.if_icmpne(sym); // not a sign -> symbol
		a.labelBinding(expSignOk);
		a.iload(8);
		a.ifne(sym); // a sign was already consumed
		a.iload(9);
		a.ifne(sym); // the sign must come before any exponent digit
		a.iload(6);
		a.loadConstant('-');
		a.if_icmpne(expPlus);
		a.loadConstant(2);
		a.istore(8);
		a.goto_(vnext);
		a.labelBinding(expPlus);
		a.loadConstant(1);
		a.istore(8);
		a.goto_(vnext);
		a.labelBinding(vnext);
		a.iinc(1, 1);
		a.goto_(vloop);
		a.labelBinding(vend);
		a.iload(3);
		a.ifeq(sym); // no digit -> symbol
		// A marker with no exponent digit ("1E", "1E+") is not a number.
		a.iload(7);
		a.ifeq(expOk);
		a.iload(9);
		a.ifeq(sym);
		a.labelBinding(expOk);
		// stripped = token.replace(",", "") ; slot5
		a.aload(0);
		ldc(a, ",");
		ldc(a, "");
		a.invokevirtual(this.stringReplace);
		a.astore(5);
		// A trailing '.' after the digits ("5.", "-5.") is a decimal INTEGER (CLHS
		// 2.3.1),
		// at any magnitude: drop the dot and take the integer path. A dot before the end
		// is a float, and a token whose dot trails an exponent marker was sent to the
		// symbol path above.
		a.aload(0);
		a.iload(2);
		a.loadConstant(1);
		a.isub();
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('.');
		a.if_icmpne(noTrailingDot);
		a.aload(5);
		a.loadConstant(0);
		a.aload(5);
		a.invokevirtual(this.stringLength);
		a.loadConstant(1);
		a.isub();
		a.invokevirtual(this.stringSubstring);
		a.astore(5);
		a.goto_(intPath);
		a.labelBinding(noTrailingDot);
		// double? -- a '.' or an exponent marker makes the token a float
		a.iload(4);
		a.iload(7);
		a.ior();
		a.ifeq(intPath);
		// Double.parseDouble knows only 'e'/'E' as an exponent marker: rewrite the
		// other CL markers to 'e' (the token is upcased here, and a valid float token
		// holds at most one marker, so replacing every spelling is safe -- the same
		// normalization the frontend lexer applies).
		a.iload(7);
		a.ifeq(markersDone);
		a.aload(5);
		ldc(a, "S");
		ldc(a, "e");
		a.invokevirtual(this.stringReplace);
		a.astore(5);
		a.aload(5);
		ldc(a, "F");
		ldc(a, "e");
		a.invokevirtual(this.stringReplace);
		a.astore(5);
		a.aload(5);
		ldc(a, "D");
		ldc(a, "e");
		a.invokevirtual(this.stringReplace);
		a.astore(5);
		a.aload(5);
		ldc(a, "L");
		ldc(a, "e");
		a.invokevirtual(this.stringReplace);
		a.astore(5);
		a.labelBinding(markersDone);
		a.aload(5);
		a.invokestatic(this.doubleParse);
		a.invokestatic(this.doubleValueOf);
		a.areturn();
		a.labelBinding(intPath);
		// bi = new BigInteger(stripped)
		a.new_(this.bigIntegerClass);
		a.dup();
		a.aload(5);
		a.invokespecial(this.bigIntegerInit);
		// if bi.bitLength() < 64 -> Long.valueOf(bi.longValue()) else bi
		a.dup();
		a.invokevirtual(this.bigIntegerBitLength);
		a.loadConstant(64);
		a.if_icmpge(big);
		a.invokevirtual(this.bigIntegerLongValue);
		a.invokestatic(this.longValueOf);
		a.areturn();
		a.labelBinding(big);
		a.areturn(); // bi (BigInteger) still on stack
		a.labelBinding(sym);
		a.aload(10);
		a.areturn();
		return a;
	}

	// _readFromString(Object strObj): parse the first datum from a string (the quotes of
	// the runtime string representation are stripped first); returns null when empty.
	// _readFail says how the parse ended (FAIL_FIELD).
	private MethodCode buildReadFromString() {
		MethodCode a = new MethodCode();
		MethodCode.Label retNull = a.newLabel();
		MethodCode.Label noTrailingWs = a.newLabel();
		a.aload(0);
		a.checkcast(this.stringClass);
		a.astore(1);
		// raw = s.substring(1, s.length()-1)
		a.aload(1);
		a.loadConstant(1);
		a.aload(1);
		a.invokevirtual(this.stringLength);
		a.loadConstant(1);
		a.isub();
		a.invokevirtual(this.stringSubstring);
		a.putstatic(this.readSrc);
		a.loadConstant(0);
		a.putstatic(this.readPos);
		a.loadConstant(0);
		a.putstatic(this.readFail);
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.if_icmpge(retNull);
		a.invokestatic(this.readExpr);
		a.astore(2);
		// CLHS 2.2 / 23.2: read consumes one whitespace character that terminates the
		// datum, a list and a character literal alike, not only a token -- so advance
		// _readPos past it here. This only affects %read-from-string-end, which reads
		// _readPos right after this call; the returned datum is unaffected.
		pos(a);
		srcLen(a);
		a.if_icmpge(noTrailingWs);
		charAtPos(a);
		a.invokestatic(this.isWhitespace);
		a.ifeq(noTrailingWs);
		advance(a);
		a.labelBinding(noTrailingWs);
		a.aload(2);
		a.areturn();
		a.labelBinding(retNull);
		// no datum at all
		markEof(a);
		a.aconst_null();
		a.areturn();
		return a;
	}

	// === # dispatch bodies ===

	/** Emits {@code new StringBuilder(head)} leaving the builder on the stack. */
	private void sbNew(MethodCode a, String head) {
		a.new_(this.stringBuilderClass);
		a.dup();
		ldc(a, head);
		a.invokespecial(this.sbInitStr);
	}

	/** Appends the constant {@code text} to the StringBuilder on the stack. */
	private void sbText(MethodCode a, String text) {
		ldc(a, text);
		a.invokevirtual(this.sbAppendStr);
	}

	/** Finishes the StringBuilder on the stack and throws it via {@code _rdErr}. */
	private void sbThrow(MethodCode a) {
		a.invokevirtual(this.sbToString);
		a.invokestatic(this.rdErr);
	}

	/** Emits a static-message throw via {@code _rdErr}. */
	private void err(MethodCode a, String message) {
		ldc(a, message);
		a.invokestatic(this.rdErr);
	}

	/** Pushes {@code _readSrc.charAt(_readPos + offset)}. */
	private void charAtPosPlus(MethodCode a, int offset) {
		a.getstatic(this.readSrc);
		a.getstatic(this.readPos);
		a.loadConstant(offset);
		a.iadd();
		a.invokevirtual(this.stringCharAt);
	}

	/** Branches to {@code target} when {@code _readPos + offset >= len}. */
	private void branchIfPosPlusGeLen(MethodCode a, int offset, MethodCode.Label target) {
		a.getstatic(this.readPos);
		a.loadConstant(offset);
		a.iadd();
		srcLen(a);
		a.if_icmpge(target);
	}

	// _readHash: the '#' dispatcher, mirroring the frontend lexer's dispatch set. The
	// cursor is still AT the '#'; any token no dispatch claims falls back to the atom
	// path, so #foo / #:g / #16r1f read as the symbols the frontend reads them as.
	private MethodCode buildReadHash() {
		MethodCode a = new MethodCode();
		MethodCode.Label atom = a.newLabel();
		MethodCode.Label notFn = a.newLabel();
		MethodCode.Label notChar = a.newLabel();
		MethodCode.Label notVec = a.newLabel();
		MethodCode.Label notStructS = a.newLabel();
		MethodCode.Label structOpen = a.newLabel();
		MethodCode.Label pathnameOpen = a.newLabel();
		MethodCode.Label notPathnameP = a.newLabel();
		MethodCode.Label notBits = a.newLabel();
		MethodCode.Label notSingle = a.newLabel();
		MethodCode.Label singleOpen = a.newLabel();
		MethodCode.Label notBf16 = a.newLabel();
		MethodCode.Label bf16Open = a.newLabel();
		MethodCode.Label bf16F = a.newLabel();
		MethodCode.Label notDouble = a.newLabel();
		MethodCode.Label doubleOpen = a.newLabel();
		MethodCode.Label notDigit = a.newLabel();
		MethodCode.Label dloop = a.newLabel();
		MethodCode.Label dend = a.newLabel();
		MethodCode.Label rankOverflow = a.newLabel();
		MethodCode.Label notArrA = a.newLabel();
		MethodCode.Label arrOpen = a.newLabel();
		MethodCode.Label labelErr = a.newLabel();
		MethodCode.Label notX = a.newLabel();
		MethodCode.Label notO = a.newLabel();
		MethodCode.Label notB = a.newLabel();
		MethodCode.Label radix16 = a.newLabel();
		MethodCode.Label radix8 = a.newLabel();
		MethodCode.Label radix2 = a.newLabel();
		MethodCode.Label notDot = a.newLabel();
		MethodCode.Label notFeature = a.newLabel();
		// if pos+1 >= len -> atom ("#" at end of input reads as the symbol #)
		branchIfPosPlusGeLen(a, 1, atom);
		// c2 = charAt(pos+1)
		charAtPosPlus(a, 1);
		a.istore(0);
		// "#'" -> (function inner)
		a.iload(0);
		a.loadConstant('\'');
		a.if_icmpne(notFn);
		advance(a);
		advance(a);
		a.invokestatic(this.readExpr);
		a.astore(3);
		wrapWithSymbol(a, LispNames.FUNCTION, 3);
		a.areturn();
		a.labelBinding(notFn);
		// "#\" -> character literal
		a.iload(0);
		a.loadConstant('\\');
		a.if_icmpne(notChar);
		advance(a);
		advance(a);
		a.invokestatic(this.readCharLit);
		a.areturn();
		a.labelBinding(notChar);
		// "#(" -> rank-1 vector
		a.iload(0);
		a.loadConstant('(');
		a.if_icmpne(notVec);
		advance(a);
		advance(a);
		a.loadConstant(1);
		a.invokestatic(this.readArrayN);
		a.areturn();
		a.labelBinding(notVec);
		// "#S(" / "#s(" -> structure literal ("#S" without the paren is a symbol)
		a.iload(0);
		a.loadConstant('S');
		a.if_icmpeq(structOpen);
		a.iload(0);
		a.loadConstant('s');
		a.if_icmpne(notStructS);
		a.labelBinding(structOpen);
		branchIfPosPlusGeLen(a, 2, atom);
		charAtPosPlus(a, 2);
		a.loadConstant('(');
		a.if_icmpne(atom);
		advance(a);
		advance(a);
		advance(a);
		a.invokestatic(this.readStruct);
		a.areturn();
		a.labelBinding(notStructS);
		// "#P\"" / "#p\"" -> pathname literal ("#P" without a string is a symbol,
		// like the frontend). The value is Object[]{PATHNAME layout, namestring};
		// with the instance gate off no pathname value can exist, so the arm signals
		// rather than answering a mistyped value.
		a.iload(0);
		a.loadConstant('P');
		a.if_icmpeq(pathnameOpen);
		a.iload(0);
		a.loadConstant('p');
		a.if_icmpne(notPathnameP);
		a.labelBinding(pathnameOpen);
		branchIfPosPlusGeLen(a, 2, atom);
		charAtPosPlus(a, 2);
		a.loadConstant('"');
		a.if_icmpne(atom);
		advance(a);
		advance(a);
		if (this.pathnameLayout != null) {
			a.invokestatic(this.readStr);
			a.astore(3);
			a.loadConstant(2);
			a.anewarray(this.objectClass);
			a.dup();
			a.loadConstant(0);
			a.getstatic(this.pathnameLayout);
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.aload(3);
			a.aastore();
		}
		else {
			err(a, "#P pathname literals need the instance runtime, which this artifact was compiled without");
			a.aconst_null();
		}
		a.areturn();
		a.labelBinding(notPathnameP);
		// "#*" -> bit vector (a general vector of 0/1, like the frontend)
		a.iload(0);
		a.loadConstant('*');
		a.if_icmpne(notBits);
		advance(a);
		advance(a);
		a.invokestatic(this.readBits);
		a.areturn();
		a.labelBinding(notBits);
		// "#bf16(" (any case) -> packed bfloat16 array. Tried BEFORE the "#b" binary
		// radix branch below (the frontend lexer orders them the same way): 'f' is not
		// a binary digit, so the wrong order would fail rather than mis-read, but only
		// the order keeps it so.
		a.iload(0);
		a.loadConstant('b');
		a.if_icmpeq(bf16Open);
		a.iload(0);
		a.loadConstant('B');
		a.if_icmpne(notBf16);
		a.labelBinding(bf16Open);
		branchIfPosPlusGeLen(a, 5, notBf16);
		charAtPosPlus(a, 2);
		a.loadConstant('F');
		a.if_icmpeq(bf16F);
		charAtPosPlus(a, 2);
		a.loadConstant('f');
		a.if_icmpne(notBf16);
		a.labelBinding(bf16F);
		charAtPosPlus(a, 3);
		a.loadConstant('1');
		a.if_icmpne(notBf16);
		charAtPosPlus(a, 4);
		a.loadConstant('6');
		a.if_icmpne(notBf16);
		charAtPosPlus(a, 5);
		a.loadConstant('(');
		a.if_icmpne(notBf16);
		for (int skip = 0; skip < 6; skip++) {
			advance(a);
		}
		a.loadConstant(2);
		a.invokestatic(this.readPacked);
		a.areturn();
		a.labelBinding(notBf16);
		// "#f(" / "#F(" -> packed single-float array
		a.iload(0);
		a.loadConstant('f');
		a.if_icmpeq(singleOpen);
		a.iload(0);
		a.loadConstant('F');
		a.if_icmpne(notSingle);
		a.labelBinding(singleOpen);
		branchIfPosPlusGeLen(a, 2, atom);
		charAtPosPlus(a, 2);
		a.loadConstant('(');
		a.if_icmpne(atom);
		advance(a);
		advance(a);
		advance(a);
		a.loadConstant(1);
		a.invokestatic(this.readPacked);
		a.areturn();
		a.labelBinding(notSingle);
		// "#d(" / "#D(" -> packed double-float array
		a.iload(0);
		a.loadConstant('d');
		a.if_icmpeq(doubleOpen);
		a.iload(0);
		a.loadConstant('D');
		a.if_icmpne(notDouble);
		a.labelBinding(doubleOpen);
		branchIfPosPlusGeLen(a, 2, atom);
		charAtPosPlus(a, 2);
		a.loadConstant('(');
		a.if_icmpne(atom);
		advance(a);
		advance(a);
		advance(a);
		a.loadConstant(0);
		a.invokestatic(this.readPacked);
		a.areturn();
		a.labelBinding(notDouble);
		// "#<digits>" -> #nA( array, #n=/#n# labels (signaled), or a symbol
		a.iload(0);
		a.loadConstant('0');
		a.if_icmplt(notDigit);
		a.iload(0);
		a.loadConstant('9');
		a.if_icmpgt(notDigit);
		a.getstatic(this.readPos);
		a.loadConstant(1);
		a.iadd();
		a.istore(1); // probe
		a.loadConstant(0);
		a.istore(2); // rank
		a.labelBinding(dloop);
		a.iload(1);
		srcLen(a);
		a.if_icmpge(dend);
		a.getstatic(this.readSrc);
		a.iload(1);
		a.invokevirtual(this.stringCharAt);
		a.istore(0);
		a.iload(0);
		a.loadConstant('0');
		a.if_icmplt(dend);
		a.iload(0);
		a.loadConstant('9');
		a.if_icmpgt(dend);
		a.iload(2);
		a.loadConstant(10);
		a.imul();
		a.iload(0);
		a.loadConstant('0');
		a.isub();
		a.iadd();
		a.istore(2);
		// A rank this large cannot denote a real array; stopping here keeps the int
		// accumulator from wrapping around into a small (wrong) rank.
		a.iload(2);
		a.loadConstant(20000);
		a.if_icmpgt(rankOverflow);
		a.iinc(1, 1);
		a.goto_(dloop);
		a.labelBinding(rankOverflow);
		sbNew(a, "Invalid array rank: ");
		a.getstatic(this.readSrc);
		a.getstatic(this.readPos);
		a.iload(1);
		a.invokevirtual(this.stringSubstring);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(dend);
		a.iload(1);
		srcLen(a);
		a.if_icmpge(atom);
		a.getstatic(this.readSrc);
		a.iload(1);
		a.invokevirtual(this.stringCharAt);
		a.istore(0);
		a.iload(0);
		a.loadConstant('A');
		a.if_icmpeq(arrOpen);
		a.iload(0);
		a.loadConstant('a');
		a.if_icmpne(notArrA);
		a.labelBinding(arrOpen);
		a.iload(1);
		a.loadConstant(1);
		a.iadd();
		srcLen(a);
		a.if_icmpge(atom);
		a.getstatic(this.readSrc);
		a.iload(1);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(this.stringCharAt);
		a.loadConstant('(');
		a.if_icmpne(atom);
		// pos = probe + 2 (past "A(")
		a.iload(1);
		a.loadConstant(2);
		a.iadd();
		a.putstatic(this.readPos);
		a.iload(2);
		a.invokestatic(this.readArrayN);
		a.areturn();
		a.labelBinding(notArrA);
		a.iload(0);
		a.loadConstant('=');
		a.if_icmpeq(labelErr);
		a.iload(0);
		a.loadConstant('#');
		a.if_icmpne(atom);
		a.labelBinding(labelErr);
		err(a, "reader labels (#N=/#N#) are not supported by the compiled runtime reader");
		a.aconst_null();
		a.areturn();
		a.labelBinding(notDigit);
		// "#x" / "#o" / "#b" -> radix integer
		a.iload(0);
		a.loadConstant('x');
		a.if_icmpeq(radix16);
		a.iload(0);
		a.loadConstant('X');
		a.if_icmpne(notX);
		a.labelBinding(radix16);
		advance(a);
		advance(a);
		a.loadConstant(16);
		a.iload(0);
		a.invokestatic(this.readRadix);
		a.areturn();
		a.labelBinding(notX);
		a.iload(0);
		a.loadConstant('o');
		a.if_icmpeq(radix8);
		a.iload(0);
		a.loadConstant('O');
		a.if_icmpne(notO);
		a.labelBinding(radix8);
		advance(a);
		advance(a);
		a.loadConstant(8);
		a.iload(0);
		a.invokestatic(this.readRadix);
		a.areturn();
		a.labelBinding(notO);
		a.iload(0);
		a.loadConstant('b');
		a.if_icmpeq(radix2);
		a.iload(0);
		a.loadConstant('B');
		a.if_icmpne(notB);
		a.labelBinding(radix2);
		advance(a);
		advance(a);
		a.loadConstant(2);
		a.iload(0);
		a.invokestatic(this.readRadix);
		a.areturn();
		a.labelBinding(notB);
		// "#." needs an evaluator at read time; a compiled artifact has none, so it is a
		// permanent limit that SIGNALS instead of misreading (the frontend evaluates it).
		a.iload(0);
		a.loadConstant('.');
		a.if_icmpne(notDot);
		err(a, "#. read-time evaluation is not supported");
		a.aconst_null();
		a.areturn();
		a.labelBinding(notDot);
		// "#+" / "#-" need the feature set at read time; same permanent limit.
		a.iload(0);
		a.loadConstant('+');
		a.if_icmpeq(notFeature);
		a.iload(0);
		a.loadConstant('-');
		a.if_icmpne(atom);
		a.labelBinding(notFeature);
		err(a, "#+/#- feature conditionals are not supported by the compiled runtime reader");
		a.aconst_null();
		a.areturn();
		// fallthrough: the token reads as a symbol, like the frontend's readSymbol
		a.labelBinding(atom);
		a.invokestatic(this.readAtom);
		a.areturn();
		return a;
	}

	// _readCharLit: cursor just past "#\". The first character is taken literally; a
	// letter starts a name scan; a length-1 token is that character verbatim; a longer
	// token resolves through the (case-insensitive) frontend name table.
	private MethodCode buildReadCharLit() {
		MethodCode a = new MethodCode();
		MethodCode.Label ok = a.newLabel();
		MethodCode.Label box = a.newLabel();
		MethodCode.Label scan = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label scanEnd = a.newLabel();
		MethodCode.Label named = a.newLabel();
		pos(a);
		srcLen(a);
		a.if_icmplt(ok);
		markEof(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(ok);
		pos(a);
		a.istore(1); // start
		charAtPos(a);
		a.istore(0); // first
		advance(a);
		a.iload(0);
		a.invokestatic(this.charIsLetter);
		a.ifne(scan);
		a.labelBinding(box);
		a.loadConstant(1);
		a.newarray(TypeKind.INT);
		a.dup();
		a.loadConstant(0);
		a.iload(0);
		a.iastore();
		a.areturn();
		a.labelBinding(scan);
		a.labelBinding(loop);
		pos(a);
		srcLen(a);
		a.if_icmpge(scanEnd);
		charAtPos(a);
		a.istore(2);
		a.iload(2);
		a.invokestatic(this.isWhitespace);
		a.ifne(scanEnd);
		stopIfSlot(a, 2, '(', scanEnd);
		stopIfSlot(a, 2, ')', scanEnd);
		stopIfSlot(a, 2, '\'', scanEnd);
		stopIfSlot(a, 2, '"', scanEnd);
		stopIfSlot(a, 2, ';', scanEnd);
		stopIfSlot(a, 2, ',', scanEnd);
		stopIfSlot(a, 2, '`', scanEnd);
		advance(a);
		a.goto_(loop);
		a.labelBinding(scanEnd);
		a.getstatic(this.readSrc);
		a.iload(1);
		pos(a);
		a.invokevirtual(this.stringSubstring);
		a.astore(3); // token
		a.aload(3);
		a.invokevirtual(this.stringLength);
		a.loadConstant(1);
		a.if_icmpne(named);
		a.goto_(box);
		a.labelBinding(named);
		charName(a, "space", 32);
		charName(a, "newline", 10);
		charName(a, "linefeed", 10);
		charName(a, "lf", 10);
		charName(a, "tab", 9);
		charName(a, "return", 13);
		charName(a, "cr", 13);
		charName(a, "page", 12);
		charName(a, "backspace", 8);
		charName(a, "vt", 11);
		charName(a, "vertical-tab", 11);
		charName(a, "bell", 7);
		charName(a, "bel", 7);
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
		a.aconst_null();
		a.areturn();
		// the named branches jump back to `box` with the code point in slot 0
		return a;
	}

	/** One case-insensitive named-character branch; on a match boxes {@code code}. */
	private void charName(MethodCode a, String name, int code) {
		MethodCode.Label next = a.newLabel();
		a.aload(3);
		ldc(a, name);
		a.invokevirtual(this.stringEqualsIgnoreCase);
		a.ifeq(next);
		a.loadConstant(1);
		a.newarray(TypeKind.INT);
		a.dup();
		a.loadConstant(0);
		a.loadConstant(code);
		a.iastore();
		a.areturn();
		a.labelBinding(next);
	}

	private void stopIfSlot(MethodCode a, int slot, char ch, MethodCode.Label target) {
		a.iload(slot);
		a.loadConstant(ch);
		a.if_icmpeq(target);
	}

	// _readRadix(radix, marker): cursor just past "#x"/"#o"/"#b". An optional leading
	// '-', then digits of the radix; bad digits (or a trailing symbol character) signal
	// the frontend's "Invalid digits after #x: ..." message. Values past 63 bits stay
	// BigInteger, matching the decimal classifier.
	private MethodCode buildReadRadix() {
		MethodCode a = new MethodCode();
		MethodCode.Label noNeg = a.newLabel();
		MethodCode.Label dloop = a.newLabel();
		MethodCode.Label dend = a.newLabel();
		MethodCode.Label hasDigits = a.newLabel();
		MethodCode.Label good = a.newLabel();
		MethodCode.Label noStrNeg = a.newLabel();
		MethodCode.Label bigRet = a.newLabel();
		MethodCode.Label errL = a.newLabel();
		MethodCode.Label useEnd = a.newLabel();
		pos(a);
		a.istore(2); // start
		a.loadConstant(0);
		a.istore(3); // neg
		pos(a);
		srcLen(a);
		a.if_icmpge(noNeg);
		charAtPos(a);
		a.loadConstant('-');
		a.if_icmpne(noNeg);
		a.loadConstant(1);
		a.istore(3);
		advance(a);
		a.labelBinding(noNeg);
		pos(a);
		a.istore(4); // digitsStart
		a.labelBinding(dloop);
		pos(a);
		srcLen(a);
		a.if_icmpge(dend);
		charAtPos(a);
		a.iload(0);
		a.invokestatic(this.charDigit);
		a.iflt(dend);
		advance(a);
		a.goto_(dloop);
		a.labelBinding(dend);
		pos(a);
		a.iload(4);
		a.if_icmpgt(hasDigits);
		a.goto_(errL);
		a.labelBinding(hasDigits);
		// a symbol character right after the digits invalidates the whole token
		pos(a);
		srcLen(a);
		a.if_icmpge(good);
		charAtPos(a);
		a.istore(6);
		a.iload(6);
		a.invokestatic(this.isWhitespace);
		a.ifne(good);
		stopIfSlot(a, 6, '(', good);
		stopIfSlot(a, 6, ')', good);
		stopIfSlot(a, 6, '\'', good);
		stopIfSlot(a, 6, '"', good);
		stopIfSlot(a, 6, ';', good);
		stopIfSlot(a, 6, ',', good);
		stopIfSlot(a, 6, '`', good);
		a.goto_(errL);
		a.labelBinding(good);
		a.getstatic(this.readSrc);
		a.iload(4);
		pos(a);
		a.invokevirtual(this.stringSubstring);
		a.astore(5); // digits
		a.iload(3);
		a.ifeq(noStrNeg);
		sbNew(a, "-");
		a.aload(5);
		a.invokevirtual(this.sbAppendStr);
		a.invokevirtual(this.sbToString);
		a.astore(5);
		a.labelBinding(noStrNeg);
		a.new_(this.bigIntegerClass);
		a.dup();
		a.aload(5);
		a.iload(0);
		a.invokespecial(this.bigIntegerInitRadix);
		a.dup();
		a.invokevirtual(this.bigIntegerBitLength);
		a.loadConstant(64);
		a.if_icmpge(bigRet);
		a.invokevirtual(this.bigIntegerLongValue);
		a.invokestatic(this.longValueOf);
		a.areturn();
		a.labelBinding(bigRet);
		a.areturn();
		a.labelBinding(errL);
		sbNew(a, "Invalid digits after #");
		a.iload(1);
		a.invokevirtual(this.sbAppendChar);
		sbText(a, ": ");
		// substring(start, min(pos + 1, len))
		pos(a);
		a.loadConstant(1);
		a.iadd();
		a.istore(6);
		a.iload(6);
		srcLen(a);
		a.if_icmple(useEnd);
		srcLen(a);
		a.istore(6);
		a.labelBinding(useEnd);
		a.getstatic(this.readSrc);
		a.iload(2);
		a.iload(6);
		a.invokevirtual(this.stringSubstring);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconst_null();
		a.areturn();
		return a;
	}

	// _readBits: cursor just past "#*"; consumes 0/1 characters into the general-array
	// runtime shape (an ArrayList with a {dims, nil, nil, nil, "BIT"} header), like the
	// frontend's bit-vector lowering -- there is no packed bit representation.
	private MethodCode buildReadBits() {
		MethodCode a = new MethodCode();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label ok01 = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.new_(this.arrayListClass);
		a.dup();
		a.invokespecial(this.alInit);
		a.astore(0);
		a.aload(0);
		a.aconst_null();
		a.invokevirtual(this.alAdd);
		a.pop();
		a.loadConstant(0);
		a.istore(1); // count
		a.labelBinding(loop);
		pos(a);
		srcLen(a);
		a.if_icmpge(done);
		charAtPos(a);
		a.istore(2);
		a.iload(2);
		a.loadConstant('0');
		a.if_icmpeq(ok01);
		a.iload(2);
		a.loadConstant('1');
		a.if_icmpne(done);
		a.labelBinding(ok01);
		a.aload(0);
		a.iload(2);
		a.loadConstant('0');
		a.isub();
		a.i2l();
		a.invokestatic(this.longValueOf);
		a.invokevirtual(this.alAdd);
		a.pop();
		a.iinc(1, 1);
		advance(a);
		a.goto_(loop);
		a.labelBinding(done);
		// header = Object[]{ Object[]{Long(count)}, null, null, null, "BIT" } into
		// slot 0 of the list: a bit vector read at run time is stamped with the
		// remembered element type bit, like the frontend's #* lowering (.todo/043).
		a.aload(0);
		a.loadConstant(0);
		a.loadConstant(5);
		a.anewarray(this.objectClass);
		a.dup();
		a.loadConstant(0);
		a.loadConstant(1);
		a.anewarray(this.objectClass);
		a.dup();
		a.loadConstant(0);
		a.iload(1);
		a.i2l();
		a.invokestatic(this.longValueOf);
		a.aastore();
		a.aastore();
		a.dup();
		a.loadConstant(4);
		ldc(a, am.ik.rontolisp.LispNames.BIT);
		a.aastore();
		a.invokevirtual(this.alSet);
		a.pop();
		a.aload(0);
		a.areturn();
		return a;
	}

	// _readArrayN(rank): cursor just past the opening '('; reads the grouped contents
	// as a list, computes/validates dims like the frontend, and builds the general
	// runtime array (ArrayList + {dims, nil, nil} header). #( is rank 1.
	private MethodCode buildReadArrayN() {
		MethodCode a = new MethodCode();
		MethodCode.Label okRank = a.newLabel();
		a.iload(0);
		a.loadConstant(1);
		a.if_icmpge(okRank);
		sbNew(a, "#");
		a.iload(0);
		a.invokevirtual(this.sbAppendInt);
		sbText(a, "A: array rank must be >= 1");
		sbThrow(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(okRank);
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
		a.new_(this.arrayListClass);
		a.dup();
		a.invokespecial(this.alInit);
		a.astore(4); // out
		a.aload(4);
		a.loadConstant(3);
		a.anewarray(this.objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(3);
		a.aastore();
		a.invokevirtual(this.alAdd);
		a.pop();
		a.aload(1);
		a.loadConstant(0);
		a.aload(3);
		a.aload(4);
		a.aload(2);
		a.invokestatic(this.rdFlat);
		a.aload(4);
		a.areturn();
		return a;
	}

	// _readPacked(width): cursor just past "#d(" (width 0) / "#f(" (1) / "#bf16(" (2);
	// rank is inferred from the nesting depth, leaves are coerced to double (narrowed to
	// the width), and the value is the packed double[]/float[]/short[] with the header
	// JvmPackedFloatWidth lays out for that width. Locals: 0=width, 1=rows, 2=marker,
	// 3=rank, 4=dims, 5=out, 6=n, 7=k, 8=arr, 9=base, 10=dim.
	private MethodCode buildReadPacked() {
		MethodCode a = new MethodCode();
		MethodCode.Label dlab = a.newLabel();
		MethodCode.Label blab = a.newLabel();
		MethodCode.Label lab = a.newLabel();
		MethodCode.Label dbl = a.newLabel();
		MethodCode.Label bfl = a.newLabel();
		MethodCode.Label bloop1 = a.newLabel();
		MethodCode.Label bdone1 = a.newLabel();
		MethodCode.Label bloop2 = a.newLabel();
		MethodCode.Label bdone2 = a.newLabel();
		MethodCode.Label floop1 = a.newLabel();
		MethodCode.Label fdone1 = a.newLabel();
		MethodCode.Label floop2 = a.newLabel();
		MethodCode.Label fdone2 = a.newLabel();
		MethodCode.Label dloop1 = a.newLabel();
		MethodCode.Label ddone1 = a.newLabel();
		MethodCode.Label dloop2 = a.newLabel();
		MethodCode.Label ddone2 = a.newLabel();
		a.iload(0);
		a.ifeq(dlab);
		a.iload(0);
		a.loadConstant(2);
		a.if_icmpeq(blab);
		ldc(a, "#f");
		a.astore(2);
		a.goto_(lab);
		a.labelBinding(blab);
		ldc(a, "#bf16");
		a.astore(2);
		a.goto_(lab);
		a.labelBinding(dlab);
		ldc(a, "#d");
		a.astore(2);
		a.labelBinding(lab);
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
		a.new_(this.arrayListClass);
		a.dup();
		a.invokespecial(this.alInit);
		a.astore(5); // out (data only)
		a.aload(1);
		a.loadConstant(0);
		a.aload(4);
		a.aload(5);
		a.aload(2);
		a.invokestatic(this.rdFlat);
		a.aload(5);
		a.invokevirtual(this.alSize);
		a.istore(6); // n
		a.loadConstant(1);
		a.iload(3);
		a.iadd();
		a.istore(9); // base = 1 + rank (the two CL widths; bfloat16 recomputes it)
		a.iload(0);
		a.ifeq(dbl);
		a.iload(0);
		a.loadConstant(2);
		a.if_icmpeq(bfl);
		// single: float[base + n]
		a.iload(9);
		a.iload(6);
		a.iadd();
		a.newarray(TypeKind.FLOAT);
		a.astore(8);
		a.aload(8);
		a.loadConstant(0);
		a.iload(3);
		a.i2f();
		a.fastore();
		a.loadConstant(0);
		a.istore(7);
		a.labelBinding(floop1);
		a.iload(7);
		a.iload(3);
		a.if_icmpge(fdone1);
		a.aload(8);
		a.loadConstant(1);
		a.iload(7);
		a.iadd();
		a.aload(4);
		a.iload(7);
		a.aaload();
		a.checkcast(this.longClass);
		a.invokevirtual(this.longLongValue);
		a.l2f();
		a.fastore();
		a.iinc(7, 1);
		a.goto_(floop1);
		a.labelBinding(fdone1);
		a.loadConstant(0);
		a.istore(7);
		a.labelBinding(floop2);
		a.iload(7);
		a.iload(6);
		a.if_icmpge(fdone2);
		a.aload(8);
		a.iload(9);
		a.iload(7);
		a.iadd();
		a.aload(5);
		a.iload(7);
		a.invokevirtual(this.alGet);
		a.invokestatic(this.rdF);
		a.d2f();
		a.fastore();
		a.iinc(7, 1);
		a.goto_(floop2);
		a.labelBinding(fdone2);
		a.aload(8);
		a.areturn();
		a.labelBinding(bfl);
		// bfloat16: short[base + n] with base = 1 + 2 * rank; every header word and
		// element goes through JvmPackedFloatWidth.BFLOAT16 / _bf16Bits.
		a.iload(3);
		JvmPackedFloatWidth.BFLOAT16.emitDataOffset(a);
		a.istore(9);
		a.iload(9);
		a.iload(6);
		a.iadd();
		a.newarray(TypeKind.SHORT);
		a.astore(8);
		a.aload(8);
		a.iload(3);
		JvmPackedFloatWidth.BFLOAT16.storeRank(a);
		a.loadConstant(0);
		a.istore(7);
		a.labelBinding(bloop1);
		a.iload(7);
		a.iload(3);
		a.if_icmpge(bdone1);
		a.aload(4);
		a.iload(7);
		a.aaload();
		a.checkcast(this.longClass);
		a.invokevirtual(this.longLongValue);
		a.l2i();
		a.istore(10);
		JvmPackedFloatWidth.BFLOAT16.storeDim(a, 8, 7, 10);
		a.iinc(7, 1);
		a.goto_(bloop1);
		a.labelBinding(bdone1);
		a.loadConstant(0);
		a.istore(7);
		a.labelBinding(bloop2);
		a.iload(7);
		a.iload(6);
		a.if_icmpge(bdone2);
		a.aload(8);
		a.iload(9);
		a.iload(7);
		a.iadd();
		a.aload(5);
		a.iload(7);
		a.invokevirtual(this.alGet);
		a.invokestatic(this.rdF);
		JvmPackedFloatWidth.BFLOAT16.storeElem(a, this.bf16Bits);
		a.iinc(7, 1);
		a.goto_(bloop2);
		a.labelBinding(bdone2);
		a.aload(8);
		a.areturn();
		a.labelBinding(dbl);
		// double: double[base + n]
		a.iload(9);
		a.iload(6);
		a.iadd();
		a.newarray(TypeKind.DOUBLE);
		a.astore(8);
		a.aload(8);
		a.loadConstant(0);
		a.iload(3);
		a.i2d();
		a.dastore();
		a.loadConstant(0);
		a.istore(7);
		a.labelBinding(dloop1);
		a.iload(7);
		a.iload(3);
		a.if_icmpge(ddone1);
		a.aload(8);
		a.loadConstant(1);
		a.iload(7);
		a.iadd();
		a.aload(4);
		a.iload(7);
		a.aaload();
		a.checkcast(this.longClass);
		a.invokevirtual(this.longLongValue);
		a.l2d();
		a.dastore();
		a.iinc(7, 1);
		a.goto_(dloop1);
		a.labelBinding(ddone1);
		a.loadConstant(0);
		a.istore(7);
		a.labelBinding(dloop2);
		a.iload(7);
		a.iload(6);
		a.if_icmpge(ddone2);
		a.aload(8);
		a.iload(9);
		a.iload(7);
		a.iadd();
		a.aload(5);
		a.iload(7);
		a.invokevirtual(this.alGet);
		a.invokestatic(this.rdF);
		a.dastore();
		a.iinc(7, 1);
		a.goto_(dloop2);
		a.labelBinding(ddone2);
		a.aload(8);
		a.areturn();
		return a;
	}

	// _readStruct: cursor just past "#S(". Parses the type name and the slot name/value
	// pairs, resolves the layout in the baked _rdStructs directory, applies the fold's
	// rules (slot designators coerced like CLHS 2.4.8.13's (string slot), leftmost
	// repeated slot wins, :allow-other-keys licensing unknown slots, an omitted slot
	// taking its nil/baked-constant initform or signals), and builds the
	// Object[]{layout, v1..vn} instance -- the
	// exact shape %obj-new emits. Without the instance gate no defstruct exists, so any
	// #S(...) resolves to the "not a defined structure type" error.
	private MethodCode buildReadStruct() {
		MethodCode a = new MethodCode();
		MethodCode.Label ne1 = a.newLabel();
		MethodCode.Label ne2 = a.newLabel();
		MethodCode.Label goodName = a.newLabel();
		MethodCode.Label badName = a.newLabel();
		MethodCode.Label unq = a.newLabel();
		MethodCode.Label splitDone = a.newLabel();
		MethodCode.Label errNoType = a.newLabel();
		pos(a);
		srcLen(a);
		a.if_icmplt(ne1);
		markEof(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(ne1);
		charAtPos(a);
		a.loadConstant(')');
		a.if_icmpne(ne2);
		advance(a);
		err(a, "#S(): a structure literal needs a type name");
		a.aconst_null();
		a.areturn();
		a.labelBinding(ne2);
		a.invokestatic(this.readExpr);
		a.astore(16); // nmObj
		a.aload(16);
		a.instanceOf(this.stringClass);
		a.ifeq(badName);
		a.aload(16);
		a.checkcast(this.stringClass);
		ldc(a, "\"");
		a.invokevirtual(this.stringStartsWith);
		a.ifeq(goodName);
		a.labelBinding(badName);
		sbNew(a, "#S: expected a structure type name, got ");
		a.aload(16);
		a.invokestatic(this.lispToString);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(goodName);
		a.aload(16);
		a.checkcast(this.stringClass);
		a.astore(0); // name
		a.aload(0);
		a.loadConstant(':');
		a.invokevirtual(this.stringIndexOf);
		a.istore(17); // ci
		a.iload(17);
		a.iflt(unq);
		a.aload(0);
		a.loadConstant(0);
		a.iload(17);
		a.invokevirtual(this.stringSubstring);
		a.astore(2); // tp
		a.aload(0);
		a.aload(0);
		a.loadConstant(':');
		a.invokevirtual(this.stringLastIndexOf);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(this.stringSubstringFrom);
		a.astore(1); // tm
		a.goto_(splitDone);
		a.labelBinding(unq);
		a.aconst_null();
		a.astore(2);
		a.aload(0);
		a.astore(1);
		a.labelBinding(splitDone);
		if (this.rdStructs == null) {
			a.goto_(errNoType);
			a.labelBinding(errNoType);
			emitNoTypeError(a, "");
			return a;
		}
		MethodCode.Label found = a.newLabel();
		MethodCode.Label errClassHint = a.newLabel();
		// pass 1: exact struct match (a qualified spelling against its qualified entry,
		// or an unqualified spelling against an unqualified entry)
		MethodCode.Label p1loop = a.newLabel();
		MethodCode.Label p1next = a.newLabel();
		MethodCode.Label p1end = a.newLabel();
		MethodCode.Label p1qual = a.newLabel();
		a.loadConstant(0);
		a.istore(4);
		a.labelBinding(p1loop);
		a.iload(4);
		a.getstatic(this.rdStructs);
		a.arraylength();
		a.if_icmpge(p1end);
		a.getstatic(this.rdStructs);
		a.iload(4);
		a.aaload();
		a.astore(3); // entry
		a.aload(3);
		a.loadConstant(1);
		a.aaload();
		a.aload(1);
		a.invokevirtual(this.objectEquals);
		a.ifeq(p1next);
		a.aload(3);
		a.loadConstant(2);
		a.aaload();
		a.ifnull(p1next); // class entry
		a.aload(3);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(this.stringClass);
		a.astore(16); // epkg
		a.aload(16);
		a.invokevirtual(this.stringLength);
		a.ifne(p1qual);
		a.aload(2);
		a.ifnonnull(p1next);
		a.goto_(found);
		a.labelBinding(p1qual);
		a.aload(2);
		a.ifnull(p1next);
		a.aload(16);
		a.aload(2);
		a.invokevirtual(this.objectEquals);
		a.ifeq(p1next);
		a.goto_(found);
		a.labelBinding(p1next);
		a.iinc(4, 1);
		a.goto_(p1loop);
		a.labelBinding(p1end);
		// pass 2: a qualified spelling falls back to an unqualified entry of the same
		// member name (findStructTag's splitQualified fallback)
		MethodCode.Label passClass = a.newLabel();
		MethodCode.Label p2loop = a.newLabel();
		MethodCode.Label p2next = a.newLabel();
		MethodCode.Label p2end = a.newLabel();
		a.aload(2);
		a.ifnull(passClass);
		a.loadConstant(0);
		a.istore(4);
		a.labelBinding(p2loop);
		a.iload(4);
		a.getstatic(this.rdStructs);
		a.arraylength();
		a.if_icmpge(p2end);
		a.getstatic(this.rdStructs);
		a.iload(4);
		a.aaload();
		a.astore(3);
		a.aload(3);
		a.loadConstant(1);
		a.aaload();
		a.aload(1);
		a.invokevirtual(this.objectEquals);
		a.ifeq(p2next);
		a.aload(3);
		a.loadConstant(2);
		a.aaload();
		a.ifnull(p2next);
		a.aload(3);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(this.stringClass);
		a.invokevirtual(this.stringLength);
		a.ifne(p2next);
		a.goto_(found);
		a.labelBinding(p2next);
		a.iinc(4, 1);
		a.goto_(p2loop);
		a.labelBinding(p2end);
		a.labelBinding(passClass);
		// pass 3: a class of that name exists -> the "#S reads defstruct types only"
		// hint, matching the fold's error
		MethodCode.Label p3loop = a.newLabel();
		MethodCode.Label p3next = a.newLabel();
		MethodCode.Label p3end = a.newLabel();
		a.loadConstant(0);
		a.istore(4);
		a.labelBinding(p3loop);
		a.iload(4);
		a.getstatic(this.rdStructs);
		a.arraylength();
		a.if_icmpge(p3end);
		a.getstatic(this.rdStructs);
		a.iload(4);
		a.aaload();
		a.astore(3);
		a.aload(3);
		a.loadConstant(1);
		a.aaload();
		a.aload(1);
		a.invokevirtual(this.objectEquals);
		a.ifeq(p3next);
		a.aload(3);
		a.loadConstant(2);
		a.aaload();
		a.ifnonnull(p3next);
		a.goto_(errClassHint);
		a.labelBinding(p3next);
		a.iinc(4, 1);
		a.goto_(p3loop);
		a.labelBinding(p3end);
		a.goto_(errNoType);
		a.labelBinding(found);
		a.aload(3);
		a.loadConstant(2);
		a.aaload();
		a.checkcast(this.stringArrayClass);
		a.astore(5); // layout
		a.aload(3);
		a.loadConstant(3);
		a.aaload();
		a.checkcast(this.stringArrayClass);
		a.astore(12); // initTexts
		a.aload(5);
		a.arraylength();
		a.loadConstant(3);
		a.isub();
		a.istore(6); // slotCount
		a.iload(6);
		a.loadConstant(1);
		a.iadd();
		a.anewarray(this.objectClass);
		a.astore(7); // inst
		a.aload(7);
		a.loadConstant(0);
		a.aload(5);
		a.aastore();
		// fill every slot with the layout as an "unset" sentinel: nil values are null,
		// so unset needs a marker no read datum can be (identity to the layout array)
		MethodCode.Label sloop = a.newLabel();
		MethodCode.Label sdone = a.newLabel();
		a.loadConstant(0);
		a.istore(11);
		a.labelBinding(sloop);
		a.iload(11);
		a.iload(6);
		a.if_icmpge(sdone);
		a.aload(7);
		a.loadConstant(1);
		a.iload(11);
		a.iadd();
		a.aload(5);
		a.aastore();
		a.iinc(11, 1);
		a.goto_(sloop);
		a.labelBinding(sdone);
		// slot name/value pairs. Locals 18/19/20 are the :allow-other-keys state (seen,
		// licensing) and the first unknown slot's display spelling, reported at the end
		// when nothing licensed it -- recording instead of signalling keeps the single
		// pass order-independent, since the marker may follow the unknown slot.
		MethodCode.Label pairLoop = a.newLabel();
		MethodCode.Label pl1 = a.newLabel();
		MethodCode.Label pl2 = a.newLabel();
		MethodCode.Label badSlot = a.newLabel();
		MethodCode.Label isCharSlot = a.newLabel();
		MethodCode.Label haveSlotName = a.newLabel();
		MethodCode.Label pv1 = a.newLabel();
		MethodCode.Label pv2 = a.newLabel();
		MethodCode.Label notAok = a.newLabel();
		MethodCode.Label aokNil = a.newLabel();
		MethodCode.Label kloop = a.newLabel();
		MethodCode.Label knext = a.newLabel();
		MethodCode.Label kdone = a.newLabel();
		MethodCode.Label haveIdx = a.newLabel();
		MethodCode.Label fillDefaults = a.newLabel();
		MethodCode.Label fillInit = a.newLabel();
		a.loadConstant(0);
		a.istore(18);
		a.loadConstant(0);
		a.istore(19);
		a.aconst_null();
		a.astore(20);
		a.labelBinding(pairLoop);
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.if_icmplt(pl1);
		markEof(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(pl1);
		charAtPos(a);
		a.loadConstant(')');
		a.if_icmpne(pl2);
		advance(a);
		a.goto_(fillDefaults);
		a.labelBinding(pl2);
		a.invokestatic(this.readExpr);
		a.astore(8); // snObj, normalized to a String display spelling below
		a.aload(8);
		a.instanceOf(this.stringClass);
		a.ifeq(isCharSlot);
		a.aload(8);
		a.checkcast(this.stringClass);
		ldc(a, "\"");
		a.invokevirtual(this.stringStartsWith);
		a.ifeq(haveSlotName);
		// A string designator spells the name with _readStr's quote wrapping: strip it.
		a.aload(8);
		a.checkcast(this.stringClass);
		a.loadConstant(1);
		a.aload(8);
		a.checkcast(this.stringClass);
		a.invokevirtual(this.stringLength);
		a.loadConstant(1);
		a.isub();
		a.invokevirtual(this.stringSubstring);
		a.astore(8);
		a.goto_(haveSlotName);
		a.labelBinding(isCharSlot);
		// A character designator is the one-code-point int[] _readCharLit boxes.
		a.aload(8);
		a.instanceOf(this.intArrayClass);
		a.ifeq(badSlot);
		a.aload(8);
		a.checkcast(this.intArrayClass);
		a.arraylength();
		a.loadConstant(1);
		a.if_icmpne(badSlot);
		a.aload(8);
		a.checkcast(this.intArrayClass);
		a.loadConstant(0);
		a.iaload();
		a.invokestatic(this.charToString);
		a.astore(8);
		a.labelBinding(haveSlotName);
		a.invokestatic(this.readSkipWs);
		pos(a);
		srcLen(a);
		a.if_icmplt(pv1);
		markEof(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(badSlot);
		sbNew(a, "#S(");
		a.aload(0);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " ...): expected a slot name, got ");
		a.aload(8);
		a.invokestatic(this.lispToString);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(pv1);
		charAtPos(a);
		a.loadConstant(')');
		a.if_icmpne(pv2);
		sbNew(a, "#S(");
		a.aload(0);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " ...): odd number of slot name/value items in a structure literal");
		sbThrow(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(pv2);
		a.invokestatic(this.readExpr);
		a.astore(9); // value
		a.aload(8);
		a.checkcast(this.stringClass);
		a.invokestatic(this.rdName);
		a.astore(13); // base name
		// :allow-other-keys is never a slot: the leftmost pair naming it decides, and a
		// non-nil value licenses every other unknown slot.
		a.aload(13);
		ldc(a, "ALLOW-OTHER-KEYS");
		a.invokevirtual(this.stringEqualsIgnoreCase);
		a.ifeq(notAok);
		a.iload(18);
		a.ifne(pairLoop);
		a.loadConstant(1);
		a.istore(18);
		a.aload(9);
		a.ifnull(aokNil);
		a.loadConstant(1);
		a.istore(19);
		a.goto_(pairLoop);
		a.labelBinding(aokNil);
		a.loadConstant(0);
		a.istore(19);
		a.goto_(pairLoop);
		a.labelBinding(notAok);
		a.loadConstant(-1);
		a.istore(10); // idx
		a.loadConstant(0);
		a.istore(11);
		a.labelBinding(kloop);
		a.iload(11);
		a.iload(6);
		a.if_icmpge(kdone);
		a.aload(5);
		a.loadConstant(3);
		a.iload(11);
		a.iadd();
		a.aaload();
		a.aload(13);
		a.invokevirtual(this.objectEquals);
		a.ifeq(knext);
		a.iload(11);
		a.istore(10);
		a.goto_(kdone);
		a.labelBinding(knext);
		a.iinc(11, 1);
		a.goto_(kloop);
		a.labelBinding(kdone);
		a.iload(10);
		a.ifge(haveIdx);
		// An unknown slot is recorded, not signalled: a later :allow-other-keys may
		// license it, and only the first one is ever reported.
		a.aload(20);
		a.ifnonnull(pairLoop);
		a.aload(8);
		a.astore(20);
		a.goto_(pairLoop);
		a.labelBinding(haveIdx);
		// leftmost wins: store only while the slot still holds the sentinel
		a.aload(7);
		a.loadConstant(1);
		a.iload(10);
		a.iadd();
		a.aaload();
		a.aload(5);
		a.if_acmpne(pairLoop);
		a.aload(7);
		a.loadConstant(1);
		a.iload(10);
		a.iadd();
		a.aload(9);
		a.aastore();
		a.goto_(pairLoop);
		a.labelBinding(fillDefaults);
		a.iload(19);
		a.ifne(fillInit);
		a.aload(20);
		a.ifnull(fillInit);
		sbNew(a, "#S(");
		a.aload(0);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " ...): ");
		a.aload(5);
		a.loadConstant(1);
		a.aaload();
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " has no slot named ");
		a.aload(20);
		a.checkcast(this.stringClass);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconst_null();
		a.areturn();
		a.labelBinding(fillInit);
		MethodCode.Label floop = a.newLabel();
		MethodCode.Label fnext = a.newLabel();
		MethodCode.Label fdone = a.newLabel();
		MethodCode.Label setNil = a.newLabel();
		MethodCode.Label parseText = a.newLabel();
		a.loadConstant(0);
		a.istore(11);
		a.labelBinding(floop);
		a.iload(11);
		a.iload(6);
		a.if_icmpge(fdone);
		a.aload(7);
		a.loadConstant(1);
		a.iload(11);
		a.iadd();
		a.aaload();
		a.aload(5);
		a.if_acmpne(fnext);
		a.aload(12);
		a.ifnull(setNil);
		a.aload(12);
		a.iload(11);
		a.aaload();
		a.astore(13); // initform action
		a.aload(13);
		a.ifnull(setNil);
		a.aload(13);
		a.loadConstant(0);
		a.invokevirtual(this.stringCharAt);
		a.loadConstant(EmittedReaderInitforms.SIGNAL_MARKER);
		a.if_icmpne(parseText);
		a.aload(13);
		a.loadConstant(1);
		a.invokevirtual(this.stringSubstringFrom);
		a.invokestatic(this.rdErr);
		a.aconst_null();
		a.areturn();
		a.labelBinding(parseText);
		// re-read the baked constant text in place (save/restore the reader state)
		a.getstatic(this.readSrc);
		a.astore(14);
		a.getstatic(this.readPos);
		a.istore(15);
		a.aload(13);
		a.putstatic(this.readSrc);
		a.loadConstant(0);
		a.putstatic(this.readPos);
		a.invokestatic(this.readExpr);
		a.astore(9);
		a.aload(14);
		a.putstatic(this.readSrc);
		a.iload(15);
		a.putstatic(this.readPos);
		a.aload(7);
		a.loadConstant(1);
		a.iload(11);
		a.iadd();
		a.aload(9);
		a.aastore();
		a.goto_(fnext);
		a.labelBinding(setNil);
		a.aload(7);
		a.loadConstant(1);
		a.iload(11);
		a.iadd();
		a.aconst_null();
		a.aastore();
		a.labelBinding(fnext);
		a.iinc(11, 1);
		a.goto_(floop);
		a.labelBinding(fdone);
		a.aload(7);
		a.areturn();
		a.labelBinding(errClassHint);
		emitNoTypeError(a, " (it names a class; #S reads defstruct types only)");
		a.labelBinding(errNoType);
		emitNoTypeError(a, "");
		return a;
	}

	/** Emits the "#S(NAME ...): NAME is not a defined structure type" throw. */
	private void emitNoTypeError(MethodCode a, String suffix) {
		sbNew(a, "#S(");
		a.aload(0);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " ...): ");
		a.aload(0);
		a.invokevirtual(this.sbAppendStr);
		sbText(a, " is not a defined structure type" + suffix);
		sbThrow(a);
		a.aconst_null();
		a.areturn();
	}

	// _rdLen(list, label): proper-list length; an improper tail signals.
	private MethodCode buildRdLen() {
		MethodCode a = new MethodCode();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label isCons = a.newLabel();
		a.loadConstant(0);
		a.istore(2);
		a.aload(0);
		a.astore(3);
		a.labelBinding(loop);
		a.aload(3);
		a.ifnull(done);
		a.aload(3);
		a.invokestatic(this.rdConsp);
		a.ifne(isCons);
		a.new_(this.stringBuilderClass);
		a.dup();
		a.aload(1);
		a.invokespecial(this.sbInitStr);
		sbText(a, ": contents must be proper lists");
		sbThrow(a);
		a.loadConstant(0);
		a.ireturn();
		a.labelBinding(isCons);
		a.iinc(2, 1);
		a.aload(3);
		a.checkcast(this.objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.astore(3);
		a.goto_(loop);
		a.labelBinding(done);
		a.iload(2);
		a.ireturn();
		return a;
	}

	// _rdConsp: the runtime cons test (an Object[] that is not a ratio, closure or
	// instance), mirroring the consp predicate's discriminators.
	private MethodCode buildRdConsp() {
		MethodCode a = new MethodCode();
		MethodCode.Label no = a.newLabel();
		a.aload(0);
		a.instanceOf(this.objectArrayClass);
		a.ifeq(no);
		a.aload(0);
		a.instanceOf(this.bigIntegerArrayClass);
		a.ifne(no);
		a.aload(0);
		a.checkcast(this.objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.instanceOf(this.integerClass);
		a.ifne(no);
		a.aload(0);
		a.checkcast(this.objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.instanceOf(this.stringArrayClass);
		a.ifne(no);
		a.loadConstant(1);
		a.ireturn();
		a.labelBinding(no);
		a.loadConstant(0);
		a.ireturn();
		return a;
	}

	// _rdLevel(v, label): one nested level of array contents -- nil or a proper list;
	// anything else is the frontend's "expected a nested list" error.
	private MethodCode buildRdLevel() {
		MethodCode a = new MethodCode();
		MethodCode.Label nn = a.newLabel();
		MethodCode.Label bad = a.newLabel();
		a.aload(0);
		a.ifnonnull(nn);
		a.aconst_null();
		a.areturn();
		a.labelBinding(nn);
		a.aload(0);
		a.invokestatic(this.rdConsp);
		a.ifeq(bad);
		a.aload(0);
		a.areturn();
		a.labelBinding(bad);
		a.new_(this.stringBuilderClass);
		a.dup();
		a.aload(1);
		a.invokespecial(this.sbInitStr);
		sbText(a, ": expected a nested list, got ");
		a.aload(0);
		a.invokestatic(this.lispToString);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.aconst_null();
		a.areturn();
		return a;
	}

	// _rdDims(rows, rank, label): dimension sizes from the first-element chain, as the
	// Object[]-of-Long shape the array header stores.
	private MethodCode buildRdDims() {
		MethodCode a = new MethodCode();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label lvlNull = a.newLabel();
		MethodCode.Label lvlSet = a.newLabel();
		a.iload(1);
		a.anewarray(this.objectClass);
		a.astore(3);
		a.aload(3);
		a.loadConstant(0);
		a.aload(0);
		a.aload(2);
		a.invokestatic(this.rdLen);
		a.i2l();
		a.invokestatic(this.longValueOf);
		a.aastore();
		a.aload(0);
		a.astore(5);
		a.loadConstant(1);
		a.istore(4);
		a.labelBinding(loop);
		a.iload(4);
		a.iload(1);
		a.if_icmpge(done);
		a.aload(5);
		a.ifnull(lvlNull);
		a.aload(5);
		a.checkcast(this.objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.aload(2);
		a.invokestatic(this.rdLevel);
		a.astore(5);
		a.goto_(lvlSet);
		a.labelBinding(lvlNull);
		a.aconst_null();
		a.astore(5);
		a.labelBinding(lvlSet);
		a.aload(3);
		a.iload(4);
		a.aload(5);
		a.aload(2);
		a.invokestatic(this.rdLen);
		a.i2l();
		a.invokestatic(this.longValueOf);
		a.aastore();
		a.iinc(4, 1);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(3);
		a.areturn();
		return a;
	}

	// _rdFlat(items, depth, dims, out, label): validates one level against dims and
	// appends the leaves to `out` in row-major order, recursing into nested levels.
	private MethodCode buildRdFlat() {
		MethodCode a = new MethodCode();
		MethodCode.Label okCount = a.newLabel();
		MethodCode.Label deeper = a.newLabel();
		MethodCode.Label aloop = a.newLabel();
		MethodCode.Label retv = a.newLabel();
		MethodCode.Label bloop = a.newLabel();
		MethodCode.Label retv2 = a.newLabel();
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
		a.i2l();
		a.lcmp();
		a.ifeq(okCount);
		a.new_(this.stringBuilderClass);
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
		a.return_();
		a.labelBinding(okCount);
		a.iload(1);
		a.aload(2);
		a.arraylength();
		a.loadConstant(1);
		a.isub();
		a.if_icmpne(deeper);
		a.aload(0);
		a.astore(6);
		a.labelBinding(aloop);
		a.aload(6);
		a.ifnull(retv);
		a.aload(3);
		a.aload(6);
		a.checkcast(this.objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.invokevirtual(this.alAdd);
		a.pop();
		a.aload(6);
		a.checkcast(this.objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.astore(6);
		a.goto_(aloop);
		a.labelBinding(retv);
		a.return_();
		a.labelBinding(deeper);
		a.aload(0);
		a.astore(6);
		a.labelBinding(bloop);
		a.aload(6);
		a.ifnull(retv2);
		a.aload(6);
		a.checkcast(this.objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.aload(4);
		a.invokestatic(this.rdLevel);
		a.iload(1);
		a.loadConstant(1);
		a.iadd();
		a.aload(2);
		a.aload(3);
		a.aload(4);
		a.invokestatic(this.rdFlat);
		a.aload(6);
		a.checkcast(this.objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.astore(6);
		a.goto_(bloop);
		a.labelBinding(retv2);
		a.return_();
		return a;
	}

	// _rdErr(message): throw the reader error; a RuntimeException is what every emitted
	// runtime helper throws, so handler-case catches it as a simple-error.
	private MethodCode buildRdErr() {
		MethodCode a = new MethodCode();
		a.new_(this.rtExClass);
		a.dup();
		a.aload(0);
		a.invokespecial(this.rtExInit);
		a.athrow();
		return a;
	}

	// _rdName(spelled): the package-stripped base name with the keyword marker dropped
	// (:X, X and PKG::X all name slot X), mirroring the fold's slotIndexOf.
	private MethodCode buildRdName() {
		MethodCode a = new MethodCode();
		MethodCode.Label n1 = a.newLabel();
		MethodCode.Label strip = a.newLabel();
		MethodCode.Label retn = a.newLabel();
		a.aload(0);
		ldc(a, "#:");
		a.invokevirtual(this.stringStartsWith);
		a.ifeq(n1);
		a.aload(0);
		a.loadConstant(2);
		a.invokevirtual(this.stringSubstringFrom);
		a.astore(0);
		a.goto_(strip);
		a.labelBinding(n1);
		a.aload(0);
		a.invokevirtual(this.stringLength);
		a.ifeq(retn);
		a.aload(0);
		a.loadConstant(0);
		a.invokevirtual(this.stringCharAt);
		a.loadConstant(':');
		a.if_icmpne(strip);
		a.aload(0);
		a.loadConstant(1);
		a.invokevirtual(this.stringSubstringFrom);
		a.astore(0);
		a.labelBinding(strip);
		a.aload(0);
		a.loadConstant(':');
		a.invokevirtual(this.stringLastIndexOf);
		a.istore(1);
		a.iload(1);
		a.iflt(retn);
		a.aload(0);
		a.iload(1);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(this.stringSubstringFrom);
		a.astore(0);
		a.labelBinding(retn);
		a.aload(0);
		a.areturn();
		return a;
	}

	// _rdF(leaf): coerce a packed-float leaf to double (integer, double, big integer or
	// ratio), or the frontend's "expected a number" error.
	private MethodCode buildRdF() {
		MethodCode a = new MethodCode();
		MethodCode.Label f1 = a.newLabel();
		MethodCode.Label f2 = a.newLabel();
		MethodCode.Label f3 = a.newLabel();
		MethodCode.Label f4 = a.newLabel();
		a.aload(0);
		a.instanceOf(this.longClass);
		a.ifeq(f1);
		a.aload(0);
		a.checkcast(this.longClass);
		a.invokevirtual(this.longLongValue);
		a.l2d();
		a.dreturn();
		a.labelBinding(f1);
		a.aload(0);
		a.instanceOf(this.doubleClass);
		a.ifeq(f2);
		a.aload(0);
		a.checkcast(this.doubleClass);
		a.invokevirtual(this.doubleDoubleValue);
		a.dreturn();
		a.labelBinding(f2);
		a.aload(0);
		a.instanceOf(this.bigIntegerClass);
		a.ifeq(f3);
		a.aload(0);
		a.checkcast(this.bigIntegerClass);
		a.invokevirtual(this.bigIntegerDoubleValue);
		a.dreturn();
		a.labelBinding(f3);
		a.aload(0);
		a.instanceOf(this.bigIntegerArrayClass);
		a.ifeq(f4);
		a.aload(0);
		a.checkcast(this.bigIntegerArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.invokevirtual(this.bigIntegerDoubleValue);
		a.aload(0);
		a.checkcast(this.bigIntegerArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.invokevirtual(this.bigIntegerDoubleValue);
		a.ddiv();
		a.dreturn();
		a.labelBinding(f4);
		sbNew(a, "packed float array: expected a number, got ");
		a.aload(0);
		a.invokestatic(this.lispToString);
		a.invokevirtual(this.sbAppendStr);
		sbThrow(a);
		a.dconst_0();
		a.dreturn();
		return a;
	}

	// _rdInferRank(rows): 1 + the depth of the first-element chain, numpy style,
	// mirroring the frontend's inferFloatArrayRank.
	private MethodCode buildRdInferRank() {
		MethodCode a = new MethodCode();
		MethodCode.Label nn = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label notNil = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(0);
		a.ifnonnull(nn);
		a.loadConstant(1);
		a.ireturn();
		a.labelBinding(nn);
		a.aload(0);
		a.checkcast(this.objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.astore(1); // probe
		a.loadConstant(1);
		a.istore(2); // rank
		a.labelBinding(loop);
		a.aload(1);
		a.ifnonnull(notNil);
		a.iinc(2, 1);
		a.goto_(done);
		a.labelBinding(notNil);
		a.aload(1);
		a.invokestatic(this.rdConsp);
		a.ifeq(done);
		a.iinc(2, 1);
		a.aload(1);
		a.checkcast(this.objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.astore(1);
		a.goto_(loop);
		a.labelBinding(done);
		a.iload(2);
		a.ireturn();
		return a;
	}

	private MethodCode buildLoad() {
		MethodCode a = new MethodCode();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label bad = a.newLabel();
		MethodCode.Label unmatched = a.newLabel();
		MethodCode.Label notBefore = a.newLabel();
		MethodCode.Label stray = a.newLabel();
		MethodRefEntry eval = java.util.Objects.requireNonNull(this.evalRef);
		MethodRefEntry paths = java.util.Objects.requireNonNull(this.pathsGet);
		MethodRefEntry files = java.util.Objects.requireNonNull(this.filesReadString);
		// path = ((String) pathVal).substring(1, len-1)
		a.aload(0);
		a.checkcast(this.stringClass);
		a.astore(1);
		a.aload(1);
		a.loadConstant(1);
		a.aload(1);
		a.invokevirtual(this.stringLength);
		a.loadConstant(1);
		a.isub();
		a.invokevirtual(this.stringSubstring);
		a.astore(2); // path
		// content = Files.readString(Paths.get(path, new String[0]))
		a.aload(2);
		a.loadConstant(0);
		a.anewarray(this.stringClass);
		a.invokestatic(paths);
		a.invokestatic(files);
		a.putstatic(this.readSrc);
		a.loadConstant(0);
		a.putstatic(this.readPos);
		a.loadConstant(0);
		a.putstatic(this.readFail);
		a.labelBinding(loop);
		a.invokestatic(this.readSkipWs);
		// a form the text ends inside of (an unterminated #| included) or a ')' that
		// closes nothing is refused before anything of it is evaluated
		a.getstatic(this.readFail);
		a.ifne(bad);
		pos(a);
		srcLen(a);
		a.if_icmpge(done);
		a.invokestatic(this.readExpr);
		a.astore(3);
		a.getstatic(this.readFail);
		a.ifne(bad);
		a.aload(3);
		a.aconst_null();
		a.invokestatic(eval);
		a.pop();
		a.goto_(loop);
		a.labelBinding(bad);
		a.getstatic(this.readFail);
		a.loadConstant(1);
		a.if_icmpne(unmatched);
		err(a, ClosRegistry.END_OF_FILE_MESSAGE);
		a.aconst_null();
		a.areturn();
		a.labelBinding(unmatched);
		a.getstatic(this.readFail);
		a.loadConstant(ReadFailure.ERROR_MASK);
		a.iand();
		a.loadConstant(ReadFailure.NOTHING_BEFORE_DOT);
		a.if_icmpne(notBefore);
		err(a, ReadFailure.NOTHING_BEFORE_DOT_MESSAGE);
		a.aconst_null();
		a.areturn();
		a.labelBinding(notBefore);
		a.getstatic(this.readFail);
		a.loadConstant(ReadFailure.ERROR_MASK);
		a.iand();
		a.loadConstant(ReadFailure.MORE_THAN_ONE_AFTER_DOT);
		a.if_icmpne(stray);
		err(a, ReadFailure.MORE_THAN_ONE_AFTER_DOT_MESSAGE);
		a.aconst_null();
		a.areturn();
		a.labelBinding(stray);
		err(a, ReadFailure.UNMATCHED_CLOSE_MESSAGE);
		a.aconst_null();
		a.areturn();
		a.labelBinding(done);
		// t is the symbol "T" (the compiled runtime's true, like every other
		// symbol), NOT the integer 1 -- the interpreter's load answers t.
		ldc(a, "T");
		a.areturn();
		return a;
	}

}
