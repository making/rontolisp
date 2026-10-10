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
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaField;
import am.ik.rontolisp.compiler.JavaImplementation;
import am.ik.rontolisp.compiler.JavaImplementationType;
import am.ik.rontolisp.compiler.JavaImplementations;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaMarkers;
import am.ik.rontolisp.compiler.JavaOverloads;
import am.ik.rontolisp.compiler.JavaSite;
import am.ik.rontolisp.compiler.JavaStaticType;
import am.ik.rontolisp.compiler.JavaType;
import am.ik.rontolisp.compiler.OperandTypes;
import am.ik.rontolisp.runtime.RontoComplex;
import am.ik.rontolisp.runtime.RontoHashTable;
import am.ik.rontolisp.runtime.RontoJavaBytesView;
import am.ik.rontolisp.runtime.RontoJavaCalls;
import am.ik.rontolisp.runtime.RontoJavaHandle;
import am.ik.rontolisp.runtime.RontoJavaListView;
import am.ik.rontolisp.runtime.RontoJavaMapView;
import am.ik.rontolisp.runtime.RontoJavaNumberHandle;
import am.ik.rontolisp.runtime.RontoJavaSetView;
import am.ik.rontolisp.runtime.RontoJavaValue;
import am.ik.rontolisp.runtime.RontoJavaVectorView;
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

	/**
	 * {@code _jrecv(Object)Object}: the object a {@code java:call} on a value that is no
	 * host object is made on -- what a Lisp value of a receiver kind converts to for an
	 * {@code Object} parameter (the bridge's {@code receiverObject}) -- or null.
	 */
	static final String RECEIVER = "_jrecv";

	/** {@code _junm(Object)Object}: a Java value as the Lisp value it stands for. */
	static final String UNMARSHAL = "_junm";

	/** {@code _jarr(Object)Object}: a Java array as a Lisp list, anything else itself. */
	static final String ARRAY_TO_LIST = "_jarr";

	/**
	 * {@code _junf(Object)Object}: {@link #UNMARSHAL} at a call ending in
	 * {@code :java-false}, which answers Java's {@code false} as {@code |false|}.
	 */
	static final String UNMARSHAL_FALSE = "_junf";

	/**
	 * {@code _jarf(Object)Object}: {@link #ARRAY_TO_LIST} over {@link #UNMARSHAL_FALSE}.
	 */
	static final String ARRAY_TO_LIST_FALSE = "_jarf";

	/**
	 * {@code _juno(Object)Object}: {@link #UNMARSHAL} at a call ending in
	 * {@code :octets}, which answers a {@code byte[]} -- the value or an array's element
	 * -- as an {@code (unsigned-byte 8)} vector: a fresh {@code byte[]} of its octets
	 * after the width in slot 0.
	 */
	static final String UNMARSHAL_OCTETS = "_juno";

	/**
	 * {@code _jaro(Object)Object}: {@link #ARRAY_TO_LIST} over {@link #UNMARSHAL_OCTETS}.
	 */
	static final String ARRAY_TO_LIST_OCTETS = "_jaro";

	/**
	 * {@code _jufo(Object)Object}: {@link #UNMARSHAL} at a call ending in both
	 * {@code :java-false} and {@code :octets}.
	 */
	static final String UNMARSHAL_FALSE_OCTETS = "_jufo";

	/**
	 * {@code _jafo(Object)Object}: {@link #ARRAY_TO_LIST} over
	 * {@link #UNMARSHAL_FALSE_OCTETS}.
	 */
	static final String ARRAY_TO_LIST_FALSE_OCTETS = "_jafo";

	// The unmarshal helpers by what the call ends in (unmarshalIndex): none, :java-false,
	// :octets, both.
	private static final String[] UNMARSHALS = { UNMARSHAL, UNMARSHAL_FALSE, UNMARSHAL_OCTETS, UNMARSHAL_FALSE_OCTETS };

	private static final String[] ARRAYS_TO_LIST = { ARRAY_TO_LIST, ARRAY_TO_LIST_FALSE, ARRAY_TO_LIST_OCTETS,
			ARRAY_TO_LIST_FALSE_OCTETS };

	/**
	 * {@code _jcbo(Object[])Object[]}: the arguments Java hands a function of an
	 * implementation made at {@code :octets}, each as {@link #UNMARSHAL_OCTETS} makes it
	 * -- a {@code byte[]} a fresh octet vector -- and an array Java hands twice one
	 * vector, as Java's one array is.
	 */
	static final String CALLBACK_OCTETS = "_jcbo";

	/**
	 * {@code _jcbf(Object[])Object[]}: {@link #CALLBACK_OCTETS} over
	 * {@link #UNMARSHAL_FALSE_OCTETS}, after {@code :java-false} too.
	 */
	static final String CALLBACK_FALSE_OCTETS = "_jcbf";

	/**
	 * {@code _jwbo(Object[] handed, Object[] values)V}: after such a function returns or
	 * throws, what it stored into the octet vector a {@code byte[]} argument became goes
	 * back into Java's array -- the octets after the width, written only where they
	 * differ, so an array the function left alone is never written.
	 */
	static final String WRITE_BACK_OCTETS = "_jwbo";

	/**
	 * {@code _jhandle(Object value, Object text, Object hash, Object order, Object class,
	 * int given)Object}: {@code (java:handle value text hash order class)} of
	 * {@code given} arguments, the absent ones {@code null}: a
	 * {@code runtime/RontoJavaHandle} (a {@code RontoJavaNumberHandle} of a real number).
	 */
	static final String HANDLE = "_jhandle";

	/** {@link #HANDLE}'s descriptor. */
	static final String HANDLE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
			+ "Ljava/lang/Object;I)Ljava/lang/Object;";

	/**
	 * {@code _jview(Object value, Object items, Object shape, Object printer, Object order,
	 * Object class, int given)Object}: {@code (java:view value items shape printer order
	 * class)} of {@code given} arguments, the absent ones {@code null}: a
	 * {@code runtime/RontoJava*View} of the shape.
	 */
	static final String VIEW = "_jview";

	/** {@link #VIEW}'s descriptor. */
	static final String VIEW_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
			+ "Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;";

	// The runtime classes a handle or a view is (runtime/RontoJava*), internal names.
	private static final String JAVA_VALUE = internal(RontoJavaValue.class);

	private static final String JAVA_CALLS = internal(RontoJavaCalls.class);

	private static final String JAVA_HANDLE = internal(RontoJavaHandle.class);

	private static final String JAVA_NUMBER_HANDLE = internal(RontoJavaNumberHandle.class);

	private static final String JAVA_LIST_VIEW = internal(RontoJavaListView.class);

	private static final String JAVA_BYTES_VIEW = internal(RontoJavaBytesView.class);

	private static final String JAVA_VECTOR_VIEW = internal(RontoJavaVectorView.class);

	private static final String JAVA_SET_VIEW = internal(RontoJavaSetView.class);

	private static final String JAVA_MAP_VIEW = internal(RontoJavaMapView.class);

	private static final String JAVA_HANDLE_USAGE = JavaImplementations.HANDLE_USAGE;

	private static final String JAVA_VIEW_USAGE = JavaImplementations.VIEW_USAGE;

	private static final String JAVA_VIEW_NO_VALUE = JavaImplementations.VIEW_NO_VALUE;

	private static String internal(Class<?> type) {
		return type.getName().replace('.', '/');
	}

	/**
	 * {@code _jcmp(Object fn, Object args, Object answer)Object}: what a function
	 * implementing {@code Comparator.compare} answered, read as Clojure's
	 * {@code AFunction.compare} reads it -- an {@code Integer} -- or the
	 * {@code RuntimeException} it throws for the answer
	 * ({@link am.ik.rontolisp.compiler.JavaImplementation#readsComparison}).
	 */
	static final String COMPARISON = "_jcmp";

	/**
	 * {@code _jkind(Object)I}: a value's classification, one of the {@code KIND_} codes.
	 */
	static final String KIND = "_jkind";

	/** {@code _jseq(Object)Object[]}: the elements of a proper list or rank-1 vector. */
	static final String SEQUENCE = "_jseq";

	/**
	 * {@code _jtab(Object)Object[]}: a Lisp hash table's entries, keys and values
	 * alternating.
	 */
	static final String TABLE = "_jtab";

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
	// five below.
	private static final JavaKind.Lisp[] LISP_KINDS = JavaKind.Lisp.values();

	private static final int KIND_CONS = LISP_KINDS.length;

	private static final int KIND_ARRAY = KIND_CONS + 1;

	private static final int KIND_HOST = KIND_CONS + 2;

	private static final int KIND_NONE = KIND_CONS + 3;

	// A Lisp hash table, in a program that can hold one (hashTables).
	private static final int KIND_TABLE = KIND_CONS + 4;

	// A java:view List (runtime/RontoJavaListView), in a program that makes handles or
	// views: no host kind, since where an array is expected it is an array of its items.
	private static final int KIND_VIEW = KIND_CONS + 5;

	// A java:view of shape :bytes (runtime/RontoJavaBytesView), in a program that makes
	// views: the byte[] it hands Java wherever one fits, never a host object.
	private static final int KIND_BYTES = KIND_CONS + 6;

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

	// _junm and its variants, and _jarr's, by unmarshalIndex: built when first asked for.
	private final @Nullable MethodRefEntry[] unmarshals = new MethodRefEntry[UNMARSHALS.length];

	private final @Nullable MethodRefEntry[] arraysToList = new MethodRefEntry[ARRAYS_TO_LIST.length];

	// _jcbo and _jcbf, by whether Java's false is |false|: built when first asked for.
	private final @Nullable MethodRefEntry[] callbackArguments = new MethodRefEntry[2];

	private @Nullable MethodRefEntry writeBack;

	private @Nullable MethodRefEntry comparison;

	private @Nullable MethodRefEntry handle;

	private @Nullable MethodRefEntry view;

	// Whether the program makes a java:handle or a java:view (a runtime/RontoJavaValue),
	// which _junm / _junf then answer the value of and _jhost counts a host object.
	private boolean handles;

	// Whether the program makes a java:view, which a List view's arms in _jkind,
	// _jcost$N and _jconv$N then test for (its class travels only then).
	private boolean views;

	// Whether a handle or view the program makes may call a Lisp function back (a
	// function order, a printer): _jhandle / _jview then make the program's $JavaCalls.
	private boolean callsBack;

	private @Nullable MethodRefEntry kind;

	private @Nullable MethodRefEntry receiver;

	private @Nullable MethodRefEntry sequence;

	private @Nullable MethodRefEntry strv;

	// The specialized vector shapes the program can hold (its gates), and the program's
	// _bf16Value when a bfloat16 vector can be one of them.
	private boolean floatVectors;

	private boolean intVectors;

	private @Nullable MethodRefEntry bf16Value;

	// The program's _hashValues when it can hold a hash table, and _jtab over it.
	private @Nullable MethodRefEntry hashValues;

	private @Nullable MethodRefEntry tableEntries;

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
	 * Names the program's {@code _hashValues} when it can hold a hash table, which a
	 * table argument's entries are read through as the bridge's {@code marshal} reads
	 * them: a table converts to a {@code java.util.LinkedHashMap} where one is expected.
	 * A program without the hash-table runtime never tests for a table.
	 * @param hashValues the program's {@code _hashValues(Object)Object[]}, or
	 * {@code null}
	 */
	void hashTables(@Nullable MethodRefEntry hashValues) {
		this.hashValues = hashValues;
	}

	/**
	 * Says whether the program makes a {@code java:handle} or a {@code java:view}: the
	 * unmarshal helpers then answer the value one Java hands back stands for, and
	 * {@code _jhost} counts one a host object though its class travels with the program.
	 * Decided before any helper is built; a program without one keeps both as they were.
	 * @param handles whether the program names {@code java:handle} or {@code java:view}
	 * @param views whether it names {@code java:view}: a List view is then tested for
	 * where a value is costed and converted
	 * @param callsBack whether one it makes may call a Lisp function back: a
	 * {@code java:view}, or a {@code java:handle} given an order
	 */
	void handles(boolean handles, boolean views, boolean callsBack) {
		this.handles = handles;
		this.views = views;
		this.callsBack = callsBack;
	}

	/**
	 * {@code _jhandle}: {@code (java:handle value text hash order class)}, made when
	 * first asked for.
	 * @return {@code _jhandle(Object,Object,Object,Object,Object,int)Object}
	 */
	MethodRefEntry handleHelper() {
		MethodRefEntry ref = this.handle;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(HANDLE);
			Utf8Entry desc = this.cp.utf8Entry(HANDLE_DESC);
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.handle = ref;
			this.methods.add(buildHandle(name, desc));
		}
		return ref;
	}

	/**
	 * {@code _jview}: {@code (java:view value items shape printer order class)}, made
	 * when first asked for.
	 * @return {@code _jview(Object,Object,Object,Object,Object,Object,int)Object}
	 */
	MethodRefEntry viewHelper() {
		MethodRefEntry ref = this.view;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(VIEW);
			Utf8Entry desc = this.cp.utf8Entry(VIEW_DESC);
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.view = ref;
			this.methods.add(buildView(name, desc));
		}
		return ref;
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
		return unmarshal(false, false);
	}

	/**
	 * {@code _junm}, or at a call ending in {@code :java-false} {@code _junf}, which
	 * answers Java's {@code false} as {@code |false|}: made when first asked for. What
	 * Java hands a function made without {@code :octets} goes through this one.
	 * @param javaFalse whether Java's false is {@code |false|}
	 * @return the helper
	 */
	MethodRefEntry unmarshalHelper(boolean javaFalse) {
		return unmarshal(javaFalse, false);
	}

	/**
	 * {@code _jcbo}, or after {@code :java-false} too {@code _jcbf}: the arguments Java
	 * hands a function made at {@code :octets} as the Lisp values it is handed, made when
	 * first asked for.
	 * @param javaFalse whether Java's false is {@code |false|}
	 * @return {@code (Object[])Object[]}
	 */
	MethodRefEntry callbackArgumentsHelper(boolean javaFalse) {
		int index = javaFalse ? 1 : 0;
		MethodRefEntry ref = this.callbackArguments[index];
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(javaFalse ? CALLBACK_FALSE_OCTETS : CALLBACK_OCTETS);
			Utf8Entry desc = this.cp.utf8Entry("([Ljava/lang/Object;)[Ljava/lang/Object;");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.callbackArguments[index] = ref;
			this.methods.add(buildCallbackArguments(name, desc, unmarshal(javaFalse, true)));
		}
		return ref;
	}

	/**
	 * {@code _jwbo}: after a function made at {@code :octets} returns or throws, what it
	 * stored into the octet vectors {@link #callbackArgumentsHelper} made goes back into
	 * Java's arrays. Made when first asked for.
	 * @return {@code (Object[],Object[])V}
	 */
	MethodRefEntry writeBackHelper() {
		MethodRefEntry ref = this.writeBack;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(WRITE_BACK_OCTETS);
			Utf8Entry desc = this.cp.utf8Entry("([Ljava/lang/Object;[Ljava/lang/Object;)V");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.writeBack = ref;
			this.methods.add(buildWriteBack(name, desc));
		}
		return ref;
	}

	/**
	 * {@code _jcmp}: what a function implementing {@code Comparator.compare} answered as
	 * an {@code Integer}, or the {@code RuntimeException} the comparator throws for it,
	 * made when first asked for. Its false arm calls the function again through
	 * {@code _apply}.
	 * @return {@code _jcmp(Object,Object,Object)Object}
	 */
	MethodRefEntry comparisonHelper() {
		MethodRefEntry ref = this.comparison;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(COMPARISON);
			Utf8Entry desc = this.cp
				.utf8Entry("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.comparison = ref;
			this.methods.add(buildComparison(name, desc));
		}
		return ref;
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

	/**
	 * {@code _jfail}: what a generated subclass's construction dispatcher calls with a
	 * throwable its superclass constructor threw, beside the text. Built by
	 * {@link #finishHelpers}.
	 * @return {@code _jfail(Throwable,String)Throwable}
	 */
	MethodRefEntry failureHelper() {
		return failure();
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
			this.methods.add(buildFailure(signals == null ? null : signals.field(), condPut, nleTl,
					channel.javaExceptionsField));
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
	// new RuntimeException(text + t), which carries no condition; where a landing can
	// catch it (_jexMap), t is recorded under it, so the landing makes the
	// java:java-exception carrying t.
	private Method buildFailure(@Nullable FieldRefEntry signals, @Nullable MethodRefEntry condPut,
			@Nullable FieldRefEntry nleTl, @Nullable FieldRefEntry javaExceptions) {
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
		if (javaExceptions != null) {
			// _jexMap.put(e, t)
			a.dup();
			a.getstatic(javaExceptions);
			a.swap();
			a.aload(0);
			a.invokeinterface(this.cp.interfaceMethodRef("java/util/Map", "put",
					"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
			a.pop();
		}
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
		if (site.functional()) {
			// a function argument converts by its arguments (Body.markers)
			key.append("|functional");
		}
		if (site.javaFalse()) {
			// Java's false answers as |false| (emitUnmarshal, Body.markers)
			key.append("|java-false");
		}
		if (site.octets()) {
			// a byte[] answers as an (unsigned-byte 8) vector (emitUnmarshal)
			key.append("|octets");
		}
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

	/**
	 * The one test a {@code java:} program tells a host object from a Lisp value by --
	 * the bridge's {@code isJavaObject}, test for test. Built on first use.
	 * @return {@code _jhost(Object)Z}
	 */
	MethodRefEntry host() {
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

	// Which of the unmarshal helpers a call ending in these markers takes.
	private static int unmarshalIndex(boolean javaFalse, boolean octets) {
		return (javaFalse ? 1 : 0) | (octets ? 2 : 0);
	}

	// _junm / _junf / _juno / _jufo: the bridge's unmarshal, Java's false answered as
	// |false| after :java-false (_junf, _jufo), a byte[] as an (unsigned-byte 8) vector
	// after :octets (_juno, _jufo).
	private MethodRefEntry unmarshal(boolean javaFalse, boolean octets) {
		int index = unmarshalIndex(javaFalse, octets);
		MethodRefEntry ref = this.unmarshals[index];
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(UNMARSHALS[index]);
			Utf8Entry desc = this.cp.utf8Entry(OBJECT_DESC);
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.unmarshals[index] = ref;
			MethodRefEntry toList = arrayToList(javaFalse, octets);
			this.methods.add(buildUnmarshal(name, desc, toList, javaFalse));
		}
		return ref;
	}

	// _jarr / _jarf / _jaro / _jafo: the arrays-to-lists over the unmarshal of the same
	// markers.
	private MethodRefEntry arrayToList(boolean javaFalse, boolean octets) {
		int index = unmarshalIndex(javaFalse, octets);
		MethodRefEntry ref = this.arraysToList[index];
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(ARRAYS_TO_LIST[index]);
			Utf8Entry desc = this.cp.utf8Entry(OBJECT_DESC);
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.arraysToList[index] = ref;
			// _jarr and _junm call each other (an Object[] element is unmarshalled): the
			// reference is published before the body naming _junm is built.
			this.methods.add(buildArrayToList(name, desc, unmarshal(javaFalse, octets), javaFalse, octets));
		}
		return ref;
	}

	// _jcbo / _jcbf(Object[] handed)Object[]: each argument through UNMARSHAL (_juno /
	// _jufo), but a byte[] an earlier argument is too takes that one's vector, so the
	// function stores into one copy of Java's one array.
	private Method buildCallbackArguments(Utf8Entry name, Utf8Entry desc, MethodRefEntry unmarshal) {
		MethodCode a = new MethodCode();
		ClassEntry bytes = cls("[B");
		// 0 = the handed arguments, 1 = the values, 2 = i, 3 = k, 4 = handed[i]
		a.aload(0);
		a.arraylength();
		a.anewarray(cls("java/lang/Object"));
		a.astore(1);
		a.loadConstant(0);
		a.istore(2);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label next = a.newLabel();
		MethodCode.Label fresh = a.newLabel();
		a.labelBinding(loop);
		a.iload(2);
		a.aload(0);
		a.arraylength();
		a.if_icmpge(done);
		a.aload(0);
		a.iload(2);
		a.aaload();
		a.astore(4);
		a.aload(4);
		a.instanceOf(bytes);
		a.ifeq(fresh);
		// an earlier argument holding the very array: its vector
		a.loadConstant(0);
		a.istore(3);
		MethodCode.Label earlier = a.newLabel();
		MethodCode.Label other = a.newLabel();
		a.labelBinding(earlier);
		a.iload(3);
		a.iload(2);
		a.if_icmpge(fresh);
		a.aload(0);
		a.iload(3);
		a.aaload();
		a.aload(4);
		a.if_acmpne(other);
		a.aload(1);
		a.iload(2);
		a.aload(1);
		a.iload(3);
		a.aaload();
		a.aastore();
		a.goto_(next);
		a.labelBinding(other);
		a.iinc(3, 1);
		a.goto_(earlier);
		a.labelBinding(fresh);
		a.aload(1);
		a.iload(2);
		a.aload(4);
		a.invokestatic(unmarshal);
		a.aastore();
		a.labelBinding(next);
		a.iinc(2, 1);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(1);
		a.areturn();
		return new Method(name, desc, a);
	}

	// _jwbo(Object[] handed, Object[] values)V: each byte[] argument whose octet vector
	// (the width in slot 0, then the octets) differs from it takes the octets back; the
	// values null when the function never ran.
	private Method buildWriteBack(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		ClassEntry bytes = cls("[B");
		// 0 = the handed arguments, 1 = the values, 2 = i, 3 = Java's array,
		// 4 = the vector
		MethodCode.Label end = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label next = a.newLabel();
		a.aload(1);
		a.ifnull(end);
		a.loadConstant(0);
		a.istore(2);
		a.labelBinding(loop);
		a.iload(2);
		a.aload(0);
		a.arraylength();
		a.if_icmpge(end);
		a.aload(0);
		a.iload(2);
		a.aaload();
		a.instanceOf(bytes);
		a.ifeq(next);
		a.aload(1);
		a.iload(2);
		a.aaload();
		a.instanceOf(bytes);
		a.ifeq(next);
		a.aload(0);
		a.iload(2);
		a.aaload();
		a.checkcast(bytes);
		a.astore(3);
		a.aload(1);
		a.iload(2);
		a.aaload();
		a.checkcast(bytes);
		a.astore(4);
		// the vector is a copy one octet longer than the array: anything else is no copy
		a.aload(4);
		a.aload(3);
		a.if_acmpeq(next);
		a.aload(4);
		a.arraylength();
		a.aload(3);
		a.arraylength();
		a.loadConstant(1);
		a.iadd();
		a.if_icmpne(next);
		// unchanged: Java's array keeps what it holds
		a.aload(4);
		a.loadConstant(1);
		a.aload(4);
		a.arraylength();
		a.aload(3);
		a.loadConstant(0);
		a.aload(3);
		a.arraylength();
		a.invokestatic(method("java/util/Arrays", "equals", "([BII[BII)Z"));
		a.ifne(next);
		a.aload(4);
		a.loadConstant(1);
		a.aload(3);
		a.loadConstant(0);
		a.aload(3);
		a.arraylength();
		a.invokestatic(method("java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V"));
		a.labelBinding(next);
		a.iinc(2, 1);
		a.goto_(loop);
		a.labelBinding(end);
		a.return_();
		return new Method(name, desc, a);
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
			this.markers = site.markers();
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
		// isJavaObject) -- or, for java:call, the object a Lisp value of a receiver kind
		// converts to (_jrecv), which then takes the receiver's place -- then an instance
		// of the site's class. A message shows the value the site was handed.
		private void emitReceiverChecks(MethodCode.Label classMissing) {
			int receiver = this.valueSlots[0];
			boolean call = this.site.operator() == JavaSite.Operator.CALL;
			String notAnObject = call ? "java:call expects a java object as the first argument, got "
					: "java:field expects a class-name string or a java object, got ";
			String notAnInstance = this.operator + ": the " + (call ? "receiver" : "object") + " is not a "
					+ this.type.name() + ", got ";
			MethodCode.Label hostObject = this.a.newLabel();
			MethodCode.Label instance = this.a.newLabel();
			this.a.aload(receiver);
			this.a.invokestatic(host());
			this.a.ifne(hostObject);
			if (call) {
				int converted = this.nextSlot++;
				MethodCode.Label lispValue = this.a.newLabel();
				MethodCode.Label convertedInstance = this.a.newLabel();
				this.a.aload(receiver);
				this.a.invokestatic(receiver());
				this.a.astore(converted);
				this.a.aload(converted);
				this.a.ifnonnull(lispValue);
				throwDescribing(this.a, notAnObject, receiver);
				this.a.labelBinding(lispValue);
				MethodCode.Label start = this.a.newBoundLabel();
				this.a.aload(converted);
				this.a.instanceOf(this.owner);
				handle(start, this.a.newBoundLabel(), classMissing, "java/lang/NoClassDefFoundError");
				this.a.ifne(convertedInstance);
				throwDescribing(this.a, notAnInstance, receiver);
				this.a.labelBinding(convertedInstance);
				this.a.aload(converted);
				this.a.astore(receiver);
				this.a.goto_(instance);
			}
			else {
				throwDescribing(this.a, notAnObject, receiver);
			}
			this.a.labelBinding(hostObject);
			MethodCode.Label start = this.a.newBoundLabel();
			this.a.aload(receiver);
			this.a.instanceOf(this.owner);
			handle(start, this.a.newBoundLabel(), classMissing, "java/lang/NoClassDefFoundError");
			this.a.ifne(instance);
			throwDescribing(this.a, notAnInstance, receiver);
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
				List<Integer> handed = bytesHanded(overload);
				emitBytesShared(overload, handed, converted, firstValue);
				stack = Math.max(stack, emitInvoke(executable, converted, hasReceiver, memberFailed));
				emitBytesWriteBack(handed, converted, firstValue);
				emitUnmarshal(executable.isConstructor() ? this.type : executable.returnType());
				a.areturn();
				if (!last) {
					a.labelBinding(nextArm);
				}
			}
			return stack;
		}

		/**
		 * The arguments of an overload's call that may be a {@code :bytes} view handed as
		 * a copy of its octets ({@code runtime/RontoJavaBytesView}): of no kind the site
		 * counted on, at a fixed parameter a {@code byte[]} fits. None where the program
		 * makes no view.
		 */
		private List<Integer> bytesHanded(JavaOverloads.Overload overload) {
			List<Integer> handed = new ArrayList<>();
			if (!JvmJavaDirectSites.this.views) {
				return handed;
			}
			List<JavaSite.Argument> arguments = this.site.arguments();
			List<? extends JavaType> params = overload.executable().parameterTypes();
			int fixed = overload.packed() ? params.size() - 1 : params.size();
			for (int j = 0; j < fixed; j++) {
				JavaSite.Argument argument = arguments.get(j);
				if (!argument.known() && argument.bound() == null
						&& JavaOverloads.bytesViewCost(params.get(j)) != JavaOverloads.NO_MATCH) {
					handed.add(j);
				}
			}
			return handed;
		}

		/**
		 * Before the call, two views of one vector among {@link #bytesHanded} hand Java
		 * one copy, as the oracle hands its one {@code byte[]} twice.
		 */
		private void emitBytesShared(JavaOverloads.Overload overload, List<Integer> handed, int[] converted,
				int firstValue) {
			MethodCode a = this.a;
			List<? extends JavaType> params = overload.executable().parameterTypes();
			MethodRefEntry shared = method(JAVA_BYTES_VIEW, "shared", "(Ljava/lang/Object;Ljava/lang/Object;)Z");
			for (int x = 1; x < handed.size(); x++) {
				for (int y = 0; y < x; y++) {
					int later = handed.get(x);
					int earlier = handed.get(y);
					MethodCode.Label apart = a.newLabel();
					a.aload(this.valueSlots[firstValue + earlier]);
					a.aload(this.valueSlots[firstValue + later]);
					a.invokestatic(shared);
					a.ifeq(apart);
					a.aload(converted[earlier]);
					if (params.get(later).isArray()) {
						a.checkcast(cls("[B"));
					}
					a.astore(converted[later]);
					a.labelBinding(apart);
				}
			}
		}

		/**
		 * After an overload's call, what Java stored into the copy of a {@code :bytes}
		 * view's octets it was handed goes back into the vector
		 * ({@code runtime/RontoJavaBytesView}): every argument {@link #bytesHanded} names
		 * is asked first, then each changed copy is written back, so a vector handed
		 * twice keeps what Java stored rather than its other copy. A varargs tail's
		 * elements are not read back.
		 */
		private void emitBytesWriteBack(List<Integer> handed, int[] converted, int firstValue) {
			if (handed.isEmpty()) {
				return;
			}
			MethodCode a = this.a;
			String pair = "(Ljava/lang/Object;Ljava/lang/Object;)";
			MethodRefEntry changed = method(JAVA_BYTES_VIEW, "changed", pair + "Z");
			MethodRefEntry store = method(JAVA_BYTES_VIEW, "store", pair + "V");
			int[] flags = new int[handed.size()];
			for (int i = 0; i < flags.length; i++) {
				int j = handed.get(i);
				flags[i] = this.nextSlot++;
				a.aload(this.valueSlots[firstValue + j]);
				a.aload(converted[j]);
				a.invokestatic(changed);
				a.istore(flags[i]);
			}
			for (int i = 0; i < flags.length; i++) {
				int j = handed.get(i);
				MethodCode.Label kept = a.newLabel();
				a.iload(flags[i]);
				a.ifeq(kept);
				a.aload(this.valueSlots[firstValue + j]);
				a.aload(converted[j]);
				a.invokestatic(store);
				a.labelBinding(kept);
			}
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
				a.invokestatic(convert(param, open, open, open ? this.markers : JavaMarkers.NONE));
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
					a.invokestatic(convert(component, open, open, open ? this.markers : JavaMarkers.NONE));
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

		// The markers the site ends in: whether a function converts to an interface by
		// its arguments (:functional, JavaImplementations.functional) rather than as its
		// proxy, and whether Java's false answers as |false| (:java-false) -- the value
		// the site answers, an argument Java hands a function converted there.
		JavaMarkers markers = JavaMarkers.NONE;

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
			if (kind instanceof JavaImplementationType implementation && implementation.superclass() != null) {
				// An object a java:subclass of the superclass made: of a class generated
				// for one (JvmJavaImplementations), extending the superclass and
				// implementing exactly the extra interfaces -- as the interpreter
				// compares the kind itself.
				JavaType superclass = implementation.superclass();
				a.aload(slot);
				a.instanceOf(cls(superclass));
				a.ifeq(fail);
				a.aload(slot);
				a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
				a.ldc(cls(superclass));
				a.if_acmpeq(fail);
				for (JavaType iface : implementation.interfaces()) {
					a.aload(slot);
					a.instanceOf(cls(iface));
					a.ifeq(fail);
				}
				a.aload(slot);
				a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
				a.invokevirtual(method("java/lang/Class", "getInterfaces", "()[Ljava/lang/Class;"));
				a.arraylength();
				a.loadConstant(implementation.interfaces().size());
				a.if_icmpne(fail);
				return;
			}
			if (kind instanceof JavaImplementationType implementation) {
				// An object a java:reify / java:proxy of the interfaces made: of a class
				// generated for one (JvmJavaImplementations), implementing exactly them
				// --
				// as the interpreter compares the kind itself.
				a.aload(slot);
				a.instanceOf(cls(implementations().baseClass()));
				a.ifeq(fail);
				for (JavaType iface : implementation.interfaces()) {
					a.aload(slot);
					a.instanceOf(cls(iface));
					a.ifeq(fail);
				}
				a.aload(slot);
				a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
				a.invokevirtual(method("java/lang/Class", "getInterfaces", "()[Ljava/lang/Class;"));
				a.arraylength();
				a.loadConstant(implementation.interfaces().size());
				a.if_icmpne(fail);
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
				case T, FALSE -> {
					a.ldc(str(kind == JavaKind.Lisp.T ? "T" : LispNames.JAVA_FALSE));
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
				case FALSE -> {
					if ("boolean".equals(name)) {
						a.loadConstant(0);
					}
					else {
						a.getstatic(field("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;"));
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
					// bridge makes it a Proxy -- at a :functional site the class
					// implementing its abstract methods by their arguments: of(new
					// Object[] { function }).
					a.loadConstant(1);
					a.anewarray(cls("java/lang/Object"));
					a.dup();
					a.loadConstant(0);
					a.aload(slot);
					a.aastore();
					a.invokestatic(this.markers.functional() ? implementations().functionalFactory(target, this.markers)
							: implementations().proxyFactory(target, this.markers));
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
			boolean javaFalse = this.markers.javaFalse();
			boolean octets = this.markers.octets();
			switch (declared.name()) {
				case "void" -> a.aconst_null();
				case "boolean" -> {
					MethodCode.Label nil = a.newLabel();
					MethodCode.Label end = a.newLabel();
					a.ifeq(nil);
					a.ldc(str("T"));
					a.goto_(end);
					a.labelBinding(nil);
					falseValue(javaFalse);
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
					MethodCode.Label isFalse = a.newLabel();
					MethodCode.Label end = a.newLabel();
					a.astore(this.objectTemp);
					a.aload(this.objectTemp);
					a.ifnull(nil);
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/Boolean"));
					a.invokevirtual(method("java/lang/Boolean", "booleanValue", "()Z"));
					a.ifeq(isFalse);
					a.ldc(str("T"));
					a.goto_(end);
					a.labelBinding(isFalse);
					falseValue(javaFalse);
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
						a.invokestatic(unmarshal(javaFalse, octets));
					}
					// Any other class holds a host object, which stays itself.
				}
			}
		}

		// Pushes what Java's false answers as: |false| at a site ending in :java-false,
		// else nil.
		void falseValue(boolean javaFalse) {
			if (javaFalse) {
				this.a.ldc(str(LispNames.JAVA_FALSE));
			}
			else {
				this.a.aconst_null();
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

	/**
	 * The one conversion a {@code java:} program hands Java a Lisp value by where Java
	 * takes it as one object -- the bridge's {@code receiverObject}: the object a
	 * {@code java:call} on the value is made on, and what {@code _equal} hands a host
	 * object's {@code equals}. Built on first use.
	 * @return {@code _jrecv(Object)Object}
	 */
	MethodRefEntry receiver() {
		MethodRefEntry ref = this.receiver;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(RECEIVER);
			Utf8Entry desc = this.cp.utf8Entry("(Ljava/lang/Object;)Ljava/lang/Object;");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.receiver = ref;
			this.methods.add(buildReceiver(name, desc));
		}
		return ref;
	}

	private MethodRefEntry tableEntries() {
		MethodRefEntry ref = this.tableEntries;
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(TABLE);
			Utf8Entry desc = this.cp.utf8Entry("(Ljava/lang/Object;)[Ljava/lang/Object;");
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.tableEntries = ref;
			this.methods.add(buildTableEntries(name, desc));
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
	 * The cost of a constructor argument for the parameter type: {@code _jcost$N} with
	 * the function arm (a function becomes the interface's generated proxy), for a value
	 * of no static kind.
	 * @param target the parameter type
	 * @return {@code _jcost$N(Object)I}
	 */
	MethodRefEntry argumentCost(JavaType target) {
		return cost(target, true);
	}

	/**
	 * The conversion of such an argument: {@code _jconv$N} with functions proxied and
	 * sequences made arrays or lists.
	 * @param target the parameter type
	 * @return {@code _jconv$N(Object)T}
	 */
	MethodRefEntry argumentConvert(JavaType target) {
		return convert(target, true, true);
	}

	/**
	 * {@link #argumentConvert(JavaType)} at a form ending in these markers: a function
	 * converted by the method's arguments after {@code :functional}, handed Java's
	 * {@code false} as {@code |false|} after {@code :java-false}.
	 * @param target the parameter type
	 * @param markers the form's markers
	 * @return {@code _jconv$N(Object)T}
	 */
	MethodRefEntry argumentConvert(JavaType target, JavaMarkers markers) {
		return convert(target, true, true, markers);
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
		return convert(target, functions, sequences, JavaMarkers.NONE);
	}

	/**
	 * {@code _jconv$N} as above at a site ending in these markers: a function converted
	 * by its arguments after {@code :functional}, and the implementation it becomes
	 * handed Java's {@code false} as {@code |false|} after {@code :java-false} and a
	 * {@code byte[]} as an octet vector after {@code :octets}. The markers only matter
	 * where a function converts, so the key carries them only then.
	 */
	private MethodRefEntry convert(JavaType target, boolean functions, boolean sequences, JavaMarkers markers) {
		JavaMarkers used = functions ? markers : JavaMarkers.NONE;
		String key = target.name() + (functions ? " functions" : "") + (sequences ? " sequences" : "")
				+ (used.functional() ? " functional" : "") + (used.javaFalse() ? " java-false" : "")
				+ (used.octets() ? " octets" : "");
		MethodRefEntry ref = this.converts.get(key);
		if (ref == null) {
			Utf8Entry name = this.cp.utf8Entry(CONVERT_PREFIX + this.converts.size());
			Utf8Entry desc = this.cp.utf8Entry("(Ljava/lang/Object;)" + descriptor(target));
			ref = this.cp.methodRef(this.thisClass, name, desc);
			this.converts.put(key, ref);
			this.methods.add(buildConvert(name, desc, target, functions, sequences, used));
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

	// The type a hash table's keys and values convert to for this parameter: Object for
	// a type a java.util.LinkedHashMap is, in a program that can hold a table, else none.
	private @Nullable JavaType tableEntry(JavaType target) {
		JavaType linkedHashMap = this.lookup.find("java.util.LinkedHashMap");
		if (this.hashValues != null && !target.isPrimitive() && linkedHashMap != null
				&& target.isAssignableFrom(linkedHashMap)) {
			return Objects.requireNonNull(this.lookup.find("java.lang.Object"), "java.lang.Object");
		}
		return null;
	}

	// v = _strv(v): a mutable character vector as the string it spells.
	void render(MethodCode a, int slot) {
		MethodRefEntry render = this.strv;
		if (render != null) {
			a.aload(slot);
			a.invokestatic(render);
			a.astore(slot);
		}
	}

	// _jtab(Object)Object[]: a Lisp hash table's live entries in insertion order, keys
	// and values alternating, read through the program's _hashValues (the pairs maphash
	// walks); an equalp table's key is the one first stored (slot 2), not its fold. The
	// bridge's tableEntries.
	private Method buildTableEntries(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		ClassEntry objects = cls("[Ljava/lang/Object;");
		int pairs = 1;
		int out = 2;
		int index = 3;
		int pair = 4;
		int key = 5;
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label keep = a.newLabel();
		a.aload(0);
		a.invokestatic(Objects.requireNonNull(this.hashValues, "_hashValues"));
		a.astore(pairs);
		a.aload(pairs);
		a.arraylength();
		a.loadConstant(2);
		a.imul();
		a.anewarray(cls("java/lang/Object"));
		a.astore(out);
		a.loadConstant(0);
		a.istore(index);
		a.labelBinding(loop);
		a.iload(index);
		a.aload(pairs);
		a.arraylength();
		a.if_icmpge(done);
		a.aload(pairs);
		a.iload(index);
		a.aaload();
		a.checkcast(objects);
		a.astore(pair);
		a.aload(pair);
		a.loadConstant(0);
		a.aaload();
		a.astore(key);
		a.aload(pair);
		a.arraylength();
		a.loadConstant(2);
		a.if_icmple(keep);
		a.aload(pair);
		a.loadConstant(2);
		a.aaload();
		a.astore(key);
		a.labelBinding(keep);
		a.aload(out);
		a.iload(index);
		a.loadConstant(2);
		a.imul();
		a.aload(key);
		a.aastore();
		a.aload(out);
		a.iload(index);
		a.loadConstant(2);
		a.imul();
		a.loadConstant(1);
		a.iadd();
		a.aload(pair);
		a.loadConstant(1);
		a.aaload();
		a.aastore();
		a.iinc(index, 1);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(out);
		a.areturn();
		return new Method(name, desc, a);
	}

	// _jkind(Object)I: the bridge's kindOf as a code -- the Lisp kinds (LISP_KINDS'
	// index; |false| among them), a cons, a Lisp array (a specialized one too), a host
	// object, a hash table, or none (any other symbol, a ratio) -- tested in its order.
	private Method buildKind(Utf8Entry name, Utf8Entry desc, MethodRefEntry hostTest) {
		MethodCode a = new MethodCode();
		ClassEntry string = cls("java/lang/String");
		ClassEntry objects = cls("[Ljava/lang/Object;");
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
		// symbol t, "false" Java's false (LispNames.JAVA_FALSE), any other string another
		// symbol.
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
		MethodCode.Label otherSymbol = a.newLabel();
		a.ldc(str(LispNames.JAVA_FALSE));
		a.aload(0);
		a.invokevirtual(method("java/lang/String", "equals", "(Ljava/lang/Object;)Z"));
		a.ifeq(otherSymbol);
		returnCode(a, code(JavaKind.Lisp.FALSE));
		a.labelBinding(otherSymbol);
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
		// A Lisp array: the shared test (_jlarr).
		MethodCode.Label notArray = a.newLabel();
		a.aload(0);
		a.invokestatic(lispArray());
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
		// Where views can exist: a java:view List, which _jhost counts a host object,
		// and a :bytes view, which it does not.
		if (this.views) {
			MethodCode.Label notView = a.newLabel();
			a.aload(0);
			a.instanceOf(cls(JAVA_LIST_VIEW));
			a.ifeq(notView);
			returnCode(a, KIND_VIEW);
			a.labelBinding(notView);
			MethodCode.Label notBytes = a.newLabel();
			a.aload(0);
			a.instanceOf(cls(JAVA_BYTES_VIEW));
			a.ifeq(notBytes);
			returnCode(a, KIND_BYTES);
			a.labelBinding(notBytes);
		}
		// A host object (_jhost); then, so a host pays nothing for it, a Lisp hash table
		// when the program can hold one; else a value of no kind (a ratio).
		MethodCode.Label notHost = a.newLabel();
		a.aload(0);
		a.invokestatic(hostTest);
		a.ifeq(notHost);
		returnCode(a, KIND_HOST);
		a.labelBinding(notHost);
		if (this.hashValues != null) {
			MethodCode.Label notTable = a.newLabel();
			a.aload(0);
			a.invokestatic(lispTable());
			a.ifeq(notTable);
			returnCode(a, KIND_TABLE);
			a.labelBinding(notTable);
		}
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
			a.iload(code);
			a.loadConstant(KIND_CONS);
			a.if_icmpeq(sequenceValue);
			a.iload(code);
			a.loadConstant(KIND_ARRAY);
			a.if_icmpne(notSequence);
			a.labelBinding(sequenceValue);
			emitSummedCost(a, sequence(), target.isArray() ? JavaOverloads.COST_CONVERT : JavaOverloads.COST_BOXED,
					cost(element, functions));
			a.labelBinding(notSequence);
		}
		JavaType entry = tableEntry(target);
		if (entry != null) {
			MethodCode.Label notTable = a.newLabel();
			a.iload(code);
			a.loadConstant(KIND_TABLE);
			a.if_icmpne(notTable);
			emitSummedCost(a, tableEntries(), JavaOverloads.COST_BOXED, cost(entry, functions));
			a.labelBinding(notTable);
		}
		if (this.views && !target.isPrimitive()) {
			emitViewCost(a, code, target, functions);
			// A :bytes view: the byte[] it is, where one fits (bytesViewCost).
			int bytesCost = JavaOverloads.bytesViewCost(target);
			if (bytesCost != JavaOverloads.NO_MATCH) {
				MethodCode.Label notBytes = a.newLabel();
				a.iload(code);
				a.loadConstant(KIND_BYTES);
				a.if_icmpne(notBytes);
				a.loadConstant(bytesCost);
				a.ireturn();
				a.labelBinding(notBytes);
			}
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

	// The cost of a java:view List (code KIND_VIEW) in slot 0 for the reference TARGET:
	// a host object's where its class fits (exact or a subclass), else -- an array
	// expected -- COST_VIEW_ARRAY plus its items' costs as the component (this very
	// _jcost$N over the items, which costs them from COST_CONVERT; none for nil), else
	// NO_MATCH. Mirrors eval/JavaInterop's marshalListView.
	private void emitViewCost(MethodCode a, int code, JavaType target, boolean functions) {
		MethodCode.Label notView = a.newLabel();
		MethodCode.Label noMatch = a.newLabel();
		a.iload(code);
		a.loadConstant(KIND_VIEW);
		a.if_icmpne(notView);
		if (target.componentType() == null) {
			ClassEntry type = cls(target);
			MethodCode.Label widen = a.newLabel();
			a.aload(0);
			a.instanceOf(type);
			a.ifeq(noMatch);
			a.aload(0);
			a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
			a.ldc(type);
			a.if_acmpne(widen);
			a.loadConstant(JavaOverloads.COST_EXACT);
			a.ireturn();
			a.labelBinding(widen);
			a.loadConstant(JavaOverloads.COST_WIDEN);
			a.ireturn();
		}
		else {
			MethodCode.Label hasItems = a.newLabel();
			int cost = code + 1;
			a.aload(0);
			a.checkcast(cls(JAVA_LIST_VIEW));
			a.invokevirtual(method(JAVA_LIST_VIEW, "items", "()Ljava/lang/Object;"));
			a.dup();
			a.ifnonnull(hasItems);
			a.pop();
			a.loadConstant(JavaOverloads.COST_VIEW_ARRAY);
			a.ireturn();
			a.labelBinding(hasItems);
			a.invokestatic(cost(target, functions));
			a.istore(cost);
			a.iload(cost);
			a.iflt(noMatch);
			a.iload(cost);
			a.loadConstant(JavaOverloads.COST_VIEW_ARRAY - JavaOverloads.COST_CONVERT);
			a.iadd();
			a.ireturn();
		}
		a.labelBinding(noMatch);
		a.loadConstant(JavaOverloads.NO_MATCH);
		a.ireturn();
		a.labelBinding(notView);
	}

	// Returns the cost of the value in slot 0 whose elements ELEMENTS answers: BASE plus
	// each element's, or NO_MATCH when it answers null or an element does not convert.
	private static void emitSummedCost(MethodCode a, MethodRefEntry elementsOf, int base, MethodRefEntry each) {
		MethodCode.Label proper = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label add = a.newLabel();
		int elements = 2;
		int total = 3;
		int index = 4;
		int cost = 5;
		a.aload(0);
		a.invokestatic(elementsOf);
		a.astore(elements);
		a.aload(elements);
		a.ifnonnull(proper);
		a.loadConstant(JavaOverloads.NO_MATCH);
		a.ireturn();
		a.labelBinding(proper);
		a.loadConstant(base);
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
		a.invokestatic(each);
		a.istore(cost);
		a.iload(cost);
		a.ifge(add);
		a.loadConstant(JavaOverloads.NO_MATCH);
		a.ireturn();
		a.labelBinding(add);
		a.iload(total);
		a.iload(cost);
		a.iadd();
		a.istore(total);
		a.iinc(index, 1);
		a.goto_(loop);
		a.labelBinding(done);
		a.iload(total);
		a.ireturn();
	}

	// _jrecv(Object)Object: the value rendered, then the bridge's convert arm of its kind
	// for an Object parameter when the kind is a receiver kind
	// (JavaOverloads.isReceiverKind), else null. A site calls it only for a value _jhost
	// refused.
	private Method buildReceiver(Utf8Entry name, Utf8Entry desc) {
		Body body = new Body(2);
		MethodCode a = body.a;
		int code = 1;
		JavaType object = Objects.requireNonNull(this.lookup.find("java.lang.Object"), "java.lang.Object");
		render(a, 0);
		a.aload(0);
		a.invokestatic(kind());
		a.istore(code);
		for (int c = 0; c < LISP_KINDS.length; c++) {
			if (!JavaOverloads.isReceiverKind(LISP_KINDS[c])) {
				continue;
			}
			MethodCode.Label next = a.newLabel();
			a.iload(code);
			a.loadConstant(c);
			a.if_icmpne(next);
			body.emitConvert(0, LISP_KINDS[c], object);
			a.areturn();
			a.labelBinding(next);
		}
		if (this.views) {
			// A :bytes view: the byte[] it is to Java.
			MethodCode.Label notBytes = a.newLabel();
			a.iload(code);
			a.loadConstant(KIND_BYTES);
			a.if_icmpne(notBytes);
			a.aload(0);
			a.checkcast(cls(JAVA_BYTES_VIEW));
			a.invokevirtual(method(JAVA_BYTES_VIEW, "bytes", "()[B"));
			a.areturn();
			a.labelBinding(notBytes);
		}
		a.aconst_null();
		a.areturn();
		return body.finish(name, desc);
	}

	// _jconv$N(Object)T for one type: the bridge's convert arm of the value's kind, a
	// host object itself, and in the open variant a function's proxy and a sequence's
	// array or list. A value no arm takes was costed NO_MATCH, so the site never passes
	// one.
	private Method buildConvert(Utf8Entry name, Utf8Entry desc, JavaType target, boolean functions, boolean sequences,
			JavaMarkers markers) {
		Body body = new Body(2);
		body.markers = markers;
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
			MethodRefEntry each = convert(element, functions, sequences, markers);
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
		JavaType entry = sequences ? tableEntry(target) : null;
		if (entry != null) {
			// A fresh LinkedHashMap of the entries, each key and value converted.
			MethodCode.Label notTable = a.newLabel();
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label done = a.newLabel();
			int entries = body.nextSlot++;
			int result = body.nextSlot++;
			int index = body.nextSlot++;
			ClassEntry linkedHashMap = cls("java/util/LinkedHashMap");
			MethodRefEntry each = convert(entry, functions, sequences, markers);
			a.iload(code);
			a.loadConstant(KIND_TABLE);
			a.if_icmpne(notTable);
			a.aload(0);
			a.invokestatic(tableEntries());
			a.astore(entries);
			a.new_(linkedHashMap);
			a.dup();
			a.invokespecial(method("java/util/LinkedHashMap", "<init>", "()V"));
			a.astore(result);
			a.loadConstant(0);
			a.istore(index);
			a.labelBinding(loop);
			a.iload(index);
			a.aload(entries);
			a.arraylength();
			a.if_icmpge(done);
			a.aload(result);
			a.checkcast(linkedHashMap);
			a.aload(entries);
			a.iload(index);
			a.aaload();
			a.invokestatic(each);
			a.aload(entries);
			a.iload(index);
			a.loadConstant(1);
			a.iadd();
			a.aaload();
			a.invokestatic(each);
			a.invokevirtual(method("java/util/LinkedHashMap", "put",
					"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
			a.pop();
			a.iinc(index, 2);
			a.goto_(loop);
			a.labelBinding(done);
			a.aload(result);
			if (needsCast) {
				a.checkcast(type);
			}
			a.areturn();
			a.labelBinding(notTable);
		}
		if (this.views && reference) {
			// A java:view List: itself where its class fits (its cost said so), else an
			// array of its items (none for nil).
			MethodCode.Label notView = a.newLabel();
			a.iload(code);
			a.loadConstant(KIND_VIEW);
			a.if_icmpne(notView);
			JavaType component = target.componentType();
			if (component == null || !sequences) {
				a.aload(0);
				if (needsCast) {
					a.checkcast(type);
				}
				a.areturn();
			}
			else {
				MethodCode.Label hasItems = a.newLabel();
				a.aload(0);
				a.checkcast(cls(JAVA_LIST_VIEW));
				a.invokevirtual(method(JAVA_LIST_VIEW, "items", "()Ljava/lang/Object;"));
				a.dup();
				a.ifnonnull(hasItems);
				a.pop();
				a.loadConstant(0);
				body.newArray(component);
				a.checkcast(type);
				a.areturn();
				a.labelBinding(hasItems);
				a.invokestatic(convert(target, functions, sequences, markers));
				a.areturn();
			}
			a.labelBinding(notView);
			if (JavaOverloads.bytesViewCost(target) != JavaOverloads.NO_MATCH) {
				// A :bytes view: a copy of its octets, which the site writes back.
				MethodCode.Label notBytes = a.newLabel();
				a.iload(code);
				a.loadConstant(KIND_BYTES);
				a.if_icmpne(notBytes);
				a.aload(0);
				a.checkcast(cls(JAVA_BYTES_VIEW));
				a.invokevirtual(method(JAVA_BYTES_VIEW, "bytes", "()[B"));
				a.areturn();
				a.labelBinding(notBytes);
			}
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
		if (this.handles) {
			// A handle or a view (runtime/RontoJavaValue) is a host object, though its
			// class travels with the program.
			MethodCode.Label notValue = a.newLabel();
			a.aload(0);
			a.instanceOf(cls(JAVA_VALUE));
			a.ifeq(notValue);
			a.loadConstant(1);
			a.ireturn();
			a.labelBinding(notValue);
		}
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
	// The class is exact (a Lisp array is never a subclass of it): a host subclass's own
	// isEmpty / get are never asked.
	private Method buildLispArray(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		ClassEntry arrayList = cls("java/util/ArrayList");
		MethodCode.Label no = a.newLabel();
		emitExactClass(a, arrayList, no);
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

	// _jltab(Object)Z: a LinkedHashMap holding an ArrayList under the order key. The
	// class is exact, as _jlarr's is: a host subclass's own get is never asked.
	private Method buildLispTable(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		ClassEntry linkedHashMap = cls(RontoHashTable.MAP_CLASS);
		MethodCode.Label no = a.newLabel();
		emitExactClass(a, linkedHashMap, no);
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

	// if (v == null || v.getClass() != exact) goto no -- v in local 0.
	private void emitExactClass(MethodCode a, ClassEntry exact, MethodCode.Label no) {
		a.aload(0);
		a.ifnull(no);
		a.aload(0);
		a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
		a.ldc(exact);
		a.if_acmpne(no);
	}

	// _junm(Object)Object: the bridge's unmarshal; _junf with javaFalse, Java's false
	// answered as |false| (the bridge's unmarshal at a call ending in :java-false).
	private Method buildUnmarshal(Utf8Entry name, Utf8Entry desc, MethodRefEntry toList, boolean javaFalse) {
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
		// Boolean -> t / nil (|false| when javaFalse)
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
		if (javaFalse) {
			a.ldc(str(LispNames.JAVA_FALSE));
		}
		else {
			a.aconst_null();
		}
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
		if (this.handles) {
			// A handle or a view (runtime/RontoJavaValue) -> the value it stands for.
			ClassEntry valueClass = cls(JAVA_VALUE);
			MethodCode.Label notValue = a.newLabel();
			a.aload(0);
			a.instanceOf(valueClass);
			a.ifeq(notValue);
			a.aload(0);
			a.checkcast(valueClass);
			a.invokeinterface(interfaceMethod(JAVA_VALUE, "value", "()Ljava/lang/Object;"));
			a.areturn();
			a.labelBinding(notValue);
		}
		// An array -> a list; any other object stays itself.
		a.aload(0);
		a.invokestatic(toList);
		a.areturn();
		return new Method(name, desc, a);
	}

	// _jhandle(Object value, Object text, Object hash, Object order, Object cls, int
	// given)Object, GIVEN the number of the form's arguments (the absent ones null):
	// eval/JavaInterop.handle, check for check and message for message -- a
	// runtime/RontoJavaHandle of the value, the unquoted text (nil: Object's spelling,
	// equal only to a handle of the value, which needs a nil hash), the hash's low 32
	// bits
	// (the text's hashCode when absent; nil: the value's identity hash), the order (an
	// unquoted text, the text when absent; nil: none; a function: asked through the
	// program's $JavaCalls) and the unquoted class; a RontoJavaNumberHandle around it
	// when the value is a real number. Every string is rendered first (a mutable
	// character vector as the string it spells).
	private Method buildHandle(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		ClassEntry handleClass = cls(JAVA_HANDLE);
		ClassEntry numberClass = cls(JAVA_NUMBER_HANDLE);
		ClassEntry longClass = cls("java/lang/Long");
		ClassEntry doubleClass = cls("java/lang/Double");
		ClassEntry bigInteger = cls("java/math/BigInteger");
		ClassEntry ratio = cls("[Ljava/math/BigInteger;");
		ClassEntry bigDecimal = cls("java/math/BigDecimal");
		String usage = JAVA_HANDLE_USAGE + ", got ";
		int text = 6;
		int identity = 7;
		int hash = 8;
		int orderMode = 9;
		int order = 10;
		int className = 11;
		int handle = 12;
		int quotient = 13;
		MethodCode.Label badText = a.newLabel();
		MethodCode.Label badHash = a.newLabel();
		MethodCode.Label badOrder = a.newLabel();
		MethodCode.Label badClass = a.newLabel();
		// the text: nil, or a string
		MethodCode.Label textRead = a.newLabel();
		MethodCode.Label textGiven = a.newLabel();
		render(a, 1);
		a.aload(1);
		a.ifnonnull(textGiven);
		a.aconst_null();
		a.astore(text);
		a.goto_(textRead);
		a.labelBinding(textGiven);
		emitUnquoted(a, 1, badText);
		a.astore(text);
		a.labelBinding(textRead);
		// the hash: the text's when absent, nil for the identity, an integer's low bits
		MethodCode.Label hashGiven = a.newLabel();
		MethodCode.Label hashRead = a.newLabel();
		MethodCode.Label notNil = a.newLabel();
		MethodCode.Label notLong = a.newLabel();
		a.loadConstant(0);
		a.istore(identity);
		a.iload(5);
		a.loadConstant(3);
		a.if_icmpge(hashGiven);
		a.aload(text);
		a.ifnull(badText);
		a.aload(text);
		a.invokevirtual(method("java/lang/String", "hashCode", "()I"));
		a.istore(hash);
		a.goto_(hashRead);
		a.labelBinding(hashGiven);
		a.aload(2);
		a.ifnonnull(notNil);
		a.loadConstant(1);
		a.istore(identity);
		a.loadConstant(0);
		a.istore(hash);
		a.goto_(hashRead);
		a.labelBinding(notNil);
		a.aload(2);
		a.instanceOf(longClass);
		a.ifeq(notLong);
		a.aload(2);
		a.checkcast(longClass);
		a.invokevirtual(method("java/lang/Long", "intValue", "()I"));
		a.istore(hash);
		a.goto_(hashRead);
		a.labelBinding(notLong);
		a.aload(2);
		a.instanceOf(bigInteger);
		a.ifeq(badHash);
		a.aload(2);
		a.checkcast(bigInteger);
		a.invokevirtual(method("java/math/BigInteger", "intValue", "()I"));
		a.istore(hash);
		a.labelBinding(hashRead);
		// a handle with no text is equal only to a handle of its value
		MethodCode.Label textOrIdentity = a.newLabel();
		a.aload(text);
		a.ifnonnull(textOrIdentity);
		a.iload(identity);
		a.ifeq(badText);
		a.labelBinding(textOrIdentity);
		// the order: the text when absent, nil none, a string, or a function
		MethodCode.Label orderGiven = a.newLabel();
		MethodCode.Label orderRead = a.newLabel();
		MethodCode.Label orderNotNil = a.newLabel();
		MethodCode.Label orderNotString = a.newLabel();
		MethodCode.Label noText = a.newLabel();
		a.iload(5);
		a.loadConstant(4);
		a.if_icmpge(orderGiven);
		a.aload(text);
		a.astore(order);
		a.aload(text);
		a.ifnull(noText);
		a.loadConstant(RontoJavaHandle.ORDER_TEXT);
		a.istore(orderMode);
		a.goto_(orderRead);
		a.labelBinding(noText);
		a.loadConstant(RontoJavaHandle.ORDER_NONE);
		a.istore(orderMode);
		a.goto_(orderRead);
		a.labelBinding(orderGiven);
		a.aload(3);
		a.ifnonnull(orderNotNil);
		a.aconst_null();
		a.astore(order);
		a.loadConstant(RontoJavaHandle.ORDER_NONE);
		a.istore(orderMode);
		a.goto_(orderRead);
		a.labelBinding(orderNotNil);
		render(a, 3);
		emitUnquoted(a, 3, orderNotString);
		a.astore(order);
		a.loadConstant(RontoJavaHandle.ORDER_TEXT);
		a.istore(orderMode);
		a.goto_(orderRead);
		a.labelBinding(orderNotString);
		emitFunctionTest(a, 3, badOrder);
		a.aload(3);
		a.astore(order);
		a.loadConstant(RontoJavaHandle.ORDER_FUNCTION);
		a.istore(orderMode);
		a.labelBinding(orderRead);
		// the class: nil or absent none, else a string
		MethodCode.Label noClass = a.newLabel();
		MethodCode.Label classRead = a.newLabel();
		a.iload(5);
		a.loadConstant(5);
		a.if_icmplt(noClass);
		a.aload(4);
		a.ifnull(noClass);
		render(a, 4);
		emitUnquoted(a, 4, badClass);
		a.astore(className);
		a.goto_(classRead);
		a.labelBinding(noClass);
		a.aconst_null();
		a.astore(className);
		a.labelBinding(classRead);
		// new RontoJavaHandle(value, text, identity, hash, mode, order, class, calls)
		a.new_(handleClass);
		a.dup();
		a.aload(0);
		a.aload(text);
		a.iload(identity);
		a.iload(hash);
		a.iload(orderMode);
		a.aload(order);
		a.aload(className);
		if (this.callsBack) {
			MethodCode.Label noCalls = a.newLabel();
			MethodCode.Label called = a.newLabel();
			ClassEntry calls = cls(implementations().callsClass());
			a.iload(orderMode);
			a.loadConstant(RontoJavaHandle.ORDER_FUNCTION);
			a.if_icmpne(noCalls);
			a.new_(calls);
			a.dup();
			a.invokespecial(this.cp.methodRef(calls, "<init>", "()V"));
			a.goto_(called);
			a.labelBinding(noCalls);
			a.aconst_null();
			a.labelBinding(called);
		}
		else {
			a.aconst_null();
		}
		a.invokespecial(this.cp.methodRef(handleClass, "<init>",
				"(Ljava/lang/Object;Ljava/lang/String;ZIILjava/lang/Object;Ljava/lang/String;L" + JAVA_CALLS + ";)V"));
		a.astore(handle);
		// a real number's handle is a RontoJavaNumberHandle of it
		String numberInit = "(L" + JAVA_HANDLE + ";DJI)V";
		MethodCode.Label notLongValue = a.newLabel();
		MethodCode.Label notDoubleValue = a.newLabel();
		MethodCode.Label notBignumValue = a.newLabel();
		MethodCode.Label notRatioValue = a.newLabel();
		a.aload(0);
		a.instanceOf(longClass);
		a.ifeq(notLongValue);
		a.new_(numberClass);
		a.dup();
		a.aload(handle);
		a.aload(0);
		a.checkcast(longClass);
		a.invokevirtual(method("java/lang/Long", "doubleValue", "()D"));
		a.aload(0);
		a.checkcast(longClass);
		a.invokevirtual(method("java/lang/Long", "longValue", "()J"));
		a.aload(0);
		a.checkcast(longClass);
		a.invokevirtual(method("java/lang/Long", "intValue", "()I"));
		a.invokespecial(this.cp.methodRef(numberClass, "<init>", numberInit));
		a.areturn();
		a.labelBinding(notLongValue);
		a.aload(0);
		a.instanceOf(doubleClass);
		a.ifeq(notDoubleValue);
		a.new_(numberClass);
		a.dup();
		a.aload(handle);
		a.aload(0);
		a.checkcast(doubleClass);
		a.invokevirtual(method("java/lang/Double", "doubleValue", "()D"));
		a.aload(0);
		a.checkcast(doubleClass);
		a.invokevirtual(method("java/lang/Double", "longValue", "()J"));
		a.aload(0);
		a.checkcast(doubleClass);
		a.invokevirtual(method("java/lang/Double", "intValue", "()I"));
		a.invokespecial(this.cp.methodRef(numberClass, "<init>", numberInit));
		a.areturn();
		a.labelBinding(notDoubleValue);
		a.aload(0);
		a.instanceOf(bigInteger);
		a.ifeq(notBignumValue);
		a.new_(numberClass);
		a.dup();
		a.aload(handle);
		a.aload(0);
		a.checkcast(bigInteger);
		a.invokevirtual(method("java/math/BigInteger", "doubleValue", "()D"));
		a.aload(0);
		a.checkcast(bigInteger);
		a.invokevirtual(method("java/math/BigInteger", "longValue", "()J"));
		a.aload(0);
		a.checkcast(bigInteger);
		a.invokevirtual(method("java/math/BigInteger", "intValue", "()I"));
		a.invokespecial(this.cp.methodRef(numberClass, "<init>", numberInit));
		a.areturn();
		a.labelBinding(notBignumValue);
		a.aload(0);
		a.instanceOf(ratio);
		a.ifeq(notRatioValue);
		// Clojure's Ratio: new BigDecimal(num).divide(new BigDecimal(den), DECIMAL64)'s
		// double, its int the intValue; num.divide(den)'s low 64 bits the longValue
		MethodRefEntry decimalOf = method("java/math/BigDecimal", "<init>", "(Ljava/math/BigInteger;)V");
		a.new_(bigDecimal);
		a.dup();
		a.aload(0);
		a.checkcast(ratio);
		a.loadConstant(0);
		a.aaload();
		a.invokespecial(decimalOf);
		a.new_(bigDecimal);
		a.dup();
		a.aload(0);
		a.checkcast(ratio);
		a.loadConstant(1);
		a.aaload();
		a.invokespecial(decimalOf);
		a.getstatic(field("java/math/MathContext", "DECIMAL64", "Ljava/math/MathContext;"));
		a.invokevirtual(method("java/math/BigDecimal", "divide",
				"(Ljava/math/BigDecimal;Ljava/math/MathContext;)Ljava/math/BigDecimal;"));
		a.invokevirtual(method("java/math/BigDecimal", "doubleValue", "()D"));
		a.dstore(quotient);
		a.new_(numberClass);
		a.dup();
		a.aload(handle);
		a.dload(quotient);
		a.aload(0);
		a.checkcast(ratio);
		a.loadConstant(0);
		a.aaload();
		a.aload(0);
		a.checkcast(ratio);
		a.loadConstant(1);
		a.aaload();
		a.invokevirtual(method("java/math/BigInteger", "divide", "(Ljava/math/BigInteger;)Ljava/math/BigInteger;"));
		a.invokevirtual(method("java/math/BigInteger", "longValue", "()J"));
		a.dload(quotient);
		a.d2i();
		a.invokespecial(this.cp.methodRef(numberClass, "<init>", numberInit));
		a.areturn();
		a.labelBinding(notRatioValue);
		a.aload(handle);
		a.areturn();
		a.labelBinding(badText);
		throwDescribing(a, usage, 1);
		a.labelBinding(badHash);
		throwDescribing(a, usage, 2);
		a.labelBinding(badOrder);
		throwDescribing(a, usage, 3);
		a.labelBinding(badClass);
		throwDescribing(a, usage, 4);
		return new Method(name, desc, a);
	}

	// Jumps to REFUSED unless the value in SLOT is a function value: an exact Object[]
	// whose first element is an Integer.
	private void emitFunctionTest(MethodCode a, int slot, MethodCode.Label refused) {
		ClassEntry objects = cls("[Ljava/lang/Object;");
		a.aload(slot);
		a.ifnull(refused);
		a.aload(slot);
		a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
		a.ldc(objects);
		a.if_acmpne(refused);
		a.aload(slot);
		a.checkcast(objects);
		a.arraylength();
		a.ifeq(refused);
		a.aload(slot);
		a.checkcast(objects);
		a.loadConstant(0);
		a.aaload();
		a.instanceOf(cls("java/lang/Integer"));
		a.ifeq(refused);
	}

	// _jview(Object value, Object items, Object shape, Object printer, Object order,
	// Object cls, int given)Object, GIVEN the number of the form's arguments (the absent
	// ones null): eval/JavaInterop.view, check for check and message for message -- the
	// shape a keyword's name (:LIST, :VECTOR, :SET, :MAP), the printer a function or nil,
	// the order a function (a :vector's) or nil, the class a string or nil, the items a
	// sequence (a :map's a hash table or a plist), each converted as an Object argument
	// (_jcost$N / _jconv$N); then the runtime/RontoJava*View of the shape, calling the
	// printer and the order through the program's $JavaCalls.
	private Method buildView(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		ClassEntry objects = cls("[Ljava/lang/Object;");
		ClassEntry string = cls("java/lang/String");
		MethodRefEntry equals = method("java/lang/String", "equals", "(Ljava/lang/Object;)Z");
		String usage = JAVA_VIEW_USAGE + ", got ";
		JavaType object = Objects.requireNonNull(this.lookup.find("java.lang.Object"), "java.lang.Object");
		int shape = 7;
		int printer = 8;
		int order = 9;
		int className = 10;
		int members = 11;
		int elements = 12;
		int index = 13;
		int calls = 14;
		MethodCode.Label badShape = a.newLabel();
		MethodCode.Label badPrinter = a.newLabel();
		MethodCode.Label badOrder = a.newLabel();
		MethodCode.Label badClass = a.newLabel();
		MethodCode.Label badItems = a.newLabel();
		MethodCode.Label noValue = a.newLabel();
		// the shape: one of the five keywords, compiled to their names
		String[] shapes = { LispNames.JAVA_VIEW_LIST, LispNames.JAVA_VIEW_VECTOR, LispNames.JAVA_VIEW_SET,
				LispNames.JAVA_VIEW_MAP, LispNames.JAVA_VIEW_BYTES };
		MethodCode.Label shapeRead = a.newLabel();
		a.aload(2);
		a.instanceOf(string);
		a.ifeq(badShape);
		for (int s = 0; s < shapes.length; s++) {
			MethodCode.Label next = a.newLabel();
			a.ldc(str(shapes[s]));
			a.aload(2);
			a.invokevirtual(equals);
			a.ifeq(next);
			a.loadConstant(s);
			a.istore(shape);
			a.goto_(shapeRead);
			a.labelBinding(next);
		}
		a.goto_(badShape);
		a.labelBinding(shapeRead);
		// the printer: absent or nil none, else a function
		MethodCode.Label printerRead = a.newLabel();
		a.aconst_null();
		a.astore(printer);
		a.iload(6);
		a.loadConstant(4);
		a.if_icmplt(printerRead);
		a.aload(3);
		a.ifnull(printerRead);
		emitFunctionTest(a, 3, badPrinter);
		a.aload(3);
		a.astore(printer);
		a.labelBinding(printerRead);
		// the order: absent or nil none, else a :vector's function
		MethodCode.Label orderRead = a.newLabel();
		a.aconst_null();
		a.astore(order);
		a.iload(6);
		a.loadConstant(5);
		a.if_icmplt(orderRead);
		a.aload(4);
		a.ifnull(orderRead);
		a.iload(shape);
		a.loadConstant(1);
		a.if_icmpne(badOrder);
		emitFunctionTest(a, 4, badOrder);
		a.aload(4);
		a.astore(order);
		a.labelBinding(orderRead);
		// the class: absent or nil none, else a string
		MethodCode.Label classRead = a.newLabel();
		a.aconst_null();
		a.astore(className);
		a.iload(6);
		a.loadConstant(6);
		a.if_icmplt(classRead);
		a.aload(5);
		a.ifnull(classRead);
		render(a, 5);
		emitUnquoted(a, 5, badClass);
		a.astore(className);
		a.labelBinding(classRead);
		// a :bytes view: the octet vector, a byte[] after its width that only a program
		// holding one makes, which Java is handed a copy of
		MethodCode.Label notBytes = a.newLabel();
		a.iload(shape);
		a.loadConstant(4);
		a.if_icmpne(notBytes);
		if (this.intVectors) {
			ClassEntry bytes = cls("[B");
			a.aload(1);
			a.instanceOf(bytes);
			a.ifeq(badItems);
			a.aload(1);
			a.checkcast(bytes);
			a.arraylength();
			a.ifeq(badItems);
			a.aload(1);
			a.checkcast(bytes);
			a.loadConstant(0);
			a.baload();
			a.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
			a.if_icmpne(badItems);
			ClassEntry view = cls(JAVA_BYTES_VIEW);
			a.new_(view);
			a.dup();
			a.aload(0);
			a.aload(1);
			a.checkcast(bytes);
			a.loadConstant(1);
			a.invokespecial(this.cp.methodRef(view, "<init>", "(Ljava/lang/Object;[BI)V"));
			a.areturn();
		}
		else {
			a.goto_(badItems);
		}
		a.labelBinding(notBytes);
		// the members: none for nil; a :map's hash table's entries; else a sequence's
		// elements (a :map's a plist: a list of even length)
		MethodCode.Label membersRead = a.newLabel();
		MethodCode.Label aSequence = a.newLabel();
		MethodCode.Label notMap = a.newLabel();
		a.aload(1);
		a.ifnonnull(aSequence);
		a.loadConstant(0);
		a.anewarray(cls("java/lang/Object"));
		a.astore(members);
		a.goto_(membersRead);
		a.labelBinding(aSequence);
		a.iload(shape);
		a.loadConstant(3);
		a.if_icmpne(notMap);
		if (this.hashValues != null) {
			MethodCode.Label notTable = a.newLabel();
			a.aload(1);
			a.invokestatic(lispTable());
			a.ifeq(notTable);
			a.aload(1);
			a.invokestatic(tableEntries());
			a.astore(members);
			a.goto_(membersRead);
			a.labelBinding(notTable);
		}
		// a plist: a cons whose cells hold an even count
		a.aload(1);
		a.invokestatic(kind());
		a.loadConstant(KIND_CONS);
		a.if_icmpne(badItems);
		a.aload(1);
		a.invokestatic(sequence());
		a.astore(members);
		a.aload(members);
		a.ifnull(badItems);
		a.aload(members);
		a.checkcast(objects);
		a.arraylength();
		a.loadConstant(2);
		a.irem();
		a.ifne(badItems);
		a.goto_(membersRead);
		a.labelBinding(notMap);
		MethodCode.Label sequenceKind = a.newLabel();
		a.aload(1);
		a.invokestatic(kind());
		a.dup();
		a.loadConstant(KIND_CONS);
		a.if_icmpeq(sequenceKind);
		a.loadConstant(KIND_ARRAY);
		a.if_icmpne(badItems);
		a.aload(1);
		a.invokestatic(sequence());
		a.astore(members);
		a.aload(members);
		a.ifnull(badItems);
		a.goto_(membersRead);
		a.labelBinding(sequenceKind);
		a.pop();
		a.aload(1);
		a.invokestatic(sequence());
		a.astore(members);
		a.aload(members);
		a.ifnull(badItems);
		a.labelBinding(membersRead);
		// each member as an Object argument
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(members);
		a.checkcast(objects);
		a.arraylength();
		a.anewarray(cls("java/lang/Object"));
		a.astore(elements);
		a.loadConstant(0);
		a.istore(index);
		a.labelBinding(loop);
		a.iload(index);
		a.aload(members);
		a.checkcast(objects);
		a.arraylength();
		a.if_icmpge(done);
		a.aload(members);
		a.checkcast(objects);
		a.iload(index);
		a.aaload();
		a.invokestatic(argumentCost(object));
		a.iflt(noValue);
		a.aload(elements);
		a.checkcast(objects);
		a.iload(index);
		a.aload(members);
		a.checkcast(objects);
		a.iload(index);
		a.aaload();
		a.invokestatic(argumentConvert(object));
		a.aastore();
		a.iinc(index, 1);
		a.goto_(loop);
		a.labelBinding(done);
		// the calls: the program's $JavaCalls when a printer or an order is given
		MethodCode.Label noCalls = a.newLabel();
		a.aconst_null();
		a.astore(calls);
		if (this.callsBack) {
			ClassEntry callsClass = cls(implementations().callsClass());
			MethodCode.Label make = a.newLabel();
			a.aload(printer);
			a.ifnonnull(make);
			a.aload(order);
			a.ifnull(noCalls);
			a.labelBinding(make);
			a.new_(callsClass);
			a.dup();
			a.invokespecial(this.cp.methodRef(callsClass, "<init>", "()V"));
			a.astore(calls);
		}
		a.labelBinding(noCalls);
		// the view of the shape
		String callsDesc = "L" + JAVA_CALLS + ";";
		String[] views = { JAVA_LIST_VIEW, JAVA_VECTOR_VIEW, JAVA_SET_VIEW, JAVA_MAP_VIEW };
		for (int s = 0; s < views.length; s++) {
			MethodCode.Label next = a.newLabel();
			ClassEntry view = cls(views[s]);
			if (s < views.length - 1) {
				a.iload(shape);
				a.loadConstant(s);
				a.if_icmpne(next);
			}
			a.new_(view);
			a.dup();
			a.aload(0);
			if (s < 2) {
				// a List keeps its items: where an array is expected it is one of them
				a.aload(1);
			}
			a.aload(elements);
			a.checkcast(objects);
			a.aload(printer);
			if (s == 1) {
				a.aload(order);
			}
			a.aload(className);
			a.aload(calls);
			String items = s < 2 ? "Ljava/lang/Object;" : "";
			String ordered = s == 1 ? "Ljava/lang/Object;" : "";
			a.invokespecial(this.cp.methodRef(view, "<init>", "(Ljava/lang/Object;" + items
					+ "[Ljava/lang/Object;Ljava/lang/Object;" + ordered + "Ljava/lang/String;" + callsDesc + ")V"));
			a.areturn();
			if (s < views.length - 1) {
				a.labelBinding(next);
			}
		}
		a.labelBinding(noValue);
		a.new_(cls("java/lang/RuntimeException"));
		a.dup();
		a.ldc(str(JAVA_VIEW_NO_VALUE));
		a.aload(members);
		a.checkcast(objects);
		a.iload(index);
		a.aaload();
		a.invokestatic(this.lispToString);
		a.invokevirtual(method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
		a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
		a.athrow();
		a.labelBinding(badShape);
		throwDescribing(a, usage, 2);
		a.labelBinding(badPrinter);
		throwDescribing(a, usage, 3);
		a.labelBinding(badOrder);
		throwDescribing(a, usage, 4);
		a.labelBinding(badClass);
		throwDescribing(a, usage, 5);
		a.labelBinding(badItems);
		throwDescribing(a, usage, 1);
		return new Method(name, desc, a);
	}

	// Pushes the Lisp string in SLOT unquoted (its quote-framed spelling without the
	// quotes), or jumps to REFUSED when the slot holds no Lisp string.
	void emitUnquoted(MethodCode a, int slot, MethodCode.Label refused) {
		ClassEntry string = cls("java/lang/String");
		MethodRefEntry length = method("java/lang/String", "length", "()I");
		a.aload(slot);
		a.instanceOf(string);
		a.ifeq(refused);
		a.aload(slot);
		a.checkcast(string);
		a.invokevirtual(length);
		a.loadConstant(2);
		a.if_icmplt(refused);
		a.aload(slot);
		a.checkcast(string);
		a.loadConstant(0);
		a.invokevirtual(method("java/lang/String", "charAt", "(I)C"));
		a.loadConstant('"');
		a.if_icmpne(refused);
		a.aload(slot);
		a.checkcast(string);
		a.loadConstant(1);
		a.aload(slot);
		a.checkcast(string);
		a.invokevirtual(length);
		a.loadConstant(1);
		a.isub();
		a.invokevirtual(method("java/lang/String", "substring", "(II)Ljava/lang/String;"));
	}

	// _jarr(Object)Object: the bridge's arrayToList over every array type (elements
	// unmarshalled as Array.get would box them), or the value itself when it is no array;
	// _jarf with javaFalse, a false boolean element |false|; with octets (_jaro, _jafo) a
	// byte[] -- the value, or as an Object[] element through UNMARSHAL -- an
	// (unsigned-byte 8) vector: a fresh byte[] of its octets after the width in slot 0.
	private Method buildArrayToList(Utf8Entry name, Utf8Entry desc, MethodRefEntry unmarshal, boolean javaFalse,
			boolean octets) {
		MethodCode a = new MethodCode();
		MethodRefEntry longValueOf = method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
		MethodRefEntry doubleValueOf = method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
		ClassEntry objectClass = cls("java/lang/Object");
		if (octets) {
			MethodCode.Label notBytes = a.newLabel();
			a.aload(0);
			a.instanceOf(cls("[B"));
			a.ifeq(notBytes);
			// vector = new byte[bytes.length + 1]; vector[0] = 8;
			// System.arraycopy(bytes, 0, vector, 1, bytes.length)
			a.aload(0);
			a.checkcast(cls("[B"));
			a.astore(2);
			a.aload(2);
			a.arraylength();
			a.loadConstant(1);
			a.iadd();
			a.newarray(TypeKind.BYTE);
			a.astore(1);
			a.aload(1);
			a.loadConstant(0);
			a.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
			a.bastore();
			a.aload(2);
			a.loadConstant(0);
			a.aload(1);
			a.loadConstant(1);
			a.aload(2);
			a.arraylength();
			a.invokestatic(method("java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V"));
			a.aload(1);
			a.areturn();
			a.labelBinding(notBytes);
		}
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
					// boolean[]: t or nil (|false| when javaFalse).
					MethodCode.Label falseValue = a.newLabel();
					MethodCode.Label stored = a.newLabel();
					a.baload();
					a.ifeq(falseValue);
					a.ldc(str("T"));
					a.goto_(stored);
					a.labelBinding(falseValue);
					if (javaFalse) {
						a.ldc(str(LispNames.JAVA_FALSE));
					}
					else {
						a.aconst_null();
					}
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

	// _jcmp(Object fn, Object args, Object answer)Object: a Comparator's function's
	// answer as Clojure's AFunction.compare reads it (eval/JavaInterop's handler, the
	// bridge's callback): t -1; |false| 1 when fn answers true -- neither nil nor
	// |false| -- for the two arguments of ARGS swapped, else 0; a fixnum or bignum its
	// low 32 bits, a float truncated (d2i saturates, NaN is 0), a ratio its DECIMAL64
	// quotient truncated -- each an Integer; nil the NullPointerException and anything
	// else the ClassCastException AFunction.compare's cast to Number throws, which the
	// callback throws as the comparator's own failure.
	private Method buildComparison(Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		ClassEntry objects = cls("[Ljava/lang/Object;");
		ClassEntry objectClass = cls("java/lang/Object");
		ClassEntry bigInteger = cls("java/math/BigInteger");
		ClassEntry ratio = cls("[Ljava/math/BigInteger;");
		ClassEntry bigDecimal = cls("java/math/BigDecimal");
		MethodRefEntry equals = method("java/lang/String", "equals", "(Ljava/lang/Object;)Z");
		MethodRefEntry integerOf = method("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");
		MethodCode.Label notTrue = a.newLabel();
		MethodCode.Label notFalse = a.newLabel();
		MethodCode.Label zero = a.newLabel();
		MethodCode.Label notLong = a.newLabel();
		MethodCode.Label notDouble = a.newLabel();
		MethodCode.Label notBignum = a.newLabel();
		MethodCode.Label notRatio = a.newLabel();
		// T: -1
		a.ldc(str("T"));
		a.aload(2);
		a.invokevirtual(equals);
		a.ifeq(notTrue);
		a.loadConstant(-1);
		a.invokestatic(integerOf);
		a.areturn();
		a.labelBinding(notTrue);
		// |false|: fn of (b a), true when neither nil nor |false|
		a.ldc(str(LispNames.JAVA_FALSE));
		a.aload(2);
		a.invokevirtual(equals);
		a.ifeq(notFalse);
		a.aload(0);
		// new Object[] { b, new Object[] { a, null } }, ARGS being { a, { b, null } }
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(1);
		a.checkcast(objects);
		a.loadConstant(1);
		a.aaload();
		a.checkcast(objects);
		a.loadConstant(0);
		a.aaload();
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(1);
		a.checkcast(objects);
		a.loadConstant(0);
		a.aaload();
		a.aastore();
		a.aastore();
		a.invokestatic(this.cp.methodRef(this.thisClass, "_apply",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		a.astore(3);
		a.aload(3);
		a.ifnull(zero);
		a.ldc(str(LispNames.JAVA_FALSE));
		a.aload(3);
		a.invokevirtual(equals);
		a.ifne(zero);
		a.loadConstant(1);
		a.invokestatic(integerOf);
		a.areturn();
		a.labelBinding(zero);
		a.loadConstant(0);
		a.invokestatic(integerOf);
		a.areturn();
		a.labelBinding(notFalse);
		// a fixnum: its low 32 bits
		a.aload(2);
		a.instanceOf(cls("java/lang/Long"));
		a.ifeq(notLong);
		a.aload(2);
		a.checkcast(cls("java/lang/Long"));
		a.invokevirtual(method("java/lang/Long", "intValue", "()I"));
		a.invokestatic(integerOf);
		a.areturn();
		a.labelBinding(notLong);
		// a float: truncated
		a.aload(2);
		a.instanceOf(cls("java/lang/Double"));
		a.ifeq(notDouble);
		a.aload(2);
		a.checkcast(cls("java/lang/Double"));
		a.invokevirtual(method("java/lang/Double", "doubleValue", "()D"));
		a.d2i();
		a.invokestatic(integerOf);
		a.areturn();
		a.labelBinding(notDouble);
		// a bignum: its low 32 bits
		a.aload(2);
		a.instanceOf(bigInteger);
		a.ifeq(notBignum);
		a.aload(2);
		a.checkcast(bigInteger);
		a.invokevirtual(method("java/math/BigInteger", "intValue", "()I"));
		a.invokestatic(integerOf);
		a.areturn();
		a.labelBinding(notBignum);
		// a ratio {num, den}: new BigDecimal(num).divide(new BigDecimal(den), DECIMAL64)
		a.aload(2);
		a.instanceOf(ratio);
		a.ifeq(notRatio);
		MethodRefEntry decimalOf = method("java/math/BigDecimal", "<init>", "(Ljava/math/BigInteger;)V");
		a.new_(bigDecimal);
		a.dup();
		a.aload(2);
		a.checkcast(ratio);
		a.loadConstant(0);
		a.aaload();
		a.invokespecial(decimalOf);
		a.new_(bigDecimal);
		a.dup();
		a.aload(2);
		a.checkcast(ratio);
		a.loadConstant(1);
		a.aaload();
		a.invokespecial(decimalOf);
		a.getstatic(field("java/math/MathContext", "DECIMAL64", "Ljava/math/MathContext;"));
		a.invokevirtual(method("java/math/BigDecimal", "divide",
				"(Ljava/math/BigDecimal;Ljava/math/MathContext;)Ljava/math/BigDecimal;"));
		a.invokevirtual(method("java/math/BigDecimal", "doubleValue", "()D"));
		a.d2i();
		a.invokestatic(integerOf);
		a.areturn();
		a.labelBinding(notRatio);
		// nil: AFunction.compare's ((Number) nil).intValue()
		MethodCode.Label notNil = a.newLabel();
		a.aload(2);
		a.ifnonnull(notNil);
		a.new_(cls("java/lang/NullPointerException"));
		a.dup();
		a.ldc(str(JavaImplementation.COMPARISON_OF_NIL));
		a.invokespecial(method("java/lang/NullPointerException", "<init>", "(Ljava/lang/String;)V"));
		a.areturn();
		a.labelBinding(notNil);
		// anything else: its cast to Number, naming a host object's class, the class of
		// the object a receiver kind is in Java (a string's), else the printed value
		MethodCode.Label notHost = a.newLabel();
		MethodCode.Label printed = a.newLabel();
		MethodCode.Label named = a.newLabel();
		MethodRefEntry getClass = method("java/lang/Object", "getClass", "()Ljava/lang/Class;");
		MethodRefEntry getName = method("java/lang/Class", "getName", "()Ljava/lang/String;");
		MethodRefEntry concat = method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		a.aload(2);
		a.invokestatic(host());
		a.ifeq(notHost);
		a.aload(2);
		a.invokevirtual(getClass);
		a.invokevirtual(getName);
		a.astore(3);
		a.goto_(named);
		a.labelBinding(notHost);
		a.aload(2);
		a.invokestatic(receiver());
		a.astore(4);
		a.aload(4);
		a.ifnull(printed);
		a.aload(4);
		a.invokevirtual(getClass);
		a.invokevirtual(getName);
		a.astore(3);
		a.goto_(named);
		a.labelBinding(printed);
		a.aload(2);
		a.invokestatic(this.lispToString);
		a.astore(3);
		a.labelBinding(named);
		a.new_(cls("java/lang/ClassCastException"));
		a.dup();
		a.ldc(str(JavaImplementation.COMPARISON_CAST_PREFIX));
		a.aload(3);
		a.checkcast(cls("java/lang/String"));
		a.invokevirtual(concat);
		a.ldc(str(JavaImplementation.COMPARISON_CAST_SUFFIX));
		a.invokevirtual(concat);
		a.invokespecial(method("java/lang/ClassCastException", "<init>", "(Ljava/lang/String;)V"));
		a.areturn();
		return new Method(name, desc, a);
	}

}
