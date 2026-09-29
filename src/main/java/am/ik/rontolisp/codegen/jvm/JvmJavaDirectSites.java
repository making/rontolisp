package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaField;
import am.ik.rontolisp.compiler.JavaImplementationType;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaOverloads;
import am.ik.rontolisp.compiler.JavaSite;
import am.ik.rontolisp.compiler.JavaStaticType;
import am.ik.rontolisp.compiler.JavaType;
import am.ik.rontolisp.compiler.OperandTypes;
import am.ik.rontolisp.runtime.RontoComplex;
import am.ik.rontolisp.runtime.RontoHashTable;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java:} sites of one compile attempt that resolved before they run, each
 * compiled to a DIRECT call ({@code .kb/java-interop.md}, "Direct calls"): one private
 * static method per site shape, {@code _jsite$N}, which the site passes its evaluated
 * receiver and arguments. The method checks the receiver and every argument exactly as
 * the interpreter's {@code JavaInterop.invokeResolved} does -- same order, same messages
 * -- converts each argument to its parameter type inline, calls the member with
 * {@code invokevirtual} / {@code invokeinterface} / {@code invokestatic} / {@code new} +
 * {@code invokespecial} / {@code getfield} / {@code getstatic}, and converts the value
 * back as the bridge's {@code unmarshal} would. Nothing in it reflects, so a class whose
 * sites are all direct carries no bridge and needs no reachability metadata under GraalVM
 * native-image.
 * <p>
 * A value is checked by its KIND ({@link JavaKind}), computed from the compiled
 * representation as the bridge's {@code kindOf} computes it; the conversions are the
 * bridge's {@code convert} and {@code convertLong}, one arm per (kind, parameter type)
 * the resolution counted on. A function value where an interface is expected becomes the
 * interface's generated proxy class ({@link JvmJavaImplementations}), as the bridge makes
 * it a {@code java.lang.reflect.Proxy}: nothing here reflects.
 * <p>
 * A DISPATCHED site -- its class known, its argument kinds only when it runs -- chooses
 * among its overloads as the interpreter does ({@code JavaOverloads.selectRanked}): each
 * argument's cost for each parameter type ({@code _jcost$N}, over the value's
 * classification {@code _jkind} and a sequence's elements {@code _jseq}), the first
 * cheapest overload, and one arm per overload that converts the arguments
 * ({@code _jconv$N}) and calls it directly.
 * <p>
 * Three shared helpers are emitted beside the sites when one needs them: {@code _jhost}
 * (the bridge's {@code isJavaObject}), {@code _junm} (its {@code unmarshal}, for a value
 * declared as a type a box, a string or an array may hide behind) and {@code _jarr} (its
 * {@code arrayToList}, over every array type without {@code java.lang.reflect.Array}).
 * <p>
 * What a site's Java call throws goes through {@code _jfail}: a throwable a function
 * called back from Java raised -- recorded on its way out by {@code _jsig}, which the
 * generated implementations and the bridge's {@code Proxy} call -- is thrown on as it is,
 * anything else as the error calling the member ({@link #finishHelpers}).
 */
final class JvmJavaDirectSites {

	/** The prefix of a direct site's method name. */
	static final String SITE_PREFIX = "_jsite$";

	/** {@code _jhost(Object)Z}: whether a value is a wrapped host object. */
	static final String HOST = "_jhost";

	/**
	 * {@code _jlarr(Object)Z}: whether a value is a Lisp array -- an {@code ArrayList}
	 * whose slot 0 is its {@code Object[]} header, not a host list a call answered.
	 */
	static final String LISP_ARRAY = "_jlarr";

	/**
	 * {@code _jltab(Object)Z}: whether a value is a Lisp hash table -- a
	 * {@code LinkedHashMap} holding its insertion-order {@code ArrayList} under
	 * {@link RontoHashTable#ORDER_KEY}, not a host map a call answered.
	 */
	static final String LISP_TABLE = "_jltab";

	/**
	 * {@code _jckarr(Object, String)Object}: an array accessor's operand, answered
	 * unchanged unless it is a host {@code ArrayList}, which is refused with the
	 * interpreter's {@code ARRAY} type-error, named after the operator the second
	 * argument spells (null for an unnamed report).
	 */
	static final String ARRAY_GUARD = "_jckarr";

	/**
	 * {@code _jcktab(Object, String)Object}: a hash-table accessor's operand, answered
	 * unchanged unless it is a host {@code LinkedHashMap}, which is refused with the
	 * interpreter's {@code HASH-TABLE} type-error, named as {@link #ARRAY_GUARD} names
	 * its.
	 */
	static final String TABLE_GUARD = "_jcktab";

	/** {@code _junm(Object)Object}: a Java value as the Lisp value it stands for. */
	static final String UNMARSHAL = "_junm";

	/** {@code _jarr(Object)Object}: a Java array as a Lisp list, anything else itself. */
	static final String ARRAY_TO_LIST = "_jarr";

	/**
	 * {@code _jkind(Object)I}: a value's classification, one of the {@code KIND_} codes.
	 */
	static final String KIND = "_jkind";

	/** {@code _jseq(Object)Object[]}: the elements of a proper list or rank-1 vector. */
	static final String SEQUENCE = "_jseq";

	/** The prefix of {@code _jcost$N(Object)I}: a value's cost for one parameter type. */
	static final String COST_PREFIX = "_jcost$";

	/**
	 * The prefix of {@code _jconv$N(Object)T}: a value converted to one parameter type.
	 */
	static final String CONVERT_PREFIX = "_jconv$";

	/**
	 * {@code _jfail(Throwable, String)Throwable}: what a site throws when its Java call
	 * throws -- what a function called back from Java raised, as it is, or else the error
	 * calling the member, the given text before the throwable's. The bridge's
	 * {@code fail} calls it too.
	 */
	static final String FAIL = "_jfail";

	/**
	 * {@code _jsig(Throwable)Throwable}: records a throwable leaving a function called
	 * back from Java -- a generated implementation's callback, the bridge's {@code Proxy}
	 * -- for {@link #FAIL}, and answers it.
	 */
	static final String SIGNAL = "_jsig";

	/** The per-thread record {@link #SIGNAL} keeps and {@link #FAIL} reads. */
	static final String SIGNALS = "_jsigTl";

	private static final String FAIL_DESC = "(Ljava/lang/Throwable;Ljava/lang/String;)Ljava/lang/Throwable;";

	private static final String SIGNAL_DESC = "(Ljava/lang/Throwable;)Ljava/lang/Throwable;";

	// _jkind's codes: a Lisp kind's is its index in LISP_KINDS (its ordinal), then the
	// four below.
	private static final JavaKind.Lisp[] LISP_KINDS = JavaKind.Lisp.values();

	private static final int KIND_CONS = LISP_KINDS.length;

	private static final int KIND_ARRAY = KIND_CONS + 1;

	private static final int KIND_HOST = KIND_CONS + 2;

	private static final int KIND_NONE = KIND_CONS + 3;

	private static final String OBJECT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String GUARD_DESC = "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;";

	/**
	 * The package of the classes that travel with a compiled program; a value of one
	 * ({@code RontoComplex}) is a Lisp value, never a host object.
	 */
	static final String RUNTIME_PACKAGE_PREFIX = RontoComplex.class.getPackageName() + ".";

	/**
	 * Past this many values a site's method takes them in one {@code Object[]}: a method
	 * has at most 255 parameter slots.
	 */
	static final int MAX_SPREAD = 200;

	/**
	 * A method the direct sites add to the class.
	 *
	 * @param name its name
	 * @param desc its descriptor
	 * @param code the body, its handlers included
	 */
	record Method(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	private final ConstantPool cp;

	private final ClassEntry thisClass;

	private final JavaClassLookup lookup;

	private final MethodRefEntry lispToString;

	private final Map<String, MethodRefEntry> sites = new LinkedHashMap<>();

	private final List<Method> methods = new ArrayList<>();

	private @Nullable MethodRefEntry host;

	private @Nullable MethodRefEntry lispArray;

	private @Nullable MethodRefEntry lispTable;

	private @Nullable MethodRefEntry arrayGuard;

	private @Nullable MethodRefEntry tableGuard;

	private @Nullable MethodRefEntry unmarshal;

	private @Nullable MethodRefEntry arrayToList;

	private @Nullable MethodRefEntry kind;

	private @Nullable MethodRefEntry sequence;

	private @Nullable MethodRefEntry strv;

	// The specialized vector shapes the program can hold (its gates), and the program's
	// _bf16Value when a bfloat16 vector can be one of them.
	private boolean floatVectors;

	private boolean intVectors;

	private @Nullable MethodRefEntry bf16Value;

	private final Map<String, MethodRefEntry> costs = new LinkedHashMap<>();

	private @Nullable JvmJavaImplementations implementations;

	private final Map<String, MethodRefEntry> converts = new LinkedHashMap<>();

	// _jfail and _jsig, named when first asked for and built by finishHelpers(), once
	// every body is compiled: what they read depends on the whole program.
	private @Nullable MethodRefEntry failure;

	private @Nullable MethodRefEntry signal;

	private boolean finished;

	/**
	 * The per-thread record of what functions called back from Java raised, a
	 * {@code ThreadLocal} field the class declares and initializes in {@code <clinit>}.
	 *
	 * @param field the field
	 * @param name its name
	 * @param desc its descriptor
	 */
	record Signals(FieldRefEntry field, Utf8Entry name, Utf8Entry desc) {
	}

	/**
	 * @param cp the class's constant pool
	 * @param thisClass the class
	 * @param lookup the classes the sites resolved against
	 * @param lispToString the program's {@code _lispToString}: how a message shows a
	 * value
	 */
	JvmJavaDirectSites(ConstantPool cp, ClassEntry thisClass, JavaClassLookup lookup, MethodRefEntry lispToString) {
		this.cp = cp;
		this.thisClass = thisClass;
		this.lookup = lookup;
		this.lispToString = lispToString;
	}

	/**
	 * Names the program's {@code _strv}, which renders a mutable character vector to the
	 * string it spells: the conversion helpers render every value, a sequence's elements
	 * too, as the bridge's {@code marshal} does.
	 * @param strv the helper, or {@code null} when the program has no arrays
	 */
	void strv(@Nullable MethodRefEntry strv) {
		this.strv = strv;
	}

	/**
	 * Names the specialized vector shapes the program can hold, which a sequence
	 * argument's elements are read from as the bridge's {@code marshal} reads them: a
	 * packed float vector ({@code double[]} / {@code float[]} / bfloat16 {@code short[]},
	 * read through the program's {@code _bf16Value}) and a packed integer vector
	 * ({@code long[]} / octet {@code byte[]}). A shape the program cannot make is never
	 * tested for.
	 * @param floats whether the program carries the packed float runtime
	 * @param ints whether it carries the packed integer runtime
	 * @param bf16Value the program's {@code _bf16Value(I)D}, present when {@code floats}
	 */
	void packedVectors(boolean floats, boolean ints, @Nullable MethodRefEntry bf16Value) {
		if (floats && bf16Value == null) {
			throw new IllegalArgumentException("the packed float runtime without _bf16Value");
		}
		this.floatVectors = floats;
		this.intVectors = ints;
		this.bf16Value = bf16Value;
	}

	/**
	 * @return the methods to add to the class, in the order they were made
	 */
	List<Method> methods() {
		return List.copyOf(this.methods);
	}

	/**
	 * Sets the generated classes a function value where an interface is expected becomes.
	 * @param implementations the attempt's implementations
	 */
	void implementations(JvmJavaImplementations implementations) {
		this.implementations = implementations;
	}

	/**
	 * {@code _junm}: the bridge's {@code unmarshal}, made when first asked for.
	 * @return the helper
	 */
	MethodRefEntry unmarshalHelper() {
		return unmarshal();
	}

	/**
	 * The cost of a value a {@code java:reify} / {@code java:proxy} function answers for
	 * the method's return type: {@code _jcost$N} without the function arm -- a function
	 * is never made a proxy on the way back -- {@code NO_MATCH} (negative) when it does
	 * not convert.
	 * @param target the return type
	 * @return {@code _jcost$N(Object)I}
	 */
	MethodRefEntry returnedCost(JavaType target) {
		return cost(target, false);
	}

	/**
	 * The conversion of such a value to the return type: {@code _jconv$N} with sequences
	 * made arrays or lists and no function arm, for a value {@link #returnedCost}
	 * accepts.
	 * @param target the return type
	 * @return {@code _jconv$N(Object)T}
	 */
	MethodRefEntry returnedConvert(JavaType target) {
		return convert(target, false, true);
	}

	/**
	 * {@code _jsig}: what a generated implementation's callback calls with the throwable
	 * leaving it. Built by {@link #finishHelpers}.
	 * @return {@code _jsig(Throwable)Throwable}
	 */
	MethodRefEntry signalHelper() {
		MethodRefEntry ref = this.signal;
		if (ref == null) {
			ref = unfinishedHelper(SIGNAL, SIGNAL_DESC);
			this.signal = ref;
		}
		return ref;
	}

	private MethodRefEntry failure() {
		MethodRefEntry ref = this.failure;
		if (ref == null) {
			ref = unfinishedHelper(FAIL, FAIL_DESC);
			this.failure = ref;
		}
		return ref;
	}

	private MethodRefEntry unfinishedHelper(String name, String desc) {
		if (this.finished) {
			throw new IllegalStateException(name + " asked for after the java: helpers were finished");
		}
		return this.cp.methodRef(this.thisClass, name, desc);
	}

	/**
	 * Builds {@code _jfail} and {@code _jsig}, once every body is compiled -- the bridge
	 * finds both by name, so it asks for them whatever the sites did.
	 * <p>
	 * A throwable a function called back from Java raises is recorded on its way out
	 * ({@code _jsig}); a site whose Java call throws that very throwable throws it on
	 * unchanged ({@code _jfail}), anything else as the error calling the member. The
	 * record is per thread -- a throwable Java moves to another thread arrives as
	 * something else -- and keeps the newest
	 * {@link am.ik.rontolisp.compiler.JavaImplementations#PENDING_SIGNALS}.
	 * <p>
	 * The two per-thread channels a compiled condition or exit also lives in travel with
	 * it: {@code _jsig} takes t's condition off {@code _condTl} and t's own entry off the
	 * exit stack {@code _nleTl}, and {@code _jfail} puts both back for the throwable it
	 * passes on. So a callback that Java lets fail meanwhile (a second close handler)
	 * cannot leave its own in their place, and one Java swallows leaves nothing behind.
	 * @param bridge whether the reflective bridge travels with the program
	 * @param channel the program's condition channel: which of {@code _condTl} and
	 * {@code _nleTl} it has
	 * @return the record's field, or {@code null} when no function can be called back
	 * from Java
	 */
	@Nullable Signals finishHelpers(boolean bridge, JvmLispCompiler.ConditionChannel channel) {
		if (bridge) {
			failure();
			signalHelper();
		}
		Signals signals = null;
		MethodRefEntry condTake = channel.used ? channel.condTake : null;
		MethodRefEntry condPut = channel.used ? channel.condPut : null;
		FieldRefEntry nleTl = channel.nleUsed ? Objects.requireNonNull(channel.nleTlField) : null;
		if (this.signal != null) {
			Utf8Entry name = this.cp.utf8Entry(SIGNALS);
			Utf8Entry desc = this.cp.utf8Entry("Ljava/lang/ThreadLocal;");
			signals = new Signals(this.cp.fieldRef(this.thisClass, name, desc), name, desc);
			this.methods.add(buildSignal(signals.field(), condTake, nleTl));
		}
		if (this.failure != null) {
			this.methods.add(buildFailure(signals == null ? null : signals.field(), condPut, nleTl));
		}
		this.finished = true;
		return signals;
	}

	// static Throwable _jsig(Throwable t): t's condition taken off _condTl, and the
	// entry of _nleTl t is the exit of off the stack; then {t, that condition, that
	// entry, the record} pushed on the record, cut to the newest PENDING_SIGNALS; t.
	private Method buildSignal(FieldRefEntry signals, @Nullable MethodRefEntry condTake,
			@Nullable FieldRefEntry nleTl) {
		MethodCode a = new MethodCode();
		ClassEntry objects = cls("[Ljava/lang/Object;");
		MethodRefEntry get = method("java/lang/ThreadLocal", "get", "()Ljava/lang/Object;");
		MethodRefEntry set = method("java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V");
		// 0 = t, 1 = the record, 2 = a node, 3 = its depth, 4 = the condition, 5 = the
		// exit. The condition leaves the channel with t: one Java swallows stays in the
		// record, not on the channel.
		if (condTake != null) {
			a.aload(0);
			a.invokestatic(condTake);
		}
		else {
			a.aconst_null();
		}
		a.astore(4);
		a.aconst_null();
		a.astore(5);
		if (nleTl != null) {
			MethodCode.Label notItsExit = a.newLabel();
			a.getstatic(nleTl);
			a.invokevirtual(get);
			a.checkcast(objects);
			a.astore(2);
			a.aload(2);
			a.ifnull(notItsExit);
			a.aload(2);
			a.loadConstant(0);
			a.aaload();
			a.aload(0);
			a.if_acmpne(notItsExit);
			a.aload(2);
			a.astore(5);
			a.getstatic(nleTl);
			a.aload(2);
			a.loadConstant(3);
			a.aaload();
			a.invokevirtual(set);
			a.labelBinding(notItsExit);
		}
		MethodCode.Label walk = a.newLabel();
		MethodCode.Label cut = a.newLabel();
		MethodCode.Label push = a.newLabel();
		a.getstatic(signals);
		a.invokevirtual(get);
		a.checkcast(objects);
		a.astore(1);
		a.aload(1);
		a.astore(2);
		a.loadConstant(1);
		a.istore(3);
		a.labelBinding(walk);
		a.aload(2);
		a.ifnull(push);
		a.iload(3);
		a.loadConstant(am.ik.rontolisp.compiler.JavaImplementations.PENDING_SIGNALS - 1);
		a.if_icmpge(cut);
		a.aload(2);
		a.loadConstant(3);
		a.aaload();
		a.checkcast(objects);
		a.astore(2);
		a.iinc(3, 1);
		a.goto_(walk);
		a.labelBinding(cut);
		a.aload(2);
		a.loadConstant(3);
		a.aconst_null();
		a.aastore();
		a.labelBinding(push);
		a.getstatic(signals);
		a.loadConstant(4);
		a.anewarray(cls("java/lang/Object"));
		int slot = 0;
		for (int local : new int[] { 0, 4, 5, 1 }) {
			a.dup();
			a.loadConstant(slot++);
			a.aload(local);
			a.aastore();
		}
		a.invokevirtual(set);
		a.aload(0);
		a.areturn();
		return new Method(this.cp.utf8Entry(SIGNAL), this.cp.utf8Entry(SIGNAL_DESC), a);
	}

	// static Throwable _jfail(Throwable t, String text): t when the record holds it --
	// taken off with every newer node, its condition and exit entry put back -- else
	// new RuntimeException(text + t), which carries no condition.
	private Method buildFailure(@Nullable FieldRefEntry signals, @Nullable MethodRefEntry condPut,
			@Nullable FieldRefEntry nleTl) {
		MethodCode a = new MethodCode();
		if (signals != null) {
			ClassEntry objects = cls("[Ljava/lang/Object;");
			MethodRefEntry get = method("java/lang/ThreadLocal", "get", "()Ljava/lang/Object;");
			MethodRefEntry set = method("java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V");
			// 0 = t, 1 = the text, 2 = a node, 3 = its exit entry
			MethodCode.Label walk = a.newLabel();
			MethodCode.Label found = a.newLabel();
			MethodCode.Label wrap = a.newLabel();
			a.getstatic(signals);
			a.invokevirtual(get);
			a.checkcast(objects);
			a.astore(2);
			a.labelBinding(walk);
			a.aload(2);
			a.ifnull(wrap);
			a.aload(2);
			a.loadConstant(0);
			a.aaload();
			a.aload(0);
			a.if_acmpeq(found);
			a.aload(2);
			a.loadConstant(3);
			a.aaload();
			a.checkcast(objects);
			a.astore(2);
			a.goto_(walk);
			a.labelBinding(found);
			a.getstatic(signals);
			a.aload(2);
			a.loadConstant(3);
			a.aaload();
			a.invokevirtual(set);
			if (condPut != null) {
				a.aload(0);
				a.aload(2);
				a.loadConstant(1);
				a.aaload();
				a.invokestatic(condPut);
				a.pop();
			}
			if (nleTl != null) {
				MethodCode.Label noExit = a.newLabel();
				a.aload(2);
				a.loadConstant(2);
				a.aaload();
				a.checkcast(objects);
				a.astore(3);
				a.aload(3);
				a.ifnull(noExit);
				a.aload(3);
				a.loadConstant(3);
				a.getstatic(nleTl);
				a.invokevirtual(get);
				a.aastore();
				a.getstatic(nleTl);
				a.aload(3);
				a.invokevirtual(set);
				a.labelBinding(noExit);
			}
			a.aload(0);
			a.areturn();
			a.labelBinding(wrap);
		}
		a.new_(cls("java/lang/RuntimeException"));
		a.dup();
		a.aload(1);
		a.aload(0);
		a.invokestatic(method("java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;"));
		a.invokevirtual(method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
		a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
		a.areturn();
		return new Method(this.cp.utf8Entry(FAIL), this.cp.utf8Entry(FAIL_DESC), a);
	}

	/**
	 * The method a resolved site calls, made the first time a site of its shape asks.
	 * @param site the resolved site
	 * @param staticField for a {@code java:field} site, whether its first argument is a
	 * class-name literal (a static read, no object passed)
	 * @param valueCount how many values the call passes: the receiver or object, then the
	 * arguments
	 * @return the method, in the {@code (Object...)Object} shape or, past
	 * {@link #MAX_SPREAD} values, {@code (Object[])Object}
	 */
	MethodRefEntry site(JavaSite site, boolean staticField, int valueCount) {
		boolean packedValues = valueCount > MAX_SPREAD;
		String key = shapeKey(site, staticField, packedValues);
		MethodRefEntry cached = this.sites.get(key);
		if (cached != null) {
			return cached;
		}
		String name = SITE_PREFIX + this.sites.size();
		StringBuilder desc = new StringBuilder("(");
		if (packedValues) {
			desc.append("[Ljava/lang/Object;");
		}
		else {
			desc.append("Ljava/lang/Object;".repeat(valueCount));
		}
		desc.append(")Ljava/lang/Object;");
		Utf8Entry nameUtf = this.cp.utf8Entry(name);
		Utf8Entry descUtf = this.cp.utf8Entry(desc.toString());
		MethodRefEntry ref = this.cp.methodRef(this.thisClass, nameUtf, descUtf);
		// Registered before it is built, which may add the shared helpers first.
		this.sites.put(key, ref);
		this.methods.add(new SiteBuilder(site, staticField, valueCount, packedValues).build(nameUtf, descUtf));
		return ref;
	}

	private static String shapeKey(JavaSite site, boolean staticField, boolean packedValues) {
		StringBuilder key = new StringBuilder();
		key.append(site.operator()).append('|').append(site.staticClass()).append('|').append(site.designator());
		key.append('|').append(site.packed()).append('|').append(staticField).append('|').append(packedValues);
		for (JavaSite.Argument argument : site.arguments()) {
			key.append('|');
			for (JavaKind kind : argument.kinds()) {
				key.append(kind instanceof JavaType type ? "~" + type.name() : ((JavaKind.Lisp) kind).name())
					.append(',');
			}
			key.append(argument.declared()).append('/').append(argument.bound());
		}
		for (JavaOverloads.Overload overload : site.overloads()) {
			JavaExecutable executable = overload.executable();
			key.append("|>")
				.append(executable.declaringClass().name())
				.append(' ')
				.append(JavaOverloads.fullDesignator(executable, executable.name()))
				.append(executable.returnType().name())
				.append(overload.packed() ? "*" : "");
		}
		return key.toString();
	}

	// --- constant-pool shorthands ---

	private ClassEntry cls(String internalName) {
		return this.cp.classEntry(internalName);
	}

	private ClassEntry cls(JavaType type) {
		return cls(internalName(type));
	}

	private MethodRefEntry method(String owner, String name, String desc) {
		return this.cp.methodRef(cls(owner), name, desc);
	}

	private InterfaceMethodRefEntry interfaceMethod(String owner, String name, String desc) {
		return this.cp.interfaceMethodRef(cls(owner), name, desc);
	}

	private FieldRefEntry field(String owner, String name, String desc) {
		return this.cp.fieldRef(cls(owner), name, desc);
	}

	private StringEntry str(String value) {
		return this.cp.stringEntry(value);
	}

	/** A class constant's name: slashes, and an array's descriptor as it is. */
	static String internalName(JavaType type) {
		return type.name().replace('.', '/');
	}

	/** A field descriptor ({@code I}, {@code Ljava/lang/String;}, {@code [I}). */
	static String descriptor(JavaType type) {
		return switch (type.name()) {
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			case "void" -> "V";
			default -> type.isArray() ? internalName(type) : "L" + internalName(type) + ";";
		};
	}

	/** The operand slots a value of this type takes: 2 for long and double. */
	private static int width(JavaType type) {
		return "long".equals(type.name()) || "double".equals(type.name()) ? 2 : 1;
	}

	// A class whose exact instances the compiled representation also uses for Lisp values
	// (a Lisp array is an ArrayList, a hash table a LinkedHashMap, a complex number a
	// travelling runtime class): a host kind of it is told apart by _jhost.
	private static boolean mayHoldALispValue(JavaType host) {
		String name = host.name();
		return "java.util.ArrayList".equals(name) || "java.util.LinkedHashMap".equals(name)
				|| name.startsWith(RUNTIME_PACKAGE_PREFIX);
	}

	private MethodRefEntry host() {
		MethodRefEntry ref = this.host;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(HOST);
			Utf8Entry desc = this.cp.utf8Entry("(Ljava/lang/Object;)Z");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.host = ref;
			this.methods.add(buildHost(name, desc));
		}
		return ref;
	}

	/**
	 * The one test a {@code java:} program tells a Lisp array from a host
	 * {@code ArrayList} by: {@code _jhost}, the printer and the array predicates all call
	 * it, so they cannot disagree about a value. Built on first use.
	 * @return {@code _jlarr(Object)Z}
	 */
	MethodRefEntry lispArray() {
		MethodRefEntry ref = this.lispArray;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(LISP_ARRAY);
			Utf8Entry desc = this.cp.utf8Entry("(Ljava/lang/Object;)Z");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.lispArray = ref;
			this.methods.add(buildLispArray(name, desc));
		}
		return ref;
	}

	/**
	 * The one test a {@code java:} program tells a Lisp hash table from a host
	 * {@code LinkedHashMap} by, shared as {@link #lispArray()} is. Built on first use.
	 * @return {@code _jltab(Object)Z}
	 */
	MethodRefEntry lispTable() {
		MethodRefEntry ref = this.lispTable;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(LISP_TABLE);
			Utf8Entry desc = this.cp.utf8Entry("(Ljava/lang/Object;)Z");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.lispTable = ref;
			this.methods.add(buildLispTable(name, desc));
		}
		return ref;
	}

	/**
	 * The guard an array accessor runs its array operand through in a {@code java:}
	 * program: a host {@code ArrayList} is no Lisp array, and the accessors read the
	 * class alone. Built on first use.
	 * @return {@code _jckarr(Object, String)Object}, the operator name second
	 */
	MethodRefEntry arrayGuard() {
		MethodRefEntry ref = this.arrayGuard;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(ARRAY_GUARD);
			Utf8Entry desc = this.cp.utf8Entry(GUARD_DESC);
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.arrayGuard = ref;
			this.methods.add(buildGuard(name, desc, "java/util/ArrayList", lispArray(), OperandTypes.Kind.ARRAY));
		}
		return ref;
	}

	/**
	 * The guard a hash-table accessor runs its table operand through in a {@code java:}
	 * program, as {@link #arrayGuard()} is for arrays. Built on first use.
	 * @return {@code _jcktab(Object, String)Object}, the operator name second
	 */
	MethodRefEntry tableGuard() {
		MethodRefEntry ref = this.tableGuard;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(TABLE_GUARD);
			Utf8Entry desc = this.cp.utf8Entry(GUARD_DESC);
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.tableGuard = ref;
			this.methods
				.add(buildGuard(name, desc, RontoHashTable.MAP_CLASS, lispTable(), OperandTypes.Kind.HASH_TABLE));
		}
		return ref;
	}

	private MethodRefEntry unmarshal() {
		MethodRefEntry ref = this.unmarshal;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(UNMARSHAL);
			Utf8Entry desc = this.cp.utf8Entry(OBJECT_DESC);
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.unmarshal = ref;
			MethodRefEntry toList = arrayToList();
			this.methods.add(buildUnmarshal(name, desc, toList));
		}
		return ref;
	}

	private MethodRefEntry arrayToList() {
		MethodRefEntry ref = this.arrayToList;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(ARRAY_TO_LIST);
			Utf8Entry desc = this.cp.utf8Entry(OBJECT_DESC);
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.arrayToList = ref;
			// _jarr and _junm call each other (an Object[] element is unmarshalled): the
			// reference is published before the body naming _junm is built.
			this.methods.add(buildArrayToList(name, desc, unmarshal()));
		}
		return ref;
	}

	// --- shared throws ---

	/** {@code throw new RuntimeException(prefix + _lispToString(local))}. */
	private void throwDescribing(MethodCode a, String prefix, int slot) {
		a.new_(cls("java/lang/RuntimeException"));
		a.dup();
		a.ldc(str(prefix));
		a.aload(slot);
		a.invokestatic(this.lispToString);
		a.invokevirtual(method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
		a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
		a.athrow();
	}

	/** {@code throw new RuntimeException(message)}. */
	private void throwMessage(MethodCode a, String message) {
		a.new_(cls("java/lang/RuntimeException"));
		a.dup();
		a.ldc(str(message));
		a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
		a.athrow();
	}

	/**
	 * One site's method: the receiver checks, each argument's kind dispatch and
	 * conversion -- at a dispatched site, each argument's check, the choice of the
	 * overload and one arm per overload -- the call, the value's conversion back, and the
	 * handlers.
	 */
	private final class SiteBuilder extends Body {

		private final JavaSite site;

		private final boolean staticField;

		private final int valueCount;

		private final boolean packedValues;

		private final int[] valueSlots;

		private final JavaType type;

		private final ClassEntry owner;

		private final String operator;

		// The handler of each bound class an argument check names: "No such class".
		private final Map<String, MethodCode.Label> boundMissing = new LinkedHashMap<>();

		SiteBuilder(JavaSite site, boolean staticField, int valueCount, boolean packedValues) {
			super(packedValues ? 1 + valueCount : valueCount);
			this.site = site;
			this.staticField = staticField;
			this.valueCount = valueCount;
			this.packedValues = packedValues;
			this.type = Objects.requireNonNull(
					JvmJavaDirectSites.this.lookup.find(Objects.requireNonNull(site.staticClass())), "resolved class");
			this.owner = cls(this.type);
			this.operator = "java:" + site.operator().name().toLowerCase(java.util.Locale.ROOT);
			this.valueSlots = new int[valueCount];
			for (int i = 0; i < valueCount; i++) {
				if (packedValues) {
					this.a.aload(0);
					this.a.loadConstant(i);
					this.a.aaload();
					this.a.astore(1 + i);
					this.valueSlots[i] = 1 + i;
				}
				else {
					this.valueSlots[i] = i;
				}
			}
		}

		Method build(Utf8Entry name, Utf8Entry desc) {
			MethodCode.Label classMissing = this.a.newLabel();
			MethodCode.Label memberFailed = this.a.newLabel();
			boolean hasReceiver = this.site.operator() == JavaSite.Operator.CALL
					|| (this.site.operator() == JavaSite.Operator.FIELD && !this.staticField);
			if (hasReceiver) {
				emitReceiverChecks(classMissing);
			}
			else {
				// The class first, as the bridge's loadClass does: a class the run-time
				// class path lacks is "No such class", before any argument is looked at.
				MethodCode.Label start = this.a.newBoundLabel();
				this.a.ldc(this.owner);
				this.a.pop();
				handle(start, this.a.newBoundLabel(), classMissing, "java/lang/NoClassDefFoundError");
			}
			int stackForCall;
			@Nullable JavaType resultType;
			String failure;
			// A dispatched site returns from each arm.
			JavaField resolvedField = this.site.field();
			if (resolvedField != null) {
				stackForCall = 2;
				resultType = resolvedField.type();
				failure = "error reading field " + resolvedField.name() + ": ";
				emitFieldRead(resolvedField, hasReceiver, memberFailed);
			}
			else if (this.site.dispatched()) {
				// Every overload has the name, so every arm fails with one text.
				JavaExecutable any = this.site.overloads().get(0).executable();
				stackForCall = emitDispatch(hasReceiver, memberFailed);
				resultType = null;
				failure = any.isConstructor() ? "error constructing " + this.type.name() + ": "
						: "error calling " + this.type.name() + "." + any.name() + ": ";
			}
			else {
				JavaExecutable executable = Objects.requireNonNull(this.site.executable());
				int[] converted = emitArguments(executable, hasReceiver ? 1 : 0);
				stackForCall = emitInvoke(executable, converted, hasReceiver, memberFailed);
				resultType = executable.isConstructor() ? this.type : executable.returnType();
				failure = executable.isConstructor() ? "error constructing " + this.type.name() + ": "
						: "error calling " + this.type.name() + "." + executable.name() + ": ";
			}
			if (resultType != null) {
				emitUnmarshal(resultType);
				this.a.areturn();
			}
			// The handlers.
			this.a.labelBinding(classMissing);
			this.a.pop();
			throwMessage(this.a, "No such class: " + this.type.name());
			for (Map.Entry<String, MethodCode.Label> bound : this.boundMissing.entrySet()) {
				this.a.labelBinding(bound.getValue());
				this.a.pop();
				throwMessage(this.a, "No such class: " + bound.getKey());
			}
			// What the member threw: through _jfail, which passes on what a function
			// called
			// back from Java raised and wraps anything else.
			this.a.labelBinding(memberFailed);
			this.a.ldc(str(failure));
			this.a.invokestatic(failure());
			this.a.athrow();
			return finish(name, desc);
		}

		// java:call / java:field on an object: a host object (the bridge's
		// isJavaObject), then an instance of the site's class.
		private void emitReceiverChecks(MethodCode.Label classMissing) {
			int receiver = this.valueSlots[0];
			boolean call = this.site.operator() == JavaSite.Operator.CALL;
			MethodCode.Label hostObject = this.a.newLabel();
			this.a.aload(receiver);
			this.a.invokestatic(host());
			this.a.ifne(hostObject);
			throwDescribing(this.a, call ? "java:call expects a java object as the first argument, got "
					: "java:field expects a class-name string or a java object, got ", receiver);
			this.a.labelBinding(hostObject);
			MethodCode.Label instance = this.a.newLabel();
			MethodCode.Label start = this.a.newBoundLabel();
			this.a.aload(receiver);
			this.a.instanceOf(this.owner);
			handle(start, this.a.newBoundLabel(), classMissing, "java/lang/NoClassDefFoundError");
			this.a.ifne(instance);
			throwDescribing(this.a, this.operator + ": the " + (call ? "receiver" : "object") + " is not a "
					+ this.type.name() + ", got ", receiver);
			this.a.labelBinding(instance);
		}

		private void emitFieldRead(JavaField resolvedField, boolean hasReceiver, MethodCode.Label memberFailed) {
			if (!hasReceiver && !resolvedField.isStatic()) {
				throw new IllegalStateException("a class-name java:field resolved to an instance field");
			}
			FieldRefEntry ref = field(internalName(this.type), resolvedField.name(), descriptor(resolvedField.type()));
			if (resolvedField.isStatic()) {
				MethodCode.Label start = this.a.newBoundLabel();
				this.a.getstatic(ref);
				handle(start, this.a.newBoundLabel(), memberFailed, "java/lang/Throwable");
			}
			else {
				this.a.aload(this.valueSlots[0]);
				this.a.checkcast(this.owner);
				MethodCode.Label start = this.a.newBoundLabel();
				this.a.getfield(ref);
				handle(start, this.a.newBoundLabel(), memberFailed, "java/lang/Throwable");
			}
		}

		/**
		 * Converts every argument into a local of its parameter type (a varargs tail into
		 * a fresh array), returning the locals in parameter order.
		 */
		private int[] emitArguments(JavaExecutable executable, int firstValue) {
			List<? extends JavaType> params = executable.parameterTypes();
			List<JavaSite.Argument> arguments = this.site.arguments();
			boolean packed = this.site.packed();
			int fixed = packed ? params.size() - 1 : params.size();
			int[] converted = new int[params.size()];
			for (int i = 0; i < fixed; i++) {
				JavaType param = params.get(i);
				emitArgument(i, this.valueSlots[firstValue + i], param, arguments.get(i));
				converted[i] = this.nextSlot;
				this.nextSlot += width(param);
				store(param, converted[i]);
			}
			if (packed) {
				JavaType arrayType = params.get(fixed);
				JavaType component = Objects.requireNonNull(arrayType.componentType());
				int tail = arguments.size() - fixed;
				int array = this.nextSlot++;
				int element = this.nextSlot;
				this.nextSlot += width(component);
				this.a.loadConstant(tail);
				newArray(component);
				this.a.astore(array);
				for (int j = 0; j < tail; j++) {
					emitArgument(fixed + j, this.valueSlots[firstValue + fixed + j], component,
							arguments.get(fixed + j));
					store(component, element);
					this.a.aload(array);
					this.a.loadConstant(j);
					load(component, element);
					this.a.arrayStore(typeKind(component));
				}
				converted[fixed] = array;
			}
			return converted;
		}

		/**
		 * Leaves the converted argument on the stack, or throws for a value of no kind it
		 * counted on.
		 */
		private void emitArgument(int index, int slot, JavaType target, JavaSite.Argument argument) {
			MethodCode.Label done = this.a.newLabel();
			List<JavaKind> kinds = argument.kinds();
			boolean bothStrings = kinds.contains(JavaKind.Lisp.STRING) && kinds.contains(JavaKind.Lisp.STRING_1);
			for (JavaKind kind : kinds) {
				if (kind == JavaKind.Lisp.STRING_1 && bothStrings) {
					continue; // the STRING test covers both, converted alike
				}
				if (kind instanceof JavaType host && !isHostKind(host)) {
					continue; // the bridge's kindOf never answers it (a bignum's class)
				}
				MethodCode.Label next = this.a.newLabel();
				emitKindTest(slot, kind, bothStrings, next);
				emitConvert(slot, kind, target);
				this.a.goto_(done);
				this.a.labelBinding(next);
			}
			throwDescribing(this.a,
					this.operator + ": argument " + (index + 1) + " is not " + argument.expected() + ", got ", slot);
			this.a.labelBinding(done);
			// The arms answer different reference types, which merge to their common
			// superclass or Object: a class parameter needs its own type back.
			if (!target.isPrimitive() && !target.isInterface() && !target.isArray()
					&& !"java.lang.Object".equals(target.name())) {
				this.a.checkcast(cls(target));
			}
		}

		// A class a value's kind can be: the bridge's kindOf answers the exact class of
		// anything outside the compiled representation, and never a bignum's.
		private boolean isHostKind(JavaType host) {
			return !"java.math.BigInteger".equals(host.name());
		}

		/** The call itself; answers the operand slots it needs. */
		private int emitInvoke(JavaExecutable executable, int[] converted, boolean hasReceiver,
				MethodCode.Label memberFailed) {
			MethodCode a = this.a;
			List<? extends JavaType> params = executable.parameterTypes();
			StringBuilder desc = new StringBuilder("(");
			int slots = 0;
			for (JavaType param : params) {
				desc.append(descriptor(param));
				slots += width(param);
			}
			desc.append(')').append(executable.isConstructor() ? "V" : descriptor(executable.returnType()));
			String internal = internalName(this.type);
			if (executable.isConstructor()) {
				a.new_(this.owner);
				a.dup();
				loadArguments(params, converted);
				MethodCode.Label start = a.newBoundLabel();
				a.invokespecial(method(internal, "<init>", desc.toString()));
				handle(start, a.newBoundLabel(), memberFailed, "java/lang/Throwable");
				return slots + 2;
			}
			boolean ownerIsInterface = this.type.isInterface();
			if (executable.isStatic()) {
				loadArguments(params, converted);
				MethodCode.Label start = a.newBoundLabel();
				a.invokestatic(ownerIsInterface ? interfaceMethod(internal, executable.name(), desc.toString())
						: method(internal, executable.name(), desc.toString()));
				handle(start, a.newBoundLabel(), memberFailed, "java/lang/Throwable");
				return slots;
			}
			if (!hasReceiver) {
				throw new IllegalStateException("an instance method resolved for java:static");
			}
			a.aload(this.valueSlots[0]);
			a.checkcast(this.owner);
			loadArguments(params, converted);
			MethodCode.Label start = a.newBoundLabel();
			if (ownerIsInterface) {
				a.invokeinterface(interfaceMethod(internal, executable.name(), desc.toString()));
			}
			else {
				a.invokevirtual(method(internal, executable.name(), desc.toString()));
			}
			handle(start, a.newBoundLabel(), memberFailed, "java/lang/Throwable");
			return slots + 1;
		}

		private void loadArguments(List<? extends JavaType> params, int[] converted) {
			for (int i = 0; i < params.size(); i++) {
				load(params.get(i), converted[i]);
			}
		}

		/**
		 * A dispatched site's arguments and call, as the interpreter's
		 * {@code JavaInterop.invokeResolved} runs them: each argument checked against
		 * what the site counted on; its cost against each parameter type an overload
		 * converts it to ({@code _jcost$N}); the cheapest overload, the first of the
		 * ranked ones on a tie ({@code JavaOverloads.selectRanked}); and one arm per
		 * overload, which converts the arguments ({@code _jconv$N}), calls it and
		 * converts its value back. Answers the operand slots the calls need.
		 */
		private int emitDispatch(boolean hasReceiver, MethodCode.Label memberFailed) {
			MethodCode a = this.a;
			int firstValue = hasReceiver ? 1 : 0;
			List<JavaSite.Argument> arguments = this.site.arguments();
			List<JavaOverloads.Overload> overloads = this.site.overloads();
			int argc = arguments.size();
			for (int i = 0; i < argc; i++) {
				emitCheck(i, this.valueSlots[firstValue + i], arguments.get(i));
			}
			// Each (argument, parameter type) cost once, in a local.
			Map<String, Integer> costs = new LinkedHashMap<>();
			int[][] costSlots = new int[overloads.size()][argc];
			for (int k = 0; k < overloads.size(); k++) {
				for (int i = 0; i < argc; i++) {
					JavaType parameter = JavaOverloads.parameterAt(overloads.get(k), i);
					String key = i + "|" + parameter.name();
					Integer slot = costs.get(key);
					if (slot == null) {
						slot = this.nextSlot++;
						a.aload(this.valueSlots[firstValue + i]);
						a.invokestatic(cost(parameter));
						a.istore(slot);
						costs.put(key, slot);
					}
					costSlots[k][i] = slot;
				}
			}
			// The cheapest overload: a later one replaces the best only when strictly
			// cheaper.
			int best = this.nextSlot++;
			int bestCost = this.nextSlot++;
			int total = this.nextSlot++;
			a.loadConstant(-1);
			a.istore(best);
			a.loadConstant(0);
			a.istore(bestCost);
			for (int k = 0; k < overloads.size(); k++) {
				MethodCode.Label next = a.newLabel();
				a.loadConstant(overloads.get(k).packed() ? JavaOverloads.COST_VARARGS : 0);
				a.istore(total);
				for (int i = 0; i < argc; i++) {
					a.iload(costSlots[k][i]);
					a.iflt(next);
					a.iload(total);
					a.iload(costSlots[k][i]);
					a.iadd();
					a.istore(total);
				}
				MethodCode.Label take = a.newLabel();
				a.iload(best);
				a.iflt(take);
				a.iload(total);
				a.iload(bestCost);
				a.if_icmpge(next);
				a.labelBinding(take);
				a.loadConstant(k);
				a.istore(best);
				a.iload(total);
				a.istore(bestCost);
				a.labelBinding(next);
			}
			MethodCode.Label found = a.newLabel();
			a.iload(best);
			a.ifge(found);
			String designator = Objects.requireNonNull(this.site.designator());
			throwMessage(a, this.site.operator() == JavaSite.Operator.NEW
					? "No matching constructor for " + designator + " with " + argc + " argument(s)"
					: "No matching method " + this.type.name() + "." + designator + " with " + argc + " argument(s)");
			a.labelBinding(found);
			int stack = 0;
			for (int k = 0; k < overloads.size(); k++) {
				boolean last = k == overloads.size() - 1;
				MethodCode.Label nextArm = a.newLabel();
				if (!last) {
					a.iload(best);
					a.loadConstant(k);
					a.if_icmpne(nextArm);
				}
				JavaOverloads.Overload overload = overloads.get(k);
				JavaExecutable executable = overload.executable();
				int[] converted = emitConverted(overload, firstValue);
				stack = Math.max(stack, emitInvoke(executable, converted, hasReceiver, memberFailed));
				emitUnmarshal(executable.isConstructor() ? this.type : executable.returnType());
				a.areturn();
				if (!last) {
					a.labelBinding(nextArm);
				}
			}
			return stack;
		}

		/**
		 * Throws unless the local holds what the site counted on for the argument: one of
		 * its kinds, or {@code nil} or a host object of its bound.
		 */
		private void emitCheck(int index, int slot, JavaSite.Argument argument) {
			MethodCode a = this.a;
			String bound = argument.bound();
			if (!argument.known() && bound == null) {
				return;
			}
			MethodCode.Label ok = a.newLabel();
			if (argument.known()) {
				List<JavaKind> kinds = argument.kinds();
				boolean bothStrings = kinds.contains(JavaKind.Lisp.STRING) && kinds.contains(JavaKind.Lisp.STRING_1);
				for (JavaKind kind : kinds) {
					if ((kind == JavaKind.Lisp.STRING_1 && bothStrings)
							|| (kind instanceof JavaType host && !isHostKind(host))) {
						continue;
					}
					MethodCode.Label next = a.newLabel();
					emitKindTest(slot, kind, bothStrings, next);
					a.goto_(ok);
					a.labelBinding(next);
				}
			}
			else {
				String boundClass = Objects.requireNonNull(bound);
				MethodCode.Label fail = a.newLabel();
				a.aload(slot);
				a.ifnull(ok);
				a.aload(slot);
				a.invokestatic(kind());
				a.loadConstant(KIND_HOST);
				a.if_icmpne(fail);
				MethodCode.Label missing = this.boundMissing.get(boundClass);
				if (missing == null) {
					missing = a.newLabel();
					this.boundMissing.put(boundClass, missing);
				}
				MethodCode.Label start = a.newBoundLabel();
				a.aload(slot);
				a.instanceOf(cls(Objects.requireNonNull(JvmJavaDirectSites.this.lookup.find(boundClass), boundClass)));
				handle(start, a.newBoundLabel(), missing, "java/lang/NoClassDefFoundError");
				a.ifne(ok);
				a.labelBinding(fail);
			}
			throwDescribing(a,
					this.operator + ": argument " + (index + 1) + " is not " + argument.expected() + ", got ", slot);
			a.labelBinding(ok);
		}

		/**
		 * Converts every argument into a local of its parameter type for one overload of
		 * a dispatched site -- a varargs tail into a fresh array -- returning the locals
		 * in parameter order.
		 */
		private int[] emitConverted(JavaOverloads.Overload overload, int firstValue) {
			MethodCode a = this.a;
			List<JavaSite.Argument> arguments = this.site.arguments();
			List<? extends JavaType> params = overload.executable().parameterTypes();
			int fixed = overload.packed() ? params.size() - 1 : params.size();
			int[] converted = new int[params.size()];
			for (int j = 0; j < fixed; j++) {
				JavaType param = params.get(j);
				a.aload(this.valueSlots[firstValue + j]);
				boolean open = arguments.get(j).mayBeFunction();
				a.invokestatic(convert(param, open, open));
				converted[j] = this.nextSlot;
				this.nextSlot += width(param);
				store(param, converted[j]);
			}
			if (overload.packed()) {
				JavaType component = Objects.requireNonNull(params.get(fixed).componentType());
				int array = this.nextSlot++;
				a.loadConstant(arguments.size() - fixed);
				newArray(component);
				a.astore(array);
				for (int j = fixed; j < arguments.size(); j++) {
					a.aload(array);
					a.loadConstant(j - fixed);
					a.aload(this.valueSlots[firstValue + j]);
					boolean open = arguments.get(j).mayBeFunction();
					a.invokestatic(convert(component, open, open));
					a.arrayStore(typeKind(component));
				}
				converted[fixed] = array;
			}
			return converted;
		}

	}

	/**
	 * A method body: its assembler, its locals and handlers, and the kind tests and
	 * conversions every {@code java:} method shares -- a site's, and the helpers that
	 * classify, cost and convert a value for one parameter type.
	 */
	private record PendingHandler(MethodCode.Label start, MethodCode.Label end, MethodCode.Label handler,
			ClassEntry catchType) {
	}

	private class Body {

		final MethodCode a = new MethodCode();

		// A handler's entry is bound after the body, so the table is added last.
		private final List<PendingHandler> pendingHandlers = new ArrayList<>();

		int nextSlot;

		final int objectTemp;

		final int longTemp;

		/**
		 * @param firstFree the first local the parameters leave free
		 */
		Body(int firstFree) {
			this.objectTemp = firstFree;
			this.longTemp = firstFree + 1;
			this.nextSlot = firstFree + 3;
		}

		void handle(MethodCode.Label start, MethodCode.Label end, MethodCode.Label handler, String catchType) {
			this.pendingHandlers.add(new PendingHandler(start, end, handler, cls(catchType)));
		}

		Method finish(Utf8Entry name, Utf8Entry desc) {
			for (PendingHandler pending : this.pendingHandlers) {
				this.a.exceptionCatch(pending.start(), pending.end(), pending.handler(), pending.catchType());
			}
			return new Method(name, desc, this.a);
		}

		/**
		 * Falls through when the local holds a value of this kind, else jumps to fail.
		 */
		void emitKindTest(int slot, JavaKind kind, boolean bothStrings, MethodCode.Label fail) {
			MethodCode a = this.a;
			if (kind instanceof JavaImplementationType implementation) {
				// An object a java:reify / java:proxy of the interface made: of a class
				// generated for one (JvmJavaImplementations), implementing it.
				a.aload(slot);
				a.instanceOf(cls(implementations().baseClass()));
				a.ifeq(fail);
				a.aload(slot);
				a.instanceOf(cls(implementation.iface()));
				a.ifeq(fail);
				return;
			}
			if (kind instanceof JavaType host) {
				a.aload(slot);
				a.ifnull(fail);
				a.aload(slot);
				a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
				a.ldc(cls(host));
				a.if_acmpne(fail);
				if (mayHoldALispValue(host)) {
					// A class the compiled representation also uses (a Lisp array is an
					// ArrayList, a hash table a LinkedHashMap): _jhost tells them apart.
					a.aload(slot);
					a.invokestatic(host());
					a.ifeq(fail);
				}
				return;
			}
			switch ((JavaKind.Lisp) kind) {
				case NIL -> {
					a.aload(slot);
					a.ifnonnull(fail);
				}
				case T -> {
					a.ldc(str("T"));
					a.aload(slot);
					a.invokevirtual(method("java/lang/String", "equals", "(Ljava/lang/Object;)Z"));
					a.ifeq(fail);
				}
				case INTEGER -> {
					a.aload(slot);
					a.instanceOf(cls("java/lang/Long"));
					a.ifeq(fail);
				}
				case BIGNUM -> {
					a.aload(slot);
					a.instanceOf(cls("java/math/BigInteger"));
					a.ifeq(fail);
				}
				case FLOAT -> {
					a.aload(slot);
					a.instanceOf(cls("java/lang/Double"));
					a.ifeq(fail);
				}
				case CHAR, SUPPLEMENTARY_CHAR -> {
					a.aload(slot);
					a.instanceOf(cls("[I"));
					a.ifeq(fail);
					a.aload(slot);
					a.checkcast(cls("[I"));
					a.arraylength();
					a.loadConstant(1);
					a.if_icmpne(fail);
					codePoint(slot);
					a.invokestatic(method("java/lang/Character", "isBmpCodePoint", "(I)Z"));
					if (kind == JavaKind.Lisp.CHAR) {
						a.ifeq(fail);
					}
					else {
						a.ifne(fail);
					}
				}
				case STRING, STRING_1 -> {
					ClassEntry string = cls("java/lang/String");
					MethodRefEntry length = method("java/lang/String", "length", "()I");
					a.aload(slot);
					a.instanceOf(string);
					a.ifeq(fail);
					a.aload(slot);
					a.checkcast(string);
					a.invokevirtual(length);
					a.ifeq(fail);
					a.aload(slot);
					a.checkcast(string);
					a.loadConstant(0);
					a.invokevirtual(method("java/lang/String", "charAt", "(I)C"));
					a.loadConstant('"');
					a.if_icmpne(fail);
					if (!bothStrings) {
						// A one-character string is the quote-framed length 3.
						a.aload(slot);
						a.checkcast(string);
						a.invokevirtual(length);
						a.loadConstant(3);
						if (kind == JavaKind.Lisp.STRING_1) {
							a.if_icmpne(fail);
						}
						else {
							a.if_icmpeq(fail);
						}
					}
				}
				case FUNCTION -> {
					ClassEntry objects = cls("[Ljava/lang/Object;");
					a.aload(slot);
					a.ifnull(fail);
					a.aload(slot);
					a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
					a.ldc(objects);
					a.if_acmpne(fail);
					a.aload(slot);
					a.checkcast(objects);
					a.arraylength();
					a.ifeq(fail);
					a.aload(slot);
					a.checkcast(objects);
					a.loadConstant(0);
					a.aaload();
					a.instanceOf(cls("java/lang/Integer"));
					a.ifeq(fail);
				}
			}
		}

		/**
		 * Pushes the value of a local of this kind as the target type (the bridge's
		 * convert).
		 */
		void emitConvert(int slot, JavaKind kind, JavaType target) {
			MethodCode a = this.a;
			if (kind instanceof JavaType) {
				a.aload(slot);
				return;
			}
			String name = target.name();
			switch ((JavaKind.Lisp) kind) {
				case NIL -> {
					if ("boolean".equals(name)) {
						a.loadConstant(0);
					}
					else if ("java.lang.Boolean".equals(name)) {
						a.getstatic(field("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;"));
					}
					else {
						a.aconst_null();
					}
				}
				case T -> {
					if ("boolean".equals(name)) {
						a.loadConstant(1);
					}
					else {
						a.getstatic(field("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;"));
					}
				}
				case INTEGER -> convertInteger(slot, name);
				// The BigInteger itself, for that class or a supertype.
				case BIGNUM -> a.aload(slot);
				case FLOAT -> {
					switch (name) {
						case "double" -> doubleValue(slot);
						case "float" -> {
							doubleValue(slot);
							a.d2f();
						}
						case "java.lang.Float" -> {
							doubleValue(slot);
							a.d2f();
							a.invokestatic(method("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;"));
						}
						default -> {
							// The Double itself, as the bridge passes it.
							a.aload(slot);
							a.checkcast(cls("java/lang/Double"));
						}
					}
				}
				case STRING, STRING_1 -> {
					switch (name) {
						case "char" -> firstCharacter(slot);
						case "java.lang.Character" -> {
							firstCharacter(slot);
							a.invokestatic(method("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;"));
						}
						default -> {
							// The unquoted string: substring(1, length - 1).
							ClassEntry string = cls("java/lang/String");
							a.aload(slot);
							a.checkcast(string);
							a.loadConstant(1);
							a.aload(slot);
							a.checkcast(string);
							a.invokevirtual(method("java/lang/String", "length", "()I"));
							a.loadConstant(1);
							a.isub();
							a.invokevirtual(method("java/lang/String", "substring", "(II)Ljava/lang/String;"));
						}
					}
				}
				case CHAR, SUPPLEMENTARY_CHAR -> {
					codePoint(slot);
					JavaType character = JvmJavaDirectSites.this.lookup.find("java.lang.Character");
					boolean asChar = kind == JavaKind.Lisp.CHAR && ("char".equals(name)
							|| "java.lang.Character".equals(name)
							|| (!target.isPrimitive() && character != null && target.isAssignableFrom(character)));
					if ("char".equals(name)) {
						a.i2c();
					}
					else if (asChar) {
						a.i2c();
						a.invokestatic(method("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;"));
					}
					else if (!"int".equals(name)) {
						a.invokestatic(method("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
					}
				}
				case FUNCTION -> {
					// The interface's generated proxy class over the function, as the
					// bridge makes it a Proxy: of(new Object[] { function }).
					a.loadConstant(1);
					a.anewarray(cls("java/lang/Object"));
					a.dup();
					a.loadConstant(0);
					a.aload(slot);
					a.aastore();
					a.invokestatic(implementations().proxyFactory(target));
				}
			}
		}

		// The bridge's convertLong over the integer in the local.
		void convertInteger(int slot, String target) {
			MethodCode a = this.a;
			longValue(slot);
			switch (target) {
				case "long" -> {
				}
				case "int" -> a.l2i();
				case "double" -> a.l2d();
				case "float" -> a.l2f();
				case "short" -> {
					a.l2i();
					a.i2s();
				}
				case "byte" -> {
					a.l2i();
					a.i2b();
				}
				case "java.lang.Integer" -> {
					a.l2i();
					a.invokestatic(method("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
				}
				case "java.lang.Long" -> a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
				case "java.lang.Double" -> {
					a.l2d();
					a.invokestatic(method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
				}
				case "java.lang.Float" -> {
					a.l2f();
					a.invokestatic(method("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;"));
				}
				case "java.lang.Short" -> {
					a.l2i();
					a.i2s();
					a.invokestatic(method("java/lang/Short", "valueOf", "(S)Ljava/lang/Short;"));
				}
				case "java.lang.Byte" -> {
					a.l2i();
					a.i2b();
					a.invokestatic(method("java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;"));
				}
				case "java.math.BigInteger" ->
					a.invokestatic(method("java/math/BigInteger", "valueOf", "(J)Ljava/math/BigInteger;"));
				default -> {
					// Boxed to the narrowest type that holds it, like a fixnum.
					MethodCode.Label wide = a.newLabel();
					MethodCode.Label end = a.newLabel();
					a.lstore(this.longTemp);
					a.lload(this.longTemp);
					a.lload(this.longTemp);
					a.l2i();
					a.i2l();
					a.lcmp();
					a.ifne(wide);
					a.lload(this.longTemp);
					a.l2i();
					a.invokestatic(method("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
					a.goto_(end);
					a.labelBinding(wide);
					a.lload(this.longTemp);
					a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
					a.labelBinding(end);
				}
			}
		}

		void longValue(int slot) {
			this.a.aload(slot);
			this.a.checkcast(cls("java/lang/Long"));
			this.a.invokevirtual(method("java/lang/Long", "longValue", "()J"));
		}

		void doubleValue(int slot) {
			this.a.aload(slot);
			this.a.checkcast(cls("java/lang/Double"));
			this.a.invokevirtual(method("java/lang/Double", "doubleValue", "()D"));
		}

		void codePoint(int slot) {
			this.a.aload(slot);
			this.a.checkcast(cls("[I"));
			this.a.loadConstant(0);
			this.a.iaload();
		}

		// The one character of a one-character string: charAt(1) of its quote frame.
		void firstCharacter(int slot) {
			this.a.aload(slot);
			this.a.checkcast(cls("java/lang/String"));
			this.a.loadConstant(1);
			this.a.invokevirtual(method("java/lang/String", "charAt", "(I)C"));
		}

		void store(JavaType type, int slot) {
			switch (type.name()) {
				case "long" -> this.a.lstore(slot);
				case "double" -> this.a.dstore(slot);
				case "float" -> this.a.fstore(slot);
				case "boolean", "byte", "char", "short", "int" -> this.a.istore(slot);
				default -> this.a.astore(slot);
			}
		}

		void load(JavaType type, int slot) {
			switch (type.name()) {
				case "long" -> this.a.lload(slot);
				case "double" -> this.a.dload(slot);
				case "float" -> this.a.fload(slot);
				case "boolean", "byte", "char", "short", "int" -> this.a.iload(slot);
				default -> this.a.aload(slot);
			}
		}

		void newArray(JavaType component) {
			switch (component.name()) {
				case "boolean", "char", "float", "double", "byte", "short", "int", "long" ->
					this.a.newarray(typeKind(component));
				default -> this.a.anewarray(cls(component));
			}
		}

		/**
		 * The kind of a Java type's value: a primitive's own, a reference for the rest
		 * ({@code void} included).
		 */
		static TypeKind typeKind(JavaType type) {
			return switch (type.name()) {
				case "boolean" -> TypeKind.BOOLEAN;
				case "byte" -> TypeKind.BYTE;
				case "char" -> TypeKind.CHAR;
				case "short" -> TypeKind.SHORT;
				case "int" -> TypeKind.INT;
				case "long" -> TypeKind.LONG;
				case "float" -> TypeKind.FLOAT;
				case "double" -> TypeKind.DOUBLE;
				default -> TypeKind.REFERENCE;
			};
		}

		/**
		 * Replaces the member's value on the stack by the Lisp value the bridge's
		 * unmarshal makes of it, specialized to the declared type.
		 */
		void emitUnmarshal(JavaType declared) {
			MethodCode a = this.a;
			switch (declared.name()) {
				case "void" -> a.aconst_null();
				case "boolean" -> {
					MethodCode.Label nil = a.newLabel();
					MethodCode.Label end = a.newLabel();
					a.ifeq(nil);
					a.ldc(str("T"));
					a.goto_(end);
					a.labelBinding(nil);
					a.aconst_null();
					a.labelBinding(end);
				}
				case "byte", "short", "int" -> {
					a.i2l();
					a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
				}
				case "long" -> a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
				case "float" -> {
					a.f2d();
					a.invokestatic(method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
				}
				case "double" -> a.invokestatic(method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
				case "char" -> {
					int charTemp = this.longTemp;
					a.istore(charTemp);
					a.loadConstant(1);
					a.newarray(TypeKind.INT);
					a.dup();
					a.loadConstant(0);
					a.iload(charTemp);
					a.iastore();
				}
				case "java.lang.Long", "java.lang.Double" -> {
					// The object itself, as the bridge answers it.
				}
				case "java.lang.String" -> nullOr(() -> {
					a.ldc(str("\""));
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/String"));
					a.invokevirtual(method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
					a.ldc(str("\""));
					a.invokevirtual(method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
				});
				case "java.lang.Boolean" -> {
					MethodCode.Label nil = a.newLabel();
					MethodCode.Label end = a.newLabel();
					a.astore(this.objectTemp);
					a.aload(this.objectTemp);
					a.ifnull(nil);
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/Boolean"));
					a.invokevirtual(method("java/lang/Boolean", "booleanValue", "()Z"));
					a.ifeq(nil);
					a.ldc(str("T"));
					a.goto_(end);
					a.labelBinding(nil);
					a.aconst_null();
					a.labelBinding(end);
				}
				case "java.lang.Integer", "java.lang.Short", "java.lang.Byte" -> nullOr(() -> {
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/Number"));
					a.invokevirtual(method("java/lang/Number", "longValue", "()J"));
					a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
				});
				case "java.lang.Float" -> nullOr(() -> {
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/Float"));
					a.invokevirtual(method("java/lang/Float", "floatValue", "()F"));
					a.f2d();
					a.invokestatic(method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
				});
				case "java.lang.Character" -> nullOr(() -> {
					a.loadConstant(1);
					a.newarray(TypeKind.INT);
					a.dup();
					a.loadConstant(0);
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/Character"));
					a.invokevirtual(method("java/lang/Character", "charValue", "()C"));
					a.iastore();
				});
				default -> {
					if (declared.isArray() || mayHideALispValue(declared)) {
						a.invokestatic(unmarshal());
					}
					// Any other class holds a host object, which stays itself.
				}
			}
		}

		// null stays nil; anything else is what the body leaves (reading the value from
		// objectTemp).
		void nullOr(Runnable body) {
			MethodCode.Label nil = this.a.newLabel();
			MethodCode.Label end = this.a.newLabel();
			this.a.astore(this.objectTemp);
			this.a.aload(this.objectTemp);
			this.a.ifnull(nil);
			body.run();
			this.a.goto_(end);
			this.a.labelBinding(nil);
			this.a.aconst_null();
			this.a.labelBinding(end);
		}

		// A supertype of a box, of String, of BigInteger or of an array, or BigInteger's
		// subclass: a value declared so may be one of those at run time, which unmarshal
		// turns into a Lisp value (compiler/JavaStaticType.ofDeclared's rule).
		boolean mayHideALispValue(JavaType declared) {
			return JavaStaticType.becomesLisp(declared, JvmJavaDirectSites.this.lookup);
		}

	}

	// --- the dispatch helpers ---

	private MethodRefEntry kind() {
		MethodRefEntry ref = this.kind;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(KIND);
			Utf8Entry desc = this.cp.utf8Entry("(Ljava/lang/Object;)I");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.kind = ref;
			this.methods.add(buildKind(name, desc, host()));
		}
		return ref;
	}

	private MethodRefEntry sequence() {
		MethodRefEntry ref = this.sequence;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(SEQUENCE);
			Utf8Entry desc = this.cp.utf8Entry("(Ljava/lang/Object;)[Ljava/lang/Object;");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.sequence = ref;
			this.methods.add(buildSequence(name, desc));
		}
		return ref;
	}

	/**
	 * {@code _jcost$N}: what the bridge's {@code marshal} costs a value for this type --
	 * an argument's, a function costing its proxy.
	 */
	private MethodRefEntry cost(JavaType target) {
		return cost(target, true);
	}

	/**
	 * {@code _jcost$N} for this type, with or without the function arm: a value a
	 * {@code java:reify} / {@code java:proxy} function answers never makes a proxy.
	 */
	private MethodRefEntry cost(JavaType target, boolean functions) {
		String key = target.name() + (functions ? "" : " returned");
		MethodRefEntry ref = this.costs.get(key);
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(COST_PREFIX + this.costs.size());
			Utf8Entry desc = this.cp.utf8Entry("(Ljava/lang/Object;)I");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			// Registered before it is built: a list of lists costs itself.
			this.costs.put(key, ref);
			this.methods.add(buildCost(name, desc, target, functions));
		}
		return ref;
	}

	/**
	 * {@code _jconv$N}: a value converted to this type as the bridge's {@code marshal}
	 * converts it. An argument that may be a function takes the open variant (both
	 * flags), which also makes a function the interface's generated proxy and a sequence
	 * an array or a list; the closed one (neither) serves a value of known kinds or an
	 * object; a value a {@code java:reify} / {@code java:proxy} function answers takes
	 * sequences without functions.
	 */
	private MethodRefEntry convert(JavaType target, boolean functions, boolean sequences) {
		String key = target.name() + (functions ? " functions" : "") + (sequences ? " sequences" : "");
		MethodRefEntry ref = this.converts.get(key);
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(CONVERT_PREFIX + this.converts.size());
			Utf8Entry desc = this.cp.utf8Entry("(Ljava/lang/Object;)" + descriptor(target));
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.converts.put(key, ref);
			this.methods.add(buildConvert(name, desc, target, functions, sequences));
		}
		return ref;
	}

	private JvmJavaImplementations implementations() {
		return Objects.requireNonNull(this.implementations, "the attempt's implementations");
	}

	// The type a sequence's elements convert to for this parameter: an array's
	// component, Object for a type a java.util.ArrayList is, else none.
	private @Nullable JavaType sequenceElement(JavaType target) {
		JavaType component = target.componentType();
		if (component != null) {
			return component;
		}
		JavaType arrayList = this.lookup.find("java.util.ArrayList");
		if (!target.isPrimitive() && arrayList != null && target.isAssignableFrom(arrayList)) {
			return Objects.requireNonNull(this.lookup.find("java.lang.Object"), "java.lang.Object");
		}
		return null;
	}

	// v = _strv(v): a mutable character vector as the string it spells.
	private void render(MethodCode a, int slot) {
		MethodRefEntry render = this.strv;
		if (render != null) {
			a.aload(slot);
			a.invokestatic(render);
			a.astore(slot);
		}
	}

	// _jkind(Object)I: the bridge's kindOf as a code -- the Lisp kinds (LISP_KINDS'
	// index), a cons, a Lisp array (a specialized one too), a host object, or none (a
	// symbol, a ratio, a hash table) -- tested in its order.
	private Method buildKind(Utf8Entry name, Utf8Entry desc, MethodRefEntry hostTest) {
		MethodCode a = new MethodCode();
		ClassEntry string = cls("java/lang/String");
		ClassEntry objects = cls("[Ljava/lang/Object;");
		ClassEntry arrayList = cls("java/util/ArrayList");
		MethodRefEntry length = method("java/lang/String", "length", "()I");
		MethodCode.Label notNil = a.newLabel();
		a.aload(0);
		a.ifnonnull(notNil);
		returnCode(a, code(JavaKind.Lisp.NIL));
		a.labelBinding(notNil);
		MethodCode.Label notInteger = a.newLabel();
		a.aload(0);
		a.instanceOf(cls("java/lang/Long"));
		a.ifeq(notInteger);
		returnCode(a, code(JavaKind.Lisp.INTEGER));
		a.labelBinding(notInteger);
		MethodCode.Label notBignum = a.newLabel();
		a.aload(0);
		a.instanceOf(cls("java/math/BigInteger"));
		a.ifeq(notBignum);
		returnCode(a, code(JavaKind.Lisp.BIGNUM));
		a.labelBinding(notBignum);
		MethodCode.Label notFloat = a.newLabel();
		a.aload(0);
		a.instanceOf(cls("java/lang/Double"));
		a.ifeq(notFloat);
		returnCode(a, code(JavaKind.Lisp.FLOAT));
		a.labelBinding(notFloat);
		// A character is an int[] of length 1; any other int[] is a host object.
		MethodCode.Label notChar = a.newLabel();
		MethodCode.Label supplementary = a.newLabel();
		a.aload(0);
		a.instanceOf(cls("[I"));
		a.ifeq(notChar);
		a.aload(0);
		a.checkcast(cls("[I"));
		a.arraylength();
		a.loadConstant(1);
		a.if_icmpne(notChar);
		a.aload(0);
		a.checkcast(cls("[I"));
		a.loadConstant(0);
		a.iaload();
		a.invokestatic(method("java/lang/Character", "isBmpCodePoint", "(I)Z"));
		a.ifeq(supplementary);
		returnCode(a, code(JavaKind.Lisp.CHAR));
		a.labelBinding(supplementary);
		returnCode(a, code(JavaKind.Lisp.SUPPLEMENTARY_CHAR));
		a.labelBinding(notChar);
		// A quote-framed string is a Lisp string (length 3: one character), "T" the
		// symbol t, any other string another symbol.
		MethodCode.Label notString = a.newLabel();
		MethodCode.Label symbol = a.newLabel();
		MethodCode.Label longer = a.newLabel();
		a.aload(0);
		a.instanceOf(string);
		a.ifeq(notString);
		a.aload(0);
		a.checkcast(string);
		a.invokevirtual(length);
		a.ifeq(symbol);
		a.aload(0);
		a.checkcast(string);
		a.loadConstant(0);
		a.invokevirtual(method("java/lang/String", "charAt", "(I)C"));
		a.loadConstant('"');
		a.if_icmpne(symbol);
		a.aload(0);
		a.checkcast(string);
		a.invokevirtual(length);
		a.loadConstant(3);
		a.if_icmpne(longer);
		returnCode(a, code(JavaKind.Lisp.STRING_1));
		a.labelBinding(longer);
		returnCode(a, code(JavaKind.Lisp.STRING));
		a.labelBinding(symbol);
		MethodCode.Label other = a.newLabel();
		a.ldc(str("T"));
		a.aload(0);
		a.invokevirtual(method("java/lang/String", "equals", "(Ljava/lang/Object;)Z"));
		a.ifeq(other);
		returnCode(a, code(JavaKind.Lisp.T));
		a.labelBinding(other);
		returnCode(a, KIND_NONE);
		a.labelBinding(notString);
		// An exact Object[] is a function value (an Integer first) or a cons.
		MethodCode.Label notObjects = a.newLabel();
		MethodCode.Label cons = a.newLabel();
		a.aload(0);
		a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
		a.ldc(objects);
		a.if_acmpne(notObjects);
		a.aload(0);
		a.checkcast(objects);
		a.arraylength();
		a.ifeq(cons);
		a.aload(0);
		a.checkcast(objects);
		a.loadConstant(0);
		a.aaload();
		a.instanceOf(cls("java/lang/Integer"));
		a.ifeq(cons);
		returnCode(a, code(JavaKind.Lisp.FUNCTION));
		a.labelBinding(cons);
		returnCode(a, KIND_CONS);
		a.labelBinding(notObjects);
		// An ArrayList whose first element is an Object[] header is a Lisp array.
		MethodCode.Label notArray = a.newLabel();
		a.aload(0);
		a.instanceOf(arrayList);
		a.ifeq(notArray);
		a.aload(0);
		a.checkcast(arrayList);
		a.invokevirtual(method("java/util/ArrayList", "isEmpty", "()Z"));
		a.ifne(notArray);
		a.aload(0);
		a.checkcast(arrayList);
		a.loadConstant(0);
		a.invokevirtual(method("java/util/ArrayList", "get", "(I)Ljava/lang/Object;"));
		a.instanceOf(objects);
		a.ifeq(notArray);
		returnCode(a, KIND_ARRAY);
		a.labelBinding(notArray);
		// A specialized array the program can hold: _jseq reads a rank-1 one.
		for (String shape : packedShapes()) {
			MethodCode.Label next = a.newLabel();
			a.aload(0);
			a.instanceOf(cls(shape));
			a.ifeq(next);
			returnCode(a, KIND_ARRAY);
			a.labelBinding(next);
		}
		// A host object (_jhost), or a value of no kind (a ratio, a hash table).
		MethodCode.Label none = a.newLabel();
		a.aload(0);
		a.invokestatic(hostTest);
		a.ifeq(none);
		returnCode(a, KIND_HOST);
		a.labelBinding(none);
		returnCode(a, KIND_NONE);
		return new Method(name, desc, a);
	}

	private static int code(JavaKind.Lisp kind) {
		return kind.ordinal();
	}

	// The array classes of the specialized arrays the program can hold.
	private List<String> packedShapes() {
		List<String> shapes = new ArrayList<>();
		if (this.floatVectors) {
			shapes.add("[D");
			shapes.add("[F");
			shapes.add("[S");
		}
		if (this.intVectors) {
			shapes.add("[J");
			shapes.add("[B");
		}
		return shapes;
	}

	private static void returnCode(MethodCode a, int code) {
		a.loadConstant(code);
		a.ireturn();
	}

	// _jseq(Object)Object[]: the bridge's element list of a cons or a Lisp array (the
	// value is one: _jkind said so), or null when it is not a sequence -- a dotted list,
	// a list ending in a function value, an array of rank other than 1, a quantized
	// matrix. A packed fixnum vector's Long.MIN_VALUE is nil; a fill pointer bounds the
	// elements; a specialized vector's are the values aref reads (emitPackedElements).
	private Method buildSequence(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		ClassEntry objects = cls("[Ljava/lang/Object;");
		ClassEntry arrayList = cls("java/util/ArrayList");
		MethodRefEntry getClass = method("java/lang/Object", "getClass", "()Ljava/lang/Class;");
		MethodRefEntry get = method("java/util/ArrayList", "get", "(I)Ljava/lang/Object;");
		MethodCode.Label notCons = a.newLabel();
		a.aload(0);
		a.invokevirtual(getClass);
		a.ldc(objects);
		a.if_acmpne(notCons);
		// Count the cells (1 = cell, 2 = count), then copy the cars (3 = out, 4 = i).
		MethodCode.Label countLoop = a.newLabel();
		MethodCode.Label counted = a.newLabel();
		MethodCode.Label improper = a.newLabel();
		a.aload(0);
		a.astore(1);
		a.loadConstant(0);
		a.istore(2);
		a.labelBinding(countLoop);
		a.aload(1);
		a.ifnull(counted);
		a.aload(1);
		a.invokevirtual(getClass);
		a.ldc(objects);
		a.if_acmpne(improper);
		a.aload(1);
		a.checkcast(objects);
		a.arraylength();
		a.loadConstant(2);
		a.if_icmpne(improper);
		a.aload(1);
		a.checkcast(objects);
		a.loadConstant(0);
		a.aaload();
		a.instanceOf(cls("java/lang/Integer"));
		a.ifne(improper);
		a.iinc(2, 1);
		a.aload(1);
		a.checkcast(objects);
		a.loadConstant(1);
		a.aaload();
		a.astore(1);
		a.goto_(countLoop);
		a.labelBinding(improper);
		a.aconst_null();
		a.areturn();
		a.labelBinding(counted);
		MethodCode.Label copyLoop = a.newLabel();
		MethodCode.Label copied = a.newLabel();
		a.iload(2);
		a.anewarray(cls("java/lang/Object"));
		a.astore(3);
		a.aload(0);
		a.astore(1);
		a.loadConstant(0);
		a.istore(4);
		a.labelBinding(copyLoop);
		a.iload(4);
		a.iload(2);
		a.if_icmpge(copied);
		a.aload(3);
		a.iload(4);
		a.aload(1);
		a.checkcast(objects);
		a.loadConstant(0);
		a.aaload();
		a.aastore();
		a.aload(1);
		a.checkcast(objects);
		a.loadConstant(1);
		a.aaload();
		a.astore(1);
		a.iinc(4, 1);
		a.goto_(copyLoop);
		a.labelBinding(copied);
		a.aload(3);
		a.areturn();
		a.labelBinding(notCons);
		for (String shape : packedShapes()) {
			emitPackedElements(a, shape);
		}
		// A Lisp array: slot 0 the {dims, fillPointer, ...} header (1 = header).
		MethodCode.Label rankOne = a.newLabel();
		a.aload(0);
		a.checkcast(arrayList);
		a.loadConstant(0);
		a.invokevirtual(get);
		a.checkcast(objects);
		a.astore(1);
		a.aload(1);
		a.loadConstant(0);
		a.aaload();
		a.instanceOf(objects);
		a.ifeq(improper);
		a.aload(1);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(objects);
		a.arraylength();
		a.loadConstant(1);
		a.if_icmpeq(rankOne);
		a.aconst_null();
		a.areturn();
		a.labelBinding(rankOne);
		// The PACKED shape: a length-6 header holding the long[] (2 = the long[]).
		MethodCode.Label boxed = a.newLabel();
		MethodCode.Label packedLoop = a.newLabel();
		MethodCode.Label packedDone = a.newLabel();
		MethodCode.Label nil = a.newLabel();
		MethodCode.Label stored = a.newLabel();
		a.aload(1);
		a.arraylength();
		a.loadConstant(6);
		a.if_icmpne(boxed);
		a.aload(1);
		a.loadConstant(5);
		a.aaload();
		a.instanceOf(cls("[J"));
		a.ifeq(boxed);
		a.aload(1);
		a.loadConstant(5);
		a.aaload();
		a.checkcast(cls("[J"));
		a.astore(2);
		a.aload(2);
		a.arraylength();
		a.anewarray(cls("java/lang/Object"));
		a.astore(3);
		a.loadConstant(0);
		a.istore(4);
		a.labelBinding(packedLoop);
		a.iload(4);
		a.aload(2);
		a.arraylength();
		a.if_icmpge(packedDone);
		a.aload(3);
		a.iload(4);
		a.aload(2);
		a.iload(4);
		a.laload();
		a.lstore(5);
		a.lload(5);
		a.ldc(this.cp.entries().longEntry(Long.MIN_VALUE));
		a.lcmp();
		a.ifeq(nil);
		a.lload(5);
		a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
		a.goto_(stored);
		a.labelBinding(nil);
		a.aconst_null();
		a.labelBinding(stored);
		a.aastore();
		a.iinc(4, 1);
		a.goto_(packedLoop);
		a.labelBinding(packedDone);
		a.aload(3);
		a.areturn();
		a.labelBinding(boxed);
		// The elements after the header, up to the fill pointer (2 = the count).
		MethodCode.Label noFill = a.newLabel();
		MethodCode.Label count = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(1);
		a.loadConstant(1);
		a.aaload();
		a.instanceOf(cls("java/lang/Long"));
		a.ifeq(noFill);
		a.aload(1);
		a.loadConstant(1);
		a.aaload();
		a.checkcast(cls("java/lang/Long"));
		a.invokevirtual(method("java/lang/Long", "intValue", "()I"));
		a.istore(2);
		a.goto_(count);
		a.labelBinding(noFill);
		a.aload(0);
		a.checkcast(arrayList);
		a.invokevirtual(method("java/util/ArrayList", "size", "()I"));
		a.loadConstant(1);
		a.isub();
		a.istore(2);
		a.labelBinding(count);
		a.iload(2);
		a.anewarray(cls("java/lang/Object"));
		a.astore(3);
		a.loadConstant(0);
		a.istore(4);
		a.labelBinding(loop);
		a.iload(4);
		a.iload(2);
		a.if_icmpge(done);
		a.aload(3);
		a.iload(4);
		a.aload(0);
		a.checkcast(arrayList);
		a.iload(4);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(get);
		a.aastore();
		a.iinc(4, 1);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(3);
		a.areturn();
		return new Method(name, desc, a);
	}

	// One specialized shape's arm of _jseq: when the value is of the array class, its
	// elements after the header -- each a Double read as aref reads it, or a Long -- or
	// null when it is no rank-1 vector. The shapes and their headers: a packed float
	// array double[] / float[] {rank, dim..., e...} and bfloat16 short[] {rank, hi, lo,
	// ..., e...} (JvmPackedFloatWidth, its element through _bf16Value), a packed integer
	// vector long[] {width, e...} and an octet vector byte[] {8, e...} (read unsigned;
	// any other byte[] is a quantized matrix). Locals 7 = the array, 8 = the count, 9 =
	// the elements, 10 = the index.
	private void emitPackedElements(MethodCode a, String shape) {
		int array = 7;
		int count = 8;
		int out = 9;
		int index = 10;
		MethodCode.Label next = a.newLabel();
		MethodCode.Label notVector = a.newLabel();
		a.aload(0);
		a.instanceOf(cls(shape));
		a.ifeq(next);
		a.aload(0);
		a.checkcast(cls(shape));
		a.astore(array);
		int offset;
		switch (shape) {
			case "[D", "[F", "[S" -> {
				JvmPackedFloatWidth width = switch (shape) {
					case "[D" -> JvmPackedFloatWidth.DOUBLE;
					case "[F" -> JvmPackedFloatWidth.SINGLE;
					default -> JvmPackedFloatWidth.BFLOAT16;
				};
				// Rank 1: slot 0 is 1.
				a.aload(array);
				a.loadConstant(0);
				switch (shape) {
					case "[D" -> {
						a.daload();
						a.d2i();
					}
					case "[F" -> {
						a.faload();
						a.f2i();
					}
					default -> a.saload();
				}
				a.loadConstant(1);
				a.if_icmpne(notVector);
				offset = width.dataOffset(1);
			}
			case "[B" -> {
				a.aload(array);
				a.arraylength();
				a.ifeq(notVector);
				a.aload(array);
				a.loadConstant(0);
				a.baload();
				a.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
				a.if_icmpne(notVector);
				offset = 1;
			}
			default -> offset = 1; // [J: the width, then the elements
		}
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(array);
		a.arraylength();
		a.loadConstant(offset);
		a.isub();
		a.istore(count);
		a.iload(count);
		a.anewarray(cls("java/lang/Object"));
		a.astore(out);
		a.loadConstant(0);
		a.istore(index);
		a.labelBinding(loop);
		a.iload(index);
		a.iload(count);
		a.if_icmpge(done);
		a.aload(out);
		a.iload(index);
		a.aload(array);
		a.iload(index);
		a.loadConstant(offset);
		a.iadd();
		MethodRefEntry doubleValueOf = method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
		MethodRefEntry longValueOf = method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
		switch (shape) {
			case "[D" -> {
				a.daload();
				a.invokestatic(doubleValueOf);
			}
			case "[F" -> {
				a.faload();
				a.f2d();
				a.invokestatic(doubleValueOf);
			}
			case "[S" -> {
				a.saload();
				a.invokestatic(Objects.requireNonNull(this.bf16Value, "_bf16Value"));
				a.invokestatic(doubleValueOf);
			}
			case "[J" -> {
				a.laload();
				a.invokestatic(longValueOf);
			}
			default -> {
				a.baload();
				a.loadConstant(0xFF);
				a.iand();
				a.i2l();
				a.invokestatic(longValueOf);
			}
		}
		a.aastore();
		a.iinc(index, 1);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(out);
		a.areturn();
		a.labelBinding(notVector);
		a.aconst_null();
		a.areturn();
		a.labelBinding(next);
	}

	// _jcost$N(Object)I for one type: the bridge's marshal cost -- kindCost of a value's
	// kind, a host object's class against the type, a sequence its base plus its
	// elements' costs -- or NO_MATCH.
	private Method buildCost(Utf8Entry name, Utf8Entry desc, JavaType target, boolean functions) {
		MethodCode a = new MethodCode();
		render(a, 0);
		int code = 1;
		a.aload(0);
		a.invokestatic(kind());
		a.istore(code);
		for (int c = 0; c < LISP_KINDS.length; c++) {
			int cost = JavaOverloads.kindCost(LISP_KINDS[c], target, this.lookup);
			if (cost == JavaOverloads.NO_MATCH || (!functions && LISP_KINDS[c] == JavaKind.Lisp.FUNCTION)) {
				continue;
			}
			MethodCode.Label next = a.newLabel();
			a.iload(code);
			a.loadConstant(c);
			a.if_icmpne(next);
			a.loadConstant(cost);
			a.ireturn();
			a.labelBinding(next);
		}
		JavaType element = sequenceElement(target);
		if (element != null) {
			MethodCode.Label sequenceValue = a.newLabel();
			MethodCode.Label notSequence = a.newLabel();
			MethodCode.Label proper = a.newLabel();
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label done = a.newLabel();
			MethodCode.Label add = a.newLabel();
			int elements = 2;
			int total = 3;
			int index = 4;
			int each = 5;
			a.iload(code);
			a.loadConstant(KIND_CONS);
			a.if_icmpeq(sequenceValue);
			a.iload(code);
			a.loadConstant(KIND_ARRAY);
			a.if_icmpne(notSequence);
			a.labelBinding(sequenceValue);
			a.aload(0);
			a.invokestatic(sequence());
			a.astore(elements);
			a.aload(elements);
			a.ifnonnull(proper);
			a.loadConstant(JavaOverloads.NO_MATCH);
			a.ireturn();
			a.labelBinding(proper);
			a.loadConstant(target.isArray() ? JavaOverloads.COST_CONVERT : JavaOverloads.COST_BOXED);
			a.istore(total);
			a.loadConstant(0);
			a.istore(index);
			a.labelBinding(loop);
			a.iload(index);
			a.aload(elements);
			a.arraylength();
			a.if_icmpge(done);
			a.aload(elements);
			a.iload(index);
			a.aaload();
			a.invokestatic(cost(element, functions));
			a.istore(each);
			a.iload(each);
			a.ifge(add);
			a.loadConstant(JavaOverloads.NO_MATCH);
			a.ireturn();
			a.labelBinding(add);
			a.iload(total);
			a.iload(each);
			a.iadd();
			a.istore(total);
			a.iinc(index, 1);
			a.goto_(loop);
			a.labelBinding(done);
			a.iload(total);
			a.ireturn();
			a.labelBinding(notSequence);
		}
		if (!target.isPrimitive()) {
			// A host object: its exact class, or a subclass, of the type.
			MethodCode.Label notHost = a.newLabel();
			MethodCode.Label widen = a.newLabel();
			ClassEntry type = cls(target);
			a.iload(code);
			a.loadConstant(KIND_HOST);
			a.if_icmpne(notHost);
			a.aload(0);
			a.instanceOf(type);
			a.ifeq(notHost);
			a.aload(0);
			a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
			a.ldc(type);
			a.if_acmpne(widen);
			a.loadConstant(JavaOverloads.COST_EXACT);
			a.ireturn();
			a.labelBinding(widen);
			a.loadConstant(JavaOverloads.COST_WIDEN);
			a.ireturn();
			a.labelBinding(notHost);
		}
		a.loadConstant(JavaOverloads.NO_MATCH);
		a.ireturn();
		return new Method(name, desc, a);
	}

	// _jconv$N(Object)T for one type: the bridge's convert arm of the value's kind, a
	// host object itself, and in the open variant a function's proxy and a sequence's
	// array or list. A value no arm takes was costed NO_MATCH, so the site never passes
	// one.
	private Method buildConvert(Utf8Entry name, Utf8Entry desc, JavaType target, boolean functions, boolean sequences) {
		Body body = new Body(2);
		MethodCode a = body.a;
		int code = 1;
		MethodCode.Label reject = a.newLabel();
		render(a, 0);
		a.aload(0);
		a.invokestatic(kind());
		a.istore(code);
		boolean reference = !target.isPrimitive();
		ClassEntry type = cls(target);
		boolean needsCast = reference && !"java.lang.Object".equals(target.name());
		for (int c = 0; c < LISP_KINDS.length; c++) {
			JavaKind.Lisp kind = LISP_KINDS[c];
			if ((kind == JavaKind.Lisp.FUNCTION && !functions)
					|| JavaOverloads.kindCost(kind, target, this.lookup) == JavaOverloads.NO_MATCH) {
				continue;
			}
			MethodCode.Label next = a.newLabel();
			a.iload(code);
			a.loadConstant(c);
			a.if_icmpne(next);
			body.emitConvert(0, kind, target);
			if (needsCast) {
				a.checkcast(type);
			}
			a.return_(Body.typeKind(target));
			a.labelBinding(next);
		}
		JavaType element = sequences ? sequenceElement(target) : null;
		if (element != null) {
			MethodCode.Label sequenceValue = a.newLabel();
			MethodCode.Label notSequence = a.newLabel();
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label done = a.newLabel();
			int elements = body.nextSlot++;
			int result = body.nextSlot++;
			int index = body.nextSlot++;
			a.iload(code);
			a.loadConstant(KIND_CONS);
			a.if_icmpeq(sequenceValue);
			a.iload(code);
			a.loadConstant(KIND_ARRAY);
			a.if_icmpne(notSequence);
			a.labelBinding(sequenceValue);
			a.aload(0);
			a.invokestatic(sequence());
			a.astore(elements);
			a.aload(elements);
			a.ifnull(reject);
			MethodRefEntry each = convert(element, functions, sequences);
			if (target.isArray()) {
				a.aload(elements);
				a.arraylength();
				body.newArray(element);
			}
			else {
				ClassEntry arrayList = cls("java/util/ArrayList");
				a.new_(arrayList);
				a.dup();
				a.aload(elements);
				a.arraylength();
				a.invokespecial(method("java/util/ArrayList", "<init>", "(I)V"));
			}
			a.astore(result);
			a.loadConstant(0);
			a.istore(index);
			a.labelBinding(loop);
			a.iload(index);
			a.aload(elements);
			a.arraylength();
			a.if_icmpge(done);
			if (target.isArray()) {
				a.aload(result);
				a.checkcast(type);
				a.iload(index);
				a.aload(elements);
				a.iload(index);
				a.aaload();
				a.invokestatic(each);
				a.arrayStore(Body.typeKind(element));
			}
			else {
				a.aload(result);
				a.checkcast(cls("java/util/ArrayList"));
				a.aload(elements);
				a.iload(index);
				a.aaload();
				a.invokestatic(each);
				a.invokevirtual(method("java/util/ArrayList", "add", "(Ljava/lang/Object;)Z"));
				a.pop();
			}
			a.iinc(index, 1);
			a.goto_(loop);
			a.labelBinding(done);
			a.aload(result);
			if (needsCast) {
				a.checkcast(type);
			}
			a.areturn();
			a.labelBinding(notSequence);
		}
		if (reference) {
			a.iload(code);
			a.loadConstant(KIND_HOST);
			a.if_icmpne(reject);
			a.aload(0);
			if (needsCast) {
				a.checkcast(type);
			}
			a.areturn();
		}
		a.labelBinding(reject);
		a.new_(cls("java/lang/IllegalStateException"));
		a.dup();
		a.ldc(str("java interop: the selected overload rejects "));
		a.aload(0);
		a.invokestatic(this.lispToString);
		a.invokevirtual(method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
		a.invokespecial(method("java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V"));
		a.athrow();
		return body.finish(name, desc);
	}

	// --- the shared helpers ---

	// _jhost(Object)Z: the bridge's isJavaObject, test for test -- anything outside the
	// compiled Lisp representation: not a Long/Double/BigInteger/String, not a Java array
	// (characters, ratios, conses, function values, specialized vectors), not a Lisp
	// array (_jlarr), not a Lisp hash table (_jltab), not a travelling runtime class's
	// value (a complex number).
	private Method buildHost(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		MethodRefEntry getClass = method("java/lang/Object", "getClass", "()Ljava/lang/Class;");
		MethodCode.Label no = a.newLabel();
		a.aload(0);
		a.ifnull(no);
		for (String excluded : new String[] { "java/lang/Long", "java/lang/Double", "java/math/BigInteger",
				"java/lang/String" }) {
			a.aload(0);
			a.instanceOf(cls(excluded));
			a.ifne(no);
		}
		a.aload(0);
		a.invokevirtual(getClass);
		a.invokevirtual(method("java/lang/Class", "isArray", "()Z"));
		a.ifne(no);
		// A Lisp array or a Lisp hash table: the shared tests.
		a.aload(0);
		a.invokestatic(lispArray());
		a.ifne(no);
		a.aload(0);
		a.invokestatic(lispTable());
		a.ifne(no);
		// A value of a class that travels with the program (a complex number).
		a.aload(0);
		a.invokevirtual(getClass);
		a.invokevirtual(method("java/lang/Class", "getName", "()Ljava/lang/String;"));
		a.ldc(str(RUNTIME_PACKAGE_PREFIX));
		a.invokevirtual(method("java/lang/String", "startsWith", "(Ljava/lang/String;)Z"));
		a.ifne(no);
		a.loadConstant(1);
		a.ireturn();
		a.labelBinding(no);
		a.loadConstant(0);
		a.ireturn();
		return new Method(name, desc, a);
	}

	// _jlarr(Object)Z: a non-empty ArrayList whose first element is an Object[] header.
	private Method buildLispArray(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		ClassEntry arrayList = cls("java/util/ArrayList");
		MethodCode.Label no = a.newLabel();
		a.aload(0);
		a.instanceOf(arrayList);
		a.ifeq(no);
		a.aload(0);
		a.checkcast(arrayList);
		a.invokevirtual(method("java/util/ArrayList", "isEmpty", "()Z"));
		a.ifne(no);
		a.aload(0);
		a.checkcast(arrayList);
		a.loadConstant(0);
		a.invokevirtual(method("java/util/ArrayList", "get", "(I)Ljava/lang/Object;"));
		a.instanceOf(cls("[Ljava/lang/Object;"));
		a.ireturn();
		a.labelBinding(no);
		a.loadConstant(0);
		a.ireturn();
		return new Method(name, desc, a);
	}

	// _jckarr / _jcktab(Object v, String op)Object: v unless it is an instance of the
	// class a Lisp array / table shares with a host collection and the shared test says
	// it is no Lisp one -- then the interpreter's refusal, a simple-error: throw new
	// RuntimeException(op + " expects ..., got " + _lispToString(v)).
	private Method buildGuard(Utf8Entry name, Utf8Entry desc, String sharedClass, MethodRefEntry lispTest,
			OperandTypes.Kind kind) {
		MethodCode a = new MethodCode();
		MethodCode.Label pass = a.newLabel();
		MethodCode.Label named = a.newLabel();
		a.aload(0);
		a.instanceOf(cls(sharedClass));
		a.ifeq(pass);
		a.aload(0);
		a.invokestatic(lispTest);
		a.ifne(pass);
		// The accessor's type-error over the host value, as the interpreter's refusal of
		// it is: _teRaw's unnamed report, renamed after the operator the site handed in
		// (null for one that is no named operator).
		a.aload(0);
		a.ldc(str(kind.typeName()));
		a.invokestatic(JvmOperandTypeRuntime.self(this.cp, this.thisClass, JvmOperandTypeRuntime.TE_RAW,
				JvmOperandTypeRuntime.TE_RAW_DESC));
		a.aload(1);
		a.ifnonnull(named);
		a.athrow();
		a.labelBinding(named);
		a.aload(1);
		a.ldc(str(OperandTypes.FUNNEL_TYPE));
		a.invokestatic(JvmOperandTypeRuntime.self(this.cp, this.thisClass, JvmOperandTypeRuntime.OP_TYPE_ERR,
				JvmOperandTypeRuntime.OP_TYPE_ERR_DESC));
		a.athrow();
		a.labelBinding(pass);
		a.aload(0);
		a.areturn();
		return new Method(name, desc, a);
	}

	// _jltab(Object)Z: a LinkedHashMap holding an ArrayList under the order key.
	private Method buildLispTable(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		ClassEntry linkedHashMap = cls(RontoHashTable.MAP_CLASS);
		MethodCode.Label no = a.newLabel();
		a.aload(0);
		a.instanceOf(linkedHashMap);
		a.ifeq(no);
		a.aload(0);
		a.checkcast(linkedHashMap);
		a.ldc(str(RontoHashTable.ORDER_KEY));
		a.invokevirtual(method(RontoHashTable.MAP_CLASS, "get", "(Ljava/lang/Object;)Ljava/lang/Object;"));
		a.instanceOf(cls(RontoHashTable.LIST_CLASS));
		a.ireturn();
		a.labelBinding(no);
		a.loadConstant(0);
		a.ireturn();
		return new Method(name, desc, a);
	}

	// _junm(Object)Object: the bridge's unmarshal.
	private Method buildUnmarshal(Utf8Entry name, Utf8Entry desc, MethodRefEntry toList) {
		MethodCode a = new MethodCode();
		MethodRefEntry longValueOf = method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
		MethodRefEntry doubleValueOf = method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
		MethodRefEntry numberLongValue = method("java/lang/Number", "longValue", "()J");
		MethodRefEntry concat = method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		MethodCode.Label notNull = a.newLabel();
		a.aload(0);
		a.ifnonnull(notNull);
		a.aconst_null();
		a.areturn();
		a.labelBinding(notNull);
		// Boolean -> t / nil
		MethodCode.Label notBoolean = a.newLabel();
		MethodCode.Label falseValue = a.newLabel();
		a.aload(0);
		a.instanceOf(cls("java/lang/Boolean"));
		a.ifeq(notBoolean);
		a.aload(0);
		a.checkcast(cls("java/lang/Boolean"));
		a.invokevirtual(method("java/lang/Boolean", "booleanValue", "()Z"));
		a.ifeq(falseValue);
		a.ldc(str("T"));
		a.areturn();
		a.labelBinding(falseValue);
		a.aconst_null();
		a.areturn();
		a.labelBinding(notBoolean);
		// Integer / Short / Byte -> the long; Long / Double -> themselves
		for (String box : new String[] { "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte",
				"java/lang/Double" }) {
			MethodCode.Label next = a.newLabel();
			a.aload(0);
			a.instanceOf(cls(box));
			a.ifeq(next);
			a.aload(0);
			if (!"java/lang/Long".equals(box) && !"java/lang/Double".equals(box)) {
				a.checkcast(cls("java/lang/Number"));
				a.invokevirtual(numberLongValue);
				a.invokestatic(longValueOf);
			}
			a.areturn();
			a.labelBinding(next);
		}
		// Float -> the double
		MethodCode.Label notFloat = a.newLabel();
		a.aload(0);
		a.instanceOf(cls("java/lang/Float"));
		a.ifeq(notFloat);
		a.aload(0);
		a.checkcast(cls("java/lang/Float"));
		a.invokevirtual(method("java/lang/Float", "floatValue", "()F"));
		a.f2d();
		a.invokestatic(doubleValueOf);
		a.areturn();
		a.labelBinding(notFloat);
		// BigInteger -> a Lisp integer: the long when it fits, else the bignum itself
		MethodCode.Label notBignum = a.newLabel();
		MethodCode.Label bignum = a.newLabel();
		ClassEntry bigInteger = cls("java/math/BigInteger");
		a.aload(0);
		a.instanceOf(bigInteger);
		a.ifeq(notBignum);
		a.aload(0);
		a.checkcast(bigInteger);
		a.invokevirtual(method("java/math/BigInteger", "bitLength", "()I"));
		a.loadConstant(64);
		a.if_icmpge(bignum);
		a.aload(0);
		a.checkcast(bigInteger);
		a.invokevirtual(method("java/math/BigInteger", "longValue", "()J"));
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(bignum);
		a.aload(0);
		a.areturn();
		a.labelBinding(notBignum);
		// Character -> int[]{code unit}
		MethodCode.Label notCharacter = a.newLabel();
		a.aload(0);
		a.instanceOf(cls("java/lang/Character"));
		a.ifeq(notCharacter);
		a.loadConstant(1);
		a.newarray(TypeKind.INT);
		a.dup();
		a.loadConstant(0);
		a.aload(0);
		a.checkcast(cls("java/lang/Character"));
		a.invokevirtual(method("java/lang/Character", "charValue", "()C"));
		a.iastore();
		a.areturn();
		a.labelBinding(notCharacter);
		// String -> the quote-framed Lisp string
		MethodCode.Label notString = a.newLabel();
		a.aload(0);
		a.instanceOf(cls("java/lang/String"));
		a.ifeq(notString);
		a.ldc(str("\""));
		a.aload(0);
		a.checkcast(cls("java/lang/String"));
		a.invokevirtual(concat);
		a.ldc(str("\""));
		a.invokevirtual(concat);
		a.areturn();
		a.labelBinding(notString);
		// An array -> a list; any other object stays itself.
		a.aload(0);
		a.invokestatic(toList);
		a.areturn();
		return new Method(name, desc, a);
	}

	// _jarr(Object)Object: the bridge's arrayToList over every array type (elements
	// unmarshalled as Array.get would box them), or the value itself when it is no array.
	private Method buildArrayToList(Utf8Entry name, Utf8Entry desc, MethodRefEntry unmarshal) {
		MethodCode a = new MethodCode();
		MethodRefEntry longValueOf = method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
		MethodRefEntry doubleValueOf = method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
		ClassEntry objectClass = cls("java/lang/Object");
		String[] arrays = { "[Ljava/lang/Object;", "[I", "[J", "[D", "[F", "[S", "[B", "[C", "[Z" };
		for (String array : arrays) {
			MethodCode.Label next = a.newLabel();
			a.aload(0);
			a.instanceOf(cls(array));
			a.ifeq(next);
			// result = nil; array = (T[]) value; i = array.length
			a.aconst_null();
			a.astore(1);
			a.aload(0);
			a.checkcast(cls(array));
			a.astore(2);
			a.aload(2);
			a.arraylength();
			a.istore(3);
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label done = a.newLabel();
			a.labelBinding(loop);
			a.iinc(3, -1);
			a.iload(3);
			a.iflt(done);
			// result = new Object[] { element(i), result }
			a.loadConstant(2);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.aload(2);
			a.iload(3);
			switch (array) {
				case "[Ljava/lang/Object;" -> {
					a.aaload();
					a.invokestatic(unmarshal);
				}
				case "[I" -> {
					a.iaload();
					a.i2l();
					a.invokestatic(longValueOf);
				}
				case "[J" -> {
					a.laload();
					a.invokestatic(longValueOf);
				}
				case "[D" -> {
					a.daload();
					a.invokestatic(doubleValueOf);
				}
				case "[F" -> {
					a.faload();
					a.f2d();
					a.invokestatic(doubleValueOf);
				}
				case "[S" -> {
					a.saload();
					a.i2l();
					a.invokestatic(longValueOf);
				}
				case "[B" -> {
					a.baload();
					a.i2l();
					a.invokestatic(longValueOf);
				}
				case "[C" -> {
					// A char element is a Character, unmarshalled to int[]{c}.
					a.caload();
					a.istore(4);
					a.loadConstant(1);
					a.newarray(TypeKind.INT);
					a.dup();
					a.loadConstant(0);
					a.iload(4);
					a.iastore();
				}
				default -> {
					// boolean[]: t or nil.
					MethodCode.Label falseValue = a.newLabel();
					MethodCode.Label stored = a.newLabel();
					a.baload();
					a.ifeq(falseValue);
					a.ldc(str("T"));
					a.goto_(stored);
					a.labelBinding(falseValue);
					a.aconst_null();
					a.labelBinding(stored);
				}
			}
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.aload(1);
			a.aastore();
			a.astore(1);
			a.goto_(loop);
			a.labelBinding(done);
			a.aload(1);
			a.areturn();
			a.labelBinding(next);
		}
		a.aload(0);
		a.areturn();
		return new Method(name, desc, a);
	}

}
