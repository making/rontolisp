package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * {@code clojure.java.io}'s values in the lowering: the kernel namespace the built-in
 * {@code clojure.java.io} source requires ({@code rontolisp.internal.io}), the
 * {@code java.io} constructions that make a value here on every backend, the instance
 * methods of a File, a URL, a URI and a byte stream, {@code file-seq}, and the resource a
 * literal name finds while the program lowers. The values and their runtime are
 * {@code clojure.lisp}'s ("clojure.java.io"); every shared verb reaches one through the
 * io family's arm test ({@link ClojureArms.Family#IO}), so a program making none compiles
 * as before.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureIoLowering {

	/** The kernel namespace only the built-in {@code clojure.java.io} requires. */
	static final String NAMESPACE = "rontolisp.internal.io";

	/** The io family's arm test: whether a value is a clojure.java.io value. */
	static final String IO_P = "RONTOLISP::%CLOJURE-IO-P";

	/** The io family's {@code instance?} test of a class among a value's supers. */
	static final String IO_INSTANCE_P = "RONTOLISP::%CLOJURE-IO-INSTANCE-P";

	/**
	 * The io family's test of what {@code slurp} and {@code spit} open through the
	 * namespace rather than as a path: a value here, or -- once the namespace has loaded
	 * -- anything but a path string, which its {@code reader} or {@code writer} opens (a
	 * type a program extended {@code IOFactory} to included) or refuses.
	 */
	static final String OPENABLE_P = "RONTOLISP::%CLOJURE-IO-OPENABLE-P";

	/** The class keyword of a clojure.java.io value. */
	static final String CLASS_KEY = "RONTOLISP::%CLOJURE-IO-CLASS-KEY";

	private static final String PREFIX = "RONTOLISP::%CLOJURE-IO-";

	/** {@code (File. path)}. */
	static final String FILE = PREFIX + "FILE";

	/** {@code (File. parent child)}. */
	static final String FILE_2 = PREFIX + "FILE-2";

	/** {@code (URL. spec)}, {@code as-url} of a string. */
	static final String URL_OF = PREFIX + "URL-OF";

	/** {@code (URI. spec)}. */
	static final String URI_OF = PREFIX + "URI-OF";

	/** A resource found while the program lowered: its URL and its text. */
	static final String URL_FOUND = PREFIX + "URL-FOUND";

	/** A resource looked up when the program runs, below the directory roots. */
	static final String RESOURCE = PREFIX + "RESOURCE";

	/**
	 * Every directory root's URL of a name when the program runs: a class loader's
	 * {@code getResources}, which {@code ring.util.response}'s resource response reads.
	 */
	static final String RESOURCE_URLS = PREFIX + "RESOURCE-URLS";

	/** {@code file-seq}. */
	static final String FILE_SEQ = PREFIX + "FILE-SEQ";

	/** {@code slurp} with options: its reader's {@code :encoding}. */
	static final String SLURP = PREFIX + "SLURP";

	/** {@code spit} to a clojure.java.io value, or with an {@code :encoding}. */
	static final String SPIT = PREFIX + "SPIT";

	/** {@code line-seq} of a clojure.java.io value. */
	static final String LINE_SEQ = PREFIX + "LINE-SEQ";

	/** A reader over a path, a File, a URL, a byte stream or a reader. */
	static final String OPEN_READER = PREFIX + "OPEN-READER";

	/**
	 * {@code (InputStreamReader. in charset)} over a byte stream here: no producer, since
	 * it only reads a value one made.
	 */
	static final String DECODING_READER = PREFIX + "DECODING-READER";

	/** The constructions of the {@code java.io} file streams. */
	private static final String FILE_INPUT = PREFIX + "FILE-INPUT";

	private static final String FILE_OUTPUT = PREFIX + "FILE-OUTPUT";

	private static final String FILE_READER = PREFIX + "FILE-READER";

	private static final String FILE_WRITER = PREFIX + "FILE-WRITER";

	private static final String WRAPPED_STREAM = PREFIX + "WRAPPED-STREAM";

	private static final String STREAM_WRITER = PREFIX + "STREAM-WRITER";

	/** {@code (ByteArrayInputStream. bytes)} and of a part of it. */
	private static final String BYTES_INPUT = PREFIX + "BYTES-INPUT";

	private static final String BYTES_INPUT_3 = PREFIX + "BYTES-INPUT-3";

	/** {@code (ByteArrayOutputStream.)}, of an initial size or none. */
	private static final String BYTES_OUTPUT = PREFIX + "BYTES-OUTPUT";

	/**
	 * The kernels: each var one call to its {@code %clojure-io-} worker, with a fixed
	 * arity, and {@code resource}, lowered in place over the program's directory roots.
	 */
	static ClojureKernelLowering.Kernels kernels() {
		Map<String, Integer> arity = Map.ofEntries(Map.entry("file", 1), Map.entry("file-2", 2), Map.entry("url", 1),
				Map.entry("file-url", 1), Map.entry("url-file", 1), Map.entry("uri-file", 1), Map.entry("uri-url", 1),
				Map.entry("refuse-reader", 1), Map.entry("refuse-writer", 1), Map.entry("refuse-input", 1),
				Map.entry("refuse-output", 1), Map.entry("open-reader", 2), Map.entry("open-writer", 3),
				Map.entry("open-input", 1), Map.entry("open-output", 2), Map.entry("copy", 3),
				Map.entry("relative-path", 1), Map.entry("delete", 1), Map.entry("refuse-delete", 1),
				Map.entry("parent-file", 1), Map.entry("mkdirs", 1), Map.entry("resource", 1),
				Map.entry("resources", 1), Map.entry("from-host", 1), Map.entry("install", 2));
		Map<String, String> workers = Map.of("url", URL_OF, "delete", PREFIX + "M-DELETE", "mkdirs",
				PREFIX + "M-MKDIRS");
		Map<String, ClojureKernelLowering.Inline> inline = Map.of("resource",
				(ctx, args) -> runtimeResource(ctx, RESOURCE, args.get(0)), "resources",
				(ctx, args) -> runtimeResource(ctx, RESOURCE_URLS, args.get(0)));
		return new ClojureKernelLowering.Kernels("clojure.java.io", PREFIX, arity, workers, false, inline);
	}

	/**
	 * What makes a clojure.java.io value: the kernels the namespace calls and the
	 * lowering's own constructions -- the io family's producers.
	 */
	static final Set<String> PRODUCERS = Set.of(FILE, FILE_2, URL_OF, URI_OF, URL_FOUND, RESOURCE, RESOURCE_URLS,
			FILE_SEQ, PREFIX + "FILE-URL", PREFIX + "URL-FILE", PREFIX + "URI-FILE", PREFIX + "URI-URL",
			PREFIX + "OPEN-INPUT", PREFIX + "OPEN-OUTPUT", OPEN_READER, PREFIX + "OPEN-WRITER", PREFIX + "PARENT-FILE",
			PREFIX + "FROM-HOST", FILE_INPUT, FILE_OUTPUT, STREAM_WRITER, BYTES_INPUT, BYTES_INPUT_3, BYTES_OUTPUT);

	/**
	 * The ones of them that answer a Common Lisp character stream, which the printer and
	 * {@code class} spell as a host stream ({@link ClojureArms.Family#STREAM}'s
	 * producers).
	 */
	static final Set<String> STREAM_PRODUCERS = Set.of(OPEN_READER, PREFIX + "OPEN-WRITER", FILE_READER, FILE_WRITER,
			STREAM_WRITER);

	private ClojureIoLowering() {
	}

	// Instance methods

	/**
	 * The instance methods of the values here, by name and argument count, to the
	 * {@code clojure.lisp} function answering them: each dispatches on its receiver's
	 * kind and refuses a kind the oracle's class has no such method for, in the oracle's
	 * words.
	 */
	static final Map<String, Map<Integer, String>> METHODS = methods();

	private static Map<String, Map<Integer, String>> methods() {
		Map<String, Map<Integer, String>> out = new HashMap<>();
		for (String method : List.of("getName", "getParent", "getParentFile", "getAbsolutePath", "getAbsoluteFile",
				"getCanonicalPath", "getCanonicalFile", "exists", "isDirectory", "isFile", "isHidden", "canRead",
				"length", "lastModified", "mkdir", "mkdirs", "delete", "createNewFile", "list", "listFiles", "getPath",
				"isAbsolute", "toURI", "toURL", "hashCode", "getProtocol", "getHost", "getPort", "getDefaultPort",
				"getFile", "getQuery", "getRef", "getAuthority", "getUserInfo", "toExternalForm", "openStream",
				"getScheme", "read", "readAllBytes", "readLine", "available", "newLine", "flush", "close",
				"toByteArray", "size", "reset", "markSupported")) {
			out.computeIfAbsent(method, m -> new HashMap<>()).put(0, PREFIX + "M-" + kebab(method));
		}
		for (String method : List.of("renameTo", "compareTo", "equals", "skip", "transferTo", "write", "append",
				"writeTo", "mark")) {
			out.computeIfAbsent(method, m -> new HashMap<>()).put(1, PREFIX + "M-" + kebab(method));
		}
		out.computeIfAbsent("read", m -> new HashMap<>()).put(1, PREFIX + "M-READ-BUFFER");
		out.computeIfAbsent("read", m -> new HashMap<>()).put(3, PREFIX + "M-READ-BUFFER-3");
		out.computeIfAbsent("readNBytes", m -> new HashMap<>()).put(1, PREFIX + "M-READ-N-BYTES");
		out.computeIfAbsent("readNBytes", m -> new HashMap<>()).put(3, PREFIX + "M-READ-N-BYTES-3");
		out.computeIfAbsent("write", m -> new HashMap<>()).put(3, PREFIX + "M-WRITE-3");
		out.computeIfAbsent("getClass", m -> new HashMap<>()).put(0, CLASS_KEY);
		out.computeIfAbsent("toString", m -> new HashMap<>()).put(0, PREFIX + "STRING");
		out.computeIfAbsent("toString", m -> new HashMap<>()).put(1, PREFIX + "M-TO-STRING");
		Map<String, Map<Integer, String>> frozen = new HashMap<>();
		out.forEach((method, arities) -> frozen.put(method, Map.copyOf(arities)));
		return Map.copyOf(frozen);
	}

	/**
	 * {@code getParentFile} to {@code GET-PARENT-FILE}, {@code toURI} to {@code TO-URI}:
	 * a dash where a capital follows a lower-case letter.
	 */
	private static String kebab(String method) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < method.length(); i++) {
			char c = method.charAt(i);
			if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(method.charAt(i - 1))) {
				out.append('-');
			}
			out.append(Character.toUpperCase(c));
		}
		return out.toString();
	}

	/**
	 * An instance call's arm for a clojure.java.io receiver: {@code (if (io-p recv)
	 * (method recv args...) call)} where the values here have the method at that count;
	 * any other member is the host object's ({@code java:call} of the receiver's host
	 * object, on the backends that have one).
	 * @param ctx the hub
	 * @param method the method name
	 * @param designator the method as {@code java:call} names it (its parameter types
	 * when tagged)
	 * @param recv the bound receiver
	 * @param args the lowered arguments
	 * @param call the call for any other receiver
	 * @return the call with the arm
	 */
	static LispVal methodArm(ClojureLowering ctx, String method, String designator, LispSymbol recv, List<LispVal> args,
			LispVal call) {
		Map<Integer, String> arities = METHODS.get(method);
		String kernel = arities == null ? null : arities.get(args.size());
		LispVal io;
		if (kernel != null) {
			List<LispVal> own = new ArrayList<>();
			own.add(new LispSymbol(kernel));
			own.add(recv);
			own.addAll(args);
			io = ClojureLowerUtil.list(own);
		}
		else {
			// any other member is the host object's, on the backends that have one
			List<LispVal> host = new ArrayList<>();
			host.add(recv);
			host.add(LispString.literal(designator));
			host.addAll(args);
			io = ClojureInteropLowering.hostCall(ctx, ClojureInteropLowering.JAVA_CALL, host, 2);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(new LispSymbol(IO_P), recv), io,
				call);
	}

	/**
	 * The view a value takes into a {@code java:} member: a File, a URL or a URI the host
	 * object it stands for (interpreter, JVM), anything else itself. The io family's
	 * view, so a program making no clojure.java.io value hands the member the value as it
	 * was.
	 */
	static final String HOST_VIEW = PREFIX + "HOST";

	/**
	 * A {@code java:call}, {@code java:new} or {@code java:static} operand list with
	 * every computed argument -- and a {@code java:call}'s receiver -- behind
	 * {@link #HOST_VIEW}; a literal crosses as it is, and so does any other operator's
	 * operand.
	 * @param operator the {@code java:} operator
	 * @param parts the operands, names first
	 * @param names how many leading operands are names ({@code java:call}'s receiver
	 * among them)
	 * @return the operands
	 */
	static List<LispVal> crossing(LispSymbol operator, List<LispVal> parts, int names) {
		boolean call = operator.equals(ClojureInteropLowering.JAVA_CALL);
		if (!call && !operator.equals(ClojureInteropLowering.JAVA_NEW)
				&& !operator.equals(ClojureInteropLowering.JAVA_STATIC)) {
			return parts;
		}
		List<LispVal> out = new ArrayList<>(parts.size());
		for (int i = 0; i < parts.size(); i++) {
			LispVal part = parts.get(i);
			boolean operand = i >= names || call && i == 0;
			out.add(operand && !(part instanceof LispString || part instanceof LispInteger || part instanceof LispDouble
					|| part instanceof LispChar || part instanceof LispNil || part instanceof LispTrue
					|| part instanceof LispSymbol symbol && symbol.isKeyword())
							? ClojureLowerUtil.list(new LispSymbol(HOST_VIEW), part) : part);
		}
		return out;
	}

	/**
	 * The protocol tag runtime's arms for a host object of a class a protocol may be
	 * extended to here ({@link #EXTENDABLE}): a host {@code java.io.File} dispatches as
	 * one made here. Arms of the host-object family, which a program naming no
	 * {@code java:} operator sheds.
	 * @param x the tagged value's variable
	 * @return the {@code cond} clauses
	 */
	static List<LispVal> hostTagArms(LispSymbol x) {
		List<LispVal> arms = new ArrayList<>();
		for (String cls : List.of("java.io.File", "java.net.URL", "java.net.URI")) {
			arms.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(ClojureDispatchLowering.HOST_OBJECT_P),
					x, LispString.literal(cls)), ClojureCollectionLowering.keywordForm(cls)));
		}
		return arms;
	}

	/**
	 * The classes the values here are, with their supers: what {@code class} answers for
	 * one, what {@code instance?}, a class chain and a protocol extension read. The last
	 * three are the classes of {@code rontolisp.http-client}'s {@code :as :stream} body:
	 * the JDK client's response stream, and the two it is decompressed through.
	 */
	static final Map<String, List<String>> CLASSES = Map.ofEntries(
			Map.entry("java.io.File", List.of("java.io.Serializable", "java.lang.Comparable")),
			Map.entry("java.net.URL", List.of("java.io.Serializable")),
			Map.entry("java.net.URI", List.of("java.lang.Comparable", "java.io.Serializable")),
			Map.entry("java.io.BufferedInputStream",
					List.of("java.io.FilterInputStream", "java.io.InputStream", "java.io.Closeable",
							"java.lang.AutoCloseable")),
			Map.entry("java.io.BufferedOutputStream",
					List.of("java.io.FilterOutputStream", "java.io.OutputStream", "java.io.Closeable",
							"java.io.Flushable", "java.lang.AutoCloseable")),
			Map.entry("java.io.BufferedReader",
					List.of("java.io.Reader", "java.lang.Readable", "java.io.Closeable", "java.lang.AutoCloseable")),
			Map.entry("java.io.BufferedWriter",
					List.of("java.io.Writer", "java.lang.Appendable", "java.io.Closeable", "java.io.Flushable",
							"java.lang.AutoCloseable")),
			Map.entry("java.io.ByteArrayInputStream",
					List.of("java.io.InputStream", "java.io.Closeable", "java.lang.AutoCloseable")),
			Map.entry("java.io.ByteArrayOutputStream",
					List.of("java.io.OutputStream", "java.io.Closeable", "java.io.Flushable",
							"java.lang.AutoCloseable")),
			Map.entry("jdk.internal.net.http.ResponseSubscribers$HttpResponseInputStream",
					List.of("java.io.InputStream", "java.io.Closeable", "java.lang.AutoCloseable",
							"java.util.concurrent.Flow$Subscriber", "java.net.http.HttpResponse$BodySubscriber")),
			Map.entry("java.util.zip.GZIPInputStream",
					List.of("java.util.zip.InflaterInputStream", "java.io.FilterInputStream", "java.io.InputStream",
							"java.io.Closeable", "java.lang.AutoCloseable")),
			Map.entry("java.util.zip.InflaterInputStream", List.of("java.io.FilterInputStream", "java.io.InputStream",
					"java.io.Closeable", "java.lang.AutoCloseable")));

	/**
	 * Whether a value here may be an instance of the class: one of {@link #CLASSES} or a
	 * super of one.
	 * @param fqn the class's binary name
	 * @return whether {@code instance?} of it takes the io arm
	 */
	static boolean mayBeInstance(String fqn) {
		if (CLASSES.containsKey(fqn)) {
			return true;
		}
		for (List<String> supers : CLASSES.values()) {
			if (supers.contains(fqn)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The classes a protocol may be extended to here: the ones no subclass is made of.
	 */
	static final Set<String> EXTENDABLE = Set.of("java.io.File", "java.net.URL", "java.net.URI");

	// Constructions

	/**
	 * A construction of a {@code java.io} class over the lowered arguments that makes a
	 * value here on every backend, or null to keep the host construction: a
	 * {@code java.io.File} of a path or of a parent and a child, a {@code java.net.URL}
	 * or {@code java.net.URI} of a string, the file streams of a path or File, a reader
	 * decoding and a writer encoding a byte stream, the buffering wrappers, which answer
	 * the stream they wrap, and the byte streams over a byte array and into one
	 * ({@code ByteArrayInputStream}, {@code ByteArrayOutputStream}).
	 * @param cls the resolved class name
	 * @param args the lowered arguments
	 * @return the construction, or null
	 */
	static @Nullable LispVal construction(String cls, List<LispVal> args) {
		int n = args.size();
		String worker = switch (cls) {
			case "java.io.File" -> n == 1 ? FILE : n == 2 ? FILE_2 : null;
			case "java.io.FileInputStream" -> n == 1 ? FILE_INPUT : null;
			case "java.io.FileOutputStream" -> n == 1 || n == 2 ? FILE_OUTPUT : null;
			case "java.io.FileReader" -> n == 1 ? FILE_READER : null;
			case "java.io.FileWriter" -> n == 1 || n == 2 ? FILE_WRITER : null;
			case "java.io.BufferedInputStream", "java.io.BufferedOutputStream", "java.io.BufferedWriter" ->
				n == 1 ? WRAPPED_STREAM : null;
			case "java.io.OutputStreamWriter" -> n == 1 || n == 2 ? STREAM_WRITER : null;
			case "java.io.ByteArrayInputStream" -> n == 1 ? BYTES_INPUT : n == 3 ? BYTES_INPUT_3 : null;
			case "java.io.ByteArrayOutputStream" -> n == 0 || n == 1 ? BYTES_OUTPUT : null;
			default -> null;
		};
		if (worker == null) {
			return null;
		}
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(worker));
		call.addAll(args);
		if (worker.equals(FILE_OUTPUT) || worker.equals(FILE_WRITER) || worker.equals(STREAM_WRITER)) {
			if (n == 1) {
				call.add(ClojureLowering.NIL_CONST);
			}
		}
		if (worker.equals(BYTES_OUTPUT) && n == 0) {
			// the oracle's default initial size
			call.add(new LispInteger(32));
		}
		return ClojureLowerUtil.list(call);
	}

	// file-seq

	/** {@code (file-seq dir)}: the lazy depth-first walk from the File {@code dir}. */
	static LispVal fileSeqOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "file-seq takes one java.io.File");
		return ClojureLowerUtil.list(new LispSymbol(FILE_SEQ), ctx.lower(items.get(1)));
	}

	/** {@code file-seq} as a value: the walk itself. */
	static LispVal fileSeqValue() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), new LispSymbol(FILE_SEQ));
	}

	// slurp, spit, line-seq

	/**
	 * {@code (slurp f & opts)} with options: the reader's {@code :encoding}, a literal
	 * keyword list like the oracle's, others accepted and ignored.
	 */
	static LispVal slurpWithOptions(ClojureLowering ctx, List<LispVal> items) {
		Map<String, LispVal> options = options(ctx, items, 2, "slurp");
		LispVal encoding = options.getOrDefault(":encoding", ClojureLowering.NIL_CONST);
		return ClojureLowerUtil.list(new LispSymbol(SLURP), ctx.lower(items.get(1)), encoding);
	}

	/**
	 * {@code (spit f content & opts)} with an {@code :encoding}: the writer of the
	 * namespace, appending under {@code :append}.
	 */
	static LispVal spitWithOptions(ClojureLowering ctx, List<LispVal> items) {
		LispVal target = ctx.lower(items.get(1));
		LispVal content = ctx.lower(items.get(2));
		Map<String, LispVal> options = options(ctx, items, 3, "spit");
		return ClojureLowerUtil.list(new LispSymbol(SPIT), target, content,
				options.getOrDefault(":append", ClojureLowering.NIL_CONST),
				options.getOrDefault(":encoding", ClojureLowering.NIL_CONST));
	}

	/**
	 * The literal options of a call from {@code from} on: keyword names to their lowered
	 * values, lowered in order; a computed key is refused.
	 */
	private static Map<String, LispVal> options(ClojureLowering ctx, List<LispVal> items, int from, String verb) {
		ClojureLowerUtil.isTrue((items.size() - from) % 2 == 0, verb + " takes its options as keyword and value pairs");
		Map<String, LispVal> out = new HashMap<>();
		for (int i = from; i < items.size(); i += 2) {
			if (!(items.get(i) instanceof LispSymbol key) || !key.name().startsWith(":")) {
				throw new LispReadException(verb + " takes literal keyword options, not " + items.get(i).print());
			}
			out.put(key.name(), ctx.lower(items.get(i + 1)));
		}
		return out;
	}

	/** Whether a {@code slurp}/{@code spit} call names an {@code :encoding} option. */
	static boolean namesEncoding(List<LispVal> items, int from) {
		for (int i = from; i < items.size(); i += 2) {
			if (items.get(i) instanceof LispSymbol key && key.name().equals(":encoding")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code spit}'s arm for a target the namespace opens ({@link #OPENABLE_P}):
	 * {@code (if (openable-p file) (spit file text append nil) plain)} over the
	 * already-bound target and text.
	 */
	static LispVal spitArm(LispSymbol file, LispSymbol text, LispVal append, LispVal plain) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(OPENABLE_P), file),
				ClojureLowerUtil.list(new LispSymbol(SPIT), file, text, append, ClojureLowering.NIL_CONST), plain);
	}

	/**
	 * {@code line-seq}'s arm for a clojure.java.io argument: {@code (if (io-p src)
	 * (line-seq src) plain)}.
	 */
	static LispVal lineSeqArm(LispSymbol src, LispVal plain) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(new LispSymbol(IO_P), src),
				ClojureLowerUtil.list(new LispSymbol(LINE_SEQ), src), plain);
	}

	/**
	 * The path a {@code slurp}, {@code spit} or {@code line-seq} opens, as the value
	 * given: a host {@code java.io.File} a {@code java:} member answered is its path (the
	 * host-object family's view, so a program naming no {@code java:} operator opens the
	 * value as it was).
	 */
	static final String HOST_FILE_PATH = "RONTOLISP::%CLOJURE-HOST-FILE-PATH";

	/**
	 * {@code (host-file-path x)}.
	 * @param x the value
	 * @return the view
	 */
	static LispVal hostFilePath(LispVal x) {
		return ClojureLowerUtil.list(new LispSymbol(HOST_FILE_PATH), x);
	}

	// Resources

	/**
	 * {@code (clojure.java.io/resource "name" [loader])} with a literal name: found while
	 * the program lowers -- a directory root's file or a jar root's entry -- the URL with
	 * its text, which travels with the program, so it reads alike wherever it runs (a
	 * jar's entry above all, which no wasm backend can open); nil when no root holds it.
	 * A loader argument still runs. Null for any other call, which runs the var.
	 * @param ctx the hub
	 * @param name the call's head as written
	 * @param items the call
	 * @return the lowered call, or null
	 */
	static @Nullable LispVal literalResource(ClojureLowering ctx, String name, List<LispVal> items) {
		if ((items.size() != 2 && items.size() != 3) || !(items.get(1) instanceof LispString literal)
				|| ctx.isLocal(name) || !"clojure.java.io/resource".equals(ctx.lookupVar(name))) {
			return null;
		}
		ClojureSourcePath.Resource found = ctx.sourcePath.findResource(literal.value());
		LispVal url = found == null ? ClojureLowering.NIL_CONST : ClojureLowerUtil.list(new LispSymbol(URL_FOUND),
				LispString.literal(found.spec()), LispString.literal(found.text()));
		if (items.size() == 3) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ctx.lower(items.get(2)), url);
		}
		return url;
	}

	/**
	 * {@code rontolisp.internal.io/resource} and {@code resources}: the run-time lookup
	 * of a name below the program's directory roots, absolute, baked in as the lowering
	 * knows them.
	 */
	private static LispVal runtimeResource(ClojureLowering ctx, String worker, LispVal name) {
		List<LispVal> roots = new ArrayList<>();
		for (String root : ctx.sourcePath.directoryRoots()) {
			roots.add(LispString.literal(root));
		}
		return ClojureLowerUtil.list(new LispSymbol(worker), name,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(roots)));
	}

}
