package am.ik.rontolisp.eval;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoublePredicate;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import am.ik.rontolisp.ArrayElementTypes;
import am.ik.rontolisp.BFloat16;
import am.ik.rontolisp.ArrayGrowth;
import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.ClConstants;
import am.ik.rontolisp.FloatArrayAccessHook;
import am.ik.rontolisp.FloatText;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBFloat16Array;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispComplex;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispEquality;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispQuantizedMatrix;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispFuture;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispStream;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.Scope;
import am.ik.rontolisp.VersionInfo;
import am.ik.rontolisp.compiler.ConcatenateForms;
import am.ik.rontolisp.compiler.FetchResponseShape;
import am.ik.rontolisp.compiler.FixedDecimal;
import am.ik.rontolisp.compiler.OpenModes;
import am.ik.rontolisp.compiler.OperandTypes;
import am.ik.rontolisp.compiler.StreamDesignators;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.macro.StreamElementType;
import am.ik.rontolisp.reader.LispLexer;
import am.ik.rontolisp.runtime.RontoCharFileReader;
import am.ik.rontolisp.runtime.RontoCharFileWriter;
import am.ik.rontolisp.runtime.RontoIoFileStream;
import am.ik.rontolisp.runtime.RontoStringInputStream;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * Lexical environment for bindings. Following the Lisp-2 model of Common Lisp, variables
 * and functions live in separate namespaces: {@link #lookup(String)} resolves the
 * variable namespace, {@link #lookupFunction(String)} the function namespace.
 */
public final class Environment implements Scope {

	/**
	 * A name-to-value map tuned for the scope an interpreted call actually creates: a
	 * handful of entries, read a few times, then discarded. Up to {@link #LINEAR_MAX}
	 * entries live in two parallel arrays scanned linearly -- cheaper than hashing and,
	 * more importantly, allocation-free until the first {@code put} -- and a scope that
	 * outgrows that (the global environment, with its hundreds of built-ins) is promoted
	 * to a {@link HashMap} once and behaves exactly as before. A binding's value is never
	 * {@code null}, so {@code get} returning {@code null} is the same answer as
	 * {@code containsKey} returning false.
	 */
	private static final class NameMap {

		private static final int LINEAR_MAX = 8;

		private static final String[] NO_KEYS = new String[0];

		private static final LispVal[] NO_VALUES = new LispVal[0];

		private String[] keys = NO_KEYS;

		private LispVal[] values = NO_VALUES;

		private int size;

		@Nullable private Map<String, LispVal> overflow;

		@Nullable LispVal get(String name) {
			Map<String, LispVal> map = this.overflow;
			if (map != null) {
				return map.get(name);
			}
			for (int i = 0; i < this.size; i++) {
				if (this.keys[i].equals(name)) {
					return this.values[i];
				}
			}
			return null;
		}

		boolean containsKey(String name) {
			return get(name) != null;
		}

		void put(String name, LispVal value) {
			Map<String, LispVal> map = this.overflow;
			if (map != null) {
				map.put(name, value);
				return;
			}
			for (int i = 0; i < this.size; i++) {
				if (this.keys[i].equals(name)) {
					this.values[i] = value;
					return;
				}
			}
			if (this.size == LINEAR_MAX) {
				// ConcurrentHashMap, not HashMap: the scope that outgrows the linear
				// arrays is above all the GLOBAL environment, which concurrently served
				// requests read while a lazy library load writes into it. A
				// binding's value is never null, so the map's no-null rule costs
				// nothing. Local scopes are thread-confined and rarely reach the
				// promotion at all.
				map = new ConcurrentHashMap<>();
				for (int i = 0; i < this.size; i++) {
					map.put(this.keys[i], this.values[i]);
				}
				map.put(name, value);
				this.overflow = map;
				this.keys = NO_KEYS;
				this.values = NO_VALUES;
				this.size = 0;
				return;
			}
			if (this.size == this.keys.length) {
				int grown = this.size == 0 ? 4 : LINEAR_MAX;
				this.keys = java.util.Arrays.copyOf(this.keys, grown);
				this.values = java.util.Arrays.copyOf(this.values, grown);
			}
			this.keys[this.size] = name;
			this.values[this.size] = value;
			this.size++;
		}

		void remove(String name) {
			Map<String, LispVal> map = this.overflow;
			if (map != null) {
				map.remove(name);
				return;
			}
			for (int i = 0; i < this.size; i++) {
				if (this.keys[i].equals(name)) {
					this.keys[i] = this.keys[this.size - 1];
					this.values[i] = this.values[this.size - 1];
					this.size--;
					return;
				}
			}
		}

	}

	private final NameMap bindings = new NameMap();

	private final NameMap functions = new NameMap();

	/**
	 * The {@code %mv-spill} channel ({@link LispNames#MV_SPILL}) of the GLOBAL
	 * environment -- the interpreter's value-count register, one per thread: nil while
	 * the last value produced was one value, the extra values after it as a fresh list,
	 * or {@code LispMacroExpander.MV_ZERO_VALUES} for no value at all. Not a binding
	 * because the evaluator writes it on every primitive step (an atom, a built-in's
	 * return, an argument list), which a map put could not afford; the Lisp variable
	 * {@code %mv-spill} the expansions read and assign resolves to it through
	 * {@link #lookup}/{@link #set}. Every Java publisher goes through
	 * {@link #publishSpill}, every clear through {@link #clearSpill}. A child scope
	 * shares its top-level scope's register.
	 */
	private final ValueCountRegister mvSpill;

	/**
	 * Resolves the print family's default destination at call time. Set by the evaluator
	 * (on the global environment) to read the current -- dynamic-first -- value of
	 * {@code *standard-output*}, so a {@code (let ((*standard-output* stream)) ...)} or
	 * {@code (with-output-to-string (*standard-output*) ...)} redirects the
	 * stream-argument-less print family. A {@code null}/{@code t}/{@code nil} result
	 * keeps the process standard output.
	 */
	@Nullable private Supplier<@Nullable LispVal> defaultOutput;

	/**
	 * Installs the default-output resolver consulted by the stream-argument-less print
	 * family; see {@link #defaultOutput}.
	 * @param supplier resolves the current {@code *standard-output*} value
	 */
	public void setDefaultOutput(Supplier<@Nullable LispVal> supplier) {
		this.defaultOutput = supplier;
	}

	/**
	 * Resolves {@code warn}'s destination at call time: the current -- dynamic-first --
	 * value of {@code *error-output*}, so {@code (let ((*error-output* s)) (warn ...))}
	 * captures the report. A {@code null} resolver (no evaluator installed one) falls
	 * back to the global cell, whose seeded value is the process standard error.
	 */
	@Nullable private Supplier<@Nullable LispVal> defaultError;

	/**
	 * Installs the resolver {@code warn} sends its report through; see
	 * {@link #defaultError}.
	 * @param supplier resolves the current {@code *error-output*} value
	 */
	public void setDefaultError(Supplier<@Nullable LispVal> supplier) {
		this.defaultError = supplier;
	}

	/**
	 * Resolves the read family's default source at call time -- the input mirror of
	 * {@link #defaultOutput}. Set by the evaluator to read the current -- dynamic-first
	 * -- value of {@code *standard-input*}, so a
	 * {@code (let ((*standard-input* stream)) ...)} or
	 * {@code (with-input-from-string (*standard-input* s) ...)} redirects the
	 * stream-argument-less read family. A {@code null}/{@code t}/{@code nil} result keeps
	 * the process standard input.
	 */
	@Nullable private Supplier<@Nullable LispVal> defaultInput;

	/**
	 * Installs the default-input resolver consulted by the stream-argument-less read
	 * family; see {@link #defaultInput}.
	 * @param supplier resolves the current {@code *standard-input*} value
	 */
	public void setDefaultInput(Supplier<@Nullable LispVal> supplier) {
		this.defaultInput = supplier;
	}

	/**
	 * The process standard input reader the read family shares with the REPL's line
	 * reader. Both go through this one {@code BufferedReader} so a piped session's
	 * look-ahead lives in exactly one place: the REPL no longer buffers lines the
	 * run-time {@code (read)} owes the program (a {@code (driver-loop)} typed at the
	 * prompt takes over the session's own stdin).
	 */
	@Nullable private BufferedReader replInputReader;

	/**
	 * Reads one REPL line through the shared standard input reader, like
	 * {@code BufferedReader.readLine} (a trailing {@code \r} is kept here; the REPL
	 * buffer's line handling is unchanged).
	 * @return the line without its terminator, or {@code null} at end of input
	 * @throws java.io.IOException if the read fails
	 */
	public @Nullable String readReplLine() throws java.io.IOException {
		BufferedReader reader = this.replInputReader;
		return reader == null ? null : reader.readLine();
	}

	/**
	 * Opens a buffered served-request body ({@link HttpRequestBodyStream}) in the stream
	 * table and answers its handle. Installed by {@code createGlobal} (the table is local
	 * to it); called by the evaluator when it builds a {@code :raw-body :buffered} Clack
	 * environment -- in Java, because marshalling the body through a Lisp call was the
	 * measured POST regression this stream exists to close.
	 */
	private java.util.function.@Nullable Function<byte[], Long> httpBodyStreamOpener;

	/**
	 * Removes a buffered served-request body from the stream table, quietly (absent is
	 * fine -- the handler may have closed it). Installed beside
	 * {@link #httpBodyStreamOpener}; the transport calls it when the request ends, which
	 * is what keeps a long-running server's stream table from growing per request.
	 */
	private java.util.function.@Nullable LongConsumer httpBodyStreamCloser;

	/**
	 * Flushes every buffered output stream still open in the stream table. Installed by
	 * {@code createGlobal} beside {@link #httpBodyStreamOpener}; see
	 * {@link #flushOpenStreams}.
	 */
	private @Nullable Runnable openStreamFlusher;

	/**
	 * Flushes every output stream the program left open -- what C's {@code exit} does for
	 * its stdio buffers, and what the wasm backends need not do because {@code fd_write}
	 * writes through. Called where the program ends, however it ends; a stream that fails
	 * to flush is skipped, as {@code exit} skips it.
	 */
	void flushOpenStreams() {
		Runnable flusher = this.openStreamFlusher;
		if (flusher != null) {
			flusher.run();
		}
	}

	/**
	 * Opens a buffered served-request body stream and returns its stream-table handle.
	 * @param octets the request body bytes
	 * @return the stream handle
	 */
	long openHttpBodyStream(byte[] octets) {
		java.util.function.Function<byte[], Long> opener = this.httpBodyStreamOpener;
		if (opener == null) {
			throw new LispEvalException("http-handler: this environment has no stream table");
		}
		return opener.apply(octets);
	}

	/**
	 * Removes a buffered served-request body from the stream table when its request ends.
	 * @param handle the stream handle {@link #openHttpBodyStream} answered
	 */
	void closeHttpBodyStream(long handle) {
		java.util.function.LongConsumer closer = this.httpBodyStreamCloser;
		if (closer != null) {
			closer.accept(handle);
		}
	}

	/**
	 * Resolves {@code (%read-eval datum)} markers in a runtime-read datum -- the
	 * {@code #.} read-time-eval hook. Set by the evaluator (on the global environment) to
	 * {@code LispEvaluator.resolveReadTimeEval}, so the runtime {@code read} /
	 * {@code read-from-string} built-ins evaluate a {@code #.} datum exactly like the
	 * frontend load path does. A {@code null} resolver (no evaluator installed one) keeps
	 * the error-mode read, whose {@code #.} signal matches the compiled backends'
	 * embedded readers.
	 */
	@Nullable private UnaryOperator<LispVal> readTimeEvalResolver;

	/**
	 * Installs the {@code #.} marker resolver consulted by the runtime read built-ins;
	 * see {@link #readTimeEvalResolver}.
	 * @param resolver replaces every read-eval marker with its datum's value
	 */
	public void setReadTimeEvalResolver(UnaryOperator<LispVal> resolver) {
		this.readTimeEvalResolver = resolver;
	}

	/**
	 * Answers the current -- dynamic binding first -- value of {@code *read-suppress*}.
	 * Set by the evaluator for the same reason
	 * {@link #setReadTimeEvalResolver(UnaryOperator)} is: the built-in holds the GLOBAL
	 * environment, and {@code (let ((*read-suppress* t)) (read-from-string ...))} is a
	 * dynamic binding only the evaluator can see. A {@code null} query (a bare
	 * {@code Environment}) reads as false.
	 */
	@Nullable private BooleanSupplier readSuppressQuery;

	/**
	 * Installs the {@code *read-suppress*} query consulted by the runtime read built-ins;
	 * see {@link #readSuppressQuery}.
	 * @param query answers whether reading is currently suppressed
	 */
	public void setReadSuppressQuery(BooleanSupplier query) {
		this.readSuppressQuery = query;
	}

	/**
	 * Answers the feature set a RUN-TIME {@code read} / {@code read-from-string} tests
	 * its {@code #+}/{@code #-} guards against: the live value of {@code *features*}.
	 * Installed by the evaluator for the same reason
	 * {@link #setReadSuppressQuery(BooleanSupplier)} is -- the built-in holds the global
	 * environment, and {@code (let ((*features* '(:x))) (read-from-string ...))} is a
	 * dynamic binding only the evaluator can see. A {@code null} query (a bare
	 * {@code Environment}) reads with the static interpreter set.
	 * <p>
	 * This is the direction the read-time set cannot supply: a READ-TIME feature set is
	 * fixed for the duration of the frontend's one read, while a program's own
	 * {@code (push :x *features*)} happens later and must reach the reads it makes after
	 * it. The two are seeded from the same names so they cannot disagree about the build
	 * ({@code .kb/reader-features.md}).
	 */
	@Nullable private Supplier<am.ik.rontolisp.reader.Features> readFeaturesQuery;

	/**
	 * Installs the {@code *features*} query consulted by the runtime read built-ins; see
	 * {@link #readFeaturesQuery}.
	 * @param query answers the current feature set
	 */
	public void setReadFeaturesQuery(Supplier<am.ik.rontolisp.reader.Features> query) {
		this.readFeaturesQuery = query;
	}

	/** The feature set the runtime read built-ins test their guards against. */
	private am.ik.rontolisp.reader.Features currentReadFeatures() {
		return this.readFeaturesQuery == null ? am.ik.rontolisp.reader.Features.INTERPRETER
				: this.readFeaturesQuery.get();
	}

	/**
	 * Value expressions registered by {@link #defineLazy} that have not been forced yet.
	 * Allocated on first use: only the compile path's macro-time environment ever has
	 * one, so an ordinary (per-call) environment pays a null check and no map.
	 */
	@Nullable private Map<String, Supplier<@Nullable LispVal>> pending;

	@Nullable private final Environment parent;

	/**
	 * The name of the {@code block} this scope ESTABLISHES, or {@code null} for an
	 * ordinary scope. A block is a LEXICAL construct: {@code (return-from name v)}
	 * resolves the name up this scope chain -- the same chain a {@code lambda} closes
	 * over -- so a handler or callback built inside a block exits THAT block, never
	 * whichever same-named block happens to be dynamically active at the signal point.
	 * The exit's target is {@link #blockOwner}: the scope itself when the block runs in a
	 * frame of its own, the frame's first block scope when the evaluator's loop entered
	 * it in tail position (.kb/interpreter-tail-calls.md), so recursion and a re-entered
	 * loop body each get their own.
	 */
	@Nullable private String blockName;

	/**
	 * The identity a {@code return-from} aimed at this block's scope targets; see
	 * {@link #blockName}. Null on an ordinary scope.
	 */
	@Nullable private Environment blockOwner;

	/**
	 * The name the program function this scope's code is WRITTEN in was defined under, or
	 * {@code null} outside every one (the top level, an async body): what the uncaught
	 * report's location line names ({@code ConditionTrace}). Lexical, so it follows the
	 * scope chain a closure keeps: a child scope inherits it, a program function's call
	 * scope and a macro expander's scope set their own.
	 */
	@Nullable private String lexicalFunction;

	/**
	 * Create a new environment with the given parent scope.
	 * @param parent the parent environment, or {@code null} for a top-level scope
	 */
	public Environment(@Nullable Environment parent) {
		this.parent = parent;
		this.mvSpill = parent == null ? new ValueCountRegister() : parent.mvSpill;
		this.lexicalFunction = parent == null ? null : parent.lexicalFunction;
	}

	/**
	 * The program function this scope's code is written in; see {@link #lexicalFunction}.
	 * @return the name it was defined under, or {@code null}
	 */
	@Nullable String lexicalFunction() {
		return this.lexicalFunction;
	}

	/**
	 * Makes this freshly created scope the start of a program function's code (a call
	 * scope, a macro expander's), or of code in none (an async body).
	 * @param name the name the function was defined under, or {@code null}
	 */
	void lexicalFunction(@Nullable String name) {
		this.lexicalFunction = name;
	}

	/**
	 * Marks this scope as the one a {@code (block name ...)} establishes and as the
	 * target of its exits; see {@link #blockName}. Called once, on a scope created for
	 * the block body alone, by a catcher that compares the exit's target with this scope.
	 * @param name the block name ({@code "NIL"} for the nil block)
	 */
	void installBlock(String name) {
		installBlock(name, this);
	}

	/**
	 * Marks this scope as the one a {@code (block name ...)} establishes, with the exit
	 * target {@code owner}: the scope the evaluator's loop frame catches exits for, i.e.
	 * the first block scope that frame entered, shared by every block it reaches in tail
	 * position afterwards (.kb/interpreter-tail-calls.md).
	 * @param name the block name ({@code "NIL"} for the nil block)
	 * @param owner the identity an exit aimed at this block carries
	 */
	void installBlock(String name, Environment owner) {
		this.blockName = name;
		this.blockOwner = owner;
	}

	/**
	 * The exit target of the block of the given name that is lexically innermost here --
	 * what a {@code return-from} evaluated in this scope aims at -- or {@code null} when
	 * no such block is lexically visible.
	 * @param name the block name being exited
	 * @return the target identity ({@link #blockOwner} of the establishing scope)
	 */
	@Nullable Environment findBlock(String name) {
		for (Environment scope = this; scope != null; scope = scope.parent) {
			if (name.equals(scope.blockName)) {
				return scope.blockOwner;
			}
		}
		return null;
	}

	@Override
	public LispVal lookup(String name) {
		LispVal val = this.bindings.get(name);
		if (val != null) {
			return val;
		}
		if (this.pending != null) {
			LispVal forced = force(name);
			if (forced != null) {
				return forced;
			}
		}
		if (this.parent != null) {
			return this.parent.lookup(name);
		}
		if (LispNames.MV_SPILL.equals(name)) {
			return this.mvSpill.get();
		}
		throw LispEvalException.ofClass(ClosRegistry.UNBOUND_VARIABLE_CLASS_NAME,
				ClosRegistry.UNBOUND_VARIABLE_MESSAGE_PREFIX + name + ClosRegistry.UNBOUND_VARIABLE_MESSAGE_SUFFIX);
	}

	/**
	 * Look up a name in the variable namespace, searching up the scope chain.
	 * @param name the variable name
	 * @return the value, or {@code null} if unbound
	 */
	public @Nullable LispVal lookupOrNull(String name) {
		LispVal val = this.bindings.get(name);
		if (val != null) {
			return val;
		}
		if (this.pending != null) {
			LispVal forced = force(name);
			if (forced != null) {
				return forced;
			}
		}
		if (this.parent != null) {
			return this.parent.lookupOrNull(name);
		}
		return LispNames.MV_SPILL.equals(name) ? this.mvSpill.get() : null;
	}

	/**
	 * Registers a value expression to be evaluated only if the name is actually read --
	 * the compile path's macro-time globals (see
	 * {@code UserMacroExpander.registerMacroTimeDefinitions}). Supersedes any current
	 * value: a forced-then-redefined name goes back to pending, which is what
	 * {@code defparameter} means.
	 * <p>
	 * COMPILE PATH ONLY. Forcing makes {@link #lookup} a writer, and this class is a
	 * plain {@code HashMap} that the interpreter shares across the virtual threads of an
	 * http handler. It is safe because the only producer -- {@code UserMacroExpander}'s
	 * single-threaded macro-expansion pass -- runs before any program does; a runtime
	 * environment never has a pending entry, and the extra null check is all it pays.
	 * @param name the variable name
	 * @param init supplies the value on first read; returns {@code null} to leave the
	 * name unbound (its own diagnostic already reported)
	 */
	public void defineLazy(String name, Supplier<@Nullable LispVal> init) {
		if (this.pending == null) {
			this.pending = new HashMap<>();
		}
		this.bindings.remove(name);
		this.pending.put(name, init);
	}

	/**
	 * Evaluates a pending value expression, if any, and installs the result. The entry is
	 * removed BEFORE the supplier runs, so an init form that reads its own variable sees
	 * it unbound rather than recursing.
	 * @return the forced value, or {@code null} when nothing was pending or the
	 * expression declined to produce a value
	 */
	private @Nullable LispVal force(String name) {
		Map<String, Supplier<@Nullable LispVal>> map = this.pending;
		if (map == null) {
			return null;
		}
		Supplier<@Nullable LispVal> init = map.remove(name);
		if (init == null) {
			return null;
		}
		LispVal value = init.get();
		if (value == null) {
			return null;
		}
		this.bindings.put(name, value);
		return value;
	}

	/**
	 * Whether a name has a value anywhere in this scope chain, WITHOUT running a pending
	 * value expression. Existence tests ({@code boundp}, {@code find-symbol}) ask this
	 * instead of {@link #lookupOrNull}: a name registered by {@link #defineLazy} is bound
	 * -- its expression is what the value will be -- and forcing it merely to answer
	 * "yes" would run arbitrary work for nothing.
	 * @param name the variable name
	 * @return {@code true} when the name has a value or a pending value expression
	 */
	public boolean hasBinding(String name) {
		if (this.bindings.containsKey(name) || (this.pending != null && this.pending.containsKey(name))) {
			return true;
		}
		return this.parent != null && this.parent.hasBinding(name);
	}

	/**
	 * Look up a name in the function namespace, searching up the scope chain.
	 * @param name the function name
	 * @return the function value
	 * @throws LispEvalException if the function is undefined
	 */
	public LispVal lookupFunction(String name) {
		LispVal val = lookupFunctionOrNull(name);
		if (val == null) {
			throw LispEvalException.ofClass(ClosRegistry.UNDEFINED_FUNCTION_CLASS_NAME,
					ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX + name
							+ ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX);
		}
		return val;
	}

	/**
	 * Look up a name in the function namespace, searching up the scope chain.
	 * @param name the function name
	 * @return the function value, or {@code null} if undefined
	 */
	public @Nullable LispVal lookupFunctionOrNull(String name) {
		LispVal val = this.functions.get(name);
		if (val != null) {
			return val;
		}
		if (this.parent != null) {
			return this.parent.lookupFunctionOrNull(name);
		}
		return null;
	}

	/**
	 * Define a binding in the function namespace of this environment.
	 * @param name the function name
	 * @param value the function value
	 */
	public void defineFunction(String name, LispVal value) {
		this.functions.put(name, value);
	}

	/**
	 * Removes a binding from the function namespace of this environment, if present. The
	 * {@code fmakunbound} primitive; parent scopes are untouched, so calling it on the
	 * global environment makes the name undefined image-wide.
	 * @param name the function name
	 */
	public void undefineFunction(String name) {
		this.functions.remove(name);
	}

	/**
	 * Define a new binding in this environment.
	 * @param name the variable name
	 * @param value the value to bind
	 */
	public void define(String name, LispVal value) {
		if (this.pending != null) {
			this.pending.remove(name);
		}
		this.bindings.put(name, value);
	}

	/**
	 * Returns whether a variable is bound in this environment (not including parent
	 * scopes). Used by {@code defvar} to decide whether to assign the initial value. A
	 * name whose value expression is still pending counts as bound -- {@code defvar} is
	 * idempotent whether or not anything has read the variable yet.
	 * @param name the variable name
	 * @return {@code true} if the name is bound in this environment
	 */
	public boolean isBound(String name) {
		return this.bindings.containsKey(name) || (this.pending != null && this.pending.containsKey(name));
	}

	/**
	 * Set an existing binding, searching up the scope chain.
	 * @param name the variable name
	 * @param value the new value
	 */
	public void set(String name, LispVal value) {
		if (this.bindings.containsKey(name) || (this.pending != null && this.pending.containsKey(name))) {
			// An assignment supersedes a pending value expression outright: the init form
			// would only be evaluated to have its result overwritten.
			if (this.pending != null) {
				this.pending.remove(name);
			}
			this.bindings.put(name, value);
			return;
		}
		if (this.parent != null) {
			this.parent.set(name, value);
			return;
		}
		if (LispNames.MV_SPILL.equals(name)) {
			// The expansions' (setq %mv-spill ...): a publish or a clear of the
			// channel, never a binding.
			this.mvSpill.set(value);
			return;
		}
		// If not found anywhere, define in current scope
		this.bindings.put(name, value);
	}

	/**
	 * Publishes a producer's values to the {@code %mv-spill} channel of this -- the
	 * global -- environment: the values after the primary as a fresh list, nil for
	 * exactly one value, {@code LispMacroExpander.MV_ZERO_VALUES} for none.
	 * @param extras the channel value
	 */
	void publishSpill(LispVal extras) {
		this.mvSpill.set(extras);
	}

	/**
	 * Clears the {@code %mv-spill} channel of this -- the global -- environment: the
	 * value just produced is one value. The evaluator calls this on every primitive step
	 * (an atom, a built-in's return, a closed argument list, a constructing special
	 * form), which is what keeps a publish from travelling past the form that made it --
	 * the value-count register of a native implementation, kept as a field.
	 */
	void clearSpill() {
		this.mvSpill.set(LispNil.INSTANCE);
	}

	/**
	 * The {@code %mv-spill} channel of this -- the global -- environment.
	 * @return nil, the extra values, or the zero-values marker
	 */
	LispVal spill() {
		return this.mvSpill.get();
	}

	/**
	 * Create the global environment with all built-in functions.
	 * @param out the output stream for print operations
	 * @return the global environment
	 */
	public static Environment createGlobal(PrintStream out) {
		return createGlobal(out, InputStream.nullInputStream());
	}

	/**
	 * Create the global environment with all built-in functions.
	 * @param out the output stream for print operations
	 * @param in the input stream for read operations
	 * @return the global environment
	 */
	public static Environment createGlobal(PrintStream out, InputStream in) {
		Environment env = new Environment(null);
		registerArithmetic(env);
		registerMath(env);
		registerComplex(env);
		registerComparison(env);
		registerIO(env, out, in);
		registerPredicates(env);
		registerListOps(env);
		registerSequenceOps(env);
		registerStringOps(env);
		registerCharacters(env);
		registerTypeConversion(env);
		registerHashTables(env);
		registerArrays(env);
		registerPackages(env);
		registerMutexes(env);
		// The multiple-value spill channel (see LispNames.MV_SPILL) is the mvSpill
		// field, not a binding; the compilers inject a top-level (setq %mv-spill nil)
		// global when needed.
		// Informational (every float is the one double representation); predefined so
		// library code reading it works. The compilers inject an equivalent setq.
		env.define(LispNames.READ_DEFAULT_FLOAT_FORMAT, new LispSymbol("DOUBLE-FLOAT"));
		// The standard constant variables (pi, the float-range names, the fixnum and
		// array limits, char-code-limit, internal-time-units-per-second,
		// lambda-list-keywords): bound as globals holding the interpreter's values,
		// so a reference in code position answers the constant while a quoted
		// reference stays the symbol (see .kb/read-time-constants.md). The maximum
		// array dimension answers what Java arrays cap just below Integer.MAX_VALUE.
		for (String name : ClConstants.sortedNames()) {
			env.define(name, java.util.Objects.requireNonNull(ClConstants.value(name, false)));
		}
		// *gensym-counter* and *random-state* special variables, bound so they are not
		// unbound.
		env.define(LispNames.GENSYM_COUNTER_VAR, new LispInteger(0));
		env.define(LispNames.RANDOM_STATE_VAR, LispNil.INSTANCE);
		// The pathname operators' default `defaults`. #P"" -- the empty pathname, SBCL's
		// initial value too -- is the only honest one here: rontolisp absolutizes
		// nothing and names no working directory
		// (LispNames.DEFAULT_PATHNAME_DEFAULTS_VAR).
		env.define(LispNames.DEFAULT_PATHNAME_DEFAULTS_VAR,
				new LispInstance(LispLayout.PATHNAME, new LispVal[] { new LispString("") }));
		// Accepted and ignored: the printer does no circle detection.
		env.define(LispNames.PRINT_CIRCLE_VAR, LispNil.INSTANCE);
		// The two printer-mode variables a portable print-object method tests. Their
		// global values are CL's; *print-escape* is REBOUND around a print-object call
		// so the method can tell prin1 from princ (LispMacroExpander.printObjectCall).
		env.define(LispNames.PRINT_ESCAPE_VAR, LispTrue.INSTANCE);
		env.define(LispNames.PRINT_READABLY_VAR, LispNil.INSTANCE);
		// The pretty-printer control variables. *print-pretty* is t as in most
		// implementations, and gates the one thing the subset really does -- a MANDATORY
		// line break; the three width variables are accepted and ignored because a
		// conditional break needs a column no backend tracks (.kb/pretty-printer.md).
		env.define(LispNames.PRINT_PRETTY_VAR, LispTrue.INSTANCE);
		env.define(LispNames.PRINT_RIGHT_MARGIN_VAR, LispNil.INSTANCE);
		env.define(LispNames.PRINT_MISER_WIDTH_VAR, LispNil.INSTANCE);
		env.define(LispNames.PRINT_LINES_VAR, LispNil.INSTANCE);
		// The remaining CL printer-control variables. Every default here is what the
		// printer ACTUALLY does, so a program that only reads them sees the truth;
		// binding one to a non-default value is what has no effect.
		env.define(LispNames.PRINT_LENGTH_VAR, LispNil.INSTANCE);
		env.define(LispNames.PRINT_LEVEL_VAR, LispNil.INSTANCE);
		env.define(LispNames.PRINT_BASE_VAR, new LispInteger(10));
		env.define(LispNames.PRINT_RADIX_VAR, LispNil.INSTANCE);
		env.define(LispNames.PRINT_CASE_VAR, new LispSymbol(LispNames.PRINT_CASE_UPCASE));
		env.define(LispNames.PRINT_ARRAY_VAR, LispTrue.INSTANCE);
		env.define(LispNames.PRINT_GENSYM_VAR, LispTrue.INSTANCE);
		// The remaining standard stream variables, all the t designator like
		// *standard-output* (see LispNames.TRACE_OUTPUT_VAR).
		env.define(LispNames.TRACE_OUTPUT_VAR, LispTrue.INSTANCE);
		env.define(LispNames.DEBUG_IO_VAR, LispTrue.INSTANCE);
		env.define(LispNames.QUERY_IO_VAR, LispTrue.INSTANCE);
		env.define(LispNames.TERMINAL_IO_VAR, LispTrue.INSTANCE);
		// The initial pprint dispatch table: a one-element list holding the (empty) entry
		// list, mutable so set-pprint-dispatch can add to the table it is handed.
		env.define(LispNames.PRINT_PPRINT_DISPATCH_VAR, new LispCons(LispNil.INSTANCE, LispNil.INSTANCE));
		// The provided-module list `provide` pushes onto and `require` consults.
		env.define(LispNames.MODULES_VAR, LispNil.INSTANCE);
		// Accepted and ignored: the reader is not readtable-driven, a "readtable" is an
		// opaque nil token (see LispNames.COPY_READTABLE).
		env.define(LispNames.READTABLE_VAR, LispNil.INSTANCE);
		// #. read-time eval is enabled by default; binding it nil makes the marker
		// resolver signal (LispEvaluator.resolveReadTimeEval), per CLHS.
		env.define(LispNames.READ_EVAL_VAR, LispTrue.INSTANCE);
		// *read-suppress* is nil by default; binding it true makes read/read-from-string
		// consume the datum's characters and answer nil (CLHS 2.2).
		env.define(LispNames.READ_SUPPRESS_VAR, LispNil.INSTANCE);
		// The load-context pathname variables. *load-pathname* / *load-truename* are
		// REBOUND around each loaded file (LispEvaluator.loadFile); the compile-file pair
		// is permanently nil because rontolisp has no compile-file (see LispNames).
		env.define(LispNames.LOAD_PATHNAME_VAR, LispNil.INSTANCE);
		env.define(LispNames.LOAD_TRUENAME_VAR, LispNil.INSTANCE);
		env.define(LispNames.COMPILE_FILE_PATHNAME_VAR, LispNil.INSTANCE);
		env.define(LispNames.COMPILE_FILE_TRUENAME_VAR, LispNil.INSTANCE);
		// The load-report switches, nil because nothing here reports: load prints no
		// banner and echoes no form value, and its own :verbose / :print keywords are
		// accepted and ignored for the same reason (see LispNames.LOAD_VERBOSE_VAR).
		env.define(LispNames.LOAD_VERBOSE_VAR, LispNil.INSTANCE);
		env.define(LispNames.LOAD_PRINT_VAR, LispNil.INSTANCE);
		env.define(LispNames.COMPILE_VERBOSE_VAR, LispNil.INSTANCE);
		env.define(LispNames.COMPILE_PRINT_VAR, LispNil.INSTANCE);
		// *features*: an ordinary special holding the interpreter's feature list. The
		// compile paths seed the same variable with their own target set
		// (LispMacroExpander.injectMvSpillGlobal); see .kb/reader-features.md.
		env.define(LispNames.FEATURES_VAR, featureKeywordList(am.ik.rontolisp.reader.Features.INTERPRETER.names()));
		// The standard streams are the t designator (the process standard stream), which
		// the whole print / read family accepts as a stream argument. The exception is
		// *error-output*: t already names the process standard OUTPUT, so the error
		// stream is a stream VALUE over the reserved handle 2 instead (the WASI fd every
		// backend writes stderr through) -- see StreamDesignators.
		env.define(LispNames.STANDARD_OUTPUT_VAR, LispTrue.INSTANCE);
		env.define(LispNames.ERROR_OUTPUT_VAR, StreamDesignators.standardErrorValue());
		env.define(LispNames.STANDARD_INPUT_VAR, LispTrue.INSTANCE);
		// A #. marker that survives into code position (a backquote template splits the
		// marker list into construction code, so the load-time substitution walk never
		// sees it whole) is called as an ordinary function: its argument -- the datum
		// -- has just been evaluated by the caller, so identity completes the deferred
		// read-time evaluation.
		env.defineFunction(LispNames.READ_EVAL, new LispFunction(LispNames.READ_EVAL, args -> {
			requireArgCount(LispNames.READ_EVAL, args, 1);
			return args.get(0);
		}));
		env.defineFunction(LispNames.READ_EVAL_TEMPLATE, new LispFunction(LispNames.READ_EVAL_TEMPLATE, args -> {
			requireArgCount(LispNames.READ_EVAL_TEMPLATE, args, 1);
			return args.get(0);
		}));
		return env;
	}

	/**
	 * The {@code *features*} list as the reader would have read it: the names as
	 * keywords, in the feature set's own order. Features are keywords printed uppercase
	 * like every other symbol under the reader's upcase premise, and the compile paths
	 * spell them the same way ({@code LispMacroExpander.injectMvSpillGlobal}).
	 * @param names the feature names, without the leading colon
	 * @return the keyword list
	 */
	static LispVal featureKeywordList(List<String> names) {
		LispVal featureList = LispNil.INSTANCE;
		for (int i = names.size() - 1; i >= 0; i--) {
			featureList = new LispCons(new LispSymbol(":" + names.get(i).toUpperCase(java.util.Locale.ROOT)),
					featureList);
		}
		return featureList;
	}

	private static void registerHashTables(Environment env) {
		env.defineFunction(LispNames.MAKE_HASH_TABLE, new LispFunction(LispNames.MAKE_HASH_TABLE, args -> {
			// Reads :test and ignores other keywords such as :size. Only equalp changes
			// placement (its keys are folded); eq and eql compare by identity for
			// aggregates (see LispHashTable).
			int testCode = LispHashTable.TEST_EQUAL;
			for (int i = 0; i + 1 < args.size(); i += 2) {
				if (args.get(i) instanceof LispSymbol kw && LispNames.TEST_KEYWORD.equals(kw.name())) {
					String testName = switch (args.get(i + 1)) {
						case LispSymbol s -> s.name();
						case LispFunction f -> f.name();
						default -> "";
					};
					testCode = LispHashTable.testCodeFor(testName);
				}
			}
			return new LispHashTable(testCode);
		}));
		env.defineFunction(LispNames.GETHASH, new LispFunction(LispNames.GETHASH, args -> {
			if (args.size() != 2 && args.size() != 3) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.GETHASH + " expects 2 or 3 arguments, got " + args.size());
			}
			LispHashTable table = requireHashTable(LispNames.GETHASH, args.get(1));
			LispVal dflt = (args.size() == 3) ? args.get(2) : LispNil.INSTANCE;
			return table.get(args.get(0), dflt);
		}));
		env.defineFunction(LispNames.PUTHASH, new LispFunction(LispNames.PUTHASH, args -> {
			requireArgCount(LispNames.PUTHASH, args, 3);
			LispHashTable table = requireHashTable(LispNames.PUTHASH, args.get(1));
			return table.put(args.get(0), args.get(2));
		}));
		env.defineFunction(LispNames.REMHASH, new LispFunction(LispNames.REMHASH, args -> {
			requireArgCount(LispNames.REMHASH, args, 2);
			LispHashTable table = requireHashTable(LispNames.REMHASH, args.get(1));
			return table.remove(args.get(0)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.CLRHASH, new LispFunction(LispNames.CLRHASH, args -> {
			requireArgCount(LispNames.CLRHASH, args, 1);
			LispHashTable table = requireHashTable(LispNames.CLRHASH, args.get(0));
			table.clear();
			return table;
		}));
		env.defineFunction(LispNames.HASH_TABLE_COUNT, new LispFunction(LispNames.HASH_TABLE_COUNT, args -> {
			requireArgCount(LispNames.HASH_TABLE_COUNT, args, 1);
			return new LispInteger(requireHashTable(LispNames.HASH_TABLE_COUNT, args.get(0)).count());
		}));
		// hash-table-size / -rehash-size / -rehash-threshold: the lite triple a
		// portable copy-hash-table reads before rebuilding a table (alexandria's).
		// rontolisp tables have no capacity of their own -- growth belongs to the host
		// map -- so size is the entry count and the two growth knobs report the
		// standard defaults.
		env.defineFunction(LispNames.HASH_TABLE_SIZE, new LispFunction(LispNames.HASH_TABLE_SIZE, args -> {
			requireArgCount(LispNames.HASH_TABLE_SIZE, args, 1);
			return new LispInteger(requireHashTable(LispNames.HASH_TABLE_SIZE, args.get(0)).count());
		}));
		env.defineFunction(LispNames.HASH_TABLE_REHASH_SIZE,
				new LispFunction(LispNames.HASH_TABLE_REHASH_SIZE, args -> {
					requireArgCount(LispNames.HASH_TABLE_REHASH_SIZE, args, 1);
					requireHashTable(LispNames.HASH_TABLE_REHASH_SIZE, args.get(0));
					return new LispDouble(1.5);
				}));
		env.defineFunction(LispNames.HASH_TABLE_REHASH_THRESHOLD,
				new LispFunction(LispNames.HASH_TABLE_REHASH_THRESHOLD, args -> {
					requireArgCount(LispNames.HASH_TABLE_REHASH_THRESHOLD, args, 1);
					requireHashTable(LispNames.HASH_TABLE_REHASH_THRESHOLD, args.get(0));
					return new LispDouble(1.0);
				}));
		// hash-table-test: the test the table actually implements.
		env.defineFunction(LispNames.HASH_TABLE_TEST, new LispFunction(LispNames.HASH_TABLE_TEST, args -> {
			requireArgCount(LispNames.HASH_TABLE_TEST, args, 1);
			LispHashTable table = requireHashTable(LispNames.HASH_TABLE_TEST, args.get(0));
			return new LispSymbol(LispHashTable.testNameFor(table.testCode()));
		}));
		env.defineFunction(LispNames.HASH_TABLE_P, new LispFunction(LispNames.HASH_TABLE_P, args -> {
			requireArgCount(LispNames.HASH_TABLE_P, args, 1);
			return (args.get(0) instanceof LispHashTable) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.FUNCTIONP, new LispFunction(LispNames.FUNCTIONP, args -> {
			requireArgCount(LispNames.FUNCTIONP, args, 1);
			return (args.get(0) instanceof LispFunction || args.get(0) instanceof LispLambda) ? LispTrue.INSTANCE
					: LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.ARRAYP_INTERNAL, new LispFunction(LispNames.ARRAYP_INTERNAL, args -> {
			requireArgCount(LispNames.ARRAYP_INTERNAL, args, 1);
			return (args.get(0) instanceof LispArray || args.get(0) instanceof LispFloatArray
					|| args.get(0) instanceof LispIntVector) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// %simple-array-p: is the value an array (a string included) that is SIMPLE --
		// no fill pointer, not adjustable, not displaced? A packed representation is
		// simple by construction (make-array degrades to the general one the moment
		// :fill-pointer / :adjustable / :displaced-to appears), and a value that is no
		// array at all answers nil, so the predicate is total and needs no guard at its
		// call sites -- the simple- type specifiers are exactly this test plus their
		// general counterpart's.
		env.defineFunction(LispNames.SIMPLE_ARRAY_P_INTERNAL,
				new LispFunction(LispNames.SIMPLE_ARRAY_P_INTERNAL, args -> {
					requireArgCount(LispNames.SIMPLE_ARRAY_P_INTERNAL, args, 1);
					boolean simple = switch (args.get(0)) {
						case LispString str -> str.fillPointer() < 0 && !str.adjustable() && str.displacedTo() == null;
						case LispArray array ->
							!array.hasFillPointer() && !array.adjustable() && array.displacedTo() == null;
						case LispFloatArray ignored -> true;
						case LispIntVector ignored -> true;
						default -> false;
					};
					return simple ? LispTrue.INSTANCE : LispNil.INSTANCE;
				}));
		// %string-dimension: the array DIMENSION of a string, which is what a sized
		// string type specifier compares against -- the capacity, not the fill-pointer-
		// bounded length `length` answers. A displaced string view reports its own span.
		env.defineFunction(LispNames.STRING_DIMENSION_INTERNAL,
				new LispFunction(LispNames.STRING_DIMENSION_INTERNAL, args -> {
					requireArgCount(LispNames.STRING_DIMENSION_INTERNAL, args, 1);
					if (args.get(0) instanceof LispString str) {
						return new LispInteger(str.capacity());
					}
					throw new LispEvalException(LispNames.STRING_DIMENSION_INTERNAL + ": not a string");
				}));
		// arrayp: the standard spelling -- a string is an array in CL, so the public
		// predicate is the internal one widened by stringp.
		env.defineFunction(LispNames.ARRAYP, new LispFunction(LispNames.ARRAYP, args -> {
			requireArgCount(LispNames.ARRAYP, args, 1);
			return (args.get(0) instanceof LispString || args.get(0) instanceof LispArray
					|| args.get(0) instanceof LispFloatArray || args.get(0) instanceof LispIntVector)
							? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// vectorp: strings are vectors in CL. A vector is a rank-1 array and nothing
		// else, so the rank IS checked -- a rank-2 or rank-0 array is an array but not
		// a vector (see makeTypeTest, which builds the same answer for the `vector`
		// type specifier on the compile paths). A string and a packed integer vector
		// are rank-1 by construction; a packed float array carries its own rank.
		env.defineFunction(LispNames.VECTORP, new LispFunction(LispNames.VECTORP, args -> {
			requireArgCount(LispNames.VECTORP, args, 1);
			return switch (args.get(0)) {
				case LispString ignored -> LispTrue.INSTANCE;
				case LispIntVector ignored -> LispTrue.INSTANCE;
				case LispArray array -> array.dimensions().length == 1 ? LispTrue.INSTANCE : LispNil.INSTANCE;
				case LispFloatArray array -> array.rank() == 1 ? LispTrue.INSTANCE : LispNil.INSTANCE;
				default -> LispNil.INSTANCE;
			};
		}));
	}

	/**
	 * The {@code make-array} builtin, with its {@code :element-type} designator resolved
	 * through {@link LispMacroExpander#resolveElementTypeAlias(LispVal, ClosRegistry)}.
	 * Registered registry-less by {@link #createGlobal}; the evaluator re-registers it
	 * with its class registry so a user {@code deftype} alias (salza2's {@code octet})
	 * selects the same representation the literal spelling would -- exactly as the
	 * compile paths resolve it, and the same arrangement {@link #concatenateBuiltin}
	 * uses.
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the literal designators only
	 * @return the builtin function
	 */
	public static LispFunction makeArrayBuiltin(@Nullable ClosRegistry closRegistry) {
		return new LispFunction(LispNames.MAKE_ARRAY, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.MAKE_ARRAY + " expects at least 1 argument");
			}
			LispVal init = LispNil.INSTANCE;
			boolean initGiven = false;
			LispVal initialContents = null;
			LispVal fillPointerArg = null;
			boolean adjustable = false;
			LispVal displacedToArg = null;
			LispVal displacedOffsetArg = null;
			LispVal elementTypeArg = null;
			for (int i = 1; i + 1 < args.size(); i += 2) {
				if (args.get(i) instanceof LispSymbol kw) {
					switch (kw.name()) {
						case LispNames.INITIAL_ELEMENT_KEYWORD -> {
							init = args.get(i + 1);
							initGiven = true;
						}
						case LispNames.INITIAL_CONTENTS_KEYWORD -> initialContents = args.get(i + 1);
						case LispNames.FILL_POINTER_KEYWORD -> fillPointerArg = args.get(i + 1);
						case LispNames.ADJUSTABLE_KEYWORD -> adjustable = !(args.get(i + 1) instanceof LispNil);
						case LispNames.DISPLACED_TO_KEYWORD -> displacedToArg = args.get(i + 1);
						case LispNames.DISPLACED_INDEX_OFFSET_KEYWORD -> displacedOffsetArg = args.get(i + 1);
						case LispNames.ELEMENT_TYPE_KEYWORD ->
							elementTypeArg = LispMacroExpander.resolveElementTypeAlias(args.get(i + 1), closRegistry);
						default -> {
						}
					}
				}
			}
			int[] dims = parseDimensions(args.get(0));
			int total = 1;
			for (int d : dims) {
				total *= d;
			}
			if (displacedToArg != null && !(displacedToArg instanceof LispNil)) {
				// A displaced array owns no storage, but it MAY carry a fill pointer and
				// the :adjustable flag -- CLHS forbids only :initial-element /
				// :initial-contents alongside :displaced-to, since the view has nothing
				// of its own to initialize.
				if (initGiven || initialContents != null) {
					throw new LispEvalException(LispNames.MAKE_ARRAY
							+ ": :displaced-to cannot be combined with :initial-element/:initial-contents");
				}
				int offset = displacedOffsetArg == null ? 0 : (int) asLong(displacedOffsetArg);
				int viewFillPointer = parseFillPointer(fillPointerArg, dims, total);
				// The TARGET decides the shape, not :element-type: displacing onto a
				// string answers a string view (a string is its own value type here, so
				// a LispArray view could not alias its buffer), and the portable
				// substring idiom -- cl-ppcre's nsubseq -- passes the target's own
				// (array-element-type sequence) rather than a literal.
				if (displacedToArg instanceof LispString targetString) {
					if (dims.length != 1) {
						throw new LispEvalException(
								LispNames.MAKE_ARRAY + ": a string :displaced-to target needs a rank-1 view");
					}
					if (offset < 0 || total + offset > targetString.capacity()) {
						throw new LispEvalException(
								LispNames.MAKE_ARRAY + ": :displaced-to string is too small for the requested view");
					}
					return new LispString(targetString, offset, total, viewFillPointer, adjustable);
				}
				// A PACKED vector or float array is a target on the same terms as a
				// general array -- it is the shape CLHS's :displaced-to element-type
				// rule is written for -- so the view is an ordinary LispArray view whose
				// element access ends on that storage and whose element type is the
				// target's representation.
				int targetTotal = switch (displacedToArg) {
					case LispIntVector iv -> iv.length();
					case LispFloatArray fa -> fa.totalSize();
					default -> requireArray(LispNames.MAKE_ARRAY, displacedToArg).totalSize();
				};
				if (offset < 0 || total + offset > targetTotal) {
					throw new LispEvalException(
							LispNames.MAKE_ARRAY + ": :displaced-to array is too small for the requested view");
				}
				return new LispArray(dims, displacedToArg, offset, viewFillPointer, adjustable);
			}
			if (displacedOffsetArg != null && !(displacedOffsetArg instanceof LispNil)) {
				throw new LispEvalException(LispNames.MAKE_ARRAY + ": :displaced-index-offset requires :displaced-to");
			}
			boolean hasFillPointer = fillPointerArg != null && !(fillPointerArg instanceof LispNil);
			LispFloatArray packedProto = LispFloatArray.prototypeFor(elementTypeArg);
			if (packedProto != null && !hasFillPointer && !adjustable) {
				// A :element-type naming a packed float width with no fill pointer /
				// adjustability / displacement selects the packed float-array
				// representation (the width's unboxed backing). :initial-contents fills
				// it
				// (any rank, walked row-major like the general array below); otherwise
				// the initial element coerces to a double (narrowed to the width where
				// the
				// backing is narrower). A non-real is a type error (there is no degrade
				// path). The switch is over the PERMITS with no default arm, so the
				// width -> allocation direction is a compile error for the next permit;
				// the name -> width direction reads each permit's own elementType()
				// answer
				// (LispFloatArray.prototypeFor), and PackedFloatReachabilityTest pins
				// that
				// every permit is in that table.
				double fill = initGiven ? asDouble(init) : 0.0;
				switch (packedProto) {
					case LispBFloat16Array ignored -> {
						short[] bdata = new short[total];
						if (initialContents != null) {
							LispVal[] tmp = new LispVal[total];
							fillInitialContents(initialContents, dims, 0, tmp, 0);
							for (int i = 0; i < total; i++) {
								bdata[i] = (short) BFloat16.bits(asDouble(tmp[i]));
							}
						}
						else if (fill != 0.0) {
							java.util.Arrays.fill(bdata, (short) BFloat16.bits(fill));
						}
						return new LispBFloat16Array(bdata, dims);
					}
					case LispSingleFloatArray ignored -> {
						float[] sdata = new float[total];
						if (initialContents != null) {
							LispVal[] tmp = new LispVal[total];
							fillInitialContents(initialContents, dims, 0, tmp, 0);
							for (int i = 0; i < total; i++) {
								sdata[i] = (float) asDouble(tmp[i]);
							}
						}
						else if (fill != 0.0) {
							java.util.Arrays.fill(sdata, (float) fill);
						}
						return new LispSingleFloatArray(sdata, dims);
					}
					case LispDoubleFloatArray ignored -> {
						double[] fdata = new double[total];
						if (initialContents != null) {
							LispVal[] tmp = new LispVal[total];
							fillInitialContents(initialContents, dims, 0, tmp, 0);
							for (int i = 0; i < total; i++) {
								fdata[i] = asDouble(tmp[i]);
							}
						}
						else if (fill != 0.0) {
							java.util.Arrays.fill(fdata, fill);
						}
						return new LispDoubleFloatArray(fdata, dims);
					}
				}
			}
			int packedIntWidth = packedIntElementWidth(elementTypeArg);
			if (packedIntWidth > 0 && dims.length == 1 && !hasFillPointer && !adjustable) {
				// :element-type '(unsigned-byte 8|16|32) on a rank-1 array selects the
				// packed integer-vector representation (todo 194 stage 2). Stores mask to
				// the width (what raw i8/i16/i32 storage does on the compiled backends);
				// reads widen unsigned. A non-integer element is a type error. Rank-n /
				// fill-pointer / adjustable / displaced combinations keep the general
				// boxed representation, like the packed float fallback.
				long[] idata = new long[total];
				if (initialContents != null) {
					LispVal[] tmp = new LispVal[total];
					fillInitialContents(initialContents, dims, 0, tmp, 0);
					for (int i = 0; i < total; i++) {
						idata[i] = exactIntElement(LispNames.MAKE_ARRAY, tmp[i]);
					}
				}
				else if (initGiven) {
					long fill = exactIntElement(LispNames.MAKE_ARRAY, init) & LispIntVector.mask(packedIntWidth);
					if (fill != 0) {
						java.util.Arrays.fill(idata, fill);
					}
				}
				return new LispIntVector(packedIntWidth, idata);
			}
			// A rank-1 :element-type 'character array is a string in CL, so build a
			// mutable LispString (the make-string result shape; space-filled unless
			// :initial-element says otherwise). :fill-pointer/:adjustable carry over --
			// the fill pointer is the string's effective length, the requested
			// dimension its capacity.
			if (isCharacterElementType(elementTypeArg) && dims.length == 1) {
				StringBuilder sb = new StringBuilder();
				if (initialContents != null) {
					LispVal[] chars = new LispVal[total];
					fillInitialContents(initialContents, dims, 0, chars, 0);
					for (LispVal c : chars) {
						sb.appendCodePoint(requireChar(LispNames.MAKE_ARRAY, c).codePoint());
					}
				}
				else {
					int fillChar = initGiven ? requireChar(LispNames.MAKE_ARRAY, init).codePoint() : ' ';
					for (int i = 0; i < total; i++) {
						sb.appendCodePoint(fillChar);
					}
				}
				if (!hasFillPointer && !adjustable) {
					return new LispString(sb.toString());
				}
				int fp = -1;
				if (hasFillPointer) {
					fp = (fillPointerArg instanceof LispInteger n) ? (int) n.value() : dims[0];
					if (fp < 0 || fp > dims[0]) {
						throw new LispEvalException(LispNames.MAKE_ARRAY + ": :fill-pointer out of range");
					}
				}
				return new LispString(sb.toString(), fp, adjustable);
			}
			// A specialized element type that reaches the GENERAL representation -- a
			// character or packed-integer one above rank 1, any of them combined with a
			// fill pointer or adjustability -- is REMEMBERED on the array: it is what
			// array-element-type answers, and its own zero is what an unsupplied element
			// takes rather than nil, in an array the program asked to hold characters,
			// bytes or floats.
			int elementTypeCode = ArrayElementTypes.codeOf(elementTypeArg);
			if (!initGiven && initialContents == null) {
				LispVal typeDefault = ArrayElementTypes.defaultElement(elementTypeCode);
				if (typeDefault != null) {
					init = typeDefault;
				}
			}
			LispVal[] data = new LispVal[total];
			for (int i = 0; i < total; i++) {
				data[i] = init;
			}
			if (initialContents != null) {
				fillInitialContents(initialContents, dims, 0, data, 0);
			}
			int fillPointer = parseFillPointer(fillPointerArg, dims, total);
			return new LispArray(dims, data, fillPointer, adjustable, elementTypeCode);
		});
	}

	// The shared make-array :fill-pointer rule: nil / absent is no fill pointer (-1),
	// t is the vector's own size and an integer is that value, range-checked. Only
	// rank-1 arrays may carry one. A DISPLACED view parses it the same way -- its size
	// is the view's dimension, not the target's.
	private static int parseFillPointer(@Nullable LispVal fillPointerArg, int[] dims, int total) {
		if (fillPointerArg == null || fillPointerArg instanceof LispNil) {
			return -1;
		}
		if (dims.length != 1) {
			throw new LispEvalException(LispNames.MAKE_ARRAY + ": :fill-pointer requires a rank-1 array");
		}
		int fillPointer = (fillPointerArg instanceof LispInteger n) ? (int) n.value() : total;
		if (fillPointer < 0 || fillPointer > total) {
			throw new LispEvalException(LispNames.MAKE_ARRAY + ": :fill-pointer out of range");
		}
		return fillPointer;
	}

	private static void registerArrays(Environment env) {
		env.defineFunction(LispNames.MAKE_ARRAY, makeArrayBuiltin(null));
		env.defineFunction(LispNames.AREF, new LispFunction(LispNames.AREF, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.AREF + " expects an array and subscripts");
			}
			int[] subs = new int[args.size() - 1];
			for (int i = 1; i < args.size(); i++) {
				subs[i - 1] = (int) asLong(args.get(i));
			}
			if (args.get(0) instanceof LispFloatArray fa) {
				return fa.aref(subs);
			}
			if (args.get(0) instanceof LispQuantizedMatrix qm) {
				// Dequantize-on-read: q * scale, a double (.kb/quantized-matrix.md).
				return new LispDouble(qm.aref(subs));
			}
			if (args.get(0) instanceof LispIntVector iv) {
				if (subs.length != 1) {
					throw new LispEvalException(LispNames.AREF + ": a packed integer vector is rank 1");
				}
				return new LispInteger(intVectorRead(LispNames.AREF, iv, subs[0]));
			}
			// A string is a rank-1 array of characters in CL, so (aref s i) reads like
			// (char s i). Writing still goes through %schar-set (the schar setf place).
			if (args.get(0) instanceof LispString && subs.length == 1) {
				return charRef(LispNames.AREF, args);
			}
			LispArray array = requireArray(LispNames.AREF, args.get(0));
			return array.aref(subs);
		}));
		env.defineFunction(LispNames.ARRAY_DIMENSIONS, new LispFunction(LispNames.ARRAY_DIMENSIONS, args -> {
			requireArgCount(LispNames.ARRAY_DIMENSIONS, args, 1);
			// A string is a rank-1 character array; its dimension is the capacity (the
			// fill pointer only limits the effective length).
			int[] sizes = (args.get(0) instanceof LispString str) ? new int[] { str.capacity() }
					: (args.get(0) instanceof LispFloatArray fa) ? fa.dims()
							: (args.get(0) instanceof LispQuantizedMatrix qm) ? qm.dims()
									: (args.get(0) instanceof LispIntVector iv) ? new int[] { iv.length() }
											: requireArray(LispNames.ARRAY_DIMENSIONS, args.get(0)).dimensions();
			LispVal dims = LispNil.INSTANCE;
			for (int i = sizes.length - 1; i >= 0; i--) {
				dims = new LispCons(new LispInteger(sizes[i]), dims);
			}
			return dims;
		}));
		env.defineFunction(LispNames.ASET, new LispFunction(LispNames.ASET, args -> {
			// (%aset array subscript... value); a rank-0 array takes no subscripts, so
			// two arguments is the shortest legal call.
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.ASET + " expects an array, subscripts and a value");
			}
			LispVal value = args.get(args.size() - 1);
			int[] subs = new int[args.size() - 2];
			for (int i = 1; i < args.size() - 1; i++) {
				subs[i - 1] = (int) asLong(args.get(i));
			}
			if (args.get(0) instanceof LispFloatArray fa) {
				// Coerce to a double (a non-real is a type error), narrow-store it (f32
				// for
				// single-float), and return the value AS STORED (read back widened), so
				// the
				// effective element value is consistent across backends and widths.
				fa.aset(asDouble(value), subs);
				return fa.aref(subs);
			}
			if (args.get(0) instanceof LispQuantizedMatrix) {
				throw new LispEvalException(quantizedImmutable(LispNames.ASET));
			}
			if (args.get(0) instanceof LispIntVector iv) {
				// Mask-store to the element width and return the value AS STORED, so the
				// effective element value is consistent across backends and widths.
				if (subs.length != 1) {
					throw new LispEvalException(LispNames.ASET + ": a packed integer vector is rank 1");
				}
				intVectorRead(LispNames.ASET, iv, subs[0]);
				return new LispInteger(iv.setElement(subs[0], exactIntElement(LispNames.ASET, value)));
			}
			// A string is a rank-1 character array: (setf (aref s i) c) mutates in
			// place like the schar setf place (cl-ppcre builds two-char strings with
			// make-array + aset).
			if (args.get(0) instanceof LispString str && subs.length == 1) {
				return storeStringChar(LispNames.ASET, str, subs[0], value);
			}
			LispArray array = requireArray(LispNames.ASET, args.get(0));
			array.aset(value, subs);
			return value;
		}));
		env.defineFunction(LispNames.ROW_MAJOR_AREF, new LispFunction(LispNames.ROW_MAJOR_AREF, args -> {
			requireArgCount(LispNames.ROW_MAJOR_AREF, args, 2);
			if (args.get(0) instanceof LispFloatArray fa) {
				return fa.readFlat(rowMajorIndex(LispNames.ROW_MAJOR_AREF, fa.totalSize(), args.get(1)));
			}
			if (args.get(0) instanceof LispQuantizedMatrix qm) {
				return new LispDouble(
						qm.elementAt(rowMajorIndex(LispNames.ROW_MAJOR_AREF, qm.totalSize(), args.get(1))));
			}
			if (args.get(0) instanceof LispIntVector iv) {
				return new LispInteger(intVectorRead(LispNames.ROW_MAJOR_AREF, iv, (int) asLong(args.get(1))));
			}
			// A string is a rank-1 array of characters in CL, so (row-major-aref s i)
			// reads like (char s i) -- the same arm AREF has above.
			if (args.get(0) instanceof LispString) {
				return charRef(LispNames.ROW_MAJOR_AREF, args);
			}
			LispArray array = requireArray(LispNames.ROW_MAJOR_AREF, args.get(0));
			return array.readFlat(rowMajorIndex(LispNames.ROW_MAJOR_AREF, array.totalSize(), args.get(1)));
		}));
		env.defineFunction(LispNames.ROW_MAJOR_ASET, new LispFunction(LispNames.ROW_MAJOR_ASET, args -> {
			// (%row-major-aset array index value)
			requireArgCount(LispNames.ROW_MAJOR_ASET, args, 3);
			LispVal value = args.get(2);
			if (args.get(0) instanceof LispFloatArray fa) {
				int flat = rowMajorIndex(LispNames.ROW_MAJOR_ASET, fa.totalSize(), args.get(1));
				// Narrow-store (f32 for single-float) then read back widened, so the
				// returned value matches what is stored across widths and backends.
				fa.setElement(flat, asDouble(value));
				return fa.readFlat(flat);
			}
			if (args.get(0) instanceof LispQuantizedMatrix) {
				throw new LispEvalException(quantizedImmutable(LispNames.ROW_MAJOR_ASET));
			}
			if (args.get(0) instanceof LispIntVector iv) {
				int flat = (int) asLong(args.get(1));
				intVectorRead(LispNames.ROW_MAJOR_ASET, iv, flat);
				return new LispInteger(iv.setElement(flat, exactIntElement(LispNames.ROW_MAJOR_ASET, value)));
			}
			if (args.get(0) instanceof LispString str) {
				return storeStringChar(LispNames.ROW_MAJOR_ASET, str, (int) asLong(args.get(1)), value);
			}
			LispArray array = requireArray(LispNames.ROW_MAJOR_ASET, args.get(0));
			array.writeFlat(rowMajorIndex(LispNames.ROW_MAJOR_ASET, array.totalSize(), args.get(1)), value);
			return value;
		}));
		env.defineFunction(LispNames.FILL_POINTER, new LispFunction(LispNames.FILL_POINTER, args -> {
			requireArgCount(LispNames.FILL_POINTER, args, 1);
			if (args.get(0) instanceof LispString str) {
				if (str.fillPointer() < 0) {
					throw new LispEvalException(LispNames.FILL_POINTER + ": string has no fill pointer");
				}
				return new LispInteger(str.fillPointer());
			}
			LispArray array = requireGeneralArray(LispNames.FILL_POINTER, args.get(0));
			if (!array.hasFillPointer()) {
				throw new LispEvalException(LispNames.FILL_POINTER + ": array has no fill pointer");
			}
			return new LispInteger(array.fillPointer());
		}));
		env.defineFunction(LispNames.SET_FILL_POINTER, new LispFunction(LispNames.SET_FILL_POINTER, args -> {
			requireArgCount(LispNames.SET_FILL_POINTER, args, 2);
			if (args.get(0) instanceof LispString str) {
				if (str.fillPointer() < 0) {
					throw new LispEvalException(LispNames.SET_FILL_POINTER + ": string has no fill pointer");
				}
				try {
					str.setFillPointer((int) asLong(args.get(1)));
				}
				catch (IllegalArgumentException ex) {
					throw new LispEvalException(LispNames.SET_FILL_POINTER + ": " + ex.getMessage());
				}
				return args.get(1);
			}
			LispArray array = requireGeneralArray(LispNames.SET_FILL_POINTER, args.get(0));
			if (!array.hasFillPointer()) {
				throw new LispEvalException(LispNames.SET_FILL_POINTER + ": array has no fill pointer");
			}
			int value = (int) asLong(args.get(1));
			try {
				array.setFillPointer(value);
			}
			catch (IndexOutOfBoundsException ex) {
				throw new LispEvalException(LispNames.SET_FILL_POINTER + ": " + ex.getMessage());
			}
			return args.get(1);
		}));
		env.defineFunction(LispNames.ARRAY_HAS_FILL_POINTER_P,
				new LispFunction(LispNames.ARRAY_HAS_FILL_POINTER_P, args -> {
					requireArgCount(LispNames.ARRAY_HAS_FILL_POINTER_P, args, 1);
					if (args.get(0) instanceof LispFloatArray || args.get(0) instanceof LispIntVector) {
						return LispNil.INSTANCE;
					}
					if (args.get(0) instanceof LispString str) {
						return str.fillPointer() >= 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
					}
					LispArray array = requireArray(LispNames.ARRAY_HAS_FILL_POINTER_P, args.get(0));
					return array.hasFillPointer() ? LispTrue.INSTANCE : LispNil.INSTANCE;
				}));
		env.defineFunction(LispNames.ARRAY_ELEMENT_TYPE, new LispFunction(LispNames.ARRAY_ELEMENT_TYPE, args -> {
			requireArgCount(LispNames.ARRAY_ELEMENT_TYPE, args, 1);
			if (args.get(0) instanceof LispFloatArray fa) {
				return new LispSymbol(fa.elementType());
			}
			if (args.get(0) instanceof LispQuantizedMatrix qm) {
				// The format symbol (q8-0): what vec.lisp's GEMV dispatches on, and the
				// answer no packed float array can give.
				return new LispSymbol(qm.format().lispName());
			}
			if (args.get(0) instanceof LispIntVector iv) {
				return iv.elementTypeSpec();
			}
			// A string is a vector of characters, so its element type is the one
			// character type (the same answer vectorp/length/aref give for a string);
			// without this arm requireArray below would reject the string.
			if (args.get(0) instanceof LispString) {
				return new LispSymbol(LispNames.CHARACTER_TYPE);
			}
			LispArray general = requireArray(LispNames.ARRAY_ELEMENT_TYPE, args.get(0));
			// The type the array REMEMBERS being asked for, which is the BOOLEAN t for
			// almost every general array -- not a symbol spelled "T": in CL the two are
			// one object, the compile backends answer the boolean, and a caller writes
			// (eq (array-element-type a) t). type-of reads this answer to decide
			// between (simple-vector n) and (simple-array et dims).
			return ArrayElementTypes.valueOf(general.elementTypeCode());
		}));
		env.defineFunction(LispNames.ADJUSTABLE_ARRAY_P, new LispFunction(LispNames.ADJUSTABLE_ARRAY_P, args -> {
			requireArgCount(LispNames.ADJUSTABLE_ARRAY_P, args, 1);
			if (args.get(0) instanceof LispFloatArray || args.get(0) instanceof LispIntVector) {
				return LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispString str) {
				return str.adjustable() ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			LispArray array = requireArray(LispNames.ADJUSTABLE_ARRAY_P, args.get(0));
			return array.adjustable() ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.VECTOR_PUSH, new LispFunction(LispNames.VECTOR_PUSH, args -> {
			requireArgCount(LispNames.VECTOR_PUSH, args, 2);
			if (args.get(1) instanceof LispString str) {
				if (str.fillPointer() < 0) {
					throw new LispEvalException(LispNames.VECTOR_PUSH + ": string has no fill pointer");
				}
				if (str.fillPointer() >= str.capacity()) {
					return LispNil.INSTANCE;
				}
				return new LispInteger(str.vectorPushExtend(requireChar(LispNames.VECTOR_PUSH, args.get(0)).codePoint(),
						ArrayGrowth.NO_EXTENSION));
			}
			LispArray array = requireGeneralArray(LispNames.VECTOR_PUSH, args.get(1));
			int index = vectorPush(LispNames.VECTOR_PUSH, array, args.get(0));
			return index < 0 ? LispNil.INSTANCE : new LispInteger(index);
		}));
		env.defineFunction(LispNames.VECTOR_POP, new LispFunction(LispNames.VECTOR_POP, args -> {
			requireArgCount(LispNames.VECTOR_POP, args, 1);
			if (args.get(0) instanceof LispString str) {
				if (str.fillPointer() <= 0) {
					throw new LispEvalException(LispNames.VECTOR_POP + ": string is empty or has no fill pointer");
				}
				str.setFillPointer(str.fillPointer() - 1);
				return new LispChar(str.charAt(str.fillPointer()));
			}
			LispArray array = requireGeneralArray(LispNames.VECTOR_POP, args.get(0));
			try {
				return array.vectorPop();
			}
			catch (IllegalStateException ex) {
				throw new LispEvalException(String.valueOf(ex.getMessage()));
			}
		}));
		env.defineFunction(LispNames.VECTOR_PUSH_EXTEND, new LispFunction(LispNames.VECTOR_PUSH_EXTEND, args -> {
			if (args.size() < 2 || args.size() > 3) {
				throw new LispEvalException(LispNames.VECTOR_PUSH_EXTEND + " expects 2 or 3 arguments");
			}
			int extension = args.size() == 3 ? (int) asLong(args.get(2)) : ArrayGrowth.NO_EXTENSION;
			if (args.get(1) instanceof LispString str) {
				if (str.fillPointer() < 0) {
					throw new LispEvalException(LispNames.VECTOR_PUSH_EXTEND + ": string has no fill pointer");
				}
				return new LispInteger(str
					.vectorPushExtend(requireChar(LispNames.VECTOR_PUSH_EXTEND, args.get(0)).codePoint(), extension));
			}
			LispArray array = requireGeneralArray(LispNames.VECTOR_PUSH_EXTEND, args.get(1));
			try {
				return new LispInteger(array.vectorPushExtend(args.get(0), extension));
			}
			catch (IllegalStateException ex) {
				throw new LispEvalException(String.valueOf(ex.getMessage()));
			}
		}));
		env.defineFunction(LispNames.ADJUST_ARRAY, new LispFunction(LispNames.ADJUST_ARRAY, args -> {
			if (args.size() < 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ADJUST_ARRAY + " expects an array and new dimensions");
			}
			// The slots the adjustment OPENS take the value's own element type zero, the
			// same fill make-array gives an unsupplied element, unless an explicit
			// :initial-element says otherwise.
			LispVal init = arrayDefaultElement(args.get(0));
			boolean initGiven = false;
			LispVal initialContents = null;
			LispVal fillPointerArg = null;
			LispVal displacedToArg = null;
			LispVal displacedOffsetArg = null;
			for (int i = 2; i < args.size(); i += 2) {
				if (i + 1 >= args.size()) {
					throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
							LispNames.ADJUST_ARRAY + ": odd keyword arguments");
				}
				if (!(args.get(i) instanceof LispSymbol kw)) {
					throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
							LispNames.ADJUST_ARRAY + ": malformed keyword arguments");
				}
				LispVal value = args.get(i + 1);
				switch (kw.name()) {
					case LispNames.ELEMENT_TYPE_KEYWORD -> {
						// Accepted and ignored: adjust-array never changes the element
						// type
						// (and CLHS only requires it to be type-equivalent when
						// supplied).
					}
					case LispNames.INITIAL_ELEMENT_KEYWORD -> {
						init = value;
						initGiven = true;
					}
					case LispNames.INITIAL_CONTENTS_KEYWORD -> initialContents = value;
					case LispNames.FILL_POINTER_KEYWORD -> fillPointerArg = value;
					case LispNames.DISPLACED_TO_KEYWORD -> displacedToArg = value;
					case LispNames.DISPLACED_INDEX_OFFSET_KEYWORD -> displacedOffsetArg = value;
					default -> throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
							LispNames.ADJUST_ARRAY + ": unknown keyword " + kw.name());
				}
			}
			if (initGiven && initialContents != null) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ADJUST_ARRAY + ": :initial-element and :initial-contents cannot both be given");
			}
			int[] dims = parseDimensions(args.get(1));
			if (displacedToArg != null && !(displacedToArg instanceof LispNil)) {
				// CLHS forbids :initial-element / :initial-contents beside :displaced-to:
				// the view has no storage of its own to initialize.
				if (initGiven || initialContents != null) {
					throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME, LispNames.ADJUST_ARRAY
							+ ": :displaced-to cannot be combined with :initial-element/:initial-contents");
				}
				int offset = displacedOffsetArg == null ? 0 : (int) asLong(displacedOffsetArg);
				int fp = adjustFillPointer(fillPointerArg, dims, valueHasFillPointer(args.get(0)),
						valueFillPointer(args.get(0)));
				return adjustArrayDisplaced(args.get(0), dims, offset, fp, displacedToArg);
			}
			if (displacedOffsetArg != null && !(displacedOffsetArg instanceof LispNil)) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ADJUST_ARRAY + ": :displaced-index-offset requires :displaced-to");
			}
			int fp = adjustFillPointer(fillPointerArg, dims, valueHasFillPointer(args.get(0)),
					valueFillPointer(args.get(0)));
			if (args.get(0) instanceof LispString str) {
				return adjustString(str, dims, init, initGiven, initialContents, fp);
			}
			LispArray array = requireGeneralArray(LispNames.ADJUST_ARRAY, args.get(0));
			return adjustArray(array, dims, init, initGiven, initialContents, fp);
		}));
		env.defineFunction(LispNames.ARRAY_ALIKE, new LispFunction(LispNames.ARRAY_ALIKE, args -> {
			requireArgCount(LispNames.ARRAY_ALIKE, args, 2);
			int n = (int) asLong(args.get(1));
			if (args.get(0) instanceof LispIntVector iv) {
				return new LispIntVector(iv.width(), new long[n]);
			}
			if (args.get(0) instanceof LispFloatArray fa) {
				return zeroPackedFloatLike(fa, n);
			}
			if (args.get(0) instanceof LispArray arr) {
				// A fill-pointer / adjustable packed vector: no
				// LispIntVector/LispFloatArray
				// instance of its own to match above, only the elementTypeCode it
				// remembers -- same representation gap subseq's general-array arm closes
				// (.todo/698).
				int width = packedIntWidthForElementTypeCode(arr.elementTypeCode());
				if (width > 0) {
					return new LispIntVector(width, new long[n]);
				}
				LispFloatArray proto = floatPrototypeForElementTypeCode(arr.elementTypeCode());
				if (proto != null) {
					return zeroPackedFloatLike(proto, n);
				}
			}
			// A bit vector IS the general boxed array stamped bit (no packed bits
			// anywhere): a copy keeps the stamp, zero-filled with 0 like a made one
			// (.todo/820).
			if (args.get(0) instanceof LispArray srcArr
					&& srcArr.elementTypeCode() == am.ik.rontolisp.ArrayElementTypes.BIT) {
				LispVal[] bitData = new LispVal[n];
				java.util.Arrays.fill(bitData, new LispInteger(0));
				return new LispArray(new int[] { n }, bitData, -1, false, am.ik.rontolisp.ArrayElementTypes.BIT);
			}
			LispVal[] data = new LispVal[n];
			java.util.Arrays.fill(data, LispNil.INSTANCE);
			return new LispArray(new int[] { n }, data);
		}));
		env.defineFunction(LispNames.ARRAY_DEFAULT_ELEMENT, new LispFunction(LispNames.ARRAY_DEFAULT_ELEMENT, args -> {
			requireArgCount(LispNames.ARRAY_DEFAULT_ELEMENT, args, 1);
			return arrayDefaultElement(args.get(0));
		}));
		env.defineFunction(LispNames.ARRAY_ADOPT_ELEMENT_TYPE,
				new LispFunction(LispNames.ARRAY_ADOPT_ELEMENT_TYPE, args -> {
					requireArgCount(LispNames.ARRAY_ADOPT_ELEMENT_TYPE, args, 2);
					if (args.get(0) instanceof LispArray fresh) {
						fresh.adoptElementType(arrayElementTypeCode(args.get(1)));
					}
					return args.get(0);
				}));
		env.defineFunction(LispNames.ARRAY_BECOME, new LispFunction(LispNames.ARRAY_BECOME, args -> {
			requireArgCount(LispNames.ARRAY_BECOME, args, 2);
			LispArray old = requireGeneralArray(LispNames.ARRAY_BECOME, args.get(0));
			old.become(requireGeneralArray(LispNames.ARRAY_BECOME, args.get(1)));
			return old;
		}));
		LispFunction dispTarget = new LispFunction(LispNames.ARRAY_DISPLACEMENT, args -> {
			requireArgCount(LispNames.ARRAY_DISPLACEMENT, args, 1);
			if (args.get(0) instanceof LispFloatArray || args.get(0) instanceof LispIntVector) {
				return LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispString str) {
				return str.displacedTo() == null ? LispNil.INSTANCE : str.displacedTo();
			}
			LispArray array = requireArray(LispNames.ARRAY_DISPLACEMENT, args.get(0));
			return array.displacedTo() == null ? LispNil.INSTANCE : array.displacedTo();
		});
		// array-displacement's primary value; the offset is the second value, read
		// through %array-disp-offset by the multiple-value consumers (syntactic tier).
		env.defineFunction(LispNames.ARRAY_DISPLACEMENT, dispTarget);
		env.defineFunction(LispNames.ARRAY_DISP_TARGET, dispTarget);
		env.defineFunction(LispNames.ARRAY_DISP_OFFSET, new LispFunction(LispNames.ARRAY_DISP_OFFSET, args -> {
			requireArgCount(LispNames.ARRAY_DISP_OFFSET, args, 1);
			if (args.get(0) instanceof LispFloatArray || args.get(0) instanceof LispIntVector) {
				return new LispInteger(0);
			}
			if (args.get(0) instanceof LispString str) {
				return new LispInteger(str.displacedOffset());
			}
			return new LispInteger(requireArray(LispNames.ARRAY_DISP_OFFSET, args.get(0)).displacedOffset());
		}));
	}

	// The element an UNSUPPLIED slot of this value takes: its element type's own zero.
	// One answer for every representation, so a slot opened AFTER allocation -- by
	// adjust-array's growth or by vector-push-extend's -- reads back as the same thing
	// make-array's unsupplied element does (%array-default-element; ArrayElementTypes).
	private static LispVal arrayDefaultElement(LispVal value) {
		LispVal zero = ArrayElementTypes.defaultElement(arrayElementTypeCode(value));
		return zero == null ? LispNil.INSTANCE : zero;
	}

	// The UPGRADED element type this value remembers, as an ArrayElementTypes code: one
	// answer per representation, and ArrayElementTypes.T for anything that is not an
	// array at all. Both %array-default-element (the zero) and
	// %array-adopt-element-type (the stamp a fresh adjust-array copy takes) ask it.
	private static int arrayElementTypeCode(LispVal value) {
		return switch (value) {
			case LispString ignored -> ArrayElementTypes.CHARACTER;
			case LispIntVector iv -> switch (iv.width()) {
				case 16 -> ArrayElementTypes.UNSIGNED_BYTE_16;
				case 32 -> ArrayElementTypes.UNSIGNED_BYTE_32;
				default -> ArrayElementTypes.UNSIGNED_BYTE_8;
			};
			// Exhaustive over the packed-float widths, so a new one is a compile error
			// here rather than a silent fall through to the general `t`.
			case LispFloatArray packed -> switch (packed) {
				case LispSingleFloatArray ignored -> ArrayElementTypes.SINGLE_FLOAT;
				case LispDoubleFloatArray ignored -> ArrayElementTypes.DOUBLE_FLOAT;
				case LispBFloat16Array ignored -> ArrayElementTypes.BFLOAT16;
			};
			case LispArray array -> array.elementTypeCode();
			default -> ArrayElementTypes.T;
		};
	}

	// The shared adjust-array core: build the resized copy (preserving the elements at
	// common subscripts, or overwriting them wholesale from :initial-contents), then
	// either adjust the array in place (:adjustable, returning it) or return the fresh
	// copy. Matches LispMacroExpander.expandAdjustArray on the compile path.
	private static LispVal adjustArray(LispArray array, int[] newDims, LispVal init, boolean initGiven,
			@Nullable LispVal initialContents, int fillPointer) {
		// A displaced argument un-displaces first (SBCL 2.2.9): its current view
		// contents become its own storage and the displacement drops, in place, before
		// the rest of the adjustment runs -- matches the compile path's expansion,
		// which calls %array-undisplace unconditionally too, and is what lets the
		// :adjustable branch's become() below (which replaces dims/data/fillPointer but
		// not displacedTo/Offset) leave the adjusted array in a consistent state.
		array.undisplace();
		int[] oldDims = array.dimensions();
		if (newDims.length != oldDims.length) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					LispNames.ADJUST_ARRAY + ": rank mismatch");
		}
		int total = 1;
		for (int d : newDims) {
			total *= d;
		}
		LispVal[] data = new LispVal[total];
		for (int i = 0; i < total; i++) {
			data[i] = init;
		}
		// :initial-contents fills the WHOLE result (like make-array), so the overlap copy
		// below is skipped; otherwise the elements at common subscripts are retained.
		if (initialContents != null) {
			fillInitialContents(initialContents, newDims, 0, data, 0);
		}
		else {
			// Copy the elements at the subscripts valid in BOTH shapes (per-subscript,
			// not flat: resizing a matrix keeps (i, j) at (i, j)).
			int[] subs = new int[newDims.length];
			for (int flat = 0; flat < total; flat++) {
				int rem = flat;
				boolean inOld = true;
				int oldFlat = 0;
				for (int k = newDims.length - 1; k >= 0; k--) {
					subs[k] = rem % newDims[k];
					rem /= newDims[k];
				}
				for (int k = 0; k < newDims.length; k++) {
					if (subs[k] >= oldDims[k]) {
						inOld = false;
						break;
					}
					oldFlat = oldFlat * oldDims[k] + subs[k];
				}
				if (inOld) {
					data[flat] = array.readFlat(oldFlat);
				}
			}
		}
		LispArray resized = new LispArray(newDims, data, fillPointer, array.adjustable(), array.elementTypeCode());
		if (array.adjustable()) {
			array.become(resized);
			return array;
		}
		return resized;
	}

	// The adjust-array :fill-pointer rule. An explicit :fill-pointer (nil is treated as
	// "carry the old one over", the behaviour the ANSI fp tests expect) wins; otherwise
	// the adjusted array inherits the original's fill pointer, range-checked against the
	// new dimension so shrinking below it errors. A rank-1 array is the only shape a
	// fill pointer is legal on.
	private static int adjustFillPointer(@Nullable LispVal fillPointerArg, int[] dims, boolean hasFillPointer,
			int existingFillPointer) {
		if (fillPointerArg != null && !(fillPointerArg instanceof LispNil)) {
			if (dims.length != 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ADJUST_ARRAY + ": :fill-pointer requires a rank-1 array");
			}
			int fp = fillPointerArg instanceof LispInteger n ? (int) n.value() : dims[0];
			if (fp < 0 || fp > dims[0]) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ADJUST_ARRAY + ": :fill-pointer out of range");
			}
			return fp;
		}
		if (hasFillPointer) {
			if (dims.length != 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ADJUST_ARRAY + ": :fill-pointer requires a rank-1 array");
			}
			if (existingFillPointer > dims[0]) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ADJUST_ARRAY + ": :fill-pointer out of range");
			}
			return existingFillPointer;
		}
		return -1;
	}

	// The :displaced-to half of adjust-array: build the displaced VIEW of `target`, or
	// turn the source (when :adjustable) into one in place. The target decides the shape
	// -- a string target is a string view -- exactly as make-array does.
	private static LispVal adjustArrayDisplaced(LispVal arrayVal, int[] dims, int offset, int fillPointer,
			LispVal target) {
		int total = 1;
		for (int d : dims) {
			total *= d;
		}
		if (target instanceof LispString targetString) {
			if (dims.length != 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ADJUST_ARRAY + ": a string :displaced-to target needs a rank-1 view");
			}
			if (offset < 0 || total + offset > targetString.capacity()) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ADJUST_ARRAY + ": :displaced-to string is too small for the requested view");
			}
			if (arrayVal instanceof LispString str && str.adjustable()) {
				str.becomeDisplaced(targetString, offset, total, fillPointer);
				return str;
			}
			return new LispString(targetString, offset, total, fillPointer,
					arrayVal instanceof LispString s && s.adjustable());
		}
		int targetTotal = switch (target) {
			case LispIntVector iv -> iv.length();
			case LispFloatArray fa -> fa.totalSize();
			default -> requireArray(LispNames.ADJUST_ARRAY, target).totalSize();
		};
		if (offset < 0 || total + offset > targetTotal) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					LispNames.ADJUST_ARRAY + ": :displaced-to array is too small for the requested view");
		}
		if (arrayVal instanceof LispArray array && array.adjustable()) {
			array.becomeDisplaced(dims, target, offset, fillPointer);
			return array;
		}
		return new LispArray(dims, target, offset, fillPointer, arrayVal instanceof LispArray a && a.adjustable());
	}

	// The string half of adjust-array: build (or, for an adjustable string, resize in
	// place) the character array, filling it from :initial-contents or retaining the
	// original's characters at the overlapping positions.
	private static LispVal adjustString(LispString str, int[] dims, LispVal init, boolean initGiven,
			@Nullable LispVal initialContents, int fillPointer) {
		int newCap = dims[0];
		int fillChar = initGiven ? requireChar(LispNames.ADJUST_ARRAY, init).codePoint()
				: ArrayElementTypes.DEFAULT_CHARACTER;
		int[] cp = new int[newCap];
		java.util.Arrays.fill(cp, fillChar);
		if (initialContents != null) {
			LispVal[] tmp = new LispVal[newCap];
			fillInitialContents(initialContents, new int[] { newCap }, 0, tmp, 0);
			for (int i = 0; i < newCap; i++) {
				cp[i] = requireChar(LispNames.ADJUST_ARRAY, tmp[i]).codePoint();
			}
		}
		else {
			int oldCap = str.capacity();
			for (int i = 0; i < newCap && i < oldCap; i++) {
				cp[i] = str.charAt(i);
			}
		}
		if (str.adjustable()) {
			str.adoptContent(cp, fillPointer);
			return str;
		}
		return new LispString(new String(cp, 0, cp.length), fillPointer, false);
	}

	private static boolean valueHasFillPointer(LispVal value) {
		return switch (value) {
			case LispString s -> s.fillPointer() >= 0;
			case LispArray a -> a.hasFillPointer();
			default -> false;
		};
	}

	private static int valueFillPointer(LispVal value) {
		return switch (value) {
			case LispString s -> s.fillPointer();
			case LispArray a -> a.fillPointer();
			default -> -1;
		};
	}

	private static int vectorPush(String fn, LispArray array, LispVal value) {
		try {
			return array.vectorPush(value);
		}
		catch (IllegalStateException ex) {
			throw new LispEvalException(String.valueOf(ex.getMessage()));
		}
	}

	private static int rowMajorIndex(String fn, int totalSize, LispVal indexVal) {
		int index = (int) asLong(indexVal);
		if (index < 0 || index >= totalSize) {
			throw LispEvalException.ofClass(ClosRegistry.TYPE_ERROR_CLASS_NAME, fn + ": index out of bounds");
		}
		return index;
	}

	// Parses a make-array dimensions argument (an integer for rank 1, or a list of
	// integers) into a dimension-size array. Any rank >= 0 is supported: the EMPTY list
	// -- (make-array nil) / (make-array '()) -- is the rank-0 array, one element reached
	// with no subscripts.
	private static int[] parseDimensions(LispVal dimsVal) {
		if (dimsVal instanceof LispInteger n) {
			return new int[] { (int) n.value() };
		}
		if (dimsVal instanceof LispCons || dimsVal instanceof LispNil) {
			List<LispVal> list = (dimsVal instanceof LispCons cons) ? cons.toList() : List.of();
			int[] dims = new int[list.size()];
			for (int i = 0; i < list.size(); i++) {
				dims[i] = (int) asLong(list.get(i));
			}
			return dims;
		}
		throw new LispEvalException(
				LispNames.MAKE_ARRAY + " expects an integer or list of dimensions, got " + dimsVal.print());
	}

	private static LispArray requireArray(String fn, LispVal val) {
		if (val instanceof LispArray array) {
			return array;
		}
		throw new LispEvalException(fn + " expects an array, got " + val.print());
	}

	// The one refusal a quantized matrix gives: an element has no slot of its own (a
	// value is q * scale over a 32-element block), so there is nothing to store into.
	private static String quantizedImmutable(String fn) {
		return fn + ": a quantized matrix is immutable (dequantize it into a packed float array to change it)";
	}

	// Requires a general (boxed) array. A packed double-float array is rejected with a
	// clear message: fill pointers, adjustability and displacement are
	// general-array-only,
	// so those operations never apply to a packed array (it is always a simple array).
	private static LispArray requireGeneralArray(String fn, LispVal val) {
		if (val instanceof LispFloatArray) {
			throw new LispEvalException(fn + ": not applicable to a packed float array");
		}
		if (val instanceof LispIntVector) {
			throw new LispEvalException(fn + ": not applicable to a packed integer vector");
		}
		return requireArray(fn, val);
	}

	// A bounds-checked packed integer-vector element read (the compiled backends trap on
	// the same condition).
	private static long intVectorRead(String fn, LispIntVector iv, int index) {
		if (index < 0 || index >= iv.length()) {
			throw new LispEvalException(
					fn + ": index " + index + " out of range for packed integer vector of length " + iv.length());
		}
		return iv.elementAt(index);
	}

	// The packed integer-vector element width a make-array :element-type argument
	// designates: 8/16/32 for the literal list (unsigned-byte 8|16|32), else 0. The
	// symbol name is matched ignoring any package qualifier, like the float widths.
	/**
	 * Builds a packed unsigned-integer vector from already-collected elements: the shared
	 * tail of {@code concatenate}'s packed vector family and of {@code %seq-int-vector}.
	 * Each element must be an integer (the same type error {@code make-array
	 * :initial-contents} signals) and is masked to the width by {@link LispIntVector}.
	 */
	private static LispVal packedIntVector(String fn, int width, List<LispVal> elements) {
		long[] data = new long[elements.size()];
		for (int i = 0; i < data.length; i++) {
			data[i] = exactIntElement(fn, elements.get(i));
		}
		return new LispIntVector(width, data);
	}

	/**
	 * Builds a packed FLOAT array from already-collected elements: the shared tail of
	 * {@code concatenate}'s packed vector family and of {@code %seq-float-vector}, the
	 * float twin of {@link #packedIntVector}. Each element coerces to a {@code double}
	 * and is narrowed to the width by the array's own storage (a non-real is the same
	 * type error {@code make-array :initial-contents} signals).
	 *
	 * <p>
	 * The switch is over the PERMITS with no default arm, like the allocation in
	 * {@code makeArrayBuiltin}: the width -&gt; allocation direction is a compile error
	 * for the next permit rather than a silent fall into whichever arm came last
	 * ({@code .kb/vec.md}).
	 * @param fn the operator name, for the type error's message
	 * @param proto the zero-length prototype naming the width
	 * @param elements the elements, in order
	 * @return the packed float array
	 */
	private static LispVal packedFloatVector(String fn, LispFloatArray proto, List<LispVal> elements) {
		int n = elements.size();
		int[] dims = { n };
		switch (proto) {
			case LispBFloat16Array ignored -> {
				short[] data = new short[n];
				for (int i = 0; i < n; i++) {
					data[i] = (short) BFloat16.bits(realElement(fn, elements.get(i)));
				}
				return new LispBFloat16Array(data, dims);
			}
			case LispSingleFloatArray ignored -> {
				float[] data = new float[n];
				for (int i = 0; i < n; i++) {
					data[i] = (float) realElement(fn, elements.get(i));
				}
				return new LispSingleFloatArray(data, dims);
			}
			case LispDoubleFloatArray ignored -> {
				double[] data = new double[n];
				for (int i = 0; i < n; i++) {
					data[i] = realElement(fn, elements.get(i));
				}
				return new LispDoubleFloatArray(data, dims);
			}
		}
	}

	// A packed float element: any real, widened to a double. Anything else (a character,
	// a string, nil, ...) is a type error -- there is no degrade path, matching the
	// packed integer vectors.
	private static double realElement(String fn, @Nullable LispVal val) {
		if (val instanceof LispInteger || val instanceof LispBigInteger || val instanceof LispDouble
				|| val instanceof LispRatio) {
			return asDouble(val);
		}
		throw new LispEvalException(
				fn + ": a packed float array stores reals, got " + (val == null ? "nil" : val.print()));
	}

	/**
	 * Builds a string from already-collected elements: the shared tail of
	 * {@code concatenate}'s {@code (vector character)} arm and of {@code %seq-string}.
	 * Each element must be a character -- the same type error {@code make-array
	 * :initial-contents} signals for a {@code :element-type 'character} array.
	 */
	private static LispVal charVector(String fn, List<LispVal> elements) {
		StringBuilder sb = new StringBuilder();
		for (LispVal element : elements) {
			sb.appendCodePoint(requireChar(fn, element).codePoint());
		}
		return new LispString(sb.toString());
	}

	private static int packedIntElementWidth(@Nullable LispVal elementType) {
		return LispNames.unsignedByteWidth(elementType);
	}

	// A zero-length prototype of the packed float width `elementTypeCode` names, or null
	// when the code is not one of the float widths. The shared name -> width lookup for a
	// general LispArray that only REMEMBERS an elementTypeCode (a fill-pointer /
	// adjustable
	// packed float vector), which has no LispFloatArray instance of its own to read the
	// width from the way a simple packed array does.
	private static @Nullable LispFloatArray floatPrototypeForElementTypeCode(int elementTypeCode) {
		return switch (elementTypeCode) {
			case ArrayElementTypes.SINGLE_FLOAT -> new LispSingleFloatArray(new float[0], new int[] { 0 });
			case ArrayElementTypes.DOUBLE_FLOAT -> new LispDoubleFloatArray(new double[0], new int[] { 0 });
			case ArrayElementTypes.BFLOAT16 -> new LispBFloat16Array(new short[0], new int[] { 0 });
			default -> null;
		};
	}

	// A fresh zero-filled rank-1 packed float array of length n, at proto's width -- the
	// float twin of `new LispIntVector(width, new long[n])`, shared by %array-alike's
	// LispFloatArray and packed-general-LispArray arms.
	private static LispFloatArray zeroPackedFloatLike(LispFloatArray proto, int n) {
		int[] dims = { n };
		return switch (proto) {
			case LispBFloat16Array ignored -> new LispBFloat16Array(new short[n], dims);
			case LispSingleFloatArray ignored -> new LispSingleFloatArray(new float[n], dims);
			case LispDoubleFloatArray ignored -> new LispDoubleFloatArray(new double[n], dims);
		};
	}

	// The packed integer width `elementTypeCode` names (8/16/32), or 0 when the code is
	// not one of the packed integer widths -- the ArrayElementTypes-code twin of
	// packedIntElementWidth (which reads a make-array :element-type designator instead).
	private static int packedIntWidthForElementTypeCode(int elementTypeCode) {
		return switch (elementTypeCode) {
			case ArrayElementTypes.UNSIGNED_BYTE_8 -> 8;
			case ArrayElementTypes.UNSIGNED_BYTE_16 -> 16;
			case ArrayElementTypes.UNSIGNED_BYTE_32 -> 32;
			default -> 0;
		};
	}

	// Rebuilds `elements` as the packed representation `elementTypeCode` remembers
	// (every width in both families), or null for ArrayElementTypes.T -- an ordinary
	// general array, which the caller builds itself. The shared tail of subseq's and
	// %array-alike's general-array arms: a fill-pointer / adjustable packed vector is
	// stored as a general LispArray that only REMEMBERS its width
	// (.kb/adjustable-arrays.md),
	// so a copy of one must consult this field rather than the runtime class the way the
	// simple (LispIntVector/LispFloatArray) arms do (.todo/698).
	private static @Nullable LispVal packedCopyForElementType(String fn, int elementTypeCode, LispVal[] elements) {
		int width = packedIntWidthForElementTypeCode(elementTypeCode);
		if (width > 0) {
			return packedIntVector(fn, width, List.of(elements));
		}
		LispFloatArray proto = floatPrototypeForElementTypeCode(elementTypeCode);
		if (proto != null) {
			return packedFloatVector(fn, proto, List.of(elements));
		}
		return null;
	}

	// A packed integer-vector element: an exact integer, masked by the caller. Anything
	// else (float, ratio, character, ...) is a type error -- there is no degrade path,
	// matching the packed float arrays.
	private static long exactIntElement(String fn, @Nullable LispVal val) {
		if (val instanceof LispInteger i) {
			return i.value();
		}
		if (val instanceof LispBigInteger b) {
			// longValue() keeps the low 64 bits; the width mask below keeps fewer.
			return b.value().longValue();
		}
		throw new LispEvalException(
				fn + ": a packed integer vector stores integers, got " + (val == null ? "nil" : val.print()));
	}

	// Fills array storage row-major from a make-array :initial-contents argument: a
	// (possibly nested) sequence -- list, string, or vector -- whose nesting depth
	// matches the rank, each level's length matching the corresponding dimension.
	private static void fillInitialContents(LispVal contents, int[] dims, int dimIndex, LispVal[] data, int offset) {
		if (dims.length == 0) {
			// A RANK-0 array's :initial-contents is the single element itself, not a
			// sequence (CLHS 15.1.1).
			data[offset] = contents;
			return;
		}
		List<LispVal> items = switch (contents) {
			case LispNil ignored -> List.of();
			case LispCons cons -> cons.toList();
			case LispString str -> {
				String s = str.value();
				List<LispVal> chars = new java.util.ArrayList<>(s.length());
				s.codePoints().forEach(cp -> chars.add(new LispChar(cp)));
				yield chars;
			}
			case LispArray arr -> {
				List<LispVal> elements = new java.util.ArrayList<>(arr.effectiveLength());
				for (int i = 0; i < arr.effectiveLength(); i++) {
					elements.add(arr.aref(i));
				}
				yield elements;
			}
			// The packed vector representations are sequences too: a (unsigned-byte 8)
			// buffer read with read-sequence is a perfectly good :initial-contents
			// source (local-time re-packs one as a bit vector).
			case am.ik.rontolisp.LispIntVector vec -> {
				List<LispVal> elements = new java.util.ArrayList<>(vec.length());
				for (int i = 0; i < vec.length(); i++) {
					elements.add(new LispInteger(vec.elementAt(i)));
				}
				yield elements;
			}
			case am.ik.rontolisp.LispFloatArray arr -> {
				List<LispVal> elements = new java.util.ArrayList<>(arr.totalSize());
				for (int i = 0; i < arr.totalSize(); i++) {
					elements.add(new LispDouble(arr.elementAt(i)));
				}
				yield elements;
			}
			default -> throw new LispEvalException(
					LispNames.MAKE_ARRAY + " :initial-contents expects a sequence, got " + contents.print());
		};
		if (items.size() != dims[dimIndex]) {
			throw new LispEvalException(LispNames.MAKE_ARRAY + " :initial-contents dimension " + dimIndex + " has "
					+ items.size() + " elements, expected " + dims[dimIndex]);
		}
		int stride = 1;
		for (int d = dimIndex + 1; d < dims.length; d++) {
			stride *= dims[d];
		}
		for (int k = 0; k < items.size(); k++) {
			if (dimIndex == dims.length - 1) {
				data[offset + k] = items.get(k);
			}
			else {
				fillInitialContents(items.get(k), dims, dimIndex + 1, data, offset + k * stride);
			}
		}
	}

	// Whether a make-array :element-type argument designates a character type
	// ("character"/"base-char"/"standard-char"), selecting the string representation.
	// The symbol name is matched ignoring any package qualifier.
	private static boolean isCharacterElementType(@Nullable LispVal elementType) {
		if (elementType instanceof LispSymbol sym) {
			String name = sym.name();
			int colon = name.lastIndexOf(':');
			String local = colon >= 0 ? name.substring(colon + 1) : name;
			return local.equals("CHARACTER") || local.equals("BASE-CHAR") || local.equals("STANDARD-CHAR");
		}
		return false;
	}

	private static LispHashTable requireHashTable(String fn, LispVal val) {
		if (val instanceof LispHashTable table) {
			return table;
		}
		throw new LispEvalException(fn + " expects a hash table, got " + val.print());
	}

	/**
	 * Registers the {@code rontolisp:make-mutex} / {@code mutex-acquire} /
	 * {@code mutex-release} primitives: real mutual exclusion for the concurrency the
	 * interpreter really runs (one virtual thread per request under
	 * {@code rontolisp:http-handler}). The handle is an OPAQUE integer index into this
	 * environment's lock table, matching the socket handles' convention -- the JVM
	 * backend hands out the {@code ReentrantLock} itself and WASM a constant, so nothing
	 * portable may print or compare one.
	 *
	 * <p>
	 * The lock is a {@code ReentrantLock} rather than an object monitor because
	 * {@code with-mutex} lowers to acquire / body / release as three separate calls, and
	 * a monitor cannot be released by a different {@code synchronized} region than the
	 * one that took it. Reentrancy is a deliberate superset of bordeaux-threads'
	 * {@code make-lock} (non-reentrant upstream, so a program that deadlocks there merely
	 * proceeds here).
	 * @param env the global environment
	 */
	private static void registerMutexes(Environment env) {
		Map<Long, ReentrantLock> mutexes = new ConcurrentHashMap<>();
		AtomicLong nextHandle = new AtomicLong(1);
		String makeMutex = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_MUTEX);
		env.defineFunction(makeMutex, new LispFunction(makeMutex, args -> {
			requireArgCount(LispNames.MAKE_MUTEX, args, 0);
			long handle = nextHandle.getAndIncrement();
			mutexes.put(handle, new ReentrantLock());
			return new LispInteger(handle);
		}));
		String acquire = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MUTEX_ACQUIRE);
		env.defineFunction(acquire, new LispFunction(acquire, args -> {
			requireArgCount(LispNames.MUTEX_ACQUIRE, args, 1);
			requireMutex(LispNames.MUTEX_ACQUIRE, mutexes, args.get(0)).lock();
			return args.get(0);
		}));
		String release = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MUTEX_RELEASE);
		env.defineFunction(release, new LispFunction(release, args -> {
			requireArgCount(LispNames.MUTEX_RELEASE, args, 1);
			ReentrantLock lock = requireMutex(LispNames.MUTEX_RELEASE, mutexes, args.get(0));
			if (!lock.isHeldByCurrentThread()) {
				throw new LispEvalException(
						LispNames.MUTEX_RELEASE + ": the mutex is not held by this thread: " + args.get(0).print());
			}
			lock.unlock();
			return args.get(0);
		}));
	}

	private static ReentrantLock requireMutex(String fn, Map<Long, ReentrantLock> mutexes, LispVal handle) {
		ReentrantLock lock = handle instanceof LispInteger index ? mutexes.get(index.value()) : null;
		if (lock == null) {
			throw new LispEvalException(fn + " expects a mutex handle, got " + handle.print());
		}
		return lock;
	}

	private static void registerPackages(Environment env) {
		// The version function belongs to the rontolisp package. It is registered under
		// its
		// canonical qualified name so that PackageResolver output resolves to it
		// directly,
		// and it is NOT visible unqualified in the cl-user package.
		String versionName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.VERSION);
		env.defineFunction(versionName, new LispFunction(versionName, args -> {
			if (!args.isEmpty()) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.VERSION + " expects no arguments, got " + args.size());
			}
			return VersionInfo.plist();
		}));
		// rontolisp:wasm-export marks a function for direct WASM export (see the WASM
		// compiler). It is a compile-time directive for that backend only; on the
		// interpreter (and the JVM backend) it is a no-op that simply returns the named
		// symbol, so the same source runs unchanged on every backend.
		String exportName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WASM_EXPORT);
		env.defineFunction(exportName,
				new LispFunction(exportName, args -> args.isEmpty() ? LispNil.INSTANCE : args.get(0)));
		// rontolisp:jvm-export is the JVM twin: it declares the typed Java-callable
		// wrapper of a defun for the JVM compiler, and is the same no-op here.
		String jvmExportName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.JVM_EXPORT);
		env.defineFunction(jvmExportName,
				new LispFunction(jvmExportName, args -> args.isEmpty() ? LispNil.INSTANCE : args.get(0)));
		// rontolisp:wasm-import declares a host function imported into a compiled WASM
		// module (see the WASM compiler). The host does not exist on the interpreter
		// (or the JVM backend), so the directive defines a stub under the declared name
		// that signals an error when called -- the same source still loads on every
		// backend, and only actually calling the import is WASM-specific.
		String importDirective = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WASM_IMPORT);
		env.defineFunction(importDirective, new LispFunction(importDirective, args -> {
			if (args.isEmpty() || !(args.get(0) instanceof LispSymbol target)) {
				throw new LispEvalException(importDirective + " expects a quoted function name, got: "
						+ (args.isEmpty() ? "nothing" : args.get(0).print()));
			}
			env.defineFunction(target.name(), new LispFunction(target.name(), stubArgs -> {
				throw new LispEvalException(target.name() + " is a host function declared by " + importDirective
						+ "; it can only be called from a compiled WASM module");
			}));
			return target;
		}));
		// fetch starts an outgoing HTTP request, JavaScript fetch-style, and immediately
		// returns a future (settling with the result property list) while the request
		// runs on a background thread. It
		// belongs to the rontolisp package (it is not a Common Lisp standard function).
		// The optional second argument is an options property list (:method, :headers,
		// :body); the options are validated eagerly (like JavaScript fetch, which throws
		// synchronously on invalid arguments). The supported methods are GET, HEAD, POST,
		// PUT, DELETE, OPTIONS and PATCH; :body is the request body string (e.g. for
		// POST/PUT).
		String fetchName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FETCH);
		env.defineFunction(fetchName, new LispFunction(fetchName, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.FETCH + " expects 1 or 2 arguments, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString url)) {
				throw new LispEvalException(LispNames.FETCH + " expects a string URL, got: " + args.get(0).print());
			}
			LispVal options = args.size() == 2 ? args.get(1) : LispNil.INSTANCE;
			String method = fetchMethod(options);
			List<HttpSupport.Header> requestHeaders = parseHeaderAlist(plistGet(options, ":HEADERS"));
			String body = fetchBody(options);
			return LispFuture.of(HttpSupport.requestAsync(method, url.value(), requestHeaders, body)
				.thenApply(Environment::fetchResponsePlist));
		}));
		// futurep tests whether a value is a future (calling an async-defun function,
		// rontolisp:fetch, rontolisp:stream-read, ...).
		String futurepName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FUTUREP);
		env.defineFunction(futurepName, new LispFunction(futurepName, args -> {
			if (args.size() != 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.FUTUREP + " expects 1 argument, got " + args.size());
			}
			return (args.get(0) instanceof LispFuture) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// rontolisp:streamp tests whether a value is an asynchronous stream -- a
		// different symbol from the cl:streamp file-stream predicate; each answers nil
		// for the other's streams.
		String asyncStreampName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.ASYNC_STREAMP);
		env.defineFunction(asyncStreampName, new LispFunction(asyncStreampName, args -> {
			if (args.size() != 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ASYNC_STREAMP + " expects 1 argument, got " + args.size());
			}
			return (args.get(0) instanceof LispStream) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// make-stream creates a fresh open asynchronous stream (both ends in one value).
		String makeStreamName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_STREAM);
		env.defineFunction(makeStreamName, new LispFunction(makeStreamName, args -> {
			if (!args.isEmpty()) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.MAKE_STREAM + " expects no arguments, got " + args.size());
			}
			return LispStream.open();
		}));
		// stream-read yields a future settling to the next chunk (nil = end of stream).
		String streamReadName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_READ);
		env.defineFunction(streamReadName, new LispFunction(streamReadName, args -> {
			LispStream stream = requireAsyncStream(LispNames.STREAM_READ, args, 1);
			return LispFuture.of(stream.read());
		}));
		// stream-write appends a chunk; the returned future settles when accepted.
		String streamWriteName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_WRITE);
		env.defineFunction(streamWriteName, new LispFunction(streamWriteName, args -> {
			LispStream stream = requireAsyncStream(LispNames.STREAM_WRITE, args, 2);
			LispVal chunk = args.get(1);
			if (chunk instanceof LispNil) {
				throw new LispEvalException(LispNames.STREAM_WRITE + ": a chunk must not be nil");
			}
			try {
				stream.write(chunk);
			}
			catch (IllegalStateException ex) {
				// The stream tells the two refusals apart: closed, or a pull stream,
				// which has no write end at all.
				throw new LispEvalException(LispNames.STREAM_WRITE + ": " + ex.getMessage());
			}
			return LispFuture.settled(LispNil.INSTANCE);
		}));
		// stream-close ends the stream: reads drain the buffer, then observe nil.
		String streamCloseName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_CLOSE);
		env.defineFunction(streamCloseName, new LispFunction(streamCloseName, args -> {
			LispStream stream = requireAsyncStream(LispNames.STREAM_CLOSE, args, 1);
			stream.close();
			return LispNil.INSTANCE;
		}));
		// wait-for starts a timer: a future settling to nil after the given number of
		// milliseconds (the async surface's timer; awaiting it is the sleeping form).
		String waitForName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WAIT_FOR);
		env.defineFunction(waitForName, new LispFunction(waitForName, args -> {
			if (args.size() != 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.WAIT_FOR + " expects 1 argument, got " + args.size());
			}
			if (!(args.get(0) instanceof LispInteger millis) || millis.value() < 0) {
				throw new LispEvalException(LispNames.WAIT_FOR
						+ " expects a non-negative integer of milliseconds, got: " + args.get(0).print());
			}
			return AsyncRuntime.timer(millis.value());
		}));
	}

	private static LispStream requireAsyncStream(String name, List<LispVal> args, int arity) {
		if (args.size() != arity) {
			throw new LispEvalException(
					name + " expects " + arity + " argument" + (arity == 1 ? "" : "s") + ", got " + args.size());
		}
		if (!(args.get(0) instanceof LispStream stream)) {
			throw new LispEvalException(name + " expects a stream, got: " + args.get(0).print());
		}
		return stream;
	}

	// The fetch result plist. The shape (keys, order) is derived from the http-plist
	// WIT response record; only the per-field value extraction is this backend's, so an
	// unmapped record field fails loudly here.
	private static LispVal fetchResponsePlist(HttpSupport.Start start) {
		List<LispVal> entries = new ArrayList<>();
		for (FetchResponseShape.Field field : FetchResponseShape.responseFields()) {
			entries.add(new LispSymbol(field.keyword()));
			entries.add(switch (field.name()) {
				case "status" -> new LispInteger(start.status());
				case "headers" -> buildHeaderAlist(start.headers());
				case "body" -> start.body();
				default -> throw new LispEvalException(
						LispNames.FETCH + " has no extraction for response field " + field.name());
			});
		}
		return fetchList(entries.toArray(new LispVal[0]));
	}

	private static LispVal fetchList(LispVal... elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			result = new LispCons(elements[i], result);
		}
		return result;
	}

	// Returns the value of key in a property list (k1 v1 k2 v2 ...), or nil if absent.
	private static LispVal plistGet(LispVal plist, String key) {
		LispVal current = plist;
		while (current instanceof LispCons cons && cons.cdr() instanceof LispCons valueCell) {
			if (cons.car() instanceof LispSymbol sym && key.equals(sym.name())) {
				return valueCell.car();
			}
			current = valueCell.cdr();
		}
		return LispNil.INSTANCE;
	}

	// The HTTP methods rontolisp:fetch supports, in canonical (upper-case) form.
	private static final List<String> SUPPORTED_METHODS = List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS",
			"PATCH");

	// Resolves the :method option (default GET), normalizing it to upper case. Only the
	// methods in SUPPORTED_METHODS are accepted.
	private static String fetchMethod(LispVal options) {
		LispVal methodVal = plistGet(options, ":METHOD");
		String method;
		if (methodVal instanceof LispNil) {
			return "GET";
		}
		else if (methodVal instanceof LispString str) {
			method = str.value();
		}
		else {
			throw new LispEvalException(LispNames.FETCH + " :method must be a string, got: " + methodVal.print());
		}
		String canonical = method.toUpperCase(Locale.ROOT);
		if (!SUPPORTED_METHODS.contains(canonical)) {
			throw new LispEvalException(LispNames.FETCH + ": unsupported method: " + method + " (supported: "
					+ String.join(", ", SUPPORTED_METHODS) + ")");
		}
		return canonical;
	}

	// Resolves the :body option (default none). Must be a string when present.
	private static @Nullable String fetchBody(LispVal options) {
		LispVal bodyVal = plistGet(options, ":BODY");
		if (bodyVal instanceof LispNil) {
			return null;
		}
		if (bodyVal instanceof LispString str) {
			return str.value();
		}
		throw new LispEvalException(LispNames.FETCH + " :body must be a string, got: " + bodyVal.print());
	}

	private static List<HttpSupport.Header> parseHeaderAlist(LispVal headers) {
		List<HttpSupport.Header> result = new ArrayList<>();
		LispVal current = headers;
		while (current instanceof LispCons cons) {
			if (!(cons.car() instanceof LispCons pair) || !(pair.car() instanceof LispString name)
					|| !(pair.cdr() instanceof LispString value)) {
				throw new LispEvalException(
						LispNames.FETCH + " :headers must be an alist of (name . value) string pairs");
			}
			result.add(new HttpSupport.Header(name.value(), value.value()));
			current = cons.cdr();
		}
		return result;
	}

	private static LispVal buildHeaderAlist(List<HttpSupport.Header> headers) {
		LispVal result = LispNil.INSTANCE;
		for (int i = headers.size() - 1; i >= 0; i--) {
			HttpSupport.Header header = headers.get(i);
			result = new LispCons(new LispCons(new LispString(header.name()), new LispString(header.value())), result);
		}
		return result;
	}

	private static void registerArithmetic(Environment env) {
		env.defineFunction(LispNames.ADD, new LispFunction(LispNames.ADD, args -> {
			if (hasComplex(args)) {
				return addComplex(args);
			}
			if (hasDouble(args)) {
				// Seed from the first operand, not from an exact 0: 0.0 + -0.0 is 0.0
				// under
				// IEEE 754, so an exact-zero seed erases the sign of an all-negative-zero
				// sum. Starting at args[0] makes (+ -0.0 -0.0) answer -0.0, matching both
				// compiler backends and upstream Common Lisp.
				double result = asDouble(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result += asDouble(args.get(i));
				}
				return new LispDouble(result);
			}
			if (hasRatio(args)) {
				return addRational(args);
			}
			if (hasBigInteger(args)) {
				return addBig(args);
			}
			try {
				long result = 0;
				for (LispVal arg : args) {
					result = Math.addExact(result, asLong(arg));
				}
				return new LispInteger(result);
			}
			catch (ArithmeticException overflow) {
				return addBig(args);
			}
		}));
		env.defineFunction(LispNames.SUB, new LispFunction(LispNames.SUB, args -> {
			// Unlike + and *, - has no identity (CLHS 12.2): the compile path
			// (compiler/ArithmeticIdentities) rejects (-) with this same text at compile
			// time, and this check keeps the interpreter's runtime signal matching it
			// instead of leaking the IndexOutOfBoundsException of indexing an empty list.
			if (args.isEmpty()) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SUB + " requires at least one argument");
			}
			if (hasComplex(args)) {
				return subComplex(args);
			}
			if (hasDouble(args)) {
				if (args.size() == 1) {
					return new LispDouble(-asDouble(args.get(0)));
				}
				double result = asDouble(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result -= asDouble(args.get(i));
				}
				return new LispDouble(result);
			}
			if (hasRatio(args)) {
				return subRational(args);
			}
			if (hasBigInteger(args)) {
				return subBig(args);
			}
			try {
				if (args.size() == 1) {
					return new LispInteger(Math.negateExact(asLong(args.get(0))));
				}
				long result = asLong(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result = Math.subtractExact(result, asLong(args.get(i)));
				}
				return new LispInteger(result);
			}
			catch (ArithmeticException overflow) {
				return subBig(args);
			}
		}));
		env.defineFunction(LispNames.MUL, new LispFunction(LispNames.MUL, args -> {
			if (hasComplex(args)) {
				return mulComplex(args);
			}
			if (hasDouble(args)) {
				double result = 1;
				for (LispVal arg : args) {
					result *= asDouble(arg);
				}
				return new LispDouble(result);
			}
			if (hasRatio(args)) {
				return mulRational(args);
			}
			if (hasBigInteger(args)) {
				return mulBig(args);
			}
			try {
				long result = 1;
				for (LispVal arg : args) {
					result = Math.multiplyExact(result, asLong(arg));
				}
				return new LispInteger(result);
			}
			catch (ArithmeticException overflow) {
				return mulBig(args);
			}
		}));
		env.defineFunction(LispNames.DIV, new LispFunction(LispNames.DIV, args -> {
			// Unlike + and *, / has no identity (CLHS 12.2): the compile path
			// (compiler/ArithmeticIdentities) rejects (/) with this same text at compile
			// time, and this check keeps the interpreter's runtime signal matching it
			// instead of leaking the IndexOutOfBoundsException of indexing an empty list.
			if (args.isEmpty()) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.DIV + " requires at least one argument");
			}
			if (hasComplex(args)) {
				return divComplex(args);
			}
			if (hasDouble(args)) {
				if (args.size() == 1) {
					return new LispDouble(1.0 / asDouble(args.get(0)));
				}
				double result = asDouble(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result /= asDouble(args.get(i));
				}
				return new LispDouble(result);
			}
			// Exact rational division (Common Lisp semantics): (/ 1 2) -> 1/2,
			// (/ 4 2) -> 2, and unary (/ x) is the reciprocal.
			BigInteger num;
			BigInteger den;
			int first;
			if (args.size() == 1) {
				num = BigInteger.ONE;
				den = BigInteger.ONE;
				first = 0;
			}
			else {
				num = numeratorOf(args.get(0));
				den = denominatorOf(args.get(0));
				first = 1;
			}
			for (int i = first; i < args.size(); i++) {
				BigInteger divisorNum = numeratorOf(args.get(i));
				if (divisorNum.signum() == 0) {
					throw LispEvalException.ofClass(ClosRegistry.DIVISION_BY_ZERO_CLASS_NAME, "Division by zero");
				}
				num = num.multiply(denominatorOf(args.get(i)));
				den = den.multiply(divisorNum);
			}
			return LispRatio.valueOf(num, den);
		}));
		// mod: modulo whose result takes the sign of the divisor (Common Lisp mod).
		env.defineFunction(LispNames.MOD, new LispFunction(LispNames.MOD, args -> {
			requireArgCount(LispNames.MOD, args, 2);
			if (hasDouble(args)) {
				double a = asDouble(args.get(0));
				double b = asDouble(args.get(1));
				double r = integerQuotientZero(a, b, a % b);
				if (r != 0 && ((r < 0) != (b < 0))) {
					r += b;
				}
				return new LispDouble(r);
			}
			if (hasRatio(args)) {
				return rationalRemainder(args.get(0), args.get(1), true);
			}
			if (hasBigInteger(args)) {
				BigInteger a = asBigInteger(args.get(0));
				BigInteger b = asBigInteger(args.get(1));
				BigInteger r = a.remainder(b);
				if (r.signum() != 0 && r.signum() != b.signum()) {
					r = r.add(b);
				}
				return normalizeBig(r);
			}
			return new LispInteger(Math.floorMod(asLong(args.get(0)), asLong(args.get(1))));
		}));
		// rem: remainder whose result takes the sign of the dividend (Common Lisp rem).
		env.defineFunction(LispNames.REM, new LispFunction(LispNames.REM, args -> {
			requireArgCount(LispNames.REM, args, 2);
			if (hasDouble(args)) {
				double a = asDouble(args.get(0));
				double b = asDouble(args.get(1));
				return new LispDouble(integerQuotientZero(a, b, a % b));
			}
			if (hasRatio(args)) {
				return rationalRemainder(args.get(0), args.get(1), false);
			}
			if (hasBigInteger(args)) {
				return normalizeBig(asBigInteger(args.get(0)).remainder(asBigInteger(args.get(1))));
			}
			return new LispInteger(asLong(args.get(0)) % asLong(args.get(1)));
		}));
		env.defineFunction(LispNames.ABS, new LispFunction(LispNames.ABS, args -> {
			requireArgCount(LispNames.ABS, args, 1);
			if (args.get(0) instanceof LispComplex c) {
				// The modulus is a real -- a float even for exact parts, like SBCL.
				return new LispDouble(StrictMath.hypot(realToDouble(c.real()), realToDouble(c.imag())));
			}
			if (hasDouble(args)) {
				return new LispDouble(Math.abs(asDouble(args.get(0))));
			}
			if (args.get(0) instanceof LispRatio r) {
				return LispRatio.valueOf(r.numerator().abs(), r.denominator());
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return normalizeBig(b.value().abs());
			}
			long value = asLong(args.get(0));
			if (value == Long.MIN_VALUE) {
				return new LispBigInteger(BigInteger.valueOf(value).negate());
			}
			return new LispInteger(Math.abs(value));
		}));
		// min/max: variadic, a left fold of the two-argument select
		//
		// min(a, b) = (a <= b) ? a : b max(a, b) = (a >= b) ? a : b
		//
		// where <= / >= are the IEEE comparisons, hence FALSE whenever either side is
		// NaN. That one rule fixes both edge axes at once, and it is what upstream
		// Common Lisp answers -- verified against SBCL bit-for-bit over every ordered
		// pair drawn from {-0.0, 0.0, 1.0, -1.0, NaN, +inf, -inf}:
		//
		// - an equal-value tie keeps the ACCUMULATOR, so the leftmost argument wins and
		// (min -0.0 0.0) is -0.0 while (min 0.0 -0.0) is 0.0. CL leaves the choice to
		// the implementation; agreeing with upstream, and across our own four backends,
		// is the reason to pick this one.
		// - NaN is unordered, so the comparison fails and the RIGHT operand is taken:
		// (min nan 1.0) is 1.0 and (min 1.0 nan) is NaN.
		//
		// This is deliberately NOT Math.min/Math.max, which resolve a signed-zero tie by
		// sign and propagate NaN from either side.
		//
		// No float contagion, either: the winning operand comes back exactly as it
		// stands, so (min 1 2.0) is the rational 1, not the double 1.0. CLHS leaves this
		// implementation-dependent too, and SBCL does not coerce -- matching it, and our
		// own compiled backends' general path, is the reason to pick this one.
		env.defineFunction(LispNames.MIN, new LispFunction(LispNames.MIN, args -> {
			requireMinArgCount(LispNames.MIN, args, 1);
			LispVal best = args.get(0);
			for (int i = 1; i < args.size(); i++) {
				LispVal cand = args.get(i);
				int sign = compareNumeric(best, cand);
				if (sign == UNORDERED || sign > 0) {
					best = cand;
				}
			}
			return best;
		}));
		env.defineFunction(LispNames.MAX, new LispFunction(LispNames.MAX, args -> {
			requireMinArgCount(LispNames.MAX, args, 1);
			LispVal best = args.get(0);
			for (int i = 1; i < args.size(); i++) {
				LispVal cand = args.get(i);
				int sign = compareNumeric(best, cand);
				if (sign == UNORDERED || sign < 0) {
					best = cand;
				}
			}
			return best;
		}));
		env.defineFunction(LispNames.ONE_PLUS, new LispFunction(LispNames.ONE_PLUS, args -> {
			requireArgCount(LispNames.ONE_PLUS, args, 1);
			if (hasComplex(args)) {
				return addComplex(List.of(args.get(0), new LispInteger(1)));
			}
			if (hasDouble(args)) {
				return new LispDouble(asDouble(args.get(0)) + 1);
			}
			if (args.get(0) instanceof LispRatio r) {
				return LispRatio.valueOf(r.numerator().add(r.denominator()), r.denominator());
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return normalizeBig(b.value().add(BigInteger.ONE));
			}
			try {
				return new LispInteger(Math.addExact(asLong(args.get(0)), 1));
			}
			catch (ArithmeticException overflow) {
				return normalizeBig(asBigInteger(args.get(0)).add(BigInteger.ONE));
			}
		}));
		env.defineFunction(LispNames.ONE_MINUS, new LispFunction(LispNames.ONE_MINUS, args -> {
			requireArgCount(LispNames.ONE_MINUS, args, 1);
			if (hasComplex(args)) {
				return subComplex(List.of(args.get(0), new LispInteger(1)));
			}
			if (hasDouble(args)) {
				return new LispDouble(asDouble(args.get(0)) - 1);
			}
			if (args.get(0) instanceof LispRatio r) {
				return LispRatio.valueOf(r.numerator().subtract(r.denominator()), r.denominator());
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return normalizeBig(b.value().subtract(BigInteger.ONE));
			}
			try {
				return new LispInteger(Math.subtractExact(asLong(args.get(0)), 1));
			}
			catch (ArithmeticException overflow) {
				return normalizeBig(asBigInteger(args.get(0)).subtract(BigInteger.ONE));
			}
		}));
	}

	private static void registerMath(Environment env) {
		// Unary floating-point functions: a double for real operands (StrictMath.<name>),
		// the float complex formula for complex ones. A real argument OUTSIDE the
		// function's real domain runs the same complex formula at (x, +0.0) instead of
		// answering NaN (SBCL parity, .todo/763): sqrt of a negative below, log of a
		// negative, asin/acos beyond [-1, 1], acosh below 1 and atanh beyond [-1, 1].
		env.defineFunction(LispNames.SQRT, new LispFunction(LispNames.SQRT, args -> {
			requireArgCount(LispNames.SQRT, args, 1);
			if (args.get(0) instanceof LispComplex c) {
				double[] r = complexSqrt(realToDouble(c.real()), realToDouble(c.imag()));
				return LispComplex.valueOf(new LispDouble(r[0]), new LispDouble(r[1]));
			}
			double d = asDouble(args.get(0));
			if (d < 0) {
				return LispComplex.valueOf(new LispDouble(0.0), new LispDouble(Math.sqrt(-d)));
			}
			return new LispDouble(Math.sqrt(d));
		}));
		defineUnaryComplex(env, LispNames.EXP, StrictMath::exp, Environment::complexExp);
		// log's real domain is the non-negative reals; a zero keeps -Infinity (the
		// zero edge is CL's own, not this escape). The optional BASE makes the answer
		// the QUOTIENT of the two logarithms, each of which takes the escape on its
		// own -- so (log -8d0 2d0) is a plane value over a real one, and nothing about
		// the one-argument answer moves.
		env.defineFunction(LispNames.LOG, new LispFunction(LispNames.LOG, args -> {
			requireArgCountBetween(LispNames.LOG, args, 1, 2);
			LispVal value = unaryComplexEscape(args.get(0), StrictMath::log, Environment::complexLog, d -> !(d < 0.0));
			if (args.size() == 1) {
				return value;
			}
			LispVal base = unaryComplexEscape(args.get(1), StrictMath::log, Environment::complexLog, d -> !(d < 0.0));
			List<LispVal> quotient = List.of(value, base);
			if (hasComplex(quotient)) {
				return divComplex(quotient);
			}
			// Both logarithms are floats, so this is the float arm of / itself.
			return new LispDouble(asDouble(value) / asDouble(base));
		}));
		defineUnaryComplex(env, LispNames.SIN, StrictMath::sin, Environment::complexSin);
		defineUnaryComplex(env, LispNames.COS, StrictMath::cos, Environment::complexCos);
		defineUnaryComplex(env, LispNames.TAN, StrictMath::tan, Environment::complexTan);
		defineUnaryComplexEscape(env, LispNames.ASIN, StrictMath::asin, Environment::complexAsin,
				Environment::insideUnit);
		defineUnaryComplexEscape(env, LispNames.ACOS, StrictMath::acos, Environment::complexAcos,
				Environment::insideUnit);
		// atan's optional second argument is C's atan2: the angle of the vector (x, y)
		// over the full circle, which IS the phase of x + yi -- the same StrictMath.atan2
		// phase already answers with, signed zeros and all. CLHS requires both
		// arguments to be real there, so a complex signals rather than computing.
		env.defineFunction(LispNames.ATAN, new LispFunction(LispNames.ATAN, args -> {
			requireArgCountBetween(LispNames.ATAN, args, 1, 2);
			if (args.size() == 1) {
				return unaryComplex(args.get(0), StrictMath::atan, Environment::complexAtan);
			}
			requireRealOperand(LispNames.ATAN, args.get(0));
			requireRealOperand(LispNames.ATAN, args.get(1));
			return new LispDouble(StrictMath.atan2(asDouble(args.get(0)), asDouble(args.get(1))));
		}));
		defineUnaryComplex(env, LispNames.SINH, StrictMath::sinh, Environment::complexSinh);
		defineUnaryComplex(env, LispNames.COSH, StrictMath::cosh, Environment::complexCosh);
		defineUnaryComplex(env, LispNames.TANH, StrictMath::tanh, Environment::complexTanh);
		// cis answers a complex for every argument (it is the polar constructor:
		// (cis x) = (complex (cos x) (sin x))), so it does not fit defineUnaryComplex's
		// real-answers-real shape.
		env.defineFunction(LispNames.CIS, new LispFunction(LispNames.CIS, args -> {
			requireArgCount(LispNames.CIS, args, 1);
			if (args.get(0) instanceof LispComplex c) {
				double[] r = complexCis(realToDouble(c.real()), realToDouble(c.imag()));
				return LispComplex.valueOf(new LispDouble(r[0]), new LispDouble(r[1]));
			}
			double d = asDouble(args.get(0));
			return LispComplex.valueOf(new LispDouble(StrictMath.cos(d)), new LispDouble(StrictMath.sin(d)));
		}));
		defineUnaryComplex(env, LispNames.ASINH, Environment::asinhReal, Environment::complexAsinh);
		defineUnaryComplexEscape(env, LispNames.ACOSH, Environment::acoshReal, Environment::complexAcosh,
				d -> !(d < 1.0));
		defineUnaryComplexEscape(env, LispNames.ATANH, Environment::atanhReal, Environment::complexAtanh,
				Environment::insideUnit);
		env.defineFunction(LispNames.SCALE_FLOAT, new LispFunction(LispNames.SCALE_FLOAT, args -> {
			requireArgCount(LispNames.SCALE_FLOAT, args, 2);
			// f * 2^n with exact IEEE semantics, including the subnormal range.
			return new LispDouble(Math.scalb(asDouble(args.get(0)), (int) asLong(args.get(1))));
		}));
		env.defineFunction(LispNames.FLOAT_RADIX, new LispFunction(LispNames.FLOAT_RADIX, args -> {
			// The radix of the float representation: every float here is a binary
			// double, so the answer is unconditionally 2.
			requireArgCount(LispNames.FLOAT_RADIX, args, 1);
			return new LispInteger(2);
		}));
		// IEEE 754 bit reinterpretation, the primitive quartet under the float-features
		// shim library. Bits travel as unsigned integers (bignums when the sign bit is
		// set), so ldb/ash arithmetic over them behaves like CL's (unsigned-byte 64).
		env.defineFunction(LispNames.IEEE754_DOUBLE_BITS, new LispFunction(LispNames.IEEE754_DOUBLE_BITS, args -> {
			requireArgCount(LispNames.IEEE754_DOUBLE_BITS, args, 1);
			long bits = Double.doubleToRawLongBits(asDouble(args.get(0)));
			return normalizeBig(new BigInteger(Long.toUnsignedString(bits)));
		}));
		env.defineFunction(LispNames.IEEE754_DOUBLE_FROM_BITS,
				new LispFunction(LispNames.IEEE754_DOUBLE_FROM_BITS, args -> {
					requireArgCount(LispNames.IEEE754_DOUBLE_FROM_BITS, args, 1);
					return new LispDouble(Double.longBitsToDouble(asBigInteger(args.get(0)).longValue()));
				}));
		env.defineFunction(LispNames.IEEE754_SINGLE_BITS, new LispFunction(LispNames.IEEE754_SINGLE_BITS, args -> {
			requireArgCount(LispNames.IEEE754_SINGLE_BITS, args, 1);
			int bits = Float.floatToRawIntBits((float) asDouble(args.get(0)));
			return normalizeBig(new BigInteger(Integer.toUnsignedString(bits)));
		}));
		env.defineFunction(LispNames.IEEE754_SINGLE_FROM_BITS,
				new LispFunction(LispNames.IEEE754_SINGLE_FROM_BITS, args -> {
					requireArgCount(LispNames.IEEE754_SINGLE_FROM_BITS, args, 1);
					return new LispDouble(Float.intBitsToFloat((int) asBigInteger(args.get(0)).longValue()));
				}));
		// bfloat16 <-> double: the top sixteen bits of an f32, round-to-nearest-even
		// on the way down and exact on the way back. Sixteen bits fit a fixnum, so
		// unlike the quartet above this pair is on all four backends (BFloat16,
		// .kb/bfloat16.md).
		String bfloat16Bits = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.BFLOAT16_BITS);
		env.defineFunction(bfloat16Bits, new LispFunction(bfloat16Bits, args -> {
			requireArgCount(LispNames.BFLOAT16_BITS, args, 1);
			return new LispInteger(BFloat16.bits(asDouble(args.get(0))));
		}));
		String bitsBfloat16 = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.BITS_BFLOAT16);
		env.defineFunction(bitsBfloat16, new LispFunction(bitsBfloat16, args -> {
			requireArgCount(LispNames.BITS_BFLOAT16, args, 1);
			return new LispDouble(BFloat16.value((int) asLong(args.get(0))));
		}));
		// IEEE binary16 (f16) bit conversion: a real width unlike single/double-float,
		// which needs no bignum model (16 bits always fits a plain fixnum), so it is a
		// rontolisp: primitive rather than a float-features one -- see .todo/671.
		// Float.floatToFloat16/float16ToFloat are JDK 20+ intrinsics. Registered under
		// their PACKAGE-QUALIFIED name (the rontolisp:version arrangement above) so
		// PackageResolver output resolves to them directly; the compiled backends'
		// dispatch switches still match on the bare LispNames constant, which is a
		// symbol's unqualified name regardless of package.
		String float16BitsName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FLOAT16_BITS);
		env.defineFunction(float16BitsName, new LispFunction(float16BitsName, args -> {
			requireArgCount(LispNames.FLOAT16_BITS, args, 1);
			return new LispInteger(Float.floatToFloat16((float) asDouble(args.get(0))) & 0xFFFF);
		}));
		String bitsFloat16Name = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.BITS_FLOAT16);
		env.defineFunction(bitsFloat16Name, new LispFunction(bitsFloat16Name, args -> {
			requireArgCount(LispNames.BITS_FLOAT16, args, 1);
			return new LispDouble(Float.float16ToFloat((short) asLong(args.get(0))));
		}));
		// The bulk f16/bf16 widen and narrow: .todo/671's actual point, since a
		// checkpoint's tensors arrive as (unsigned-byte 16) bit patterns, never as
		// single elements. Dispatches on the DESTINATION's/SOURCE's concrete packed
		// float width through an exhaustive switch (not an instanceof-vs-else guess at
		// "the other width") so a third LispFloatArray permit (.todo/484's #bf16) fails
		// to compile here instead of silently widening into the wrong width; see
		// FloatBitsWidening.
		String widenFloatBitsName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WIDEN_FLOAT_BITS);
		env.defineFunction(widenFloatBitsName, new LispFunction(widenFloatBitsName,
				args -> FloatBitsWidening.widen(LispNames.WIDEN_FLOAT_BITS, args)));
		String narrowFloatBitsName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.NARROW_FLOAT_BITS);
		env.defineFunction(narrowFloatBitsName, new LispFunction(narrowFloatBitsName,
				args -> FloatBitsWidening.narrow(LispNames.NARROW_FLOAT_BITS, args)));
		// The block-quantized weight matrix (.kb/quantized-matrix.md): ggml's Q8_0 held
		// verbatim, dequantized on read, immutable. The two %-accessors are what
		// vec.lisp's integer-dot GEMV reads, so the scalar oracle folds the very
		// integers the lane kernels fold.
		String quantizeName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZE);
		env.defineFunction(quantizeName,
				new LispFunction(quantizeName, args -> QuantizedMatrices.quantize(quantizeName, args)));
		String dequantizeName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.DEQUANTIZE);
		env.defineFunction(dequantizeName,
				new LispFunction(dequantizeName, args -> QuantizedMatrices.dequantize(dequantizeName, args)));
		String makeQuantizedName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_QUANTIZED_MATRIX);
		env.defineFunction(makeQuantizedName,
				new LispFunction(makeQuantizedName, args -> QuantizedMatrices.make(makeQuantizedName, args)));
		String quantizedRowsName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_ROWS);
		env.defineFunction(quantizedRowsName,
				new LispFunction(quantizedRowsName, args -> QuantizedMatrices.rows(quantizedRowsName, args)));
		String quantizedPName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_MATRIX_P);
		env.defineFunction(quantizedPName,
				new LispFunction(quantizedPName, args -> QuantizedMatrices.isMatrix(quantizedPName, args)));
		String quantizedQuantName = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
				LispNames.QUANTIZED_QUANT_INTERNAL);
		env.defineFunction(quantizedQuantName,
				new LispFunction(quantizedQuantName, args -> QuantizedMatrices.quant(quantizedQuantName, args)));
		String quantizedScaleName = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
				LispNames.QUANTIZED_SCALE_INTERNAL);
		env.defineFunction(quantizedScaleName,
				new LispFunction(quantizedScaleName, args -> QuantizedMatrices.scale(quantizedScaleName, args)));
		// random: a non-negative random number below the (positive) limit, of the same
		// type as the limit (integer -> integer, float -> float). The interpreter and the
		// JVM backend draw from ThreadLocalRandom (a per-thread generator seeded from the
		// process's entropy); the WASM backends draw from a module-local SplitMix64
		// generator seeded once from the host. All three are plain PRNGs, which is what
		// CL's random is -- the entropy API is %random-byte below (.kb/random.md).
		// make-random-state: nil, always -- no random-state objects exist (random
		// ignores its optional state argument), and nil is what a caller stores and
		// passes back. The argument (nil / t / a state) is accepted and ignored.
		env.defineFunction(LispNames.MAKE_RANDOM_STATE, new LispFunction(LispNames.MAKE_RANDOM_STATE, args -> {
			if (args.size() > 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.MAKE_RANDOM_STATE + " expects 0 or 1 arguments, got " + args.size());
			}
			return LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.RANDOM, new LispFunction(LispNames.RANDOM, args -> {
			// CL's optional second argument is a random-state; no random-state
			// objects exist here (make-random-state answers nil), so it is accepted
			// and ignored -- the backend's own entropy draws (uuid's
			// (random #xffffffffffff *uuid-random-state*)).
			if (args.size() != 1 && args.size() != 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.RANDOM + " expects 1 or 2 arguments, got " + args.size());
			}
			LispVal limit = args.get(0);
			// CLHS's domain is (OR (INTEGER 1) (FLOAT (0.0))): a ratio is real but
			// neither, and an integer or float limit <= 0 is out of range either way.
			// Both are reported under RANDOM's own registered REAL type (like a
			// non-real limit below) rather than teaching the operand-type table a
			// compound type for this one operator (.todo/981,
			// .kb/error-handling.md).
			if (limit instanceof LispDouble d) {
				if (d.value() <= 0.0) {
					throw OperandTypeException.of(limit, OperandTypes.Kind.REAL).named(LispNames.RANDOM);
				}
				return new LispDouble(ThreadLocalRandom.current().nextDouble() * d.value());
			}
			if (limit instanceof LispInteger i) {
				if (i.value() <= 0) {
					throw OperandTypeException.of(limit, OperandTypes.Kind.REAL).named(LispNames.RANDOM);
				}
				return new LispInteger((long) (ThreadLocalRandom.current().nextDouble() * i.value()));
			}
			if (limit instanceof LispBigInteger b) {
				if (b.value().signum() <= 0) {
					throw OperandTypeException.of(limit, OperandTypes.Kind.REAL).named(LispNames.RANDOM);
				}
				// Scale a [0,1) random fraction across the bignum range, then floor.
				return normalizeBig(new java.math.BigDecimal(b.value())
					.multiply(java.math.BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble()))
					.toBigInteger());
			}
			if (limit instanceof LispRatio) {
				throw OperandTypeException.of(limit, OperandTypes.Kind.REAL).named(LispNames.RANDOM);
			}
			throw OperandTypeException
				.of(limit, limit instanceof LispComplex ? OperandTypes.Kind.REAL : OperandTypes.Kind.NUMBER)
				.named(LispNames.RANDOM);
		}));
		// %random-byte: ONE cryptographically strong byte (0-255). Unlike random (a
		// plain PRNG here), the source is java.security.SecureRandom -- the same
		// contract the WASM backends get from the WASI random_get host function. The
		// generator is created once and reused; rontolisp:random-bytes (a prelude
		// defun) is the public API over it.
		String randomByteName = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
				LispNames.RANDOM_BYTE_INTERNAL);
		java.security.SecureRandom secureRandom = new java.security.SecureRandom();
		env.defineFunction(randomByteName, new LispFunction(randomByteName, args -> {
			requireArgCount(LispNames.RANDOM_BYTE_INTERNAL, args, 0);
			return new LispInteger(secureRandom.nextInt(256));
		}));
		// get-universal-time: seconds since 1900-01-01 00:00:00 GMT (Common Lisp epoch).
		// The interpreter and JVM backends read the real wall clock; the WASM backend
		// reads
		// wasi:clocks in component mode and a deterministic value in Preview 1 mode.
		env.defineFunction(LispNames.GET_UNIVERSAL_TIME, new LispFunction(LispNames.GET_UNIVERSAL_TIME, args -> {
			requireArgCount(LispNames.GET_UNIVERSAL_TIME, args, 0);
			return new LispInteger(System.currentTimeMillis() / 1000L + 2208988800L);
		}));
		// get-internal-real-time: elapsed real time in internal units (milliseconds
		// here).
		env.defineFunction(LispNames.GET_INTERNAL_REAL_TIME,
				new LispFunction(LispNames.GET_INTERNAL_REAL_TIME, args -> {
					requireArgCount(LispNames.GET_INTERNAL_REAL_TIME, args, 0);
					return new LispInteger(System.currentTimeMillis());
				}));
		// get-internal-run-time: consumed run time in internal units (milliseconds here).
		env.defineFunction(LispNames.GET_INTERNAL_RUN_TIME, new LispFunction(LispNames.GET_INTERNAL_RUN_TIME, args -> {
			requireArgCount(LispNames.GET_INTERNAL_RUN_TIME, args, 0);
			return new LispInteger(System.nanoTime() / 1000000L);
		}));
		// sleep: park the thread for a non-negative number of seconds and answer nil.
		// Registered as a real function (not only as the %sleep-ms expansion) so #'sleep
		// and native-image mode work; the argument may be any real, sub-second values
		// included.
		env.defineFunction(LispNames.SLEEP, new LispFunction(LispNames.SLEEP, args -> {
			requireArgCount(LispNames.SLEEP, args, 1);
			double seconds = asDouble(args.get(0));
			long millis = Math.round(seconds * 1000.0);
			if (millis > 0) {
				try {
					Thread.sleep(millis);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new LispEvalException(LispNames.SLEEP + " was interrupted");
				}
			}
			return LispNil.INSTANCE;
		}));
		// The internal primitive the compile-path expansion lowers onto, registered here
		// too so an interpreted program that reached the expanded form still runs.
		env.defineFunction(LispNames.SLEEP_MS, new LispFunction(LispNames.SLEEP_MS, args -> {
			requireArgCount(LispNames.SLEEP_MS, args, 1);
			long millis = (long) asDouble(args.get(0));
			if (millis > 0) {
				try {
					Thread.sleep(millis);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new LispEvalException(LispNames.SLEEP + " was interrupted");
				}
			}
			return LispNil.INSTANCE;
		}));
		// %host-getenv: the HOST's value for an environment variable as a string, or nil
		// if unset. The public uiop:getenv is Lisp over this (uiop-os.lisp), consulting
		// the (setf (uiop:getenv ...)) override map first -- no host lets a process
		// rewrite its own environment, so the write is an overlay on every backend.
		// Reading one is spelled uiop:getenv and only that: Common Lisp has no getenv,
		// and uiop's is the portable spelling every implementation-independent library
		// already uses.
		String getenvName = LispNames.HOST_GETENV;
		env.defineFunction(getenvName, new LispFunction(getenvName, args -> {
			requireArgCount(getenvName, args, 1);
			if (!(args.get(0) instanceof LispString name)) {
				throw new LispEvalException(getenvName + " expects a string, got: " + args.get(0).print());
			}
			String value = System.getenv(name.value());
			return value == null ? LispNil.INSTANCE : new LispString(value);
		}));
		// %host-getcwd: the host's working-directory namestring, or nil where the host
		// has no such notion (both WASM backends: WASI programs have preopened
		// directories and no current one). uiop:getcwd is Lisp over this and turns the
		// nil into its not-implemented-error, so the backends share one definition.
		env.defineFunction(LispNames.HOST_GETCWD, new LispFunction(LispNames.HOST_GETCWD, args -> {
			requireArgCount(LispNames.HOST_GETCWD, args, 0);
			String cwd;
			try {
				cwd = System.getProperty("user.dir");
			}
			catch (RuntimeException ex) {
				// A host without system properties (the browser playground) has no
				// working directory to answer, which is the same nil as WASI's.
				cwd = null;
			}
			return cwd == null || cwd.isEmpty() ? LispNil.INSTANCE : new LispString(cwd);
		}));
		// %target-machine-type: the ABI the running artifact targets, which is what
		// machine-type answers (a per-backend CONSTANT, no host is consulted -- see
		// .kb/time-environment-builtins.md). The interpreter runs on the JVM, so it
		// gives the same answer the JVM backend's emitted class does.
		env.defineFunction(LispNames.TARGET_MACHINE_TYPE, new LispFunction(LispNames.TARGET_MACHINE_TYPE, args -> {
			requireArgCount(LispNames.TARGET_MACHINE_TYPE, args, 0);
			return new LispString("JVM");
		}));
		// %host-exit: end the process with a status code. uiop:quit is Lisp over this
		// (uiop-image.lisp) and finishes the output streams first, so the four backends
		// share one definition of what quitting means. Here it is a SIGNAL rather than a
		// System.exit: run() is embedded (the tests, the playground), and only main may
		// turn a program's exit code into the process's -- the same rule the JVM
		// backend's uncaught-condition handler follows.
		env.defineFunction(LispNames.HOST_EXIT, new LispFunction(LispNames.HOST_EXIT, args -> {
			requireArgCount(LispNames.HOST_EXIT, args, 1);
			if (!(args.get(0) instanceof LispInteger code)) {
				throw new LispEvalException(LispNames.HOST_EXIT + " expects an integer, got: " + args.get(0).print());
			}
			throw new LispExitSignal((int) code.value());
		}));
		// isqrt: exact integer square root (floor of the real square root).
		env.defineFunction(LispNames.ISQRT, new LispFunction(LispNames.ISQRT, args -> {
			requireArgCount(LispNames.ISQRT, args, 1);
			BigInteger n = asBigInteger(args.get(0));
			if (n.signum() < 0) {
				throw new LispEvalException("isqrt expects a non-negative integer, got: " + args.get(0).print());
			}
			return normalizeBig(n.sqrt());
		}));
		// expt: rational^integer stays exact (a negative exponent yields the
		// reciprocal, e.g. (expt 2 -1) -> 1/2); otherwise StrictMath.pow (double). A
		// complex operand with an integer exponent stays exact by repeated
		// multiplication (e.g. (expt #c(1 1) 2) -> #C(0 2)); any other complex
		// exponentiation goes through exp(w*log(z)) in floats. A NEGATIVE real base
		// to a non-integer power leaves the real line and answers the plane
		// (negativeBasePow), where StrictMath.pow alone would answer NaN.
		env.defineFunction(LispNames.EXPT, new LispFunction(LispNames.EXPT, args -> {
			requireArgCount(LispNames.EXPT, args, 2);
			if (hasComplex(args)) {
				return exptComplex(args.get(0), args.get(1));
			}
			if (!hasDouble(args) && !(args.get(1) instanceof LispRatio)) {
				long power = asLong(args.get(1));
				if (power >= -Integer.MAX_VALUE && power <= Integer.MAX_VALUE) {
					BigInteger baseNum = numeratorOf(args.get(0));
					BigInteger baseDen = denominatorOf(args.get(0));
					return power >= 0 ? LispRatio.valueOf(baseNum.pow((int) power), baseDen.pow((int) power))
							: LispRatio.valueOf(baseDen.pow((int) -power), baseNum.pow((int) -power));
				}
			}
			double base = asDouble(args.get(0));
			double power = asDouble(args.get(1));
			if (escapesToPlane(base, power)) {
				return negativeBasePow(base, power);
			}
			return new LispDouble(StrictMath.pow(base, power));
		}));
		// gcd: greatest common divisor (always non-negative). Variadic: (gcd) is 0 and
		// (gcd n) is (abs n).
		env.defineFunction(LispNames.GCD, new LispFunction(LispNames.GCD, args -> {
			BigInteger result = BigInteger.ZERO;
			for (LispVal arg : args) {
				result = result.gcd(asBigInteger(arg));
			}
			return normalizeBig(result);
		}));
		// lcm: least common multiple (0 if any argument is 0). Variadic: (lcm) is 1 and
		// (lcm n) is (abs n).
		env.defineFunction(LispNames.LCM, new LispFunction(LispNames.LCM, args -> {
			BigInteger result = BigInteger.ONE;
			for (LispVal arg : args) {
				BigInteger b = asBigInteger(arg);
				if (result.signum() == 0 || b.signum() == 0) {
					result = BigInteger.ZERO;
				}
				else {
					result = result.divide(result.gcd(b)).multiply(b).abs();
				}
			}
			return normalizeBig(result);
		}));
		// signum: sign as -1/0/1, preserving the float/integer type of the argument.
		env.defineFunction(LispNames.SIGNUM, new LispFunction(LispNames.SIGNUM, args -> {
			requireArgCount(LispNames.SIGNUM, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispComplex c) {
				// The unit vector z/|z| in floats (SBCL parity: (signum #c(3 4)) is
				// #C(0.6 0.8)). A zero answers the canonicalization of its own parts
				// -- 0 for exact parts, #C(0.0 0.0) for float parts, like SBCL.
				double re = realToDouble(c.real());
				double im = realToDouble(c.imag());
				double abs = StrictMath.hypot(re, im);
				if (abs == 0.0) {
					return LispComplex.valueOf(c.real(), c.imag());
				}
				return LispComplex.valueOf(new LispDouble(re / abs), new LispDouble(im / abs));
			}
			if (arg instanceof LispDouble d) {
				return new LispDouble(Math.signum(d.value()));
			}
			if (arg instanceof LispRatio r) {
				return new LispInteger(r.numerator().signum());
			}
			if (arg instanceof LispBigInteger b) {
				return new LispInteger(b.value().signum());
			}
			return new LispInteger(Long.signum(asLong(arg)));
		}));
		// Bitwise integer operations. logand/logior/logxor are variadic with identities
		// -1/0/0. Values inside the long range answer with the matching long operator --
		// 64-bit two's complement agrees with BigInteger's infinite two's complement on
		// every value a long can hold -- so masking (unsigned-byte 32) arithmetic, which
		// is what a SHA-256 style loop does for every operation, allocates nothing. Any
		// operand outside the range falls back to the exact BigInteger operation.
		env.defineFunction(LispNames.LOGAND, new LispFunction(LispNames.LOGAND, args -> {
			if (allFixnums(args)) {
				long result = -1;
				for (LispVal arg : args) {
					result &= ((LispInteger) arg).value();
				}
				return new LispInteger(result);
			}
			BigInteger result = BigInteger.valueOf(-1);
			for (LispVal arg : args) {
				result = result.and(asBigInteger(arg));
			}
			return normalizeBig(result);
		}));
		env.defineFunction(LispNames.LOGIOR, new LispFunction(LispNames.LOGIOR, args -> {
			if (allFixnums(args)) {
				long result = 0;
				for (LispVal arg : args) {
					result |= ((LispInteger) arg).value();
				}
				return new LispInteger(result);
			}
			BigInteger result = BigInteger.ZERO;
			for (LispVal arg : args) {
				result = result.or(asBigInteger(arg));
			}
			return normalizeBig(result);
		}));
		env.defineFunction(LispNames.LOGXOR, new LispFunction(LispNames.LOGXOR, args -> {
			if (allFixnums(args)) {
				long result = 0;
				for (LispVal arg : args) {
					result ^= ((LispInteger) arg).value();
				}
				return new LispInteger(result);
			}
			BigInteger result = BigInteger.ZERO;
			for (LispVal arg : args) {
				result = result.xor(asBigInteger(arg));
			}
			return normalizeBig(result);
		}));
		env.defineFunction(LispNames.LOGNOT, new LispFunction(LispNames.LOGNOT, args -> {
			requireArgCount(LispNames.LOGNOT, args, 1);
			if (args.get(0) instanceof LispInteger i) {
				return new LispInteger(~i.value());
			}
			return normalizeBig(asBigInteger(args.get(0)).not());
		}));
		env.defineFunction(LispNames.LOGANDC1, new LispFunction(LispNames.LOGANDC1, args -> {
			requireArgCount(LispNames.LOGANDC1, args, 2);
			return normalizeBig(asBigInteger(args.get(1)).andNot(asBigInteger(args.get(0))));
		}));
		env.defineFunction(LispNames.LOGANDC2, new LispFunction(LispNames.LOGANDC2, args -> {
			requireArgCount(LispNames.LOGANDC2, args, 2);
			return normalizeBig(asBigInteger(args.get(0)).andNot(asBigInteger(args.get(1))));
		}));
		env.defineFunction(LispNames.LOGORC1, new LispFunction(LispNames.LOGORC1, args -> {
			requireArgCount(LispNames.LOGORC1, args, 2);
			return normalizeBig(asBigInteger(args.get(0)).not().or(asBigInteger(args.get(1))));
		}));
		env.defineFunction(LispNames.LOGORC2, new LispFunction(LispNames.LOGORC2, args -> {
			requireArgCount(LispNames.LOGORC2, args, 2);
			return normalizeBig(asBigInteger(args.get(0)).or(asBigInteger(args.get(1)).not()));
		}));
		env.defineFunction(LispNames.LOGNAND, new LispFunction(LispNames.LOGNAND, args -> {
			requireArgCount(LispNames.LOGNAND, args, 2);
			return normalizeBig(asBigInteger(args.get(0)).and(asBigInteger(args.get(1))).not());
		}));
		env.defineFunction(LispNames.LOGNOR, new LispFunction(LispNames.LOGNOR, args -> {
			requireArgCount(LispNames.LOGNOR, args, 2);
			return normalizeBig(asBigInteger(args.get(0)).or(asBigInteger(args.get(1))).not());
		}));
		env.defineFunction(LispNames.LOGEQV, new LispFunction(LispNames.LOGEQV, args -> {
			// The left fold of the two-argument (lognot (logxor x y)): folding from -1
			// answers -1 of no arguments and the lone argument of one, since
			// (logeqv -1 x) is x.
			BigInteger acc = BigInteger.ONE.negate();
			for (LispVal arg : args) {
				acc = acc.xor(asBigInteger(arg)).not();
			}
			return normalizeBig(acc);
		}));
		// ash: shift left for a non-negative count, arithmetic right shift otherwise.
		// A count the narrowing below cannot represent never reaches it: narrowing
		// first would wrap a huge negative count positive and build a monster
		// bignum (MISC.47/.48). Any magnitude past the int range dwarfs every
		// feasible bit length, so a huge right shift saturates to the sign, while a
		// huge left shift of a non-zero value cannot be represented
		// (BigInteger.shiftLeft takes an int) and signals instead of answering a
		// wrapped-around wrong value. Counts within the int range keep the
		// width-aware paths below: saturating at 64 is only valid for a fixnum --
		// a bignum shifted right by 70 still has high bits (ASH.3).
		env.defineFunction(LispNames.ASH, new LispFunction(LispNames.ASH, args -> {
			requireArgCount(LispNames.ASH, args, 2);
			// A bignum count is always past the saturation width: a negative one
			// shifts the whole value out (ASH.5 reaches (ash j j) with j = -(2^64)).
			if (args.get(1) instanceof LispBigInteger b) {
				if (b.value().signum() < 0) {
					return asBigInteger(args.get(0)).signum() < 0 ? new LispInteger(-1) : new LispInteger(0);
				}
				if (asBigInteger(args.get(0)).signum() == 0) {
					return new LispInteger(0);
				}
				throw new LispEvalException(LispNames.ASH + ": shift count too large: " + b.value());
			}
			long count = asLong(args.get(1));
			if (count < Integer.MIN_VALUE) {
				return asBigInteger(args.get(0)).signum() < 0 ? new LispInteger(-1) : new LispInteger(0);
			}
			if (count > Integer.MAX_VALUE) {
				if (asBigInteger(args.get(0)).signum() == 0) {
					return new LispInteger(0);
				}
				throw new LispEvalException(LispNames.ASH + ": shift count too large: " + count);
			}
			if (args.get(0) instanceof LispInteger i) {
				long value = i.value();
				int shift = (int) count;
				if (shift <= 0) {
					// Shifting past the sign bit leaves 0 (or -1 for a negative value).
					return new LispInteger(shift <= -64 ? value >> 63 : value >> -shift);
				}
				if (shift < 64) {
					long shifted = value << shift;
					if (shifted >> shift == value) {
						return new LispInteger(shifted);
					}
				}
			}
			return normalizeBig(asBigInteger(args.get(0)).shiftLeft((int) count));
		}));
		env.defineFunction(LispNames.INTEGER_LENGTH, new LispFunction(LispNames.INTEGER_LENGTH, args -> {
			requireArgCount(LispNames.INTEGER_LENGTH, args, 1);
			if (args.get(0) instanceof LispInteger i) {
				// integer-length is BigInteger.bitLength: the minimal two's-complement
				// width without the sign bit, so a negative value measures its
				// complement.
				long value = i.value();
				return new LispInteger(64 - Long.numberOfLeadingZeros(value < 0 ? ~value : value));
			}
			return new LispInteger(asBigInteger(args.get(0)).bitLength());
		}));
		env.defineFunction(LispNames.LOGBITP, new LispFunction(LispNames.LOGBITP, args -> {
			requireArgCount(LispNames.LOGBITP, args, 2);
			int index = (int) asLong(args.get(0));
			if (index >= 0 && args.get(1) instanceof LispInteger i) {
				// An index at or past the sign bit reads the sign.
				return (i.value() >>> Math.min(index, 63) & 1) == 1 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asBigInteger(args.get(1)).testBit(index) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.LOGTEST, new LispFunction(LispNames.LOGTEST, args -> {
			requireArgCount(LispNames.LOGTEST, args, 2);
			if (args.get(0) instanceof LispInteger a && args.get(1) instanceof LispInteger b) {
				return (a.value() & b.value()) != 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asBigInteger(args.get(0)).and(asBigInteger(args.get(1))).signum() != 0 ? LispTrue.INSTANCE
					: LispNil.INSTANCE;
		}));
		// byte specifier: a plain (size position) list, matching the compile-path
		// macro lowering (LispMacroExpander.expandByte). ldb/dpb read it back.
		env.defineFunction(LispNames.BYTE, new LispFunction(LispNames.BYTE, args -> {
			requireArgCount(LispNames.BYTE, args, 2);
			return new LispCons(args.get(0), new LispCons(args.get(1), LispNil.INSTANCE));
		}));
		env.defineFunction(LispNames.BYTE_SIZE, new LispFunction(LispNames.BYTE_SIZE, args -> {
			requireArgCount(LispNames.BYTE_SIZE, args, 1);
			return byteSpecSize(args.get(0));
		}));
		env.defineFunction(LispNames.BYTE_POSITION, new LispFunction(LispNames.BYTE_POSITION, args -> {
			requireArgCount(LispNames.BYTE_POSITION, args, 1);
			return byteSpecPosition(args.get(0));
		}));
		env.defineFunction(LispNames.LDB, new LispFunction(LispNames.LDB, args -> {
			requireArgCount(LispNames.LDB, args, 2);
			int size = (int) asLong(byteSpecSize(args.get(0)));
			int position = (int) asLong(byteSpecPosition(args.get(0)));
			BigInteger mask = BigInteger.ONE.shiftLeft(size).subtract(BigInteger.ONE);
			return normalizeBig(asBigInteger(args.get(1)).shiftRight(position).and(mask));
		}));
		env.defineFunction(LispNames.DPB, new LispFunction(LispNames.DPB, args -> {
			requireArgCount(LispNames.DPB, args, 3);
			int size = (int) asLong(byteSpecSize(args.get(1)));
			int position = (int) asLong(byteSpecPosition(args.get(1)));
			BigInteger fieldMask = BigInteger.ONE.shiftLeft(size).subtract(BigInteger.ONE).shiftLeft(position);
			BigInteger cleared = asBigInteger(args.get(2)).andNot(fieldMask);
			BigInteger newBits = asBigInteger(args.get(0)).shiftLeft(position).and(fieldMask);
			return normalizeBig(cleared.or(newBits));
		}));
		env.defineFunction(LispNames.DEPOSIT_FIELD, new LispFunction(LispNames.DEPOSIT_FIELD, args -> {
			// Not dpb: dpb deposits newbyte's LOW size bits, while deposit-field
			// deposits newbyte's bits AT the field position (the suite's dpb.lsp
			// checks (logbitp (- i pos) newbyte) where deposit-field.lsp checks
			// (logbitp i newbyte)).
			requireArgCount(LispNames.DEPOSIT_FIELD, args, 3);
			int size = (int) asLong(byteSpecSize(args.get(1)));
			int position = (int) asLong(byteSpecPosition(args.get(1)));
			BigInteger fieldMask = BigInteger.ONE.shiftLeft(size).subtract(BigInteger.ONE).shiftLeft(position);
			BigInteger cleared = asBigInteger(args.get(2)).andNot(fieldMask);
			BigInteger newBits = asBigInteger(args.get(0)).and(fieldMask);
			return normalizeBig(cleared.or(newBits));
		}));
		env.defineFunction(LispNames.MASK_FIELD, new LispFunction(LispNames.MASK_FIELD, args -> {
			requireArgCount(LispNames.MASK_FIELD, args, 2);
			// (mask-field spec n) = the ldb field left in its original position.
			int size = (int) asLong(byteSpecSize(args.get(0)));
			int position = (int) asLong(byteSpecPosition(args.get(0)));
			BigInteger mask = BigInteger.ONE.shiftLeft(size).subtract(BigInteger.ONE).shiftLeft(position);
			return normalizeBig(asBigInteger(args.get(1)).and(mask));
		}));
	}

	private static LispVal byteSpecSize(LispVal spec) {
		if (spec instanceof LispCons cons) {
			return cons.car();
		}
		throw new LispEvalException("byte specifier expected, got " + spec.print());
	}

	private static LispVal byteSpecPosition(LispVal spec) {
		if (spec instanceof LispCons cons && cons.cdr() instanceof LispCons rest) {
			return rest.car();
		}
		throw new LispEvalException("byte specifier expected, got " + spec.print());
	}

	/**
	 * A unary math function with a complex path: a complex operand answers the float
	 * complex formula (canonicalized, so a float zero imaginary part stays complex); any
	 * other operand answers the real {@code Math} function, with the funnel signaling for
	 * non-numbers exactly as before.
	 */
	private static void registerComplex(Environment env) {
		// complex: the canonical value for one real part and an optional imaginary
		// part (defaulting to zero). A non-real part signals a catchable
		// type-error, like SBCL.
		env.defineFunction(LispNames.COMPLEX, new LispFunction(LispNames.COMPLEX, args -> {
			requireArgCountBetween(LispNames.COMPLEX, args, 1, 2);
			LispVal real = requireReal(LispNames.COMPLEX, args.get(0));
			LispVal imag = args.size() == 2 ? requireReal(LispNames.COMPLEX, args.get(1)) : new LispInteger(0);
			return LispComplex.valueOf(real, imag);
		}));
		env.defineFunction(LispNames.REALPART, new LispFunction(LispNames.REALPART, args -> {
			requireArgCount(LispNames.REALPART, args, 1);
			if (args.get(0) instanceof LispComplex c) {
				return c.real();
			}
			return requireReal(LispNames.REALPART, args.get(0));
		}));
		env.defineFunction(LispNames.IMAGPART, new LispFunction(LispNames.IMAGPART, args -> {
			requireArgCount(LispNames.IMAGPART, args, 1);
			if (args.get(0) instanceof LispComplex c) {
				return c.imag();
			}
			LispVal arg = requireReal(LispNames.IMAGPART, args.get(0));
			// CLHS: (imagpart x) of a real IS (* 0 x) -- a float answers a
			// floating-point zero of the same format, sign included (SBCL
			// answers -0.0 for (imagpart -1.0)); any other real an integer zero.
			if (arg instanceof LispDouble d) {
				return new LispDouble(0.0 * d.value());
			}
			return new LispInteger(0);
		}));
		env.defineFunction(LispNames.CONJUGATE, new LispFunction(LispNames.CONJUGATE, args -> {
			requireArgCount(LispNames.CONJUGATE, args, 1);
			if (args.get(0) instanceof LispComplex c) {
				return LispComplex.valueOf(c.real(), negateReal(c.imag()));
			}
			return requireReal(LispNames.CONJUGATE, args.get(0));
		}));
		env.defineFunction(LispNames.PHASE, new LispFunction(LispNames.PHASE, args -> {
			requireArgCount(LispNames.PHASE, args, 1);
			if (args.get(0) instanceof LispComplex c) {
				return new LispDouble(StrictMath.atan2(realToDouble(c.imag()), realToDouble(c.real())));
			}
			// A non-negative real is at angle zero, a negative one at pi (SBCL).
			return new LispDouble(asDouble(requireReal(LispNames.PHASE, args.get(0))) < 0 ? Math.PI : 0.0);
		}));
	}

	private static void defineUnaryComplex(Environment env, String name, DoubleUnaryOperator realFn,
			ComplexFn complexFn) {
		env.defineFunction(name, new LispFunction(name, args -> {
			requireArgCount(name, args, 1);
			return unaryComplex(args.get(0), realFn, complexFn);
		}));
	}

	/**
	 * One complex-capable unary math function over one argument: the complex formula for
	 * a complex, the real one for a real. The body {@link #defineUnaryComplex} registers,
	 * factored out for the operators that carry an optional SECOND argument and so cannot
	 * be registered by it.
	 * @param arg the argument
	 * @param realFn the real arm
	 * @param complexFn the complex formula
	 * @return the function's value at {@code arg}
	 */
	private static LispVal unaryComplex(LispVal arg, DoubleUnaryOperator realFn, ComplexFn complexFn) {
		if (arg instanceof LispComplex c) {
			double[] r = complexFn.apply(realToDouble(c.real()), realToDouble(c.imag()));
			return LispComplex.valueOf(new LispDouble(r[0]), new LispDouble(r[1]));
		}
		return new LispDouble(realFn.applyAsDouble(asDouble(arg)));
	}

	/**
	 * {@link #defineUnaryComplex} for a function whose REAL domain is narrower than the
	 * real line: an argument {@code staysReal} rejects runs the SAME complex formula at
	 * {@code (x, +0.0)} rather than answering NaN, which is what CL means by the function
	 * being defined over the whole plane. A NaN argument fails every comparison the
	 * predicates are written around, so it keeps the real arm and stays a NaN double.
	 * @param env the environment to define into
	 * @param name the Lisp name
	 * @param realFn the real arm, applied inside the real domain
	 * @param complexFn the complex formula, applied to a complex argument and to a real
	 * one outside the domain
	 * @param staysReal whether a real argument is inside the real domain
	 */
	private static void defineUnaryComplexEscape(Environment env, String name, DoubleUnaryOperator realFn,
			ComplexFn complexFn, DoublePredicate staysReal) {
		env.defineFunction(name, new LispFunction(name, args -> {
			requireArgCount(name, args, 1);
			return unaryComplexEscape(args.get(0), realFn, complexFn, staysReal);
		}));
	}

	/**
	 * The body {@link #defineUnaryComplexEscape} registers, over one argument -- factored
	 * out for {@code log}, whose optional BASE applies the SAME escape to a second
	 * argument before dividing.
	 * @param arg the argument
	 * @param realFn the real arm, applied inside the real domain
	 * @param complexFn the complex formula
	 * @param staysReal whether a real argument is inside the real domain
	 * @return the function's value at {@code arg}
	 */
	private static LispVal unaryComplexEscape(LispVal arg, DoubleUnaryOperator realFn, ComplexFn complexFn,
			DoublePredicate staysReal) {
		if (arg instanceof LispComplex c) {
			double[] r = complexFn.apply(realToDouble(c.real()), realToDouble(c.imag()));
			return LispComplex.valueOf(new LispDouble(r[0]), new LispDouble(r[1]));
		}
		double d = asDouble(arg);
		if (staysReal.test(d)) {
			return new LispDouble(realFn.applyAsDouble(d));
		}
		double[] r = complexFn.apply(d, 0.0);
		return LispComplex.valueOf(new LispDouble(r[0]), new LispDouble(r[1]));
	}

	/** A float complex formula: two doubles in, two doubles out. */
	private interface ComplexFn {

		double[] apply(double real, double imag);

	}

	private static void registerComparison(Environment env) {
		// Numeric comparisons are variadic (Common Lisp): they are true when every
		// adjacent
		// pair satisfies the relation, e.g. (< 1 2 3) is t. A single argument is true.
		env.defineFunction(LispNames.EQ,
				new LispFunction(LispNames.EQ, args -> compareChain(LispNames.EQ, args, 0, 0)));
		env.defineFunction(LispNames.LT,
				new LispFunction(LispNames.LT, args -> compareChain(LispNames.LT, args, -1, -1)));
		env.defineFunction(LispNames.GT,
				new LispFunction(LispNames.GT, args -> compareChain(LispNames.GT, args, 1, 1)));
		env.defineFunction(LispNames.LE,
				new LispFunction(LispNames.LE, args -> compareChain(LispNames.LE, args, -1, 0)));
		env.defineFunction(LispNames.GE,
				new LispFunction(LispNames.GE, args -> compareChain(LispNames.GE, args, 0, 1)));
		env.defineFunction(LispNames.EQ_GENERAL, new LispFunction(LispNames.EQ_GENERAL, args -> {
			requireArgCount(LispNames.EQ_GENERAL, args, 2);
			return eqValue(args.get(0), args.get(1));
		}));
		env.defineFunction(LispNames.EQL, new LispFunction(LispNames.EQL, args -> {
			requireArgCount(LispNames.EQL, args, 2);
			return eqlValue(args.get(0), args.get(1));
		}));
		env.defineFunction(LispNames.EQUAL, new LispFunction(LispNames.EQUAL, args -> {
			requireArgCount(LispNames.EQUAL, args, 2);
			return equalValue(args.get(0), args.get(1));
		}));
	}

	// eq: object identity for aggregates, value for numbers and characters -- the same
	// predicate as eql (LispEquality.eq, .kb/eq-numbers.md).
	private static LispVal eqValue(LispVal a, LispVal b) {
		return isEqStrict(a, b) ? LispTrue.INSTANCE : LispNil.INSTANCE;
	}

	/**
	 * The {@code eq} predicate as a Java boolean, for evaluator internals that compare
	 * with {@code eq} without building a Lisp value -- the {@code catch}/{@code throw}
	 * tag match.
	 * @param a the first value
	 * @param b the second value
	 * @return whether the two values are {@code eq}
	 */
	static boolean isEqStrict(LispVal a, LispVal b) {
		return LispEquality.eq(a, b);
	}

	// eql: like eq, but numbers of the same type and value are eql. Cons cells (and other
	// aggregates) compare by reference identity. The predicate itself lives in the root
	// package next to the structural hash a hash table pairs it with (LispEquality) --
	// an eql table is exactly that pair, and the two may not drift apart.
	private static LispVal eqlValue(LispVal a, LispVal b) {
		return LispEquality.eql(a, b) ? LispTrue.INSTANCE : LispNil.INSTANCE;
	}

	/**
	 * The {@code eql} predicate as a Java boolean, for evaluator internals that compare
	 * two sequence elements under the DEFAULT sequence test without building a Lisp value
	 * ({@link SequenceScanFast}, the native {@code search}/{@code mismatch} arm).
	 * @param a the first value
	 * @param b the second value
	 * @return whether the two values are {@code eql}
	 */
	static boolean isEql(LispVal a, LispVal b) {
		return eqlValue(a, b) instanceof LispTrue;
	}

	// equal: structural equality. Cons cells are compared recursively by car and cdr;
	// everything else falls back to eql (so numbers, symbols, strings, and nil compare by
	// value). The predicate itself lives in the root package next to the structural hash
	// that must agree with it (LispEquality) -- a hash table is exactly that pair, and
	// the two may not drift apart.
	private static LispVal equalValue(LispVal a, LispVal b) {
		return LispEquality.equal(a, b) ? LispTrue.INSTANCE : LispNil.INSTANCE;
	}

	/**
	 * The aggregates {@code eq}/{@code eql} compare by REFERENCE, never by contents --
	 * the single answer every backend shares ({@link LispEquality#isIdentityAggregate}).
	 */
	private static boolean isIdentityAggregate(LispVal v) {
		return LispEquality.isIdentityAggregate(v);
	}

	private static boolean isEq(LispVal a, LispVal b) {
		return LispEquality.eq(a, b);
	}

	/**
	 * Normalizes a sequence argument: a string becomes a list of its characters (indexed
	 * like char/length), anything else is returned unchanged. The sequence functions
	 * accept strings as well as lists (Common Lisp sequences) through this helper.
	 * @param val the sequence argument
	 * @return the value as a list
	 */
	static LispVal seqAsList(LispVal val) {
		if (val instanceof LispString str) {
			// Walk by CODE POINT: a supplementary code point produces one LispChar, not
			// two surrogate halves (which #\? would print). Read the buffer slot by slot
			// rather than through value(), which rebuilds the whole Java String -- the
			// per-access whole-string allocation .kb/string-index-cost.md records.
			LispVal result = LispNil.INSTANCE;
			for (int i = str.codePointCount() - 1; i >= 0; i--) {
				result = new LispCons(new LispChar(str.codePointAt(i)), result);
			}
			return result;
		}
		if (val instanceof LispArray arr && arr.dimensions().length == 1) {
			LispVal result = LispNil.INSTANCE;
			for (int i = arr.effectiveLength() - 1; i >= 0; i--) {
				result = new LispCons(arr.readFlat(i), result);
			}
			return result;
		}
		if (val instanceof LispIntVector iv) {
			LispVal result = LispNil.INSTANCE;
			for (int i = iv.length() - 1; i >= 0; i--) {
				result = new LispCons(new LispInteger(iv.elementAt(i)), result);
			}
			return result;
		}
		// A rank-1 PACKED FLOAT array is a sequence too -- the compile paths reach its
		// elements through (coerce x 'list) and the interpreter used to hand the array
		// itself back here, so every list-walking sequence scan silently saw an EMPTY
		// sequence (ANSI count-if.special-vector.3 counts 0 of the 3 zeros in a #f
		// vector).
		if (val instanceof LispFloatArray packed && packed.rank() == 1) {
			LispVal result = LispNil.INSTANCE;
			for (int i = packed.totalSize() - 1; i >= 0; i--) {
				result = new LispCons(packed.readFlat(i), result);
			}
			return result;
		}
		return val;
	}

	/**
	 * Rebuilds a string from a list of characters when the original sequence argument was
	 * a string; otherwise returns the list unchanged.
	 * @param original the original sequence argument
	 * @param list the list result of the scan over {@link #seqAsList(LispVal)}
	 * @return the result in the original sequence's representation
	 */
	static LispVal seqResult(LispVal original, LispVal list) {
		if (original instanceof LispString) {
			StringBuilder sb = new StringBuilder();
			LispVal cur = list;
			while (cur instanceof LispCons cell) {
				if (!(cell.car() instanceof LispChar c)) {
					throw new LispEvalException(
							"cannot build a string from a non-character element: " + cell.car().print());
				}
				sb.appendCodePoint(c.codePoint());
				cur = cell.cdr();
			}
			return new LispString(sb.toString());
		}
		if (original instanceof LispArray) {
			List<LispVal> flat = new ArrayList<>();
			LispVal cur = list;
			while (cur instanceof LispCons cell) {
				flat.add(cell.car());
				cur = cell.cdr();
			}
			LispVal[] data = flat.toArray(new LispVal[0]);
			return new LispArray(new int[] { data.length }, data);
		}
		if (original instanceof LispFloatArray packed && packed.rank() == 1) {
			// A general vector, on the packed-integer arm's rule below.
			List<LispVal> flat = new ArrayList<>();
			LispVal cur = list;
			while (cur instanceof LispCons cell) {
				flat.add(cell.car());
				cur = cell.cdr();
			}
			return new LispArray(new int[] { flat.size() }, flat.toArray(new LispVal[0]));
		}
		if (original instanceof LispIntVector) {
			// A general vector, NOT a packed rebuild: the compile backends' sequence
			// expansions produce general vectors here, and the cheap consistent contract
			// is "vector in, vector out" with packing preserved only by the dedicated
			// subseq/copy-seq/replace arms (which every backend implements).
			List<LispVal> flat = new ArrayList<>();
			LispVal cur = list;
			while (cur instanceof LispCons cell) {
				flat.add(cell.car());
				cur = cell.cdr();
			}
			LispVal[] data = flat.toArray(new LispVal[0]);
			return new LispArray(new int[] { data.length }, data);
		}
		return list;
	}

	/**
	 * Like {@link #seqResult}, but for a DESTRUCTIVE caller ({@code sort},
	 * {@code stable-sort}, {@code nreverse} -- {@code .todo/623}): a string/array/packed
	 * vector argument is written back into its OWN storage and answered as itself, so it
	 * keeps its fill pointer, its adjustable flag and its identity, instead of losing
	 * them to a fresh rebuild. {@code list} must have exactly as many elements as
	 * {@code original}'s own active length (true for every caller here -- all three
	 * permute {@code original}'s elements, never adding or removing one), so this never
	 * grows or shrinks the backing store; a fill-pointered array with spare capacity
	 * keeps that capacity untouched, matching every implementation that sorts these three
	 * kinds in place. A list argument is unaffected (falls through to {@link #seqResult},
	 * whose list branch already answers a fresh list without touching {@code original}).
	 * @param original the original sequence argument
	 * @param list the list result of the scan over {@link #seqAsList}
	 * @return {@code original}, mutated in place, for a string/array/packed vector;
	 * otherwise the same answer as {@link #seqResult}
	 */
	static LispVal seqResultDestructive(LispVal original, LispVal list) {
		if (original instanceof LispString str) {
			// A source literal is the shared constant the reader built for the program
			// text (.kb/string-write-runtime.md): write to a fresh copy instead of
			// corrupting it, the same latitude replace's target arm takes.
			LispString into = str.sourceLiteral() ? str.copyForBulkWrite() : str;
			LispVal cur = list;
			int i = 0;
			while (cur instanceof LispCons cell) {
				if (!(cell.car() instanceof LispChar c)) {
					throw new LispEvalException(
							"cannot build a string from a non-character element: " + cell.car().print());
				}
				into.setCharAt(i++, c.codePoint());
				cur = cell.cdr();
			}
			return into;
		}
		if (original instanceof LispArray arr && arr.dimensions().length == 1) {
			LispVal cur = list;
			int i = 0;
			while (cur instanceof LispCons cell) {
				arr.writeFlat(i++, cell.car());
				cur = cell.cdr();
			}
			return arr;
		}
		if (original instanceof LispIntVector iv) {
			LispVal cur = list;
			int i = 0;
			while (cur instanceof LispCons cell) {
				iv.setElement(i++, exactIntElement(LispNames.SORT, cell.car()));
				cur = cell.cdr();
			}
			return iv;
		}
		return seqResult(original, list);
	}

	/**
	 * Appends every element of a sequence to {@code out}, in order: a list, a string (by
	 * code point), a general rank-1 array or a rank-1 packed float array -- the same set
	 * the compile paths reach through {@code (coerce x 'list)}.
	 * @param seq the sequence argument
	 * @param out the collector
	 */
	private static void appendSequenceElements(LispVal seq, List<LispVal> out) {
		if (seq instanceof LispFloatArray packed && packed.rank() == 1) {
			for (int i = 0; i < packed.totalSize(); i++) {
				out.add(packed.readFlat(i));
			}
			return;
		}
		if (seq instanceof LispIntVector iv) {
			for (int i = 0; i < iv.length(); i++) {
				out.add(new LispInteger(iv.elementAt(i)));
			}
			return;
		}
		LispVal cur = seqAsList(seq);
		if (!(cur instanceof LispCons) && !(cur instanceof LispNil)) {
			throw new LispEvalException("not a sequence: " + seq.print());
		}
		while (cur instanceof LispCons cell) {
			out.add(cell.car());
			cur = cell.cdr();
		}
	}

	private static void registerSequenceOps(Environment env) {
		env.defineFunction(LispNames.LENGTH, new LispFunction(LispNames.LENGTH, args -> {
			requireArgCount(LispNames.LENGTH, args, 1);
			// length applies to strings and vectors as well as lists (Common Lisp
			// sequences). A rank-2 array is not a sequence, so it is an error.
			if (args.get(0) instanceof LispString str) {
				// codePointCount() -- a supplementary code point counts as one character.
				return new LispInteger(str.codePointCount());
			}
			if (args.get(0) instanceof LispArray array) {
				if (array.dimensions().length != 1) {
					throw new LispEvalException(LispNames.LENGTH + ": argument is not a sequence (rank-"
							+ array.dimensions().length + " array)");
				}
				return new LispInteger(array.effectiveLength());
			}
			if (args.get(0) instanceof LispFloatArray fa) {
				if (fa.rank() != 1) {
					throw new LispEvalException(
							LispNames.LENGTH + ": argument is not a sequence (rank-" + fa.rank() + " array)");
				}
				return new LispInteger(fa.totalSize());
			}
			if (args.get(0) instanceof LispIntVector iv) {
				return new LispInteger(iv.length());
			}
			if (args.get(0) instanceof LispQuantizedMatrix qm) {
				// A rank-1 quantized matrix is a sequence of its dequantized values, as
				// a packed vector is; a rank-2 one is not.
				if (qm.rank() != 1) {
					throw new LispEvalException(
							LispNames.LENGTH + ": argument is not a sequence (rank-" + qm.rank() + " array)");
				}
				return new LispInteger(qm.totalSize());
			}
			if (!(args.get(0) instanceof LispCons) && !(args.get(0) instanceof LispNil)) {
				throw OperandTypeException.of(args.get(0), OperandTypes.Kind.SEQUENCE, LispNames.LENGTH);
			}
			long count = 0;
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell) {
				count++;
				cur = cell.cdr();
			}
			// A dotted list is no proper sequence: its tail is the report's datum.
			requireListEnd(LispNames.LENGTH, cur, OperandTypes.Kind.SEQUENCE);
			return new LispInteger(count);
		}));
		env.defineFunction(LispNames.REVERSE, new LispFunction(LispNames.REVERSE, args -> {
			requireArgCount(LispNames.REVERSE, args, 1);
			LispVal result = LispNil.INSTANCE;
			LispVal cur = seqAsList(args.get(0));
			while (cur instanceof LispCons cell) {
				result = new LispCons(cell.car(), result);
				cur = cell.cdr();
			}
			requireListEnd(LispNames.REVERSE, cur, OperandTypes.Kind.SEQUENCE);
			return seqResult(args.get(0), result);
		}));
		// member is registered in LispEvaluator so the optional :test keyword designator
		// can be applied through the evaluator (it may name a user function or lambda).
		// find (and find-if/-if-not, position and its two) are registered in
		// LispEvaluator so the :test/:test-not/:key keyword designators can be applied
		// through the evaluator; the find family shares the position family's scan.
		// count, remove, delete, substitute and nsubstitute are registered in
		// LispEvaluator too: they take the :test/:test-not/:key designators and CLHS
		// 17.2.1's bounding keywords, which the evaluator's shared scan applies.
		// assoc/rassoc are registered in LispEvaluator: their :test keyword needs
		// `apply`.
		env.defineFunction(LispNames.ACONS, new LispFunction(LispNames.ACONS, args -> {
			requireArgCount(LispNames.ACONS, args, 3);
			return new LispCons(new LispCons(args.get(0), args.get(1)), args.get(2));
		}));
		env.defineFunction(LispNames.PAIRLIS, new LispFunction(LispNames.PAIRLIS, args -> {
			if (args.size() < 2 || args.size() > 3) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.PAIRLIS + " expects 2 or 3 arguments, got " + args.size());
			}
			LispVal keys = args.get(0);
			LispVal data = args.get(1);
			LispVal result = (args.size() == 3) ? args.get(2) : LispNil.INSTANCE;
			// Collect pairs front-to-back, then prepend in reverse to preserve key
			// order.
			List<LispCons> pairs = new ArrayList<>();
			while (keys instanceof LispCons keyCell && data instanceof LispCons dataCell) {
				pairs.add(new LispCons(keyCell.car(), dataCell.car()));
				keys = keyCell.cdr();
				data = dataCell.cdr();
			}
			for (int i = pairs.size() - 1; i >= 0; i--) {
				result = new LispCons(pairs.get(i), result);
			}
			return result;
		}));
		env.defineFunction(LispNames.COPY_ALIST, new LispFunction(LispNames.COPY_ALIST, args -> {
			requireArgCount(LispNames.COPY_ALIST, args, 1);
			// Copy the spine and each (key . value) pair cell; keys and values are
			// shared.
			List<LispVal> elements = new ArrayList<>();
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell) {
				LispVal element = cell.car();
				elements.add((element instanceof LispCons pair) ? new LispCons(pair.car(), pair.cdr()) : element);
				cur = cell.cdr();
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = elements.size() - 1; i >= 0; i--) {
				result = new LispCons(elements.get(i), result);
			}
			return result;
		}));
		env.defineFunction(LispNames.LAST, new LispFunction(LispNames.LAST, args -> {
			requireArgCountBetween(LispNames.LAST, args, 1, 2);
			LispVal cur = requireListArgument(LispNames.LAST, args.get(0));
			if (args.size() == 1) {
				while (cur instanceof LispCons cell && cell.cdr() instanceof LispCons) {
					cur = cell.cdr();
				}
				return cur instanceof LispCons ? cur : LispNil.INSTANCE;
			}
			// (last list n): the last n conses. A lead cursor runs n cells ahead, then
			// both advance until it falls off the end. n beyond the length answers the
			// whole list; n of 0 answers the terminating atom.
			long n = asLong(args.get(1));
			if (n < 0) {
				throw new LispEvalException(LispNames.LAST + ": the count must be a non-negative integer");
			}
			LispVal lead = cur;
			while (n > 0 && lead instanceof LispCons cell) {
				lead = cell.cdr();
				n--;
			}
			while (lead instanceof LispCons cell) {
				lead = cell.cdr();
				cur = ((LispCons) cur).cdr();
			}
			return cur;
		}));
		env.defineFunction(LispNames.BUTLAST, new LispFunction(LispNames.BUTLAST, args -> {
			requireArgCountBetween(LispNames.BUTLAST, args, 1, 2);
			// (butlast list [n]): a copy of the list without its last n CONSES (n
			// defaults to 1, and a dotted list's terminator is not one of them). The
			// conses are counted first rather than trailed by a second cursor, so a
			// count past the end -- ANSI passes most-positive-fixnum + 1 -- decides by
			// subtraction instead of by walking.
			List<LispVal> elements = new java.util.ArrayList<>();
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell) {
				elements.add(cell.car());
				cur = cell.cdr();
			}
			java.math.BigInteger count = args.size() > 1 ? butlastCount(args.get(1)) : java.math.BigInteger.ONE;
			java.math.BigInteger keep = java.math.BigInteger.valueOf(elements.size()).subtract(count);
			LispVal result = LispNil.INSTANCE;
			int kept = keep.signum() <= 0 ? 0 : keep.min(java.math.BigInteger.valueOf(elements.size())).intValueExact();
			for (int i = kept - 1; i >= 0; i--) {
				result = new LispCons(elements.get(i), result);
			}
			return result;
		}));
		env.defineFunction(LispNames.GETF, new LispFunction(LispNames.GETF, args -> {
			requireArgCountBetween(LispNames.GETF, args, 2, 3);
			// (getf plist indicator [default]): the property list is the first argument.
			LispVal cur = args.get(0);
			LispVal key = args.get(1);
			// Walk the property list two cells at a time, returning the value after the
			// first key eql to the indicator.
			while (cur instanceof LispCons cell && cell.cdr() instanceof LispCons valueCell) {
				if (isEq(key, cell.car())) {
					return valueCell.car();
				}
				cur = valueCell.cdr();
			}
			// Absent: the optional default, nil when it was not supplied.
			return (args.size() == 3) ? args.get(2) : LispNil.INSTANCE;
		}));
		// remove-duplicates and delete-duplicates are registered by LispEvaluator
		// (removeDuplicatesValues), not here: they read the whole 17.2.1 keyword set and
		// the :test/:test-not/:key designators are applied through the evaluator.
		env.defineFunction(LispNames.NCONC, new LispFunction(LispNames.NCONC, args -> {
			// Variadic: (nconc) = nil, (nconc x) = x, otherwise destructively link each
			// non-nil list's last cdr to the next argument, returning the first non-nil
			// argument (the last argument may be any object).
			LispVal result = LispNil.INSTANCE;
			LispCons pendingTail = null; // last cons of the accumulated result so far
			for (int i = 0; i < args.size(); i++) {
				LispVal cur = args.get(i);
				boolean last = (i == args.size() - 1);
				if (last) {
					// The final argument is spliced in untouched -- it is never walked,
					// so splicing a list onto itself ((nconc x x), the standard way to
					// build a circular list) terminates instead of chasing the cycle it
					// has just created.
					if (pendingTail != null) {
						pendingTail.setCdr(cur);
					}
					else {
						result = cur;
					}
				}
				else if (cur instanceof LispCons head) {
					// Find the last cons BEFORE linking: linking first would make the
					// walk chase the new tail (and loop forever when it is a cycle).
					LispCons tail = head;
					while (tail.cdr() instanceof LispCons next) {
						tail = next;
					}
					if (pendingTail != null) {
						pendingTail.setCdr(head);
					}
					if (result == LispNil.INSTANCE) {
						result = head;
					}
					pendingTail = tail;
				}
				// non-last nil arguments are skipped
			}
			return result;
		}));
		env.defineFunction(LispNames.IDENTITY, new LispFunction(LispNames.IDENTITY, args -> {
			requireArgCount(LispNames.IDENTITY, args, 1);
			return args.get(0);
		}));
		// gensym: a per-environment counter; the result is an ordinary symbol (rontolisp
		// has no uninterned symbols) whose "#:" prefix keeps it out of the way of
		// user-written names. The compilers require a literal string prefix; here the
		// prefix is any runtime string.
		AtomicLong gensymCounter = new AtomicLong();
		env.defineFunction(LispNames.GENSYM, new LispFunction(LispNames.GENSYM, args -> {
			if (args.size() > 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.GENSYM + " expects at most 1 argument, got " + args.size());
			}
			String prefix = "G";
			if (args.size() == 1) {
				if (!(args.get(0) instanceof LispString s)) {
					throw new LispEvalException(
							LispNames.GENSYM + " prefix must be a string, got " + args.get(0).print());
				}
				prefix = s.value();
			}
			return new LispSymbol("#:" + prefix + gensymCounter.incrementAndGet());
		}));
		// symbol-name returns the CL name: a package qualifier (foo::bar -> "BAR") is
		// where the symbol lives, not part of its name, and a keyword's leading ':'
		// and a gensym's "#:" are markers, not part of the name. princ keeps the
		// qualifier; symbol-name must not (LispSymbol.memberName).
		env.defineFunction(LispNames.SYMBOL_NAME, new LispFunction(LispNames.SYMBOL_NAME, args -> {
			requireArgCount(LispNames.SYMBOL_NAME, args, 1);
			return switch (args.get(0)) {
				case LispSymbol sym -> LispString.symbolName(LispSymbol.memberName(sym.name()));
				// nil and t are the SYMBOLS NIL and T, so they coerce like any other
				// symbol -- upcase-canonical, matching CL and the three compile backends
				// (the interpreter used to answer "t"/"nil").
				case LispTrue ignored -> LispString.symbolName("T");
				case LispNil ignored -> LispString.symbolName("NIL");
				default -> throw new LispEvalException(
						LispNames.SYMBOL_NAME + " expects a symbol, got " + args.get(0).print());
			};
		}));
		// string: the CL string-designator coercion. A string is returned unchanged, a
		// symbol yields its name (the same spelling princ prints), a character yields a
		// one-character string. t/nil coerce like symbols ("t"/"nil").
		env.defineFunction(LispNames.STRING, new LispFunction(LispNames.STRING, args -> {
			requireArgCount(LispNames.STRING, args, 1);
			return switch (args.get(0)) {
				case LispString s -> s;
				// Coercing a symbol yields its name; a keyword's ':' and a gensym's
				// "#:" are markers, and a package qualifier is not part of the name,
				// so (string :html) is "html" and (string 'pkg::sym) is "sym"
				// (matches CL, and is what cl-who's maybe-downcase relies on to emit
				// <html> not <:html>). Same spelling as symbol-name.
				case LispSymbol sym -> LispString.symbolName(LispSymbol.memberName(sym.name()));
				case LispChar c -> new LispString(new String(Character.toChars(c.codePoint())));
				// nil and t are the SYMBOLS NIL and T: they coerce upcase-canonical like
				// any other symbol, matching CL and the three compile backends (the
				// interpreter used to answer "t"/"nil").
				case LispTrue ignored -> LispString.symbolName("T");
				case LispNil ignored -> LispString.symbolName("NIL");
				// Same wording the compile backends' guarded coercion signals with
				// (LispMacroExpander.strictStringDesignatorForm), so a non-designator
				// reads the same on all four.
				default -> throw new LispEvalException(
						LispNames.STRING + " expects a string designator, got: " + args.get(0).print());
			};
		}));
		// %normalize-string: the transport boundary's explicit render. The interpreter
		// has no framed-vs-charvec distinction, so there is nothing to normalize and
		// the argument answers itself; the compile backends route a mutable character
		// vector through their normalizer and pass anything else through unchanged.
		// Strings only; identity and mutability of the result are unspecified.
		env.defineFunction(LispNames.NORMALIZE_STRING, new LispFunction(LispNames.NORMALIZE_STRING, args -> {
			requireArgCount(LispNames.NORMALIZE_STRING, args, 1);
			return args.get(0);
		}));
		// make-symbol: rontolisp has no intern table (symbols compare by name), so
		// "uninterned" is represented by the same "#:" name prefix gensym uses.
		env.defineFunction(LispNames.MAKE_SYMBOL, new LispFunction(LispNames.MAKE_SYMBOL, args -> {
			requireArgCount(LispNames.MAKE_SYMBOL, args, 1);
			return new LispSymbol("#:" + requireString(LispNames.MAKE_SYMBOL, args.get(0)));
		}));
		// intern: symbols compare by name, so interning is just symbol construction. The
		// name is used verbatim (no case folding, the current package is ignored); a
		// package argument is an error until packages exist at runtime.
		env.defineFunction(LispNames.INTERN, new LispFunction(LispNames.INTERN, args -> {
			if (args.size() == 2) {
				// (intern name :keyword) builds a keyword symbol (name prefixed with
				// ':');
				// any other package argument stays unsupported (no runtime intern table).
				if (LispMacroExpander.isKeywordPackageDesignator(args.get(1))) {
					return new LispSymbol(":" + requireString(LispNames.INTERN, args.get(0)));
				}
				throw new LispEvalException(LispNames.INTERN + " with a non-keyword package argument is not supported");
			}
			requireArgCount(LispNames.INTERN, args, 1);
			return new LispSymbol(requireString(LispNames.INTERN, args.get(0)));
		}));
		// find-symbol: the resolver-less fallback, like intern above -- the
		// LispEvaluator registration (registry-backed, package-aware) replaces it. It
		// exists so the #'find-symbol wrapper's name is a Java-backed builtin on a bare
		// Environment too (the ShadowedBuiltins parity pin): known = a cl symbol, a
		// keyword, or a global function/variable binding under the verbatim name.
		env.defineFunction(LispNames.FIND_SYMBOL, new LispFunction(LispNames.FIND_SYMBOL, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.FIND_SYMBOL + " expects 1 or 2 arguments, got " + args.size());
			}
			String name = requireString(LispNames.FIND_SYMBOL, args.get(0));
			boolean known = PackageRegistry.isClSymbol(name) || (!name.isEmpty() && name.charAt(0) == ':')
					|| env.lookupFunctionOrNull(name) != null || env.hasBinding(name);
			return known ? new LispSymbol(name) : LispNil.INSTANCE;
		}));
		// The status second value, on the same resolver-less terms as find-symbol above:
		// nil for exactly the names that one answers nil for.
		env.defineFunction(LispNames.FIND_SYMBOL_STATUS, new LispFunction(LispNames.FIND_SYMBOL_STATUS, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.FIND_SYMBOL + " expects 1 or 2 arguments, got " + args.size());
			}
			String name = requireString(LispNames.FIND_SYMBOL, args.get(0));
			if (!name.isEmpty() && name.charAt(0) == ':') {
				return new LispSymbol(LispNames.STATUS_EXTERNAL);
			}
			if (PackageRegistry.isClSymbol(name)) {
				return new LispSymbol(name.startsWith("%") ? LispNames.STATUS_INTERNAL : LispNames.STATUS_EXTERNAL);
			}
			return env.lookupFunctionOrNull(name) != null || env.hasBinding(name)
					? new LispSymbol(LispNames.STATUS_INTERNAL) : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.COPY_LIST, new LispFunction(LispNames.COPY_LIST, args -> {
			requireArgCount(LispNames.COPY_LIST, args, 1);
			LispVal list = requireListArgument(LispNames.COPY_LIST, args.get(0));
			// A fresh spine whose last cdr is the argument's final atom: a dotted list
			// copies dotted (CLHS copy-list), as the compile paths' %copy-list-runtime.
			List<LispVal> elements = new java.util.ArrayList<>();
			LispVal cur = list;
			while (cur instanceof LispCons cell) {
				elements.add(cell.car());
				cur = cell.cdr();
			}
			LispVal result = cur;
			for (int i = elements.size() - 1; i >= 0; i--) {
				result = new LispCons(elements.get(i), result);
			}
			return result;
		}));
		// (copy-structure s): CLHS 18.3, the generic counterpart of the per-type
		// `copy-<name>` copier `defstruct` generates -- a fresh instance of the same
		// layout, sharing the slot VALUES (a shallow copy). Unlike the generated
		// copier, the argument's type is not known until run time, so this cannot
		// expand into a literal-tag `%obj-new` call the way `copy-<name>` does; it
		// goes straight through `LispInstance.shallowCopy()` instead.
		env.defineFunction(LispNames.COPY_STRUCTURE, new LispFunction(LispNames.COPY_STRUCTURE, args -> {
			requireArgCount(LispNames.COPY_STRUCTURE, args, 1);
			if (!(args.get(0) instanceof LispInstance inst)) {
				throw LispEvalException.ofClass(ClosRegistry.TYPE_ERROR_CLASS_NAME,
						"The value " + args.get(0).print() + " is not of type STRUCTURE-OBJECT");
			}
			return inst.shallowCopy();
		}));
		env.defineFunction(LispNames.NREVERSE, new LispFunction(LispNames.NREVERSE, args -> {
			requireArgCount(LispNames.NREVERSE, args, 1);
			// Destructive: rewire each cdr to its predecessor and return the former last
			// cell as the new head (Common Lisp semantics; use the return value). A
			// string/vector argument is not a cons chain -- it reverses via a coerced
			// list (seqAsList, the sort/reduce precedent) and is written back into its
			// own storage (seqResultDestructive, .todo/623), keeping its fill pointer,
			// adjustable flag and identity, like sort/stable-sort above.
			LispVal original = args.get(0);
			boolean isSeq = !(original instanceof LispCons) && !(original instanceof LispNil);
			LispVal prev = LispNil.INSTANCE;
			LispVal cur = isSeq ? seqAsList(original) : original;
			while (cur instanceof LispCons cell) {
				LispVal next = cell.cdr();
				cell.setCdr(prev);
				prev = cell;
				cur = next;
			}
			requireListEnd(LispNames.NREVERSE, cur, OperandTypes.Kind.SEQUENCE);
			return isSeq ? seqResultDestructive(original, prev) : prev;
		}));
		env.defineFunction(LispNames.MAKE_LIST, new LispFunction(LispNames.MAKE_LIST, args -> {
			requireMinArgCount(LispNames.MAKE_LIST, args, 1);
			// (make-list n &key initial-element): n cells sharing the ONE element value
			// (nil by default). quri's ip-addr= pads an abbreviated IPv6 address with
			// (make-list (- 9 len) :initial-element 0).
			long n = asLong(args.get(0));
			LispVal element = LispNil.INSTANCE;
			if ((args.size() - 1) % 2 != 0) {
				throw new LispEvalException(LispNames.MAKE_LIST + " expects (size &key initial-element)");
			}
			for (int i = 1; i + 1 < args.size(); i += 2) {
				if (!(args.get(i) instanceof LispSymbol key) || !LispNames.INITIAL_ELEMENT_KEYWORD.equals(key.name())) {
					throw new LispEvalException(LispNames.MAKE_LIST + ": unsupported keyword " + args.get(i).print()
							+ " (only :initial-element)");
				}
				element = args.get(i + 1);
			}
			LispVal result = LispNil.INSTANCE;
			for (long i = 0; i < n; i++) {
				result = new LispCons(element, result);
			}
			return result;
		}));
		// union / intersection / set-difference / adjoin / subsetp are NOT registered
		// here: their first-class values live in LispEvaluator beside the position and
		// sequence-scan families, because the :test / :test-not / :key designators have
		// to be APPLIED through the evaluator -- an eql-only fallback here answered
		// "expects 2 arguments" to every (apply #'set-difference x y :test f).
	}

	// butlast's optional count, widened to BigInteger: it is compared against a list
	// length and is allowed to exceed the fixnum range (most-positive-fixnum + 1 is one
	// of the counts CL has to answer nil for).
	private static java.math.BigInteger butlastCount(LispVal value) {
		return switch (value) {
			case LispInteger integer -> java.math.BigInteger.valueOf(integer.value());
			case LispBigInteger big -> big.value();
			default ->
				throw new LispEvalException(LispNames.BUTLAST + " expects an integer count, got: " + value.print());
		};
	}

	private static void registerStringOps(Environment env) {
		env.defineFunction(LispNames.STRING_UPCASE, new LispFunction(LispNames.STRING_UPCASE, args -> {
			requireArgCount(LispNames.STRING_UPCASE, args, 1);
			return new LispString(caseFoldString(stringDesignator(LispNames.STRING_UPCASE, args.get(0)), true));
		}));
		env.defineFunction(LispNames.STRING_DOWNCASE, new LispFunction(LispNames.STRING_DOWNCASE, args -> {
			requireArgCount(LispNames.STRING_DOWNCASE, args, 1);
			return new LispString(caseFoldString(stringDesignator(LispNames.STRING_DOWNCASE, args.get(0)), false));
		}));
		env.defineFunction(LispNames.STRING_CAPITALIZE, new LispFunction(LispNames.STRING_CAPITALIZE, args -> {
			requireArgCount(LispNames.STRING_CAPITALIZE, args, 1);
			return new LispString(capitalizeString(stringDesignator(LispNames.STRING_CAPITALIZE, args.get(0))));
		}));
		// subseq: strings and lists. (seq start [end]); end defaults to the sequence
		// length.
		env.defineFunction(LispNames.SUBSEQ, new LispFunction(LispNames.SUBSEQ, args -> {
			requireMinArgCount(LispNames.SUBSEQ, args, 2);
			if (args.size() > 3) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SUBSEQ + " expects 2 or 3 arguments, got " + args.size());
			}
			LispVal endArg = (args.size() == 3 && !(args.get(2) instanceof LispNil)) ? args.get(2) : null;
			int start = requireIndex(LispNames.SUBSEQ, args.get(1));
			if (args.get(0) instanceof LispString str) {
				// Bounds are CHARACTER positions (code points), not UTF-16 code units, so
				// a non-BMP glyph still counts as one index step. The slice is copied
				// straight out of the code-point buffer: rendering the string through
				// value() first cost O(length) per call whatever the slice's width, which
				// made a left-to-right parse that cuts one piece per token out of a long
				// document quadratic (.kb/string-index-cost.md).
				int cpLen = str.length();
				int end = (endArg != null) ? requireIndex(LispNames.SUBSEQ, endArg) : cpLen;
				if (start < 0 || end > cpLen || start > end) {
					throw new LispEvalException(LispNames.SUBSEQ + ": invalid bounds " + start + ", " + end
							+ " for string of length " + cpLen);
				}
				return str.subsequence(start, end);
			}
			if (args.get(0) instanceof LispCons || args.get(0) instanceof LispNil) {
				List<LispVal> elements = new ArrayList<>();
				LispVal cur = args.get(0);
				while (cur instanceof LispCons cell) {
					elements.add(cell.car());
					cur = cell.cdr();
				}
				int end = (endArg != null) ? requireIndex(LispNames.SUBSEQ, endArg) : elements.size();
				if (start < 0 || end > elements.size() || start > end) {
					throw new LispEvalException(LispNames.SUBSEQ + ": invalid bounds " + start + ", " + end
							+ " for list of length " + elements.size());
				}
				LispVal result = LispNil.INSTANCE;
				for (int i = end - 1; i >= start; i--) {
					result = new LispCons(elements.get(i), result);
				}
				return result;
			}
			if (args.get(0) instanceof LispArray arr && arr.dimensions().length == 1) {
				// A general 1-D array: return a fresh vector of the same element type.
				// uax-15's canonical-ordering does (setf (subseq vec beg end) ...) on a
				// unicode-string, so subseq must round-trip through the vector shape.
				// When the array REMEMBERS a packed width (a fill-pointer / adjustable
				// packed vector, which the general boxed representation is the only one
				// that can carry) the copy stays packed too -- CLHS says subseq answers a
				// sequence of the same kind, and the simple (non-adjustable) case already
				// does this via the LispIntVector/LispFloatArray arms below, so the
				// adjustable case must match rather than silently degrading to a
				// simple-vector (.todo/698).
				int len = arr.effectiveLength();
				int end = (endArg != null) ? requireIndex(LispNames.SUBSEQ, endArg) : len;
				if (start < 0 || end > len || start > end) {
					throw new LispEvalException(LispNames.SUBSEQ + ": invalid bounds " + start + ", " + end
							+ " for vector of length " + len);
				}
				LispVal[] copy = new LispVal[end - start];
				for (int i = start; i < end; i++) {
					copy[i - start] = arr.readFlat(i);
				}
				LispVal packed = packedCopyForElementType(LispNames.SUBSEQ, arr.elementTypeCode(), copy);
				if (packed != null) {
					return packed;
				}
				// A bit-vector subsequence stays a bit vector (CLHS): the stamp rides
				// the copy the way adjust-array carries it (.todo/820).
				if (arr.elementTypeCode() == am.ik.rontolisp.ArrayElementTypes.BIT) {
					return new LispArray(new int[] { copy.length }, copy, -1, false,
							am.ik.rontolisp.ArrayElementTypes.BIT);
				}
				return new LispArray(new int[] { copy.length }, copy);
			}
			if (args.get(0) instanceof LispIntVector iv) {
				// Type-preserving: a subsequence of a packed integer vector stays packed
				// at the same width (ironclad's pbkdf1 subseqs its byte-vector key).
				int len = iv.length();
				int end = (endArg != null) ? requireIndex(LispNames.SUBSEQ, endArg) : len;
				if (start < 0 || end > len || start > end) {
					throw new LispEvalException(LispNames.SUBSEQ + ": invalid bounds " + start + ", " + end
							+ " for vector of length " + len);
				}
				long[] copy = new long[end - start];
				System.arraycopy(iv.data(), start, copy, 0, copy.length);
				return new LispIntVector(iv.width(), copy);
			}
			if (args.get(0) instanceof LispFloatArray fa && fa.rank() == 1) {
				// Type-preserving, the packed-float twin of the LispIntVector arm above:
				// a subsequence of a packed float array (single/double/bfloat16) stays
				// packed at the same width instead of falling through to the "expects a
				// string, list, or vector" refusal below, which is what every packed
				// float subseq did before .todo/698 (crashing on the interpreter; the
				// compile paths silently degraded to a general boxed vector instead).
				int len = fa.totalSize();
				int end = (endArg != null) ? requireIndex(LispNames.SUBSEQ, endArg) : len;
				if (start < 0 || end > len || start > end) {
					throw new LispEvalException(LispNames.SUBSEQ + ": invalid bounds " + start + ", " + end
							+ " for vector of length " + len);
				}
				List<LispVal> elements = new ArrayList<>(end - start);
				for (int i = start; i < end; i++) {
					elements.add(fa.readFlat(i));
				}
				return packedFloatVector(LispNames.SUBSEQ, fa, elements);
			}
			throw new LispEvalException(
					LispNames.SUBSEQ + " expects a string, list, or vector, got: " + args.get(0).print());
		}));
		// copy-seq is (subseq seq 0): a fresh copy of a string or list. The call
		// position expands to exactly that; this registration covers first-class use.
		env.defineFunction(LispNames.COPY_SEQ, new LispFunction(LispNames.COPY_SEQ, args -> {
			requireArgCount(LispNames.COPY_SEQ, args, 1);
			if (args.get(0) instanceof LispString str) {
				return new LispString(str.value());
			}
			if (args.get(0) instanceof LispIntVector iv) {
				return new LispIntVector(iv.width(), iv.data().clone());
			}
			if (args.get(0) instanceof LispCons || args.get(0) instanceof LispNil) {
				List<LispVal> elements = new ArrayList<>();
				LispVal cur = args.get(0);
				while (cur instanceof LispCons cell) {
					elements.add(cell.car());
					cur = cell.cdr();
				}
				LispVal result = LispNil.INSTANCE;
				for (int i = elements.size() - 1; i >= 0; i--) {
					result = new LispCons(elements.get(i), result);
				}
				return result;
			}
			throw new LispEvalException(LispNames.COPY_SEQ + " expects a string or list, got: " + args.get(0).print());
		}));
		env.defineFunction(LispNames.STRING_EQ, new LispFunction(LispNames.STRING_EQ, args -> {
			String a = boundedStringArg(LispNames.STRING_EQ, args, 0);
			String b = boundedStringArg(LispNames.STRING_EQ, args, 1);
			return a.equals(b) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.STRING_EQUAL, new LispFunction(LispNames.STRING_EQUAL, args -> {
			String a = boundedStringArg(LispNames.STRING_EQUAL, args, 0);
			String b = boundedStringArg(LispNames.STRING_EQUAL, args, 1);
			return a.equalsIgnoreCase(b) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.STRING_TRIM, new LispFunction(LispNames.STRING_TRIM, args -> {
			requireArgCount(LispNames.STRING_TRIM, args, 2);
			return new LispString(trimString(LispNames.STRING_TRIM, args.get(0), args.get(1), true, true));
		}));
		env.defineFunction(LispNames.STRING_LEFT_TRIM, new LispFunction(LispNames.STRING_LEFT_TRIM, args -> {
			requireArgCount(LispNames.STRING_LEFT_TRIM, args, 2);
			return new LispString(trimString(LispNames.STRING_LEFT_TRIM, args.get(0), args.get(1), true, false));
		}));
		env.defineFunction(LispNames.STRING_RIGHT_TRIM, new LispFunction(LispNames.STRING_RIGHT_TRIM, args -> {
			requireArgCount(LispNames.STRING_RIGHT_TRIM, args, 2);
			return new LispString(trimString(LispNames.STRING_RIGHT_TRIM, args.get(0), args.get(1), false, true));
		}));
	}

	private static String requireString(String name, LispVal val) {
		if (val instanceof LispString str) {
			return str.value();
		}
		throw new LispEvalException(name + " expects a string, got: " + val.print());
	}

	/**
	 * Returns the length of a sequence (list, string, or general 1-D array). The general
	 * {@code replace} needs it to bound the source or target when {@code :end1}/
	 * {@code :end2} is omitted.
	 */
	private static int sequenceLength(String name, LispVal val) {
		if (val instanceof LispString str) {
			return str.codePointCount();
		}
		if (val instanceof LispArray arr && arr.dimensions().length == 1) {
			return arr.effectiveLength();
		}
		if (val instanceof LispIntVector iv) {
			return iv.length();
		}
		if (val instanceof LispNil) {
			return 0;
		}
		if (val instanceof LispCons) {
			int n = 0;
			LispVal cur = val;
			while (cur instanceof LispCons cell) {
				n++;
				cur = cell.cdr();
			}
			return n;
		}
		throw new LispEvalException(name + ": expected a sequence, got: " + val.print());
	}

	/**
	 * Reads the {@code index}-th element of a sequence (list, string, or general 1-D
	 * array). Bounds are assumed to be checked already by the caller ({@code replace}).
	 */
	private static LispVal sequenceRef(LispVal val, int index) {
		if (val instanceof LispString str) {
			// Index by CODE POINT: a supplementary character is one indexed element, not
			// two surrogate halves. Matches the LENGTH/CHAR contract everywhere else.
			// One slot read, like charRef: rebuilding the Java String and walking to the
			// index made `replace` over a string quadratic, with a whole-string
			// allocation per element (.kb/string-index-cost.md).
			return new LispChar(str.codePointAt(index));
		}
		if (val instanceof LispArray arr) {
			return arr.readFlat(index);
		}
		if (val instanceof LispIntVector iv) {
			return new LispInteger(iv.elementAt(index));
		}
		LispVal cur = val;
		int i = 0;
		while (cur instanceof LispCons cell) {
			if (i == index) {
				return cell.car();
			}
			cur = cell.cdr();
			i++;
		}
		throw new LispEvalException("sequence-ref: index " + index + " out of range for " + val.print());
	}

	/**
	 * A forward reader over a {@code replace} SOURCE sequence. Every representation but a
	 * list is read by {@link #sequenceRef} exactly as before -- a slot read either way --
	 * while a LIST is walked with a cons cursor instead of re-indexed from the head per
	 * element, which is what made the element loop quadratic: 10.6 ms for a 4,000-element
	 * list source against 0.013 ms for the same call compiled, which is the wrong way
	 * round. The cursor is monotonic and re-seeds if a caller ever reads backwards, so it
	 * answers the same element for the same index however it is driven.
	 * <p>
	 * It also raises the SAME {@code sequence-ref: index N out of range} error at the
	 * same element, rather than stopping silently when the list runs out. That error is
	 * the one surviving three-way disagreement with the compile paths, which truncate
	 * (see {@code .kb/sequence-op-runtimes.md}); erasing it here would be a behavior
	 * change hiding inside a performance fix.
	 */
	private static final class SequenceSourceCursor {

		private final LispVal source;

		// null exactly when the source is not a list: then every read is a sequenceRef.
		private @Nullable LispVal cursor;

		private int at;

		private SequenceSourceCursor(LispVal source) {
			this.source = source;
			this.cursor = (source instanceof LispCons || source instanceof LispNil) ? source : null;
		}

		private LispVal read(int index) {
			if (this.cursor == null) {
				return sequenceRef(this.source, index);
			}
			if (index < this.at) {
				this.cursor = this.source;
				this.at = 0;
			}
			while (this.at < index && this.cursor instanceof LispCons cell) {
				this.cursor = cell.cdr();
				this.at++;
			}
			if (this.at == index && this.cursor instanceof LispCons cell) {
				return cell.car();
			}
			throw new LispEvalException("sequence-ref: index " + index + " out of range for " + this.source.print());
		}

	}

	// Coerces a Common Lisp string designator (a string, a symbol, or a character) to a
	// String, dropping a keyword's leading package colon like (string ...) does. Used by
	// the string comparison functions (string=/string-equal), which accept designators --
	// cl-who compares tags (keywords) against the empty-tag list with #'string-equal.
	private static String stringDesignator(String name, LispVal val) {
		return switch (val) {
			case LispString str -> str.value();
			// The symbol-name spelling: markers and package qualifiers are not part
			// of the name.
			case LispSymbol sym -> LispSymbol.memberName(sym.name());
			case LispChar c -> new String(Character.toChars(c.codePoint()));
			// nil and t are SYMBOLS, so they designate strings like any other -- the same
			// spelling the (string ...) builtin above gives them. quri's
			// scheme-constructor asks (string= scheme "http") of a relative reference,
			// whose scheme is nil, and CL answers false rather than signalling.
			case LispNil ignored -> "NIL";
			case LispTrue ignored -> "T";
			default -> throw new LispEvalException(name + " expects a string designator, got: " + val.print());
		};
	}

	// One side of a (string= s1 s2 &key start1 end1 start2 end2) call -- which is also
	// the string-equal lambda list -- as the substring the bounding indices designate.
	// Parsed here so the interpreter keeps the direct Java String comparison; the compile
	// paths lower the same call shape onto subseq
	// (LispMacroExpander.expandStringComparisonBounds), and the ordering predicates
	// (string</string-lessp/...) take their bounds through the shared %string-compare
	// walk. Indices are CHARACTER positions (code points), like subseq's.
	private static String boundedStringArg(String name, List<LispVal> args, int which) {
		requireMinArgCount(name, args, 2);
		String s = stringDesignator(name, args.get(which));
		int cpLen = s.codePointCount(0, s.length());
		int start = 0;
		int end = cpLen;
		String startKey = (which == 0) ? LispNames.START1_KEYWORD : LispNames.START2_KEYWORD;
		String endKey = (which == 0) ? LispNames.END1_KEYWORD : LispNames.END2_KEYWORD;
		for (int i = 2; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol key && !(args.get(i + 1) instanceof LispNil)) {
				// A nil bound keeps its default (nil :end = the string's length, as in
				// CL). The two keywords addressing the OTHER argument are this call's
				// business too, so they are accepted and skipped rather than rejected.
				if (startKey.equals(key.name())) {
					start = requireIndex(name, args.get(i + 1));
				}
				else if (endKey.equals(key.name())) {
					end = requireIndex(name, args.get(i + 1));
				}
				else if (!LispNames.START1_KEYWORD.equals(key.name()) && !LispNames.END1_KEYWORD.equals(key.name())
						&& !LispNames.START2_KEYWORD.equals(key.name()) && !LispNames.END2_KEYWORD.equals(key.name())) {
					throw new LispEvalException(name + ": unsupported keyword " + key.name());
				}
			}
		}
		if (start < 0 || end > cpLen || start > end) {
			throw new LispEvalException(
					name + ": invalid bounds " + start + ", " + end + " for string of length " + cpLen);
		}
		if (start == 0 && end == cpLen) {
			return s;
		}
		return s.substring(s.offsetByCodePoints(0, start), s.offsetByCodePoints(0, end));
	}

	/**
	 * A {@code char}/{@code schar} subscript: one that is no integer is the operator's
	 * {@code INTEGER} type-error. A bignum is an integer, out of any string's bounds.
	 */
	private static int requireStringIndex(String operator, LispVal val) {
		return requireStringIndex(operator, operator, val);
	}

	/**
	 * A string subscript under {@code operator}, unnamed when it is null; a bignum's
	 * out-of-range report names {@code fallback}.
	 */
	private static int requireStringIndex(@Nullable String operator, String fallback, LispVal val) {
		if (val instanceof LispInteger i) {
			return (int) i.value();
		}
		if (val instanceof LispBigInteger) {
			return requireIndex(operator == null ? fallback : operator, val);
		}
		throw operator == null ? OperandTypeException.of(val, OperandTypes.Kind.INTEGER)
				: OperandTypeException.of(val, OperandTypes.Kind.INTEGER, operator);
	}

	/**
	 * A list argument: nil or a cons answers itself, anything else is the operator's
	 * {@code LIST} type-error.
	 * @param operator the operator's symbol name
	 * @param val the argument
	 * @return the argument
	 */
	static LispVal requireListArgument(String operator, LispVal val) {
		if (val instanceof LispCons || val instanceof LispNil) {
			return val;
		}
		throw OperandTypeException.of(val, OperandTypes.Kind.LIST, operator);
	}

	/**
	 * The end of a list walk: nil passes, anything else -- the argument itself when it
	 * was no list, a dotted list's tail -- is the operator's type-error.
	 * @param operator the operator's symbol name
	 * @param end the value the walk stopped at
	 * @param kind the type a report names for an operator without a fixed one
	 */
	static void requireListEnd(String operator, LispVal end, OperandTypes.Kind kind) {
		if (!(end instanceof LispNil)) {
			throw OperandTypeException.of(end, kind, operator);
		}
	}

	static int requireIndex(String name, LispVal val) {
		if (val instanceof LispInteger i) {
			return (int) i.value();
		}
		throw new LispEvalException(name + " expects an integer index, got: " + val.print());
	}

	// Applies char-upcase (or char-downcase) to EVERY character, which is how CLHS
	// defines string-upcase / string-downcase. Deliberately not String.toUpperCase: the
	// String overload applies multi-character special casing (sharp s -> "SS") and the
	// context-sensitive Greek final-sigma rule, both of which would change the character
	// count and diverge from char-upcase applied per character.
	private static String caseFoldString(String s, boolean upcase) {
		StringBuilder sb = new StringBuilder(s.length());
		int i = 0;
		while (i < s.length()) {
			int cp = s.codePointAt(i);
			sb.appendCodePoint(upcase ? Character.toUpperCase(cp) : Character.toLowerCase(cp));
			i += Character.charCount(cp);
		}
		return sb.toString();
	}

	// Capitalizes the first letter of each alphanumeric word and lowercases the rest,
	// matching Common Lisp string-capitalize. Walks by CODE POINT so a Latin-1 supplement
	// letter or an astral cased letter is treated as a single word character.
	private static String capitalizeString(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		boolean atWordStart = true;
		int i = 0;
		while (i < s.length()) {
			int cp = s.codePointAt(i);
			if (Character.isLetterOrDigit(cp)) {
				sb.appendCodePoint(atWordStart ? Character.toUpperCase(cp) : Character.toLowerCase(cp));
				atWordStart = false;
			}
			else {
				sb.appendCodePoint(cp);
				atWordStart = true;
			}
			i += Character.charCount(cp);
		}
		return sb.toString();
	}

	// The characters of a trim CHARACTER BAG as a string. CL allows ANY sequence of
	// characters: a LIST bag is what libraries write (postmodern's execute-file lexer
	// trims with '(#\Space #\Tab)), and a general VECTOR of characters is a sequence
	// too -- the compile paths reach both through (coerce bag 'string) in
	// LispMacroExpander.normalizeCharBag, so rejecting the vector here was an
	// interpreter-only refusal. What stays a type error on every backend is a bag that is
	// no sequence at all, a lone CHARACTER above all: (string-trim #\* "*x*") signals in
	// CL rather than trimming asterisks.
	private static String charBagString(String name, LispVal bagVal) {
		if (bagVal instanceof LispString s) {
			return s.value();
		}
		if (bagVal instanceof LispNil) {
			return "";
		}
		StringBuilder chars = new StringBuilder();
		if (bagVal instanceof LispArray arr && arr.dimensions().length == 1) {
			int n = arr.effectiveLength();
			for (int i = 0; i < n; i++) {
				if (!(arr.readFlat(i) instanceof LispChar ch)) {
					throw new LispEvalException(charBagTypeError(name, bagVal));
				}
				chars.appendCodePoint(ch.codePoint());
			}
			return chars.toString();
		}
		LispVal cur = bagVal;
		while (cur instanceof LispCons cell) {
			if (!(cell.car() instanceof LispChar ch)) {
				throw new LispEvalException(charBagTypeError(name, bagVal));
			}
			chars.appendCodePoint(ch.codePoint());
			cur = cell.cdr();
		}
		if (!(cur instanceof LispNil)) {
			throw new LispEvalException(charBagTypeError(name, bagVal));
		}
		return chars.toString();
	}

	private static String charBagTypeError(String name, LispVal bagVal) {
		return name + " expects a sequence of characters as the bag, got: " + bagVal.print();
	}

	// Removes characters that appear in the bag string from the requested ends. Walks by
	// CODE POINT so a supplementary character is one indexed step and its surrogate
	// halves
	// are not compared against the bag as individual characters.
	private static String trimString(String name, LispVal bagVal, LispVal strVal, boolean left, boolean right) {
		String bag = charBagString(name, bagVal);
		// CL specifies the trimmed value as a string DESIGNATOR -- (string-trim "*"
		// '*foo*) is "FOO". The BAG above is not one: it is a SEQUENCE of characters, so
		// a lone character there stays a type error (SBCL signals for (string-trim #\*
		// "*x*")). The compile paths widen the same one position through
		// LispMacroExpander.normalizeStringTrimArgs.
		String s = stringDesignator(name, strVal);
		int start = 0;
		int end = s.length();
		if (left) {
			while (start < end) {
				int cp = s.codePointAt(start);
				if (!containsCodePoint(bag, cp)) {
					break;
				}
				start += Character.charCount(cp);
			}
		}
		if (right) {
			while (end > start) {
				int cp = s.codePointBefore(end);
				if (!containsCodePoint(bag, cp)) {
					break;
				}
				end -= Character.charCount(cp);
			}
		}
		return s.substring(start, end);
	}

	// Whether bag contains the given code point, walked as a single indexed step (so a
	// supplementary code point in the bag is one match, not two surrogate halves).
	private static boolean containsCodePoint(String bag, int cp) {
		int i = 0;
		while (i < bag.length()) {
			int bcp = bag.codePointAt(i);
			if (bcp == cp) {
				return true;
			}
			i += Character.charCount(bcp);
		}
		return false;
	}

	private static void registerIO(Environment env, PrintStream out, InputStream in) {
		BufferedReader stdinReader = new BufferedReader(new InputStreamReader(in));
		env.replInputReader = stdinReader;
		// Tracks whether standard output is at the beginning of a line, so fresh-line
		// (~&) can emit a newline only when needed.
		boolean[] atLineStart = { true };
		java.util.function.Consumer<String> emit = text -> {
			if (text.isEmpty()) {
				return;
			}
			out.print(text);
			atLineStart[0] = text.charAt(text.length() - 1) == '\n';
		};
		// File streams opened by open/with-open-file (plus the string streams of
		// with-output-to-string/with-input-from-string): an integer handle indexes this
		// table, matching the compiled backends (JVM: a static stream table; WASM: the
		// WASI file descriptor, negative for string streams). Declared before the print
		// family so their optional stream argument can route into it.
		//
		// CONCURRENT: http-handler serves one virtual thread per request, so several
		// requests allocate handles at the same time. A plain map plus a `long[]`
		// counter handed two of them the SAME handle -- one stream was dropped and the
		// two conversations crossed on the survivor. The table and the
		// counter must therefore stay thread-safe; see .kb/tcp-sockets.md.
		Map<Long, Closeable> streams = new ConcurrentHashMap<>();
		// The namestring each FILE stream was opened on, keyed by the same handle. It is
		// what file-length stats: a Reader/Writer does not remember where it came from,
		// and re-deriving it is impossible. Only `open` fills it, so every other stream
		// kind (string streams, sockets, the standard streams) is simply absent here and
		// file-length answers nil for it. Concurrent for the same reason the table above
		// is (one virtual thread per served request).
		Map<Long, String> streamPaths = new ConcurrentHashMap<>();
		// The byte position of each BINARY file stream, keyed by the same handle. Only
		// `open` fills a path (below), so only a binary file stream has an entry here,
		// and it is what file-position answers and repositions
		// (.kb/read-load-streams.md):
		// the query is the CLOSEABLE caller's offset and the set reopens the file at it.
		// Concurrent for the same reason the table above is (one virtual thread per
		// served request).
		Map<Long, Long> streamPositions = new ConcurrentHashMap<>();
		// The element type of each BINARY file stream, keyed by the same handle: what
		// read-byte / write-byte move per element (octets, sign), what
		// stream-element-type answers, and what file-length / file-position count in
		// (.kb/read-load-streams.md, "Element types wider and narrower than one octet").
		// Cleared by close, as the compile paths' registry is (a WASM descriptor is
		// reused), so a closed stream answers character on all four.
		Map<Long, StreamElementType> streamElementTypes = new ConcurrentHashMap<>();
		// The handles of the :overwrite streams opened for OUTPUT alone: they run the one
		// bidirectional class (RontoIoFileStream) with the read half unused, so the class
		// cannot tell output-stream-p's caller which direction was asked for.
		Set<Long> outputOnlyCursorStreams = ConcurrentHashMap.newKeySet();
		// The set half of file-position for a BINARY file stream: reposition the file at
		// the given byte offset and answer T, or nil if the stream was not a file
		// stream or has left the table. An input stream re-opens the file (which also
		// drops the buffered lookahead, whose bytes a repositioned channel would
		// otherwise re-emit out of order); an output stream flushes first, then re-opens
		// for WRITE -- no CREATE, no TRUNCATE -- so the file keeps everything before the
		// offset and the write position is the new channel's (a write then lands n bytes
		// in, exactly as CL requires for a repositioned output stream).
		java.util.function.BiFunction<Long, LispVal, LispVal> binaryFileStreamSet = (handle, posArg) -> {
			if (!(posArg instanceof LispInteger position)) {
				throw new LispEvalException(LispNames.FILE_POSITION + " expects an integer position");
			}
			long n = position.value();
			if (n < 0) {
				throw new LispEvalException(LispNames.FILE_POSITION + ": position must be non-negative");
			}
			String path = streamPaths.get(handle);
			if (path == null) {
				return LispNil.INSTANCE;
			}
			try {
				Closeable entry = streams.get(handle);
				if (entry instanceof java.io.Flushable flushable) {
					flushable.flush();
				}
				if (entry instanceof InputStream) {
					java.io.FileInputStream fis = new java.io.FileInputStream(path);
					fis.getChannel().position(n);
					streams.put(handle, new BufferedInputStream(fis));
				}
				else if (entry instanceof OutputStream) {
					java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(Path.of(path),
							java.nio.file.StandardOpenOption.WRITE);
					ch.position(n);
					streams.put(handle, new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(ch)));
				}
				else {
					return LispNil.INSTANCE;
				}
				if (entry instanceof Closeable) {
					entry.close();
				}
				streamPositions.put(handle, n);
				return LispTrue.INSTANCE;
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		};
		// Handles 0/1/2 are the process standard streams (the WASI file descriptors the
		// wasm backends use), so a user stream never collides with the *error-output*
		// designator; the table entry for 2 makes every stream operation -- print family,
		// write-string/line, fresh-line, force-output -- reach stderr with no special
		// case of its own. It writes THROUGH on every call (System.err is autoflush, and
		// a buffered writer would reorder warnings against stdout).
		AtomicLong nextStreamHandle = new AtomicLong(StreamDesignators.FIRST_USER_HANDLE);
		// The buffered served-request body rides the same table (its handle IS the
		// :raw-body value), but it is opened and closed from Java: per-request Lisp
		// marshalling was the measured POST regression, and the transport must be able
		// to reclaim the entry when the request ends whether or not the handler closed
		// it.
		env.httpBodyStreamOpener = octets -> {
			long handle = nextStreamHandle.getAndIncrement();
			streams.put(handle, new HttpRequestBodyStream(octets));
			return handle;
		};
		env.httpBodyStreamCloser = streams::remove;
		env.openStreamFlusher = () -> {
			for (Closeable entry : streams.values()) {
				if (entry instanceof java.io.Flushable flushable) {
					try {
						flushable.flush();
					}
					catch (IOException ex) {
						// exit's rule: the other streams still get their flush.
					}
				}
			}
		};
		streams.put(StreamDesignators.STANDARD_ERROR_HANDLE, new Writer() {
			@Override
			public void write(char[] cbuf, int off, int len) {
				System.err.print(new String(cbuf, off, len));
				System.err.flush();
			}

			@Override
			public void flush() {
				System.err.flush();
			}

			@Override
			public void close() {
				// The process standard error outlives any close of it.
			}
		});
		// CL's output stream DESIGNATOR rule: an absent argument AND an explicit nil both
		// denote *standard-output*, so both read the evaluator's hook (dynamic-first;
		// null when no evaluator installed it) at call time -- that is what makes
		// (let ((*standard-output* s)) (print x nil)) and a forwarded optional
		// (defun p (&optional stream) (princ "x" stream)) reach s. Only t is hard-wired
		// to the process standard output. Resolution happens ONCE, so a *standard-output*
		// that is itself nil falls through to standard output instead of looping.
		java.util.function.UnaryOperator<@Nullable LispVal> resolveOutputDest = dest -> {
			if ((dest == null || dest == LispNil.INSTANCE) && env.defaultOutput != null) {
				return streamTargetOrNull(env.defaultOutput.get());
			}
			return streamTargetOrNull(dest);
		};
		// warn's destination: the current -- dynamic-first -- value of *error-output*,
		// so (let ((*error-output* s)) (warn ...)) captures the report the way CL does.
		// Without an evaluator hook (a bare Environment) the seeded global answers, which
		// is the process standard error.
		java.util.function.Supplier<@Nullable LispVal> resolveErrorDest = () -> {
			if (env.defaultError != null) {
				return env.defaultError.get();
			}
			return env.lookupOrNull(LispNames.ERROR_OUTPUT_VAR);
		};
		// The same rule on the input side: an absent argument AND an explicit nil both
		// denote *standard-input*. Only t is hard-wired to the process standard input.
		java.util.function.UnaryOperator<@Nullable LispVal> resolveInputSrc = src -> {
			if ((src == null || src == LispNil.INSTANCE) && env.defaultInput != null) {
				return streamTargetOrNull(env.defaultInput.get());
			}
			return streamTargetOrNull(src);
		};
		// The socket arm of the character built-ins: a RESOLVED designator whose table
		// entry is a raw Socket. write-string / write-char / read-char take it exactly
		// as write-line / read-line / the byte ops already did; the
		// print family deliberately does NOT, because it has no socket dispatch on the
		// --component backend either and a program that wrote through it here would trap
		// there (see .kb/tcp-sockets.md).
		java.util.function.Function<@Nullable LispVal, @Nullable Socket> socketEntry = designator -> designator instanceof LispInteger handle
				&& streams.get(handle.value()) instanceof Socket socket ? socket : null;
		// Routes print-family output: the destination is resolved through the designator
		// rule above; nil or t is standard output; an integer handle selects a Writer
		// entry in the stream table (file output streams and string streams).
		java.util.function.BiConsumer<String, @Nullable LispVal> emitTo = (text, rawDest) -> {
			LispVal dest = resolveOutputDest.apply(rawDest);
			if (dest == null || dest == LispNil.INSTANCE || dest instanceof LispTrue) {
				emit.accept(text);
				return;
			}
			if (!(dest instanceof LispInteger handle) || !(streams.get(handle.value()) instanceof Writer writer)) {
				throw new LispEvalException("not an output stream: " + dest.print());
			}
			try {
				writer.write(text);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		};
		env.defineFunction(LispNames.PRINT, new LispFunction(LispNames.PRINT, args -> {
			requireArgCountBetween(LispNames.PRINT, args, 1, 2);
			LispVal val = args.get(0);
			emitTo.accept(printString(val) + "\n", args.size() > 1 ? args.get(1) : null);
			return val;
		}));
		env.defineFunction(LispNames.PRIN1, new LispFunction(LispNames.PRIN1, args -> {
			requireArgCountBetween(LispNames.PRIN1, args, 1, 2);
			LispVal val = args.get(0);
			emitTo.accept(printString(val), args.size() > 1 ? args.get(1) : null);
			return val;
		}));
		env.defineFunction(LispNames.PRINC, new LispFunction(LispNames.PRINC, args -> {
			requireArgCountBetween(LispNames.PRINC, args, 1, 2);
			LispVal val = args.get(0);
			emitTo.accept(displayString(val), args.size() > 1 ? args.get(1) : null);
			return val;
		}));
		env.defineFunction(LispNames.TERPRI, new LispFunction(LispNames.TERPRI, args -> {
			requireArgCountBetween(LispNames.TERPRI, args, 0, 1);
			LispVal dest = resolveOutputDest.apply(args.isEmpty() ? null : args.get(0));
			Socket socket = socketEntry.apply(dest);
			if (socket != null) {
				// The socket arm: the entry is a raw Socket, not a Writer.
				SocketSupport.writeString(socket, "\n");
				return LispNil.INSTANCE;
			}
			emitTo.accept("\n", dest);
			return LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.WRITE_STRING, new LispFunction(LispNames.WRITE_STRING, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.WRITE_STRING + " expects a string");
			}
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.WRITE_STRING + " expects a string");
			}
			// (write-string string [stream] [:start s] [:end e]): the keywords bound
			// the written substring (a nil :end means the string's length).
			LispVal stream = null;
			int start = 0;
			String full = str.value();
			// :start / :end are CHARACTER positions (code points), not code units, so a
			// supplementary code point in the string counts as one index step.
			int cpLen = full.codePointCount(0, full.length());
			int end = cpLen;
			int i = 1;
			if (args.size() > 1 && !(args.get(1) instanceof LispSymbol kw && kw.name().startsWith(":"))) {
				stream = args.get(1);
				i = 2;
			}
			// CL: the FIRST occurrence of a keyword is the one that counts, and
			// :allow-other-keys (itself always accepted) decides whether an unknown one
			// is a program-error or ignored.
			boolean allowOtherKeys = false;
			for (int k = i; k + 1 < args.size(); k += 2) {
				if (args.get(k) instanceof LispSymbol kw && ":ALLOW-OTHER-KEYS".equals(kw.name())) {
					allowOtherKeys = !(args.get(k + 1) instanceof LispNil);
					break;
				}
			}
			boolean sawStart = false;
			boolean sawEnd = false;
			for (; i + 1 < args.size(); i += 2) {
				if (args.get(i) instanceof LispSymbol kw) {
					switch (kw.name()) {
						case ":START" -> {
							if (!sawStart) {
								start = (int) asLong(args.get(i + 1));
								sawStart = true;
							}
						}
						case ":END" -> {
							if (!sawEnd) {
								end = args.get(i + 1) instanceof LispNil ? cpLen : (int) asLong(args.get(i + 1));
								sawEnd = true;
							}
						}
						case ":ALLOW-OTHER-KEYS" -> {
						}
						default -> {
							if (!allowOtherKeys) {
								throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
										LispNames.WRITE_STRING + ": unsupported keyword " + kw.name());
							}
						}
					}
				}
			}
			if (start < 0 || end > cpLen || start > end) {
				throw new LispEvalException(LispNames.WRITE_STRING + ": bad bounding indices " + start + ".." + end);
			}
			int startCU = full.offsetByCodePoints(0, start);
			int endCU = full.offsetByCodePoints(0, end);
			String text = full.substring(startCU, endCU);
			LispVal dest = resolveOutputDest.apply(stream);
			Socket socket = socketEntry.apply(dest);
			if (socket != null) {
				SocketSupport.writeString(socket, text);
				return str;
			}
			emitTo.accept(text, dest);
			return str;
		}));
		env.defineFunction(LispNames.WRITE_TO_STRING, new LispFunction(LispNames.WRITE_TO_STRING, args -> {
			requireArgCount(LispNames.WRITE_TO_STRING, args, 1);
			return new LispString(printString(args.get(0)));
		}));
		// String streams: internal helpers behind with-output-to-string /
		// with-input-from-string. An output string stream is a StringWriter entry; an
		// input string stream is a BufferedReader over the string, so read/read-line
		// consume it like any file stream.
		java.util.function.Function<List<LispVal>, LispVal> makeStringOutputStream = args -> {
			long handle = nextStreamHandle.getAndIncrement();
			streams.put(handle, new StringWriter());
			return streamValue(handle, LispLayout.Kinds.STRING_OUTPUT);
		};
		env.defineFunction(LispNames.MAKE_STRING_OUTPUT_STREAM_INTERNAL,
				new LispFunction(LispNames.MAKE_STRING_OUTPUT_STREAM_INTERNAL, args -> {
					requireArgCount(LispNames.MAKE_STRING_OUTPUT_STREAM_INTERNAL, args, 0);
					return makeStringOutputStream.apply(args);
				}));
		// The public spelling. CL's lambda list is (&key element-type); every rontolisp
		// stream is a character stream, so the option is accepted and dropped -- but the
		// tail is still validated, as the compile paths' expansion validates it.
		env.defineFunction(LispNames.MAKE_STRING_OUTPUT_STREAM,
				new LispFunction(LispNames.MAKE_STRING_OUTPUT_STREAM, args -> {
					String problem = LispMacroExpander.keywordTailProblem(LispNames.MAKE_STRING_OUTPUT_STREAM, args, 0,
							List.of(LispNames.ELEMENT_TYPE_KEYWORD));
					if (problem != null) {
						throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME, problem);
					}
					return makeStringOutputStream.apply(args);
				}));
		env.defineFunction(LispNames.MAKE_STRING_INPUT_STREAM_INTERNAL,
				new LispFunction(LispNames.MAKE_STRING_INPUT_STREAM_INTERNAL, args -> {
					requireArgCount(LispNames.MAKE_STRING_INPUT_STREAM_INTERNAL, args, 1);
					if (!(args.get(0) instanceof LispString str)) {
						throw new LispEvalException(LispNames.MAKE_STRING_INPUT_STREAM_INTERNAL + " expects a string");
					}
					long handle = nextStreamHandle.getAndIncrement();
					streams.put(handle, new RontoStringInputStream(str.value()));
					return streamValue(handle, LispLayout.Kinds.STRING_INPUT);
				}));
		// The public spelling. CL's lambda list is (string &optional start end); the
		// bounded form is the stream over that subsequence, which is what the compile
		// paths expand it to as well.
		env.defineFunction(LispNames.MAKE_STRING_INPUT_STREAM,
				new LispFunction(LispNames.MAKE_STRING_INPUT_STREAM, args -> {
					if (args.isEmpty() || args.size() > 3) {
						throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
								LispNames.MAKE_STRING_INPUT_STREAM + " expects 1 to 3 arguments, got " + args.size());
					}
					if (!(args.get(0) instanceof LispString str)) {
						throw new LispEvalException(LispNames.MAKE_STRING_INPUT_STREAM + " expects a string");
					}
					String text = str.value();
					// Bounds are CHARACTER positions (code points), not UTF-16 code units
					// -- subseq's idiom, and the same one the compile paths get for free
					// by expanding through subseq.
					int cpLen = text.codePointCount(0, text.length());
					int start = args.size() > 1 ? requireIndex(LispNames.MAKE_STRING_INPUT_STREAM, args.get(1)) : 0;
					int end = (args.size() > 2 && !(args.get(2) instanceof LispNil))
							? requireIndex(LispNames.MAKE_STRING_INPUT_STREAM, args.get(2)) : cpLen;
					if (start < 0 || end > cpLen || start > end) {
						throw new LispEvalException(LispNames.MAKE_STRING_INPUT_STREAM + ": invalid bounds " + start
								+ ", " + end + " for string of length " + cpLen);
					}
					String bounded = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
					long handle = nextStreamHandle.getAndIncrement();
					streams.put(handle, new RontoStringInputStream(bounded));
					return streamValue(handle, LispLayout.Kinds.STRING_INPUT);
				}));
		// Lite: with no component streams a broadcast stream is a discarding sink -- a
		// fresh string output stream nobody ever reads. A CALL with components never
		// reaches here (LispEvaluator expands it, like the compile paths, into the Gray
		// %make-broadcast-stream); this definition survives so #'make-broadcast-stream is
		// still a first-class value, and that value is the sink shape only.
		env.defineFunction(LispNames.MAKE_BROADCAST_STREAM, new LispFunction(LispNames.MAKE_BROADCAST_STREAM, args -> {
			if (!args.isEmpty()) {
				throw new LispEvalException(
						LispNames.MAKE_BROADCAST_STREAM + " supports the zero-argument (sink) form only as a value");
			}
			long handle = nextStreamHandle.getAndIncrement();
			streams.put(handle, new StringWriter());
			return streamValue(handle, LispLayout.Kinds.STRING_OUTPUT);
		}));
		// file-position: REAL for the three position-bearing streams -- the buffered
		// served-request body (a real byte index, what lets circular-streams rewind a
		// body lack-request already parsed) and a BINARY FILE stream, whose position
		// file-position exists to report and move. Everything else -- string streams,
		// sockets, the standard streams, a character file stream -- answers nil, which
		// is what Common Lisp prescribes for "the position cannot be determined"; a
		// SEEK that cannot reposition answers nil rather than pretending, because a
		// caller that seeks and then reads would otherwise get the wrong bytes
		// (.kb/read-load-streams.md). The set repositions by re-opening the file at the
		// requested offset, which also drops the buffered lookahead.
		env.defineFunction(LispNames.FILE_POSITION, new LispFunction(LispNames.FILE_POSITION, args -> {
			if (args.isEmpty() || !(streamTarget(args.get(0)) instanceof LispInteger handle)) {
				return LispNil.INSTANCE;
			}
			long h = handle.value();
			LispVal stringPosition = stringStreamPosition(streams.get(h), args);
			if (stringPosition != null) {
				return stringPosition;
			}
			// CL's two position DESIGNATORS. The compile paths rewrite a literal
			// :start / :end at the call site (LispMacroExpander.rewriteFilePositionArg);
			// here the keyword is read at run time, so #'file-position answers too.
			if (args.size() >= 2 && args.get(1) instanceof LispSymbol spec && spec.name().startsWith(":")) {
				if (":START".equals(spec.name())) {
					args = List.of(args.get(0), new LispInteger(0));
				}
				else if (":END".equals(spec.name())) {
					long length = 0;
					try {
						if (streams.get(h) instanceof RontoIoFileStream sized) {
							length = sized.length();
						}
						else if (streamPaths.get(h) instanceof String named) {
							// What is still buffered counts: it lands before the end.
							if (streams.get(h) instanceof java.io.Flushable buffered) {
								buffered.flush();
							}
							length = Files.size(Path.of(named));
						}
					}
					catch (IOException ignored) {
						// A stream whose length cannot be read seeks to 0, which is the
						// same answer file-length gives for it (nil -> nothing to seek).
					}
					args = List.of(args.get(0), new LispInteger(length));
				}
			}
			if (streams.get(h) instanceof RontoIoFileStream io) {
				// A bidirectional (or :overwrite) file stream owns its cursor, so both
				// halves are the RandomAccessFile's own -- no side table, no re-open.
				try {
					if (args.size() >= 2) {
						if (!(args.get(1) instanceof LispInteger position)) {
							throw new LispEvalException(LispNames.FILE_POSITION + " expects an integer position");
						}
						if (position.value() < 0) {
							throw new LispEvalException(LispNames.FILE_POSITION + ": position must be non-negative");
						}
						io.position(position.value());
						return LispTrue.INSTANCE;
					}
					return new LispInteger(io.position());
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}
			Closeable charEntry = streams.get(h);
			if (charEntry instanceof RontoCharFileReader || charEntry instanceof RontoCharFileWriter) {
				// A character file stream: the byte offset its own buffer accounts for.
				try {
					if (args.size() >= 2) {
						if (!(args.get(1) instanceof LispInteger position)) {
							throw new LispEvalException(LispNames.FILE_POSITION + " expects an integer position");
						}
						if (position.value() < 0) {
							throw new LispEvalException(LispNames.FILE_POSITION + ": position must be non-negative");
						}
						if (charEntry instanceof RontoCharFileReader reader) {
							reader.position(position.value());
						}
						else {
							((RontoCharFileWriter) charEntry).position(position.value());
						}
						return LispTrue.INSTANCE;
					}
					return new LispInteger(charEntry instanceof RontoCharFileReader reader ? reader.position()
							: ((RontoCharFileWriter) charEntry).position());
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}
			if (streams.get(h) instanceof HttpRequestBodyStream body) {
				if (args.size() >= 2) {
					if (!(args.get(1) instanceof LispInteger position)) {
						throw new LispEvalException(LispNames.FILE_POSITION + " expects an integer position");
					}
					body.position((int) position.value());
					return LispTrue.INSTANCE;
				}
				return new LispInteger(body.position());
			}
			// A binary file stream: an InputStream/OutputStream table entry that was
			// opened with a path. Only these carry a sitting entry in streamPositions,
			// so the query is exactly the counter the byte primitives advance.
			if (streamPaths.containsKey(h)) {
				if (streams.get(h) instanceof InputStream) {
					if (args.size() >= 2) {
						return binaryFileStreamSet.apply(h, args.get(1));
					}
					return new LispInteger(streamPositions.getOrDefault(h, 0L));
				}
				if (streams.get(h) instanceof OutputStream) {
					if (args.size() >= 2) {
						return binaryFileStreamSet.apply(h, args.get(1));
					}
					return new LispInteger(streamPositions.getOrDefault(h, 0L));
				}
			}
			return LispNil.INSTANCE;
		}));
		// file-length: real for a FILE stream, answered from the path it was opened with
		// (streamPaths). Every other stream -- string streams, sockets, the standard
		// streams -- has no file behind it and answers nil, which is exactly what Common
		// Lisp prescribes for "the length cannot be determined".
		env.defineFunction(LispNames.FILE_LENGTH, new LispFunction(LispNames.FILE_LENGTH, args -> {
			requireArgCount(LispNames.FILE_LENGTH, args, 1);
			if (!(streamTarget(args.get(0)) instanceof LispInteger handle)) {
				return LispNil.INSTANCE;
			}
			String path = streamPaths.get(handle.value());
			if (path == null) {
				return LispNil.INSTANCE;
			}
			// An output stream buffers, so flush before stat'ing: file-length must count
			// what has been written, not what happens to have reached the disk.
			if (streams.get(handle.value()) instanceof java.io.Flushable flushable) {
				try {
					flushable.flush();
				}
				catch (IOException ignored) {
					// A stream that cannot flush still has whatever length it has.
				}
			}
			try {
				return new LispInteger(Files.size(Path.of(path)));
			}
			catch (IOException | RuntimeException ex) {
				return LispNil.INSTANCE;
			}
		}));
		// The REAL direction (.kb/read-load-streams.md, "String streams"), read the way
		// the compile paths' %stream-direction reads it: off the stream value's KIND, and
		// for a FILE stream off what the table holds -- a closed one holds nothing and is
		// neither. The t designator is both.
		env.defineFunction(LispNames.INPUT_STREAM_P, new LispFunction(LispNames.INPUT_STREAM_P, args -> {
			requireArgCount(LispNames.INPUT_STREAM_P, args, 1);
			return (streamDirection(args.get(0), streams, outputOnlyCursorStreams) & 1) != 0 ? LispTrue.INSTANCE
					: LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.OUTPUT_STREAM_P, new LispFunction(LispNames.OUTPUT_STREAM_P, args -> {
			requireArgCount(LispNames.OUTPUT_STREAM_P, args, 1);
			return (streamDirection(args.get(0), streams, outputOnlyCursorStreams) & 2) != 0 ? LispTrue.INSTANCE
					: LispNil.INSTANCE;
		}));
		// open-stream-p: REAL against the stream table (close removes the entry), so
		// the close-if-open idiom (cl-postgres's ensure-socket-is-closed) neither
		// double-closes nor leaks. A closed-elsewhere Socket answers nil too. nil (no
		// stream) answers nil rather than signaling -- callers probe with the raw slot.
		// A CLOS INSTANCE is open: it is a Gray stream (or a synonym), holding nothing
		// this table could have closed, and this arm is what the compiled backends'
		// lite lowering already answered -- without it the two seams disagreed for a
		// program that OWNS open-stream-p with a defmethod, which is the one case the
		// Gray dispatch stands down for (.kb/gray-streams.md).
		env.defineFunction(LispNames.OPEN_STREAM_P, new LispFunction(LispNames.OPEN_STREAM_P, args -> {
			requireArgCount(LispNames.OPEN_STREAM_P, args, 1);
			return switch (streamTarget(args.get(0))) {
				case LispTrue ignored -> LispTrue.INSTANCE;
				case LispInstance ignored -> LispTrue.INSTANCE;
				case LispInteger handle -> switch (streams.get(handle.value())) {
					case Socket socket -> socket.isClosed() ? LispNil.INSTANCE : LispTrue.INSTANCE;
					case null -> LispNil.INSTANCE;
					default -> LispTrue.INSTANCE;
				};
				default -> LispNil.INSTANCE;
			};
		}));
		// CL's get-output-stream-string CLEARS the stream as it answers, so a second call
		// sees only what was written after the first; with-output-to-string fetches once
		// and then closes, so it cannot tell the difference.
		java.util.function.Function<List<LispVal>, LispVal> streamContents = args -> {
			requireArgCount(LispNames.STRING_STREAM_CONTENTS_INTERNAL, args, 1);
			if (!(streamTarget(args.get(0)) instanceof LispInteger handle)
					|| !(streams.get(handle.value()) instanceof StringWriter writer)) {
				throw new LispEvalException(
						LispNames.STRING_STREAM_CONTENTS_INTERNAL + " expects a string output stream");
			}
			LispString contents = new LispString(writer.toString());
			writer.getBuffer().setLength(0);
			return contents;
		};
		env.defineFunction(LispNames.STRING_STREAM_CONTENTS_INTERNAL,
				new LispFunction(LispNames.STRING_STREAM_CONTENTS_INTERNAL, streamContents));
		env.defineFunction(LispNames.GET_OUTPUT_STREAM_STRING,
				new LispFunction(LispNames.GET_OUTPUT_STREAM_STRING, streamContents));
		env.defineFunction(LispNames.FRESH_LINE, new LispFunction(LispNames.FRESH_LINE, args -> {
			requireArgCountBetween(LispNames.FRESH_LINE, args, 0, 1);
			LispVal dest = resolveOutputDest.apply(args.isEmpty() ? null : args.get(0));
			if (dest == null || dest == LispNil.INSTANCE || dest instanceof LispTrue) {
				if (!atLineStart[0]) {
					emit.accept("\n");
				}
			}
			else if (dest instanceof LispInteger handle && streams.get(handle.value()) instanceof Writer writer) {
				// A string stream exposes its contents, so the line start is exact; a
				// file stream's column is unknown, so a newline is always written (the
				// same rule on every backend).
				if (!(writer instanceof StringWriter sw)
						|| (!sw.getBuffer().isEmpty() && sw.getBuffer().charAt(sw.getBuffer().length() - 1) != '\n')) {
					emitTo.accept("\n", dest);
				}
			}
			else {
				throw new LispEvalException("not an output stream: " + dest.print());
			}
			return LispNil.INSTANCE;
		}));
		// princ-to-string / prin1-to-string: the string that princ / prin1 would print.
		env.defineFunction(LispNames.PRINC_TO_STRING, new LispFunction(LispNames.PRINC_TO_STRING, args -> {
			requireArgCount(LispNames.PRINC_TO_STRING, args, 1);
			return new LispString(displayString(args.get(0)));
		}));
		env.defineFunction(LispNames.PRIN1_TO_STRING, new LispFunction(LispNames.PRIN1_TO_STRING, args -> {
			requireArgCount(LispNames.PRIN1_TO_STRING, args, 1);
			return new LispString(printString(args.get(0)));
		}));
		// The print-object-free aliases the print-object renderer's fallback calls -- the
		// same two functions under internal names, so the rewrite cannot re-enter itself.
		env.defineFunction(LispNames.PRINC_TO_STRING_RAW, new LispFunction(LispNames.PRINC_TO_STRING_RAW, args -> {
			requireArgCount(LispNames.PRINC_TO_STRING_RAW, args, 1);
			return new LispString(displayString(args.get(0)));
		}));
		env.defineFunction(LispNames.PRIN1_TO_STRING_RAW, new LispFunction(LispNames.PRIN1_TO_STRING_RAW, args -> {
			requireArgCount(LispNames.PRIN1_TO_STRING_RAW, args, 1);
			return new LispString(printString(args.get(0)));
		}));
		// The &optional surplus-argument message (LambdaLists): max and required are
		// literals, the count is required plus the rest list's length.
		env.defineFunction(LispNames.ARITY_SURPLUS_MESSAGE_INTERNAL,
				new LispFunction(LispNames.ARITY_SURPLUS_MESSAGE_INTERNAL, args -> {
					requireArgCount(LispNames.ARITY_SURPLUS_MESSAGE_INTERNAL, args, 3);
					int got = (int) ((LispInteger) args.get(1)).value();
					for (LispVal l = args.get(2); l instanceof LispCons c; l = c.cdr()) {
						got++;
					}
					return new LispString(
							ClosRegistry.aritySurplusMessage((int) ((LispInteger) args.get(0)).value(), got));
				}));
		// The destructuring missing-element message (LambdaLists): required and got
		// are literals, the message the lower-bound half of the arity report.
		env.defineFunction(LispNames.ARITY_MISSING_MESSAGE_INTERNAL,
				new LispFunction(LispNames.ARITY_MISSING_MESSAGE_INTERNAL, args -> {
					requireArgCount(LispNames.ARITY_MISSING_MESSAGE_INTERNAL, args, 2);
					return new LispString(ClosRegistry.arityMessage((int) ((LispInteger) args.get(0)).value(), true,
							(int) ((LispInteger) args.get(1)).value()));
				}));
		// The piece aliases the expander builds format directives, map 'string
		// accumulators and condition messages with: on the compile backends they are the
		// public conversions minus the mutable-result wrap; here every string is mutable
		// already, so they ARE the public functions (LispEvaluator's operator seam routes
		// them through print-object / *print-case* exactly like the public names).
		env.defineFunction(LispNames.PRINC_PIECE_INTERNAL, new LispFunction(LispNames.PRINC_PIECE_INTERNAL, args -> {
			requireArgCount(LispNames.PRINC_PIECE_INTERNAL, args, 1);
			return new LispString(displayString(args.get(0)));
		}));
		env.defineFunction(LispNames.PRIN1_PIECE_INTERNAL, new LispFunction(LispNames.PRIN1_PIECE_INTERNAL, args -> {
			requireArgCount(LispNames.PRIN1_PIECE_INTERNAL, args, 1);
			return new LispString(printString(args.get(0)));
		}));
		// concatenate: the string, list and vector result families (ConcatenateForms is
		// the shared contract the compilers lower through as well). The registry-less
		// registration here cannot resolve user deftype aliases; LispEvaluator overrides
		// it with concatenateBuiltin(closRegistry) so 'simple-byte-vector-style alias
		// designators resolve exactly as they do on the compile paths.
		env.defineFunction(LispNames.CONCATENATE, concatenateBuiltin(null));
		// %string-concat: internal binary string concatenation used by
		// format/concatenate.
		env.defineFunction(LispNames.STRING_CONCAT, new LispFunction(LispNames.STRING_CONCAT, args -> {
			requireArgCount(LispNames.STRING_CONCAT, args, 2);
			if (!(args.get(0) instanceof LispString a) || !(args.get(1) instanceof LispString b)) {
				throw new LispEvalException(
						"%string-concat expects strings, got: " + args.get(0).print() + ", " + args.get(1).print());
			}
			return new LispString(a.value() + b.value());
		}));
		// %text-control / %control-text: rendered text as the format-control that
		// renders it verbatim, and back (ClosRegistry.textControl).
		env.defineFunction(LispNames.TEXT_CONTROL_INTERNAL, new LispFunction(LispNames.TEXT_CONTROL_INTERNAL, args -> {
			requireArgCount(LispNames.TEXT_CONTROL_INTERNAL, args, 1);
			return ClosRegistry.textControl(args.get(0));
		}));
		env.defineFunction(LispNames.CONTROL_TEXT_INTERNAL, new LispFunction(LispNames.CONTROL_TEXT_INTERNAL, args -> {
			requireArgCount(LispNames.CONTROL_TEXT_INTERNAL, args, 1);
			return ClosRegistry.controlText(args.get(0));
		}));
		// %str-fresh: "this value, as a FRESH mutable string". The compile backends
		// emit their mutable-result wrap for it (a non-string passes through), which is
		// how the pure-builtin fold keeps a literal-argument producer's value constant
		// while each evaluation answers a fresh string, and how the first-class
		// #'concatenate wrapper's string arm reaches the identity call position has.
		// The interpreter's strings are already mutable, so all this has to do is copy.
		env.defineFunction(LispNames.STR_FRESH, new LispFunction(LispNames.STR_FRESH, args -> {
			requireArgCount(LispNames.STR_FRESH, args, 1);
			return args.get(0) instanceof LispString s ? new LispString(s.value()) : args.get(0);
		}));
		// %fixed-decimal: internal fixed-point rendering, what format's ~F and ~$ lower
		// to on both format paths. The algorithm is compiler/FixedDecimal, which the two
		// compile backends emit as bytecode / a runtime function.
		env.defineFunction(LispNames.FIXED_DECIMAL, new LispFunction(LispNames.FIXED_DECIMAL, args -> {
			requireArgCount(LispNames.FIXED_DECIMAL, args, 4);
			return new LispString(FixedDecimal.render(asDouble(args.get(0)), (int) asLong(args.get(1)),
					(int) asLong(args.get(2)), !(args.get(3) instanceof LispNil)));
		}));
		// %seq-string: one character sequence as a string. The compile paths generate
		// calls to it around every non-literal concatenate 'string argument; the
		// interpreter never sees those, but the name is a cl internal, so it answers
		// here too (and an interpreted (funcall '%seq-string x) behaves the same).
		env.defineFunction(LispNames.SEQ_STRING, new LispFunction(LispNames.SEQ_STRING, args -> {
			requireArgCount(LispNames.SEQ_STRING, args, 1);
			if (args.get(0) instanceof LispString s) {
				return s;
			}
			List<LispVal> chars = new ArrayList<>();
			appendSequenceElements(args.get(0), chars);
			return charVector(LispNames.SEQ_STRING, chars);
		}));
		// %seq-int-vector: one sequence of integers as a packed (unsigned-byte 8|16|32)
		// vector. The compile paths call it from the concatenate vector family's lowering
		// whenever the result type spells a packed element type; the interpreter's own
		// concatenate builds the same value directly, but the name is a cl internal, so
		// it answers here too.
		env.defineFunction(LispNames.SEQ_INT_VECTOR, new LispFunction(LispNames.SEQ_INT_VECTOR, args -> {
			requireArgCount(LispNames.SEQ_INT_VECTOR, args, 2);
			int width = (int) asLong(args.get(1));
			if (width != 8 && width != 16 && width != 32) {
				throw new LispEvalException(LispNames.SEQ_INT_VECTOR + ": unsupported element width " + width);
			}
			List<LispVal> elements = new ArrayList<>();
			appendSequenceElements(args.get(0), elements);
			return packedIntVector(LispNames.SEQ_INT_VECTOR, width, elements);
		}));
		// %seq-float-vector: one sequence of reals as a packed float array of the width
		// an ArrayElementTypes code names -- the float twin of %seq-int-vector, called
		// from the same two lowerings (concatenate's vector family and coerce) and
		// answering here for the same reason: the name is a cl internal.
		env.defineFunction(LispNames.SEQ_FLOAT_VECTOR, new LispFunction(LispNames.SEQ_FLOAT_VECTOR, args -> {
			requireArgCount(LispNames.SEQ_FLOAT_VECTOR, args, 2);
			int code = (int) asLong(args.get(1));
			LispFloatArray proto = LispFloatArray.prototypeFor(ArrayElementTypes.valueOf(code));
			if (proto == null) {
				throw new LispEvalException(LispNames.SEQ_FLOAT_VECTOR + ": unsupported element type code " + code);
			}
			List<LispVal> elements = new ArrayList<>();
			appendSequenceElements(args.get(0), elements);
			return packedFloatVector(LispNames.SEQ_FLOAT_VECTOR, proto, elements);
		}));
		// %error: internal single-argument primitive that signals an error with a
		// pre-built message string. Produced by the error macro expansion.
		env.defineFunction(LispNames.ERROR_INTERNAL, new LispFunction(LispNames.ERROR_INTERNAL, args -> {
			requireArgCount(LispNames.ERROR_INTERNAL, args, 1);
			String message = (args.get(0) instanceof LispString s) ? s.value() : args.get(0).display();
			throw new LispEvalException(message);
		}));
		// %error-cond: internal two-argument primitive that signals an error carrying a
		// condition object (a CLOS-subset tagged-list instance) alongside the message.
		// Produced by the typed / condition-object error designator expansions.
		env.defineFunction(LispNames.ERROR_COND_INTERNAL, new LispFunction(LispNames.ERROR_COND_INTERNAL, args -> {
			requireArgCount(LispNames.ERROR_COND_INTERNAL, args, 2);
			String message = (args.get(1) instanceof LispString s) ? s.value() : args.get(1).display();
			throw new LispEvalException(message, args.get(0));
		}));
		// %warn: internal single-argument primitive that writes a pre-built
		// "WARNING: ..." message to the current *error-output* -- the seeded handle 2
		// (the process standard error) unless the program rebound it -- and returns nil.
		// Produced by the warn macro expansion.
		env.defineFunction(LispNames.WARN_INTERNAL, new LispFunction(LispNames.WARN_INTERNAL, args -> {
			requireArgCount(LispNames.WARN_INTERNAL, args, 1);
			String message = (args.get(0) instanceof LispString s) ? s.value() : args.get(0).display();
			emitTo.accept(message + "\n", resolveErrorDest.get());
			return LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.OPEN, new LispFunction(LispNames.OPEN, args -> {
			requireMinArgCount(LispNames.OPEN, args, 1);
			String openPath = PathnameOps.designatorNamestring(args.get(0));
			if (openPath == null) {
				throw new LispEvalException(LispNames.OPEN + " expects a pathname designator");
			}
			LispString path = new LispString(openPath);
			// The CL keyword-argument shape ((open path :direction :input :element-type
			// 'character ...)) is normalized to the positional one; :external-format is
			// accepted and dropped (UTF-8 is the native format) while :if-exists and
			// :if-does-not-exist become the existence guard below -- the same table
			// compiler/OpenModes and LispMacroExpander.lowerRuntimeOpenOptions read for
			// the compile paths (.kb/read-load-streams.md; keep the readings in step).
			boolean probe = false;
			String ifExists = null;
			String ifDoesNotExist = null;
			// The POSITIONAL shape is the internal one the expansions produce -- its
			// :append pseudo-direction has already absorbed the option pair -- so the
			// guard applies to the keyword shape only.
			boolean keywordForm = args.size() > 2 && args.get(1) instanceof LispSymbol first
					&& first.name().startsWith(":") && OpenModes.directionMode(first.name()) < 0;
			if (keywordForm) {
				LispVal direction = new LispSymbol(LispNames.INPUT_KEYWORD);
				LispVal elementType = null;
				for (int i = 1; i < args.size(); i += 2) {
					if (i + 1 >= args.size() || !(args.get(i) instanceof LispSymbol key)
							|| !key.name().startsWith(":")) {
						throw new LispEvalException(LispNames.OPEN + " expects :option value pairs");
					}
					LispVal value = args.get(i + 1);
					switch (key.name()) {
						case ":DIRECTION" -> direction = value;
						case ":ELEMENT-TYPE" -> elementType = value;
						case ":IF-EXISTS" -> ifExists = openOptionName(value);
						case ":IF-DOES-NOT-EXIST" -> ifDoesNotExist = openOptionName(value);
						case ":EXTERNAL-FORMAT" -> {
							if (!LispMacroExpander.ignorableOpenOptionValue(key.name(), value)) {
								throw new LispEvalException(
										LispNames.OPEN + ": " + key.name() + " supports only the native default value");
							}
						}
						default -> throw new LispEvalException(LispNames.OPEN + ": unsupported option " + key.name());
					}
				}
				if (direction instanceof LispSymbol dirSym && LispNames.PROBE_KEYWORD.equals(dirSym.name())) {
					probe = true;
					direction = new LispSymbol(LispNames.INPUT_KEYWORD);
				}
				boolean ioDirection = direction instanceof LispSymbol dirSym
						&& LispNames.IO_KEYWORD.equals(dirSym.name());
				boolean outputDirection = ioDirection
						|| (direction instanceof LispSymbol dirSym && LispNames.OUTPUT_KEYWORD.equals(dirSym.name()));
				if (!outputDirection) {
					// CL reads :if-exists only on an output open.
					ifExists = null;
				}
				else {
					// The direction and the :if-exists disposition normalize into ONE
					// token, exactly as the compile paths' lowering does (LispNames
					// .outputDirectionToken): an :io stream that keeps the file's
					// content is a different open, not a different flag.
					direction = new LispSymbol(LispNames.outputDirectionToken(ioDirection,
							LispNames.APPEND_KEYWORD.equals(ifExists), LispNames.OVERWRITE_KEYWORD.equals(ifExists)));
				}
				List<LispVal> positional = new ArrayList<>(List.of(args.get(0), direction));
				if (elementType != null) {
					positional.add(elementType);
				}
				args = positional;
			}
			int directionMode = 0;
			if (args.size() > 1) {
				directionMode = args.get(1) instanceof LispSymbol dir ? OpenModes.directionMode(dir.name()) : -1;
				if (directionMode < 0) {
					throw new LispEvalException(
							LispNames.OPEN + " supports :input, :output, :io and :probe directions");
				}
			}
			boolean append = (directionMode & OpenModes.APPEND_BIT) != 0;
			boolean overwrite = (directionMode & OpenModes.OVERWRITE_BIT) != 0;
			boolean bidirectional = (directionMode & OpenModes.IO_BIT) != 0;
			boolean output = (directionMode & OpenModes.OUTPUT_BIT) != 0;
			// The optional third argument is the element type: any integer type opens a
			// binary stream of the width StreamElementType decides, 'character (the
			// default) a text stream.
			StreamElementType elementType = StreamElementType.CHARACTER;
			if (args.size() > 2) {
				elementType = streamElementType(args.get(2));
			}
			boolean binary = !elementType.isCharacter();
			// The existence guard. :if-exists is read only on an output open (it is
			// already nil otherwise); :if-does-not-exist defaults to :create for an
			// output open that supersedes, nil for :probe and :error everywhere else --
			// including an APPENDING output open, which CLHS does not let create the
			// file.
			boolean exists = keywordForm && Files.exists(Path.of(path.value()));
			if (exists && ifExists != null) {
				switch (ifExists) {
					case ":ERROR" -> throw openGuardError(args.get(0), LispMacroExpander.OPEN_EXISTS_MESSAGE);
					case "NIL" -> {
						return LispNil.INSTANCE;
					}
					// :supersede and the three version spellings all leave the caller
					// writing over the old content, which is what the truncating open
					// below does; :append and :overwrite are the two dispositions the
					// direction token above absorbed.
					case ":SUPERSEDE", ":NEW-VERSION", ":RENAME", ":RENAME-AND-DELETE", ":APPEND", ":OVERWRITE" -> {
					}
					default -> throw new LispEvalException(
							LispNames.OPEN + ": :IF-EXISTS supports only the native default value");
				}
			}
			if (keywordForm && !exists) {
				String missing = ifDoesNotExist != null ? ifDoesNotExist
						: probe ? "NIL" : (output && !append && !overwrite) ? ":CREATE" : ":ERROR";
				switch (missing) {
					case "NIL" -> {
						return LispNil.INSTANCE;
					}
					case ":ERROR" -> {
						if (output) {
							throw openGuardError(args.get(0), LispMacroExpander.OPEN_MISSING_MESSAGE);
						}
						// An input (or probe) open signals by failing, with the one
						// message every backend spells for a failed open.
					}
					case ":CREATE" -> {
						if (!output) {
							try {
								Files.newOutputStream(Path.of(path.value())).close();
							}
							catch (IOException ex) {
								throw new UncheckedIOException(ex);
							}
						}
					}
					default -> throw new LispEvalException(
							LispNames.OPEN + ": :IF-DOES-NOT-EXIST supports only the native default value");
				}
			}
			try {
				Closeable stream;
				// :append opens CREATE + APPEND instead of the default
				// CREATE + TRUNCATE_EXISTING, so an existing file keeps its content and
				// every write lands at the end.
				java.nio.file.OpenOption[] writeOptions = append
						? new java.nio.file.OpenOption[] { java.nio.file.StandardOpenOption.CREATE,
								java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND }
						: new java.nio.file.OpenOption[0];
				if (bidirectional || overwrite) {
					// One cursor for both directions and for file-position: a
					// Reader/Writer pair over the same path cannot answer what was just
					// written, and an :overwrite open is the same "write where the
					// cursor is, keep the rest" stream with the read half unused. The
					// compile paths run this very class (runtime/RontoIoFileStream).
					stream = new RontoIoFileStream(path.value(), directionMode);
				}
				else if (binary) {
					stream = output
							? new BufferedOutputStream(Files.newOutputStream(Path.of(path.value()), writeOptions))
							: new BufferedInputStream(Files.newInputStream(Path.of(path.value())));
				}
				else {
					// A character stream that knows its BYTE offset, so file-position is
					// real for it (the compile paths run these very classes on the JVM).
					stream = output ? new RontoCharFileWriter(path.value(), append)
							: new RontoCharFileReader(path.value());
				}
				long handle = nextStreamHandle.getAndIncrement();
				if (binary) {
					streamElementTypes.put(handle, elementType);
					if (append && output) {
						// An appending stream starts at the end of the file (sbcl).
						streamPositions.put(handle, Files.size(Path.of(path.value())));
					}
				}
				if (probe) {
					// CL's probe open answers a file stream that is already CLOSED: the
					// program may ask typep / pathname about it but not read it. The
					// stream is opened for real first, so a file that exists but cannot
					// be opened signals here exactly as it does on the compile paths,
					// whose lowering is literally an open followed by a close.
					stream.close();
					return streamValue(handle, LispLayout.Kinds.FILE);
				}
				streams.put(handle, stream);
				streamPaths.put(handle, path.value());
				if (overwrite && !bidirectional) {
					outputOnlyCursorStreams.add(handle);
				}
				return streamValue(handle, LispLayout.Kinds.FILE);
			}
			catch (IOException ex) {
				// A file-error carrying the designator as given, with the message every
				// backend spells identically (LispMacroExpander.openFailureMessage).
				String message = LispNames.OPEN + ": cannot open file " + path.value();
				throw new LispEvalException(message,
						ClosRegistry.newFileErrorCondition(args.get(0), new LispString(message)));
			}
		}));
		// %make-directories: the ONE directory-CREATING primitive, the write-side sibling
		// of %list-directory. Uses Files directly, like open -- the SourceLoader seam is
		// the read side, and a host that cannot open a file for writing cannot create a
		// directory either. Answers nil rather than signalling when the host refuses, the
		// %delete-file / %rename-file shape: the "a refused directory is a file-error"
		// decision lives once, in the Lisp ensure-directories-exist above it
		// (LispPreludeLibrary), which is also where "which part of the namestring is the
		// directory" is decided.
		env.defineFunction(LispNames.MAKE_DIRECTORIES, new LispFunction(LispNames.MAKE_DIRECTORIES, args -> {
			requireArgCount(LispNames.MAKE_DIRECTORIES, args, 1);
			if (!(args.get(0) instanceof LispString path)) {
				throw new LispEvalException(LispNames.MAKE_DIRECTORIES + " expects a string pathname");
			}
			try {
				Files.createDirectories(Path.of(path.value()));
				return LispTrue.INSTANCE;
			}
			catch (IOException | RuntimeException ex) {
				return LispNil.INSTANCE;
			}
		}));
		// %delete-file: the ONE file-REMOVING primitive, the other write-side sibling of
		// %list-directory, and Files-based for the same reason as %make-directories.
		// Answers nil rather than signalling when the file is not there or cannot be
		// removed, so the "a missing file is a file-error" decision lives once, in the
		// Lisp delete-file above it (LispPreludeLibrary).
		env.defineFunction(LispNames.DELETE_FILE_INTERNAL, new LispFunction(LispNames.DELETE_FILE_INTERNAL, args -> {
			requireArgCount(LispNames.DELETE_FILE_INTERNAL, args, 1);
			if (!(args.get(0) instanceof LispString path)) {
				throw new LispEvalException(LispNames.DELETE_FILE_INTERNAL + " expects a string pathname");
			}
			try {
				return Files.deleteIfExists(Path.of(path.value())) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			catch (IOException | RuntimeException ex) {
				return LispNil.INSTANCE;
			}
		}));
		// %rename-file: the third write-side sibling of %list-directory /
		// %make-directories / %delete-file, and Files-based for the same reason. Answers
		// nil rather than signalling when the source is not there or the host refused,
		// so the "a missing file is a file-error" decision lives once, in the Lisp
		// rename-file above it (LispPreludeLibrary).
		env.defineFunction(LispNames.RENAME_FILE_INTERNAL, new LispFunction(LispNames.RENAME_FILE_INTERNAL, args -> {
			requireArgCount(LispNames.RENAME_FILE_INTERNAL, args, 2);
			if (!(args.get(0) instanceof LispString from) || !(args.get(1) instanceof LispString to)) {
				throw new LispEvalException(LispNames.RENAME_FILE_INTERNAL + " expects two string pathnames");
			}
			try {
				Files.move(Path.of(from.value()), Path.of(to.value()),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				return LispTrue.INSTANCE;
			}
			catch (IOException | RuntimeException ex) {
				return LispNil.INSTANCE;
			}
		}));
		env.defineFunction(LispNames.CLOSE, new LispFunction(LispNames.CLOSE, args -> {
			// (close stream) or (close stream :abort expr) -- every rontolisp close is
			// effectively aborting (no buffered data survives it), so :abort is
			// accepted and ignored.
			if (!(args.size() == 1
					|| (args.size() == 3 && args.get(1) instanceof LispSymbol kw && ":ABORT".equals(kw.name())))) {
				requireArgCount(LispNames.CLOSE, args, 1);
			}
			if (isSynonymStream(args.get(0))) {
				// Closing a synonym stream closes the SYNONYM, not the stream it
				// forwards to -- which is nothing to do (CLHS 21.1.3).
				return LispTrue.INSTANCE;
			}
			if (!(streamTarget(args.get(0)) instanceof LispInteger handle)) {
				throw new LispEvalException(LispNames.CLOSE + " expects a stream");
			}
			if (handle.value() < StreamDesignators.FIRST_USER_HANDLE) {
				// CL lets a program close a standard stream; the process ones survive it
				// here, so a later warn still reaches stderr.
				return LispTrue.INSTANCE;
			}
			Closeable stream = streams.remove(handle.value());
			streamPaths.remove(handle.value());
			streamPositions.remove(handle.value());
			streamElementTypes.remove(handle.value());
			outputOnlyCursorStreams.remove(handle.value());
			if (stream == null) {
				// CL: close on an ALREADY-CLOSED stream is not an error -- it answers
				// true and does nothing (SBCL agrees). The unwind-protect idiom the ANSI
				// suite is written in closes a stream the body already closed, and the
				// same shape is what with-open-file expands to, so signalling here made
				// a correct program fail on its way out.
				return LispTrue.INSTANCE;
			}
			try {
				stream.close();
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return LispTrue.INSTANCE;
		}));
		// force-output / finish-output: flush an output stream's buffered bytes to the
		// underlying sink. Every rontolisp write is synchronous once flushed, so the two
		// CL operations coincide; both return nil. No argument (or nil/t) flushes
		// standard output.
		java.util.function.Function<List<LispVal>, LispVal> forceOutput = args -> {
			requireArgCountBetween(LispNames.FORCE_OUTPUT, args, 0, 1);
			try {
				LispVal dest = resolveOutputDest.apply(args.isEmpty() ? null : args.get(0));
				if (dest == null || dest instanceof LispNil || dest instanceof LispTrue) {
					out.flush();
					return LispNil.INSTANCE;
				}
				if (!(dest instanceof LispInteger handle)) {
					throw new LispEvalException(LispNames.FORCE_OUTPUT + " expects an output stream");
				}
				Closeable entry = streams.get(handle.value());
				switch (entry) {
					case Socket socket -> socket.getOutputStream().flush();
					case Writer writer -> writer.flush();
					case OutputStream os -> os.flush();
					case null, default -> throw new LispEvalException(
							LispNames.FORCE_OUTPUT + " expects an output stream, got: " + dest.print());
				}
				return LispNil.INSTANCE;
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		};
		env.defineFunction(LispNames.FORCE_OUTPUT, new LispFunction(LispNames.FORCE_OUTPUT, forceOutput::apply));
		env.defineFunction(LispNames.FINISH_OUTPUT, new LispFunction(LispNames.FINISH_OUTPUT, forceOutput::apply));
		// clear-output: DISCARD what the stream has buffered. Nothing is buffered in a
		// discardable way here -- a write reaches the underlying Writer/Socket
		// immediately -- so the operation validates its designator and answers nil. It
		// exists because the Gray protocol names stream-clear-output and a portable
		// stream class implements it (.kb/gray-streams.md).
		env.defineFunction(LispNames.CLEAR_OUTPUT, new LispFunction(LispNames.CLEAR_OUTPUT, args -> {
			requireArgCountBetween(LispNames.CLEAR_OUTPUT, args, 0, 1);
			LispVal dest = resolveOutputDest.apply(args.isEmpty() ? null : args.get(0));
			if (dest == null || dest instanceof LispNil || dest instanceof LispTrue) {
				return LispNil.INSTANCE;
			}
			if (!(dest instanceof LispInteger handle) || streams.get(handle.value()) == null) {
				throw new LispEvalException(LispNames.CLEAR_OUTPUT + " expects an output stream, got: " + dest.print());
			}
			return LispNil.INSTANCE;
		}));
		// The handle-side one-slot pushback of unread-char: ONE character for ONE
		// stream at a time (declared here, ahead of listen, which consults it).
		// An EMPTY cell is nil in both slots: the KEY nil folds onto t, so no live
		// key is ever nil and the two states cannot be confused.
		final LispVal[] pushbackStream = { LispNil.INSTANCE };
		final LispVal[] pushbackChar = { LispNil.INSTANCE };
		java.util.function.UnaryOperator<LispVal> pushbackKey = stream -> stream instanceof LispNil ? LispTrue.INSTANCE
				: stream;
		// (listen &optional stream): whether input is immediately available without
		// blocking -- InputStream.available() / Reader.ready() semantics. Sockets answer
		// from the kernel receive buffer, which is what cl-postgres's
		// man-in-the-middle probe relies on.
		env.defineFunction(LispNames.LISTEN, new LispFunction(LispNames.LISTEN, args -> {
			requireArgCountBetween(LispNames.LISTEN, args, 0, 1);
			try {
				LispVal src = resolveInputSrc.apply(args.isEmpty() ? null : args.get(0));
				if (src == null || src instanceof LispNil || src instanceof LispTrue) {
					return stdinReader.ready() ? LispTrue.INSTANCE : LispNil.INSTANCE;
				}
				if (!(src instanceof LispInteger handle)) {
					throw new LispEvalException(LispNames.LISTEN + " expects an input stream");
				}
				// A parked unread-char counts as a character that remains, whatever
				// the stream itself says.
				LispVal listenKey = pushbackKey.apply(args.isEmpty() ? LispNil.INSTANCE : args.get(0));
				if (pushbackStream[0].equals(listenKey)) {
					return LispTrue.INSTANCE;
				}
				Closeable entry = streams.get(handle.value());
				boolean ready = switch (entry) {
					case Socket socket -> socket.getInputStream().available() > 0;
					case RontoIoFileStream io -> io.ready();
					// A string input stream answers whether a character remains,
					// not Reader.ready() (true until close): at the end, with
					// nothing parked, there is nothing to listen to.
					case RontoStringInputStream stringIn -> stringIn.hasRemaining();
					case BufferedReader reader -> reader.ready();
					case InputStream in2 -> in2.available() > 0;
					case null, default ->
						throw new LispEvalException(LispNames.LISTEN + " expects an input stream, got: " + src.print());
				};
				return ready ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}));
		env.defineFunction(LispNames.WRITE_LINE, new LispFunction(LispNames.WRITE_LINE, args -> {
			requireMinArgCount(LispNames.WRITE_LINE, args, 1);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.WRITE_LINE + " expects a string");
			}
			// (write-line string [stream] [:start s] [:end e]): the keywords bound
			// the written substring (a nil :end means the string's length), the same
			// rule write-string applies -- call position usually arrives lowered
			// (LispMacroExpander.lowerWriteLineBounds), but first-class use and an
			// odd tail the lowering leaves alone reach this function directly.
			String full = str.value();
			int cpLen = full.codePointCount(0, full.length());
			int start = 0;
			int end = cpLen;
			LispVal streamArg = null;
			int i = 1;
			if (args.size() > 1 && !(args.get(1) instanceof LispSymbol kw && kw.name().startsWith(":"))) {
				streamArg = args.get(1);
				i = 2;
			}
			String text = full;
			if (args.size() > i) {
				// The tail rule is the expansion's
				// (LispMacroExpander.keywordTailProblem), over values here instead of
				// forms: first occurrence wins, a true :allow-other-keys admits the
				// unknown, an unknown indicator or an odd tail is a program-error.
				String problem = am.ik.rontolisp.macro.LispMacroExpander.keywordTailProblem(LispNames.WRITE_LINE, args,
						i, java.util.List.of(LispNames.START_KEYWORD, LispNames.END_KEYWORD));
				if (problem != null) {
					throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME, problem);
				}
				boolean sawStart = false;
				boolean sawEnd = false;
				for (; i + 1 < args.size(); i += 2) {
					if (args.get(i) instanceof LispSymbol kw) {
						switch (kw.name()) {
							case ":START" -> {
								if (!sawStart) {
									start = (int) asLong(args.get(i + 1));
									sawStart = true;
								}
							}
							case ":END" -> {
								if (!sawEnd) {
									end = args.get(i + 1) instanceof LispNil ? cpLen : (int) asLong(args.get(i + 1));
									sawEnd = true;
								}
							}
							default -> {
							}
						}
					}
				}
				if (start < 0 || end > cpLen || start > end) {
					throw new LispEvalException(LispNames.WRITE_LINE + ": bad bounding indices " + start + ".." + end);
				}
				if (start != 0 || end != cpLen) {
					text = full.substring(full.offsetByCodePoints(0, start), full.offsetByCodePoints(0, end));
				}
			}
			// nil and t are the standard-output DESIGNATORS, not stream handles -- the
			// same rule the JVM and both wasm backends already applied (their stdout
			// test is "not a handle"). An absent argument and an explicit nil resolve
			// through *standard-output*; t is the process standard output.
			LispVal dest = resolveOutputDest.apply(streamArg);
			if (dest == null || dest instanceof LispNil || dest instanceof LispTrue) {
				out.println(text);
				return str;
			}
			if (!(dest instanceof LispInteger handle)) {
				throw new LispEvalException(LispNames.WRITE_LINE + " expects an output stream");
			}
			Closeable entry = streams.get(handle.value());
			if (entry instanceof Socket socket) {
				// Socket writes are unbuffered: the line goes out immediately.
				SocketSupport.writeLine(socket, text);
				return str;
			}
			if (!(entry instanceof Writer writer)) {
				throw new LispEvalException(LispNames.WRITE_LINE + " expects an output stream");
			}
			try {
				writer.write(text);
				writer.write("\n");
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return str;
		}));
		// The handle-side one-slot pushback of unread-char: ONE character for ONE
		// stream at a time, the same shape the Gray protocol keeps for an instance and
		// exactly what CL promises. No stream this table holds can be un-read -- a
		// BufferedReader's mark budget is the peek's, not a cell -- so the character is
		// parked here and read-char / %peek-char / read-line consult it before touching
		// the stream. The compile paths answer the same contract in Lisp
		// (eval/UnreadCharLibrary rewrites their call sites onto unread-char.lisp's
		// defuns), which is what keeps the four backends identical.
		//
		// The KEY is the stream argument AS GIVEN, with an omitted stream and the nil
		// designator both folded onto t -- the value *standard-input* holds by default,
		// so the three spellings of standard input compare equal. read-byte,
		// read-sequence and read deliberately do NOT consult the cell (a character
		// pushed back before a BYTE read has no meaning, and the other two expand into
		// their loops long after the compile paths' rewrite), so all four backends
		// agree about that too.
		// An EMPTY cell is nil in both slots: the KEY nil folds onto t, so no live key
		// is ever nil and the two states cannot be confused. (The cells themselves
		// are declared ahead of listen, which consults them.)
		// The parked character of this stream, draining the cell -- nil when the cell is
		// empty or holds another stream's character.
		java.util.function.Function<List<LispVal>, LispVal> pushbackTake = args -> {
			LispVal key = pushbackKey.apply(args.isEmpty() ? LispNil.INSTANCE : args.get(0));
			if (pushbackStream[0].equals(key)) {
				LispVal parked = pushbackChar[0];
				pushbackStream[0] = LispNil.INSTANCE;
				pushbackChar[0] = LispNil.INSTANCE;
				return parked;
			}
			return LispNil.INSTANCE;
		};
		// The stream an end-of-file condition carries: the designator as passed, or t
		// for the standard input an absent/nil designator means -- so
		// stream-error-stream answers what the suite checks streamp of.
		java.util.function.Function<List<LispVal>, LispVal> eofStream = args -> {
			LispVal first = args.isEmpty() ? null : args.get(0);
			return (first == null || first instanceof LispNil) ? LispTrue.INSTANCE : first;
		};
		env.defineFunction(LispNames.READ_LINE, new LispFunction(LispNames.READ_LINE, args -> {
			// (read-line &optional stream eof-error-p eof-value): rontolisp lite
			// defaults eof-error-p to NIL (returns eof-value / nil at EOF) rather than
			// CL's t -- historical convention here that a lot of tests + call sites
			// rely on. The 3-arg (in nil nil) form is the standard "swallow EOF" idiom
			// real libraries use to loop over a file's lines, and it works out of the
			// box because both branches converge on the nil-at-EOF behavior.
			// 0 to 4: the fourth is CL's recursive-p, which nothing here reads.
			requireArgCountBetween(LispNames.READ_LINE, args, 0, 4);
			// A pushed-back character DRAINS into the line rather than signalling:
			// peek-char is a read plus an unread, so a parked character before a line
			// read is an ordinary shape, and answering the line without it would be
			// silently short by one. A pushed-back newline ends the line right there.
			LispVal pushedLine = pushbackTake.apply(args);
			if (pushedLine instanceof LispChar first && first.codePoint() == '\n') {
				return new LispString("");
			}
			try {
				String line;
				LispVal src = resolveInputSrc.apply(args.isEmpty() ? null : args.get(0));
				if (src == null || src instanceof LispNil || src instanceof LispTrue) {
					// Drain buffered output so any prompt is visible before we block on
					// stdin.
					out.flush();
					line = stdinReader.readLine();
				}
				else if (!(src instanceof LispInteger handle)) {
					throw new LispEvalException(LispNames.READ_LINE + " expects an input stream");
				}
				else {
					Closeable entry = streams.get(handle.value());
					if (entry instanceof Socket socket) {
						line = SocketSupport.readLine(socket);
					}
					else if (entry instanceof BufferedReader reader) {
						line = reader.readLine();
					}
					else if (entry instanceof HttpRequestBodyStream body) {
						line = body.readLine();
					}
					else if (entry instanceof RontoIoFileStream io) {
						// End of file is ready()'s answer here: the travelling class
						// cannot spell @Nullable (.kb/jvm-export.md).
						line = io.ready() ? io.readLine() : null;
					}
					else {
						throw new LispEvalException(LispNames.READ_LINE + " expects an input stream");
					}
				}
				if (pushedLine instanceof LispChar first) {
					String head = new String(Character.toChars(first.codePoint()));
					return new LispString(line == null ? head : head + line);
				}
				if (line != null) {
					return new LispString(line);
				}
				// EOF: honor eof-error-p ONLY when it is explicitly non-nil; the default
				// is silent nil to match the callers that predate the 3-arg form.
				boolean eofError = args.size() >= 2 && args.get(1) != LispNil.INSTANCE
						&& !(args.get(1) instanceof LispSymbol sym && "NIL".equals(sym.name()));
				if (eofError) {
					throw endOfFile(eofStream.apply(args));
				}
				return args.size() > 2 ? args.get(2) : LispNil.INSTANCE;
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}));
		// (read-char [stream [eof-error-p [eof-value]]]): one character from standard
		// input or a text stream handle (file streams and string input streams). A
		// CHARACTER is a Unicode CODE POINT on every backend, so a supplementary code
		// point read as a UTF-16 surrogate pair combines into the full code point via a
		// mark/reset peek on the low half. The interpreter's stream table only holds
		// BufferedReader instances (mark is supported).
		java.util.function.BiFunction<String, List<LispVal>, Reader> inputReader = (name, args) -> {
			LispVal src = resolveInputSrc.apply(args.isEmpty() ? null : args.get(0));
			if (src == null || src instanceof LispNil || src instanceof LispTrue) {
				// Drain buffered output so any prompt is visible before we block on
				// stdin.
				out.flush();
				return stdinReader;
			}
			if (!(src instanceof LispInteger handle) || !(streams.get(handle.value()) instanceof BufferedReader r)) {
				throw new LispEvalException(name + " expects an input stream");
			}
			return r;
		};
		// A buffered served-request body decodes its characters off its own byte cursor
		// (UTF-8 at the cursor), so it cannot ride the Reader path below -- a Reader
		// buffers, and the byte cursor read-byte / file-position share must not drift.
		java.util.function.Function<List<LispVal>, @Nullable HttpRequestBodyStream> bufferedBodyArg = args -> {
			LispVal src = resolveInputSrc.apply(args.isEmpty() ? null : args.get(0));
			return src instanceof LispInteger handle
					&& streams.get(handle.value()) instanceof HttpRequestBodyStream body ? body : null;
		};
		// A BIDIRECTIONAL (:io) or :overwrite file stream reads off the same cursor it
		// writes and file-position moves, so it cannot ride the Reader path either --
		// the same reason the buffered body cannot.
		java.util.function.Function<List<LispVal>, @Nullable RontoIoFileStream> ioStreamArg = args -> {
			LispVal src = resolveInputSrc.apply(args.isEmpty() ? null : args.get(0));
			return src instanceof LispInteger handle && streams.get(handle.value()) instanceof RontoIoFileStream io ? io
					: null;
		};
		// The shared end-of-file answer of the character reads (read-char / %peek-char,
		// every stream kind): signal unless eof-error-p is explicitly nil, in which case
		// the eof-value -- CL's default, unlike read-line's lite nil-at-EOF convention.
		java.util.function.Function<List<LispVal>, LispVal> charEof = args -> {
			boolean eofError = args.size() < 2 || args.get(1) != LispNil.INSTANCE;
			if (eofError) {
				throw endOfFile(eofStream.apply(args));
			}
			return args.size() > 2 ? args.get(2) : LispNil.INSTANCE;
		};
		java.util.function.Function<List<LispVal>, LispVal> readChar = args -> {
			requireArgCountBetween(LispNames.READ_CHAR, args, 0, 4);
			LispVal pushed = pushbackTake.apply(args);
			if (pushed instanceof LispChar) {
				return pushed;
			}
			HttpRequestBodyStream bufferedBody = bufferedBodyArg.apply(args);
			if (bufferedBody != null) {
				int cp = bufferedBody.readCodePoint();
				return (cp < 0) ? charEof.apply(args) : new LispChar(cp);
			}
			// A socket entry decodes ONE UTF-8 sequence off the raw input stream: the
			// entry is a Socket, not a Reader, and wrapping one here would read ahead and
			// swallow bytes a following read-byte / read-line owes the caller.
			Socket socket = socketEntry.apply(resolveInputSrc.apply(args.isEmpty() ? null : args.get(0)));
			if (socket != null) {
				int cp = SocketSupport.readChar(socket);
				return (cp < 0) ? charEof.apply(args) : new LispChar(cp);
			}
			try {
				RontoIoFileStream ioSource = ioStreamArg.apply(args);
				if (ioSource != null) {
					int cp = ioSource.readCodePoint();
					return (cp < 0) ? charEof.apply(args) : new LispChar(cp);
				}
				Reader reader = inputReader.apply(LispNames.READ_CHAR, args);
				int c = reader.read();
				if (c < 0) {
					return charEof.apply(args);
				}
				if (Character.isHighSurrogate((char) c)) {
					reader.mark(1);
					int low = reader.read();
					if (low >= 0 && Character.isLowSurrogate((char) low)) {
						c = Character.toCodePoint((char) c, (char) low);
					}
					else if (low >= 0) {
						reader.reset();
					}
				}
				return new LispChar(c);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		};
		env.defineFunction(LispNames.READ_CHAR, new LispFunction(LispNames.READ_CHAR, readChar::apply));
		// (%peek-char [stream [eof-error-p [eof-value]]]): the next character, LEFT IN
		// THE STREAM. A mark/reset around the read is what makes it a peek -- the stream
		// table holds BufferedReader instances only, and the mark budget of 2 covers a
		// surrogate pair, so the position after the reset is exactly the position before.
		java.util.function.Function<List<LispVal>, LispVal> peekChar = args -> {
			if (args.size() > 3) {
				throw new LispEvalException(LispNames.PEEK_CHAR + " expects 0 to 3 arguments");
			}
			// A peek LEAVES the character in the stream, so the cell is read, not
			// drained; the peek-type loop above drains it through read-char when the
			// character is one to skip.
			LispVal peekKey = pushbackKey.apply(args.isEmpty() ? LispNil.INSTANCE : args.get(0));
			if (pushbackStream[0].equals(peekKey)) {
				return pushbackChar[0];
			}
			HttpRequestBodyStream bufferedBody = bufferedBodyArg.apply(args);
			if (bufferedBody != null) {
				int cp = bufferedBody.peekCodePoint();
				return (cp < 0) ? charEof.apply(args) : new LispChar(cp);
			}
			try {
				RontoIoFileStream ioSource = ioStreamArg.apply(args);
				if (ioSource != null) {
					int cp = ioSource.peekCodePoint();
					return (cp < 0) ? charEof.apply(args) : new LispChar(cp);
				}
				Reader reader = inputReader.apply(LispNames.PEEK_CHAR, args);
				reader.mark(2);
				int c = reader.read();
				if (c < 0) {
					reader.reset();
					return charEof.apply(args);
				}
				if (Character.isHighSurrogate((char) c)) {
					int low = reader.read();
					if (low >= 0 && Character.isLowSurrogate((char) low)) {
						c = Character.toCodePoint((char) c, (char) low);
					}
				}
				reader.reset();
				return new LispChar(c);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		};
		env.defineFunction(LispNames.PEEK_CHAR_INTERNAL, new LispFunction(LispNames.PEEK_CHAR_INTERNAL, peekChar));
		// read-char-no-hang on a stream HANDLE is read-char: no source rontolisp can open
		// answers "a character would block" separately from "read one", and CL lets an
		// implementation say so. A Gray instance reaches
		// rontolisp:stream-read-char-no-hang through LispEvaluator's wrap instead, which
		// is where a class with a genuinely non-blocking source gets its answer.
		env.defineFunction(LispNames.READ_CHAR_NO_HANG, new LispFunction(LispNames.READ_CHAR_NO_HANG, args -> {
			requireArgCountBetween(LispNames.READ_CHAR_NO_HANG, args, 0, 4);
			return readChar.apply(args);
		}));
		// unread-char: the Gray protocol's own one-slot pushback carries it for an
		// INSTANCE stream (LispEvaluator's wrap); a stream HANDLE parks the character in
		// the cell above, which the character reads drain. A second unread with the cell
		// still full SIGNALS -- CL calls two unreads without an intervening read an
		// error, and one slot is all the protocol's own default keeps either.
		env.defineFunction(LispNames.UNREAD_CHAR, new LispFunction(LispNames.UNREAD_CHAR, args -> {
			requireArgCountBetween(LispNames.UNREAD_CHAR, args, 1, 2);
			if (!(args.get(0) instanceof LispChar parked)) {
				throw new LispEvalException(LispNames.UNREAD_CHAR + " expects a character");
			}
			if (!(pushbackStream[0] instanceof LispNil)) {
				throw new LispEvalException(LispMacroExpander.UNREAD_CHAR_TWICE_MESSAGE);
			}
			pushbackChar[0] = parked;
			pushbackStream[0] = pushbackKey.apply(args.size() >= 2 ? args.get(1) : LispNil.INSTANCE);
			return LispNil.INSTANCE;
		}));
		// (peek-char [peek-type [stream [eof-error-p [eof-value]]]]): the peek-type
		// skipping forms of CL 21.2 -- nil peeks, t skips whitespace, a character skips
		// up to that character; the character stopped on stays in the stream in every
		// case. The compiled backends reach the same behavior through
		// LispMacroExpander.expandPeekChar, which lowers the loop onto %peek-char.
		env.defineFunction(LispNames.PEEK_CHAR, new LispFunction(LispNames.PEEK_CHAR, args -> {
			if (args.size() > 4) {
				throw new LispEvalException(LispNames.PEEK_CHAR + " expects 0 to 4 arguments");
			}
			LispVal peekType = args.isEmpty() ? LispNil.INSTANCE : args.get(0);
			if (!(peekType instanceof LispNil || peekType instanceof LispTrue || peekType instanceof LispChar)) {
				throw new LispEvalException(LispNames.PEEK_CHAR + " expects nil, t or a character as the peek type");
			}
			List<LispVal> rest = args.isEmpty() ? List.of() : args.subList(1, args.size());
			while (true) {
				LispVal peeked = peekChar.apply(rest);
				if (peekType instanceof LispNil || !(peeked instanceof LispChar ch)) {
					return peeked;
				}
				boolean stop = (peekType instanceof LispTrue) ? !isLispWhitespace(ch.codePoint())
						: (peekType instanceof LispChar want && want.codePoint() == ch.codePoint());
				if (stop) {
					return peeked;
				}
				readChar.apply(
						List.of(rest.isEmpty() ? LispNil.INSTANCE : rest.get(0), LispNil.INSTANCE, LispNil.INSTANCE));
			}
		}));
		// The byte ops take the same stream DESIGNATOR every other stream operation
		// takes: nil resolves through *standard-input* / *standard-output*, and t --
		// which those variables hold by default -- is the process standard stream. So
		// (read-byte *standard-input*), CL's own spelling for a bivalent standard
		// stream, works without giving the standard-stream variables a handle value.
		// The raw process streams are used directly, NOT the stdinReader/emit character
		// path: mixing text and byte reads on one stream is out of contract (the reader
		// buffers ahead), while the byte WRITE shares the PrintStream `out` so binary
		// output and princ cannot reorder.
		//
		// Only the DESIGNATOR names a process stream; a numeric handle stays a table
		// index (2, the *error-output* value, is the one exception, and it is a real
		// table entry here). The JVM cannot promise more than that -- it reserves 0/1/2
		// only for a program that names *error-output*, so elsewhere a user stream IS
		// handle 0 -- and a rule that holds on one backend only is worse than none.
		env.defineFunction(LispNames.READ_BYTE, new LispFunction(LispNames.READ_BYTE, args -> {
			requireMinArgCount(LispNames.READ_BYTE, args, 1);
			requireArgCountBetween(LispNames.READ_BYTE, args, 1, 3);
			LispVal src = resolveInputSrc.apply(args.get(0));
			int b;
			if (src == null || src instanceof LispNil || src instanceof LispTrue) {
				try {
					b = in.read();
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}
			else if (!(src instanceof LispInteger handle)) {
				throw new LispEvalException(LispNames.READ_BYTE + " expects a binary input stream");
			}
			else {
				Closeable byteEntry = streams.get(handle.value());
				if (byteEntry instanceof Socket socket) {
					b = SocketSupport.readByte(socket);
				}
				else if (byteEntry instanceof RontoIoFileStream io) {
					try {
						b = io.readByte();
					}
					catch (IOException ex) {
						throw new UncheckedIOException(ex);
					}
				}
				else if (byteEntry instanceof InputStream in2) {
					try {
						b = in2.read();
					}
					catch (IOException ex) {
						throw new UncheckedIOException(ex);
					}
					if (streamPaths.containsKey(handle.value())) {
						streamPositions.merge(handle.value(), 1L, Long::sum);
					}
				}
				else {
					throw new LispEvalException(LispNames.READ_BYTE + " expects a binary input stream");
				}
			}
			if (b < 0) {
				boolean eofError = args.size() < 2 || args.get(1) != LispNil.INSTANCE;
				if (eofError) {
					throw endOfFile(eofStream.apply(args));
				}
				return args.size() > 2 ? args.get(2) : LispNil.INSTANCE;
			}
			return new LispInteger(b);
		}));
		env.defineFunction(LispNames.WRITE_BYTE, new LispFunction(LispNames.WRITE_BYTE, args -> {
			requireArgCount(LispNames.WRITE_BYTE, args, 2);
			if (!(args.get(0) instanceof LispInteger value) || value.value() < 0 || value.value() > 255) {
				throw new LispEvalException(LispNames.WRITE_BYTE + " expects an integer between 0 and 255");
			}
			LispVal dest = resolveOutputDest.apply(args.get(1));
			if (dest == null || dest instanceof LispNil || dest instanceof LispTrue) {
				// The same PrintStream princ writes through, so a program that mixes
				// text and raw bytes on standard output keeps them in order.
				out.write((int) value.value());
				atLineStart[0] = value.value() == '\n';
				return value;
			}
			if (dest instanceof LispInteger err && err.value() == StreamDesignators.STANDARD_ERROR_HANDLE) {
				System.err.write((int) value.value());
				System.err.flush();
				return value;
			}
			if (!(dest instanceof LispInteger handle)) {
				throw new LispEvalException(LispNames.WRITE_BYTE + " expects a binary output stream");
			}
			Closeable byteEntry = streams.get(handle.value());
			if (byteEntry instanceof Socket socket) {
				SocketSupport.writeByte(socket, (int) value.value());
				return value;
			}
			if (byteEntry instanceof RontoIoFileStream io) {
				try {
					io.writeByte((int) value.value());
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
				return value;
			}
			if (!(byteEntry instanceof OutputStream out2)) {
				throw new LispEvalException(LispNames.WRITE_BYTE + " expects a binary output stream");
			}
			try {
				out2.write((int) value.value());
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			if (streamPaths.containsKey(handle.value())) {
				streamPositions.merge(handle.value(), 1L, Long::sum);
			}
			return value;
		}));
		// The bulk binary-I/O primitives behind read-sequence / write-sequence over a
		// PACKED buffer (.kb/binary-sequence-io.md): raw little-endian elements moved in
		// one native transfer instead of one read-byte/write-byte per byte. Handled: a
		// binary file stream (an InputStream / OutputStream table entry) and the
		// standard-stream designators; anything else -- a Gray instance, a text stream, a
		// socket, a general array -- answers nil and the expansion's element loop runs.
		env.defineFunction(LispNames.READ_SEQUENCE_PACKED, new LispFunction(LispNames.READ_SEQUENCE_PACKED, args -> {
			requireArgCount(LispNames.READ_SEQUENCE_PACKED, args, 4);
			PackedBuffer buf = PackedBuffer.of(args.get(0));
			if (buf == null) {
				return LispNil.INSTANCE;
			}
			LispVal src = resolveInputSrc.apply(args.get(1));
			InputStream in2;
			if (src == null || src instanceof LispNil || src instanceof LispTrue) {
				in2 = in;
			}
			else if (src instanceof LispInteger handle && streams.get(handle.value()) instanceof InputStream entry) {
				in2 = entry;
			}
			else {
				return LispNil.INSTANCE;
			}
			int start = PackedBuffer.bound(LispNames.READ_SEQUENCE, args.get(2), 0);
			int end = PackedBuffer.bound(LispNames.READ_SEQUENCE, args.get(3), buf.size());
			if (start < 0 || end > buf.size() || start > end) {
				throw new LispEvalException(LispNames.READ_SEQUENCE + ": bounds " + start + ".." + end
						+ " exceed the buffer size " + buf.size());
			}
			try {
				byte[] bytes = in2.readNBytes((end - start) * buf.width());
				int n = bytes.length / buf.width();
				buf.load(ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN), start, n);
				if (src instanceof LispInteger handle && streamPaths.containsKey(handle.value())) {
					streamPositions.merge(handle.value(), (long) bytes.length, Long::sum);
				}
				return new LispInteger(start + n);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}));
		// The bulk CHARACTER-I/O primitive behind read-sequence over a character buffer
		// (.kb/character-sequence-io.md): a block of code points decoded into the buffer
		// in one host read instead of one read-char per character. Handled: a
		// BufferedReader table entry (a file or a string input stream) and the
		// standard-stream designators; a Gray instance, a socket, a served request body,
		// a binary stream and any non-character buffer answer nil and the expansion's
		// per-character loop runs. The pushback cell is deliberately NOT consulted, which
		// is what the loop this replaces does (see read-line's cell above).
		env.defineFunction(LispNames.READ_SEQUENCE_CHARS, new LispFunction(LispNames.READ_SEQUENCE_CHARS, args -> {
			requireArgCount(LispNames.READ_SEQUENCE_CHARS, args, 4);
			if (!(args.get(0) instanceof LispString buf) || buf.sourceLiteral()) {
				return LispNil.INSTANCE;
			}
			LispVal src = resolveInputSrc.apply(args.get(1));
			Reader reader;
			if (src == null || src instanceof LispNil || src instanceof LispTrue) {
				out.flush();
				reader = stdinReader;
			}
			else if (src instanceof LispInteger handle && streams.get(handle.value()) instanceof BufferedReader entry) {
				reader = entry;
			}
			else {
				return LispNil.INSTANCE;
			}
			int start = PackedBuffer.bound(LispNames.READ_SEQUENCE, args.get(2), 0);
			int end = PackedBuffer.bound(LispNames.READ_SEQUENCE, args.get(3), buf.length());
			if (start < 0 || end > buf.length() || start > end) {
				// Out of the buffer: decline, so the loop signals exactly as it did.
				return LispNil.INSTANCE;
			}
			try {
				return new LispInteger(readCodePoints(reader, buf, start, end));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}));
		env.defineFunction(LispNames.WRITE_SEQUENCE_PACKED, new LispFunction(LispNames.WRITE_SEQUENCE_PACKED, args -> {
			requireArgCount(LispNames.WRITE_SEQUENCE_PACKED, args, 4);
			PackedBuffer buf = PackedBuffer.of(args.get(0));
			if (buf == null) {
				return LispNil.INSTANCE;
			}
			LispVal dest = resolveOutputDest.apply(args.get(1));
			OutputStream out2;
			if (dest == null || dest instanceof LispNil || dest instanceof LispTrue) {
				out2 = out;
			}
			else if (dest instanceof LispInteger err && err.value() == StreamDesignators.STANDARD_ERROR_HANDLE) {
				out2 = System.err;
			}
			else if (dest instanceof LispInteger handle && streams.get(handle.value()) instanceof OutputStream entry) {
				out2 = entry;
			}
			else {
				return LispNil.INSTANCE;
			}
			int start = PackedBuffer.bound(LispNames.WRITE_SEQUENCE, args.get(2), 0);
			int end = PackedBuffer.bound(LispNames.WRITE_SEQUENCE, args.get(3), buf.size());
			if (start < 0 || end > buf.size() || start > end) {
				throw new LispEvalException(LispNames.WRITE_SEQUENCE + ": bounds " + start + ".." + end
						+ " exceed the buffer size " + buf.size());
			}
			byte[] bytes = new byte[(end - start) * buf.width()];
			buf.store(ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN), start, end - start);
			try {
				out2.write(bytes);
				if (out2 == out && bytes.length > 0) {
					atLineStart[0] = bytes[bytes.length - 1] == '\n';
				}
				else if (out2 == System.err) {
					out2.flush();
				}
				if (dest instanceof LispInteger handle && streamPaths.containsKey(handle.value())) {
					streamPositions.merge(handle.value(), (long) bytes.length, Long::sum);
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return args.get(0);
		}));
		// Element types beyond character and (unsigned-byte 8): the octet primitives
		// above stay exactly what they were, and these wrappers compose a WIDE element
		// -- more than one octet, or a signed one -- out of them, little-endian and
		// two's complement (StreamElementType; .kb/read-load-streams.md, "Element types
		// wider and narrower than one octet"). file-length and file-position count
		// ELEMENTS, so they divide (and a set multiplies) by the width; the packed bulk
		// path declines a wide stream so the per-element loop composes. The compile
		// paths run the same rules in Lisp (the %wide-* prelude entries).
		java.util.function.Function<@Nullable LispVal, @Nullable StreamElementType> wideElementType = target -> {
			if (target instanceof LispInteger handle) {
				StreamElementType type = streamElementTypes.get(handle.value());
				return type != null && type.isWide() ? type : null;
			}
			return null;
		};
		java.util.function.Function<List<LispVal>, LispVal> octetReadByte = ((LispFunction) env
			.lookupFunction(LispNames.READ_BYTE)).body();
		env.defineFunction(LispNames.READ_BYTE, new LispFunction(LispNames.READ_BYTE, args -> {
			requireArgCountBetween(LispNames.READ_BYTE, args, 1, 3);
			StreamElementType type = wideElementType.apply(resolveInputSrc.apply(args.get(0)));
			if (type == null) {
				return octetReadByte.apply(args);
			}
			byte[] octets = new byte[type.octets()];
			List<LispVal> raw = List.of(args.get(0), LispNil.INSTANCE, LispNil.INSTANCE);
			for (int i = 0; i < octets.length; i++) {
				if (!(octetReadByte.apply(raw) instanceof LispInteger b)) {
					// End of file, before the element or inside it: CL has no partial
					// element to hand back, so both are the stream's end.
					boolean eofError = args.size() < 2 || args.get(1) != LispNil.INSTANCE;
					if (eofError) {
						throw endOfFile(eofStream.apply(args));
					}
					return args.size() > 2 ? args.get(2) : LispNil.INSTANCE;
				}
				octets[i] = (byte) b.value();
			}
			return composeElement(octets, type.signed());
		}));
		java.util.function.Function<List<LispVal>, LispVal> octetWriteByte = ((LispFunction) env
			.lookupFunction(LispNames.WRITE_BYTE)).body();
		env.defineFunction(LispNames.WRITE_BYTE, new LispFunction(LispNames.WRITE_BYTE, args -> {
			requireArgCount(LispNames.WRITE_BYTE, args, 2);
			StreamElementType type = wideElementType.apply(resolveOutputDest.apply(args.get(1)));
			if (type == null) {
				return octetWriteByte.apply(args);
			}
			BigInteger value = switch (args.get(0)) {
				case LispInteger i -> BigInteger.valueOf(i.value());
				case LispBigInteger b -> b.value();
				default -> null;
			};
			int bits = 8 * type.octets();
			BigInteger lo = type.signed() ? BigInteger.ONE.shiftLeft(bits - 1).negate() : BigInteger.ZERO;
			BigInteger hi = (type.signed() ? BigInteger.ONE.shiftLeft(bits - 1) : BigInteger.ONE.shiftLeft(bits))
				.subtract(BigInteger.ONE);
			if (value == null || value.compareTo(lo) < 0 || value.compareTo(hi) > 0) {
				throw new LispEvalException(LispNames.WRITE_BYTE + " expects an integer between " + lo + " and " + hi);
			}
			for (int i = 0; i < type.octets(); i++) {
				octetWriteByte.apply(List.of(new LispInteger(value.shiftRight(8 * i).intValue() & 0xFF), args.get(1)));
			}
			return args.get(0);
		}));
		java.util.function.Function<List<LispVal>, LispVal> octetFileLength = ((LispFunction) env
			.lookupFunction(LispNames.FILE_LENGTH)).body();
		env.defineFunction(LispNames.FILE_LENGTH, new LispFunction(LispNames.FILE_LENGTH, args -> {
			LispVal octets = octetFileLength.apply(args);
			StreamElementType type = wideElementType.apply(streamTarget(args.get(0)));
			return type != null && octets instanceof LispInteger n ? new LispInteger(n.value() / type.octets())
					: octets;
		}));
		java.util.function.Function<List<LispVal>, LispVal> octetFilePosition = ((LispFunction) env
			.lookupFunction(LispNames.FILE_POSITION)).body();
		env.defineFunction(LispNames.FILE_POSITION, new LispFunction(LispNames.FILE_POSITION, args -> {
			StreamElementType type = args.isEmpty() ? null : wideElementType.apply(streamTarget(args.get(0)));
			if (type == null) {
				return octetFilePosition.apply(args);
			}
			if (args.size() >= 2) {
				// :start / :end name the same octet as the same element; an index is
				// scaled to its first octet.
				return octetFilePosition.apply(args.get(1) instanceof LispInteger n
						? List.of(args.get(0), new LispInteger(n.value() * type.octets())) : args);
			}
			LispVal octets = octetFilePosition.apply(args);
			return octets instanceof LispInteger n ? new LispInteger(n.value() / type.octets()) : octets;
		}));
		// A character parked in the unread-char cell is not consumed yet (sbcl), so the
		// query answers the offset BEFORE it -- one character on a string input stream,
		// whose position counts characters, its UTF-8 length on a file stream -- and a
		// set drops it. unread-char.lisp's %unread-file-position /
		// %unread-file-position-set are the compile paths' twins.
		java.util.function.Function<List<LispVal>, LispVal> unparkedFilePosition = ((LispFunction) env
			.lookupFunction(LispNames.FILE_POSITION)).body();
		env.defineFunction(LispNames.FILE_POSITION, new LispFunction(LispNames.FILE_POSITION, args -> {
			if (args.isEmpty() || !pushbackStream[0].equals(pushbackKey.apply(args.get(0)))) {
				return unparkedFilePosition.apply(args);
			}
			if (args.size() >= 2) {
				pushbackStream[0] = LispNil.INSTANCE;
				pushbackChar[0] = LispNil.INSTANCE;
				return unparkedFilePosition.apply(args);
			}
			LispVal position = unparkedFilePosition.apply(args);
			if (position instanceof LispInteger n && pushbackChar[0] instanceof LispChar parked) {
				int cp = parked.codePoint();
				boolean characters = streamTarget(args.get(0)) instanceof LispInteger handle
						&& streams.get(handle.value()) instanceof RontoStringInputStream;
				return new LispInteger(
						n.value() - (characters ? 1 : (cp < 0x80) ? 1 : (cp < 0x800) ? 2 : (cp < 0x10000) ? 3 : 4));
			}
			return position;
		}));
		for (String packed : List.of(LispNames.READ_SEQUENCE_PACKED, LispNames.WRITE_SEQUENCE_PACKED)) {
			java.util.function.Function<List<LispVal>, LispVal> octetPacked = ((LispFunction) env
				.lookupFunction(packed)).body();
			env.defineFunction(packed,
					new LispFunction(packed,
							args -> args.size() == 4 && wideElementType.apply(streamTarget(args.get(1))) != null
									? LispNil.INSTANCE : octetPacked.apply(args)));
		}
		env.defineFunction(LispNames.STREAM_ELEMENT_TYPE, new LispFunction(LispNames.STREAM_ELEMENT_TYPE, args -> {
			requireArgCount(LispNames.STREAM_ELEMENT_TYPE, args, 1);
			// A binary FILE stream answers the type it was opened with, widened the way
			// SBCL widens it; every other stream is a character stream.
			if (streamTarget(args.get(0)) instanceof LispInteger handle
					&& streamElementTypes.get(handle.value()) instanceof StreamElementType type) {
				return type.spec();
			}
			return new LispSymbol(LispNames.CHARACTER_TYPE);
		}));
		// TCP sockets (rontolisp package). A socket or listener handle lives in the same
		// stream table as file streams: read-line/write-line/read-byte/write-byte
		// dispatch on the entry type above, and close works unchanged (Socket and
		// ServerSocket are Closeable). Sockets are bidirectional and mode-less -- they
		// never go through open. The functions are registered here (not in
		// registerPackages) because they share the stream table local to this method.
		String tcpConnectName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_CONNECT);
		env.defineFunction(tcpConnectName, new LispFunction(tcpConnectName, args -> {
			requireArgCount(LispNames.TCP_CONNECT, args, 2);
			if (!(args.get(0) instanceof LispString host)) {
				throw new LispEvalException(
						LispNames.TCP_CONNECT + " expects a string host, got: " + args.get(0).print());
			}
			if (!(args.get(1) instanceof LispInteger port)) {
				throw new LispEvalException(
						LispNames.TCP_CONNECT + " expects an integer port, got: " + args.get(1).print());
			}
			Socket socket = SocketSupport.connect(host.value(), (int) port.value());
			long handle = nextStreamHandle.getAndIncrement();
			streams.put(handle, socket);
			return streamValue(handle, LispLayout.Kinds.SOCKET);
		}));
		String tlsConnectName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_CONNECT);
		env.defineFunction(tlsConnectName, new LispFunction(tlsConnectName, args -> {
			if (args.size() != 2 && args.size() != 4) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.TLS_CONNECT + " expects 2 or 4 arguments, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString host)) {
				throw new LispEvalException(
						LispNames.TLS_CONNECT + " expects a string host, got: " + args.get(0).print());
			}
			if (!(args.get(1) instanceof LispInteger port)) {
				throw new LispEvalException(
						LispNames.TLS_CONNECT + " expects an integer port, got: " + args.get(1).print());
			}
			boolean insecure = false;
			if (args.size() == 4) {
				if (!(args.get(2) instanceof LispSymbol option) || !option.isKeyword()
						|| !":INSECURE".equals(option.name())) {
					throw new LispEvalException(
							LispNames.TLS_CONNECT + " expects :insecure, got: " + args.get(2).print());
				}
				insecure = !(args.get(3) instanceof LispNil);
			}
			Socket socket = SocketSupport.connectTls(host.value(), (int) port.value(), insecure);
			long handle = nextStreamHandle.getAndIncrement();
			streams.put(handle, socket);
			return streamValue(handle, LispLayout.Kinds.SOCKET);
		}));
		// tls-upgrade wraps an ALREADY-CONNECTED socket handle in TLS as a client (the
		// cl+ssl shim's make-ssl-client-stream substrate): same option shape as
		// tls-connect (a literal :insecure keyword, runtime value), but the first
		// argument is an existing stream handle and the result is a NEW handle over the
		// same connection.
		String tlsUpgradeName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_UPGRADE);
		env.defineFunction(tlsUpgradeName, new LispFunction(tlsUpgradeName, args -> {
			if (args.size() != 2 && args.size() != 4) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.TLS_UPGRADE + " expects 2 or 4 arguments, got " + args.size());
			}
			if (!(streamTarget(args.get(0)) instanceof LispInteger handle)
					|| !(streams.get(handle.value()) instanceof Socket socket)) {
				throw new LispEvalException(
						LispNames.TLS_UPGRADE + " expects a connected socket handle, got: " + args.get(0).print());
			}
			if (!(args.get(1) instanceof LispString host)) {
				throw new LispEvalException(
						LispNames.TLS_UPGRADE + " expects a string host, got: " + args.get(1).print());
			}
			boolean insecure = false;
			if (args.size() == 4) {
				if (!(args.get(2) instanceof LispSymbol option) || !option.isKeyword()
						|| !":INSECURE".equals(option.name())) {
					throw new LispEvalException(
							LispNames.TLS_UPGRADE + " expects :insecure, got: " + args.get(2).print());
				}
				insecure = !(args.get(3) instanceof LispNil);
			}
			Socket upgraded = SocketSupport.upgradeTls(socket, host.value(), insecure);
			long upgradedHandle = nextStreamHandle.getAndIncrement();
			streams.put(upgradedHandle, upgraded);
			return streamValue(upgradedHandle, LispLayout.Kinds.SOCKET);
		}));
		String tcpListenName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_LISTEN);
		env.defineFunction(tcpListenName, new LispFunction(tcpListenName, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.TCP_LISTEN + " expects 1 or 2 arguments, got " + args.size());
			}
			if (!(args.get(0) instanceof LispInteger port)) {
				throw new LispEvalException(
						LispNames.TCP_LISTEN + " expects an integer port, got: " + args.get(0).print());
			}
			String host = null;
			if (args.size() == 2) {
				if (!(args.get(1) instanceof LispString hostString)) {
					throw new LispEvalException(
							LispNames.TCP_LISTEN + " expects a string host, got: " + args.get(1).print());
				}
				host = hostString.value();
			}
			ServerSocket listener = SocketSupport.listen((int) port.value(), host);
			long handle = nextStreamHandle.getAndIncrement();
			streams.put(handle, listener);
			return streamValue(handle, LispLayout.Kinds.SOCKET_SERVER);
		}));
		String tlsListenName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_LISTEN);
		env.defineFunction(tlsListenName, new LispFunction(tlsListenName, args -> {
			if (args.size() < 3 || args.size() > 4) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.TLS_LISTEN + " expects 3 or 4 arguments, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString keyStore)) {
				throw new LispEvalException(
						LispNames.TLS_LISTEN + " expects a string keystore path, got: " + args.get(0).print());
			}
			if (!(args.get(1) instanceof LispString password)) {
				throw new LispEvalException(
						LispNames.TLS_LISTEN + " expects a string password, got: " + args.get(1).print());
			}
			if (!(args.get(2) instanceof LispInteger port)) {
				throw new LispEvalException(
						LispNames.TLS_LISTEN + " expects an integer port, got: " + args.get(2).print());
			}
			String host = null;
			if (args.size() == 4) {
				if (!(args.get(3) instanceof LispString hostString)) {
					throw new LispEvalException(
							LispNames.TLS_LISTEN + " expects a string host, got: " + args.get(3).print());
				}
				host = hostString.value();
			}
			ServerSocket listener = SocketSupport.listenTls(keyStore.value(), password.value(), (int) port.value(),
					host);
			long handle = nextStreamHandle.getAndIncrement();
			streams.put(handle, listener);
			return streamValue(handle, LispLayout.Kinds.SOCKET_SERVER);
		}));
		String tlsListenPemName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_LISTEN_PEM);
		env.defineFunction(tlsListenPemName, new LispFunction(tlsListenPemName, args -> {
			if (args.size() < 3 || args.size() > 4) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.TLS_LISTEN_PEM + " expects 3 or 4 arguments, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString certPath)) {
				throw new LispEvalException(
						LispNames.TLS_LISTEN_PEM + " expects a string certificate path, got: " + args.get(0).print());
			}
			if (!(args.get(1) instanceof LispString keyPath)) {
				throw new LispEvalException(
						LispNames.TLS_LISTEN_PEM + " expects a string key path, got: " + args.get(1).print());
			}
			if (!(args.get(2) instanceof LispInteger port)) {
				throw new LispEvalException(
						LispNames.TLS_LISTEN_PEM + " expects an integer port, got: " + args.get(2).print());
			}
			String host = null;
			if (args.size() == 4) {
				if (!(args.get(3) instanceof LispString hostString)) {
					throw new LispEvalException(
							LispNames.TLS_LISTEN_PEM + " expects a string host, got: " + args.get(3).print());
				}
				host = hostString.value();
			}
			ServerSocket listener = SocketSupport.listenTlsPem(certPath.value(), keyPath.value(), (int) port.value(),
					host);
			long handle = nextStreamHandle.getAndIncrement();
			streams.put(handle, listener);
			return streamValue(handle, LispLayout.Kinds.SOCKET_SERVER);
		}));
		String tlsListenP12Name = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_LISTEN_P12);
		env.defineFunction(tlsListenP12Name, new LispFunction(tlsListenP12Name, args -> {
			if (args.size() < 3 || args.size() > 4) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.TLS_LISTEN_P12 + " expects 3 or 4 arguments, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString base64)) {
				throw new LispEvalException(
						LispNames.TLS_LISTEN_P12 + " expects a string keystore, got: " + args.get(0).print());
			}
			if (!(args.get(1) instanceof LispString password)) {
				throw new LispEvalException(
						LispNames.TLS_LISTEN_P12 + " expects a string password, got: " + args.get(1).print());
			}
			if (!(args.get(2) instanceof LispInteger port)) {
				throw new LispEvalException(
						LispNames.TLS_LISTEN_P12 + " expects an integer port, got: " + args.get(2).print());
			}
			String host = null;
			if (args.size() == 4) {
				if (!(args.get(3) instanceof LispString hostString)) {
					throw new LispEvalException(
							LispNames.TLS_LISTEN_P12 + " expects a string host, got: " + args.get(3).print());
				}
				host = hostString.value();
			}
			ServerSocket listener = SocketSupport.listenTlsP12(base64.value(), password.value(), (int) port.value(),
					host);
			long handle = nextStreamHandle.getAndIncrement();
			streams.put(handle, listener);
			return streamValue(handle, LispLayout.Kinds.SOCKET_SERVER);
		}));
		String tcpAcceptName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_ACCEPT);
		env.defineFunction(tcpAcceptName, new LispFunction(tcpAcceptName, args -> {
			requireArgCount(LispNames.TCP_ACCEPT, args, 1);
			if (!(streamTarget(args.get(0)) instanceof LispInteger handle)
					|| !(streams.get(handle.value()) instanceof ServerSocket listener)) {
				throw new LispEvalException(LispNames.TCP_ACCEPT + " expects a listener handle");
			}
			Socket socket = SocketSupport.accept(listener);
			long acceptedHandle = nextStreamHandle.getAndIncrement();
			streams.put(acceptedHandle, socket);
			return streamValue(acceptedHandle, LispLayout.Kinds.SOCKET);
		}));
		String tcpLocalPortName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_LOCAL_PORT);
		env.defineFunction(tcpLocalPortName, new LispFunction(tcpLocalPortName, args -> {
			requireArgCount(LispNames.TCP_LOCAL_PORT, args, 1);
			if (!(streamTarget(args.get(0)) instanceof LispInteger handle)) {
				throw new LispEvalException(LispNames.TCP_LOCAL_PORT + " expects a socket or listener handle");
			}
			Closeable entry = streams.get(handle.value());
			long port = (entry == null) ? -1 : SocketSupport.localPort(entry);
			if (port < 0) {
				throw new LispEvalException(LispNames.TCP_LOCAL_PORT + " expects a socket or listener handle");
			}
			return new LispInteger(port);
		}));
		String tcpLocalAddressName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_LOCAL_ADDRESS);
		env.defineFunction(tcpLocalAddressName, new LispFunction(tcpLocalAddressName, args -> {
			requireArgCount(LispNames.TCP_LOCAL_ADDRESS, args, 1);
			if (!(streamTarget(args.get(0)) instanceof LispInteger handle)) {
				throw new LispEvalException(LispNames.TCP_LOCAL_ADDRESS + " expects a socket or listener handle");
			}
			Closeable entry = streams.get(handle.value());
			String address = (entry == null) ? null : SocketSupport.localAddress(entry);
			if (address == null) {
				throw new LispEvalException(LispNames.TCP_LOCAL_ADDRESS + " expects a socket or listener handle");
			}
			return new LispString(address);
		}));
		String tcpPeerAddressName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_PEER_ADDRESS);
		env.defineFunction(tcpPeerAddressName, new LispFunction(tcpPeerAddressName, args -> {
			requireArgCount(LispNames.TCP_PEER_ADDRESS, args, 1);
			if (!(streamTarget(args.get(0)) instanceof LispInteger handle)) {
				throw new LispEvalException(LispNames.TCP_PEER_ADDRESS + " expects a connected socket handle");
			}
			Closeable entry = streams.get(handle.value());
			String address = (entry == null) ? null : SocketSupport.peerAddress(entry);
			if (address == null) {
				throw new LispEvalException(LispNames.TCP_PEER_ADDRESS + " expects a connected socket handle");
			}
			return new LispString(address);
		}));
		String tcpPeerPortName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_PEER_PORT);
		env.defineFunction(tcpPeerPortName, new LispFunction(tcpPeerPortName, args -> {
			requireArgCount(LispNames.TCP_PEER_PORT, args, 1);
			if (!(streamTarget(args.get(0)) instanceof LispInteger handle)) {
				throw new LispEvalException(LispNames.TCP_PEER_PORT + " expects a connected socket handle");
			}
			Closeable entry = streams.get(handle.value());
			long port = (entry == null) ? -1 : SocketSupport.peerPort(entry);
			if (port < 0) {
				throw new LispEvalException(LispNames.TCP_PEER_PORT + " expects a connected socket handle");
			}
			return new LispInteger(port);
		}));
		// (tcp-set-timeout handle milliseconds): a per-socket read deadline
		// (SO_TIMEOUT). Milliseconds is a non-negative integer like rontolisp:wait-for;
		// nil clears the deadline. Listener handles are deliberately not accepted (the
		// deadline is a READ deadline; an accept deadline has no consumer).
		String tcpSetTimeoutName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_SET_TIMEOUT);
		env.defineFunction(tcpSetTimeoutName, new LispFunction(tcpSetTimeoutName, args -> {
			requireArgCount(LispNames.TCP_SET_TIMEOUT, args, 2);
			if (!(streamTarget(args.get(0)) instanceof LispInteger handle)
					|| !(streams.get(handle.value()) instanceof Socket socket)) {
				throw new LispEvalException(
						LispNames.TCP_SET_TIMEOUT + " expects a connected socket handle, got: " + args.get(0).print());
			}
			LispVal ms = args.get(1);
			if (ms instanceof LispNil) {
				SocketSupport.setTimeout(socket, 0);
				return LispNil.INSTANCE;
			}
			if (!(ms instanceof LispInteger millis) || millis.value() < 0) {
				throw new LispEvalException(LispNames.TCP_SET_TIMEOUT
						+ " expects a non-negative integer (milliseconds) or nil, got: " + ms.print());
			}
			SocketSupport.setTimeout(socket, (int) Math.min(millis.value(), Integer.MAX_VALUE));
			return millis;
		}));
		// One datum out of a runtime-read string. With the evaluator's #. resolver
		// installed, a datum textually containing #. is read in marker mode and the
		// resolver evaluates each marker in place -- CL's read under a true *read-eval*
		// (the resolver itself signals when *read-eval* is bound nil). A bare Environment
		// keeps the error-mode read, matching the compiled backends' embedded readers.
		// An input holding no datum at all (empty, whitespace/comments only, or
		// everything #+/#- suppressed away) is end of file, like CL's read: the
		// read is through readAll (whose first datum readFromString answers), so a
		// literal nil still reads as nil and only a missing datum signals.
		java.util.function.Function<String, LispVal> readRuntimeDatum = input -> {
			if (env.readTimeEvalResolver != null && input.contains("#.")) {
				java.util.List<LispVal> exprs = LispReader.readAllWithReadEvalMarkers(input, env.currentReadFeatures());
				if (exprs.isEmpty()) {
					throw endOfFile(errorStream(streams, nextStreamHandle, input));
				}
				return env.readTimeEvalResolver.apply(exprs.get(0));
			}
			java.util.List<LispVal> exprs = LispReader.readAllFromString(input, env.currentReadFeatures());
			if (exprs.isEmpty()) {
				throw endOfFile(errorStream(streams, nextStreamHandle, input));
			}
			return exprs.get(0);
		};
		// read itself is NOT here: it is prelude rontolisp over read-char /
		// unread-char / read-from-string (LispPreludeLibrary), so one definition
		// consumes exactly one datum's characters on all four backends and leaves the
		// stream positioned after them. See .kb/read-load-streams.md.
		// read-from-string: parse the first datum from a string (the optional
		// eof-error-p/eof-value and :start/:end keywords of Common Lisp are not
		// supported).
		env.defineFunction(LispNames.READ_FROM_STRING, new LispFunction(LispNames.READ_FROM_STRING, args -> {
			requireMinArgCount(LispNames.READ_FROM_STRING, args, 1);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.READ_FROM_STRING + " expects a string");
			}
			if (env.readSuppressQuery != null && env.readSuppressQuery.getAsBoolean()) {
				// *read-suppress* (CLHS 2.2): the characters of the datum are consumed
				// -- which is the stop index, and the whole of what a suppressed read
				// answers -- and the datum is neither built nor complained about.
				// Nothing is parsed, so an unknown package, an unresolvable symbol and
				// an out-of-range digit all pass silently.
				return LispNil.INSTANCE;
			}
			// Folds like read above (upcase premise), so (read-from-string "foo") is
			// the symbol FOO and (read-from-string "car") folds to the standard car.
			return readRuntimeDatum.apply(str.value());
		}));
		// The stop index -- read-from-string's SECOND value -- as its own built-in, so
		// only a multiple-value consumer's lowering pays for it (LispMacroExpander's
		// read-from-string producer). A raw-character scan rather than a second parse:
		// it must answer for the text a suppressed read swallows, which by definition
		// does not parse.
		env.defineFunction(LispNames.READ_FROM_STRING_END, new LispFunction(LispNames.READ_FROM_STRING_END, args -> {
			requireMinArgCount(LispNames.READ_FROM_STRING_END, args, 1);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.READ_FROM_STRING_END + " expects a string");
			}
			return readDatumStop(str.value(), env.currentReadFeatures());
		}));
		// parse-integer: parse an integer from a string, with the common :radix,
		// :junk-allowed, :start and :end keywords.
		env.defineFunction(LispNames.PARSE_INTEGER, new LispFunction(LispNames.PARSE_INTEGER, args -> {
			requireMinArgCount(LispNames.PARSE_INTEGER, args, 1);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.PARSE_INTEGER + " expects a string");
			}
			int radix = 10;
			boolean junkAllowed = false;
			int start = 0;
			int end = str.value().length();
			for (int i = 1; i + 1 < args.size(); i += 2) {
				String key = (args.get(i) instanceof LispSymbol kw) ? kw.name() : "";
				LispVal value = args.get(i + 1);
				switch (key) {
					case LispNames.RADIX_KEYWORD -> radix = (int) asLong(value);
					case LispNames.JUNK_ALLOWED_KEYWORD -> junkAllowed = !(value instanceof LispNil);
					case LispNames.START_KEYWORD -> start = (int) asLong(value);
					case LispNames.END_KEYWORD -> end = (int) asLong(value);
					default -> throw new LispEvalException(LispNames.PARSE_INTEGER + ": unsupported keyword " + key);
				}
			}
			LispVal[] valueAndPos = parseInteger(str.value(), start, end, radix, junkAllowed);
			// Publish the stop position as the second value through the spill, so a
			// first-class #'parse-integer matches the call-position expansion.
			env.publishSpill(new LispCons(valueAndPos[1], LispNil.INSTANCE));
			return valueAndPos[0];
		}, true));
	}

	/**
	 * The index after the datum {@code read-from-string} reads out of {@code input} --
	 * its second value. The scan is deliberately SEPARATE from the parse: it answers for
	 * text the parse refuses, which is what a suppressed read needs. An input the scan
	 * cannot delimit at all answers the whole length rather than signalling: a
	 * disagreement between the scan and the parse must never turn a working read into an
	 * error.
	 * @param input the source text
	 * @param features the features the {@code #+}/{@code #-} guards in it test
	 */
	private static LispVal readDatumStop(String input, am.ik.rontolisp.reader.Features features) {
		try {
			return new LispInteger(LispLexer.datumEnd(input, features));
		}
		catch (RuntimeException ex) {
			return new LispInteger(input.length());
		}
	}

	// Shared parse-integer logic: trims whitespace, accepts an optional sign, and
	// accumulates digits in the given radix. With junkAllowed, stops at the first
	// non-digit and returns nil when no digits were seen; otherwise signals on junk --
	// the text a call's expansion signals (LispMacroExpander.expandParseInteger: ~s of
	// the string), which every compiled backend prints for #'parse-integer too.
	private static LispVal[] parseInteger(String s, int start, int end, int radix, boolean junkAllowed) {
		int i = start;
		while (i < end && Character.isWhitespace(s.charAt(i))) {
			i++;
		}
		int sign = 1;
		if (i < end && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
			sign = s.charAt(i) == '-' ? -1 : 1;
			i++;
		}
		java.math.BigInteger acc = java.math.BigInteger.ZERO;
		java.math.BigInteger base = java.math.BigInteger.valueOf(radix);
		boolean sawDigit = false;
		while (i < end) {
			int digit = Character.digit(s.charAt(i), radix);
			if (digit < 0) {
				break;
			}
			acc = acc.multiply(base).add(java.math.BigInteger.valueOf(digit));
			sawDigit = true;
			i++;
		}
		if (!junkAllowed) {
			while (i < end && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
			if (i != end) {
				throw new LispEvalException("parse-integer: junk in string " + new LispString(s).print());
			}
		}
		if (!sawDigit) {
			if (junkAllowed) {
				return new LispVal[] { LispNil.INSTANCE, new LispInteger(i) };
			}
			throw new LispEvalException("parse-integer: no integer in string " + new LispString(s).print());
		}
		return new LispVal[] { normalizeBig(acc.multiply(java.math.BigInteger.valueOf(sign))), new LispInteger(i) };
	}

	/**
	 * Stores a character into a mutable string cell ({@code (setf (aref s i) c)} and the
	 * row-major variant): bounds-checked against the capacity (the fill pointer only
	 * limits the effective length, so a write between them is legal, as in CL). One
	 * indexed slot holds one full code point (including supplementary code points),
	 * matching the JVM and WASM char-vec representations.
	 */
	private static LispVal storeStringChar(String op, LispString str, int index, LispVal value) {
		// A non-character is the store's type-error, named by the built-in seam.
		if (!(value instanceof LispChar c)) {
			throw OperandTypeException.of(value, OperandTypes.Kind.CHARACTER);
		}
		if (index < 0 || index >= str.capacity()) {
			throw new LispEvalException(
					op + ": index " + index + " out of bounds for string of capacity " + str.capacity());
		}
		// A SOURCE LITERAL is never written (.kb/string-write-runtime.md). These two
		// entries -- %aset and %row-major-aset -- carry no rebind hook, so like
		// %schar-set as a first-class value they refuse rather than rewrite the program
		// text. The (setf (aref s i) c) spelling never arrives here: expandSetf routes
		// it through %schar-set, which rebinds the place.
		if (str.sourceLiteral()) {
			throw new LispEvalException(
					op + " on a string literal requires a variable string place, got " + str.print());
		}
		str.setCharAt(index, c.codePoint());
		return c;
	}

	/**
	 * The {@code concatenate} builtin: the string, list and vector result families over
	 * any sequence arguments, with the result-type designator resolved through
	 * {@link ConcatenateForms#resultFamily(LispVal, ClosRegistry)}. Registered
	 * registry-less by {@link #createGlobal}; the evaluator re-registers it with its
	 * class registry so a user {@code deftype} alias (fast-http's
	 * {@code simple-byte-vector}) resolves exactly as it does on the compile paths.
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in family members only
	 * @return the builtin function
	 */
	public static LispFunction concatenateBuiltin(@Nullable ClosRegistry closRegistry) {
		return new LispFunction(LispNames.CONCATENATE, args -> {
			requireMinArgCount(LispNames.CONCATENATE, args, 1);
			ConcatenateForms.ResultSpec spec = ConcatenateForms.resultSpec(args.get(0), closRegistry);
			if (spec == null) {
				throw new LispEvalException(
						"concatenate supports the string, list and vector result types, got: " + args.get(0).print());
			}
			ConcatenateForms.ResultFamily family = spec.family();
			List<LispVal> rest = args.subList(1, args.size());
			if (family == ConcatenateForms.ResultFamily.STRING) {
				// Like the other two families, the string family takes any character
				// SEQUENCE -- a string, a cons list, a vector, or nil, the empty list.
				// The compile paths reach the same contract by sending each non-literal
				// argument through %seq-string before the %string-concat fold.
				StringBuilder sb = new StringBuilder();
				for (LispVal arg : rest) {
					if (arg instanceof LispString str) {
						sb.append(str.value());
						continue;
					}
					List<LispVal> chars = new ArrayList<>();
					appendSequenceElements(arg, chars);
					for (LispVal element : chars) {
						if (!(element instanceof LispChar ch)) {
							throw new LispEvalException(
									"concatenate: a 'string result needs characters, got: " + element.print());
						}
						sb.appendCodePoint(ch.codePoint());
					}
				}
				return new LispString(sb.toString());
			}
			// The list / vector families walk elements, so any sequence argument works.
			List<LispVal> elements = new ArrayList<>();
			for (LispVal arg : rest) {
				appendSequenceElements(arg, elements);
			}
			if (family == ConcatenateForms.ResultFamily.VECTOR) {
				// An (unsigned-byte 8|16|32) or packed float element type asks for the
				// PACKED representation make-array already builds; the compile paths
				// reach the same results through %seq-int-vector / %seq-float-vector.
				if (spec.intWidth() != 0) {
					return packedIntVector(LispNames.CONCATENATE, spec.intWidth(), elements);
				}
				LispFloatArray proto = LispFloatArray.prototypeFor(ArrayElementTypes.valueOf(spec.elementType()));
				if (proto != null) {
					return packedFloatVector(LispNames.CONCATENATE, proto, elements);
				}
				// A (vector character) result is the representation make-array builds
				// for the same element type too: a mutable LispString, not a general
				// vector that merely holds characters.
				if (spec.elementType() == ArrayElementTypes.CHARACTER) {
					return charVector(LispNames.CONCATENATE, elements);
				}
				// A (vector bit) result is the general boxed array stamped bit, like
				// the make-array spelling of the same designator (.todo/043).
				if (spec.elementType() == ArrayElementTypes.BIT) {
					return new LispArray(new int[] { elements.size() }, elements.toArray(new LispVal[0]), -1, false,
							ArrayElementTypes.BIT);
				}
				return new LispArray(new int[] { elements.size() }, elements.toArray(new LispVal[0]));
			}
			LispVal list = LispNil.INSTANCE;
			for (int i = elements.size() - 1; i >= 0; i--) {
				list = new LispCons(elements.get(i), list);
			}
			return list;
		});
	}

	/**
	 * The {@code %schar-set} primitive: writes {@code c} into slot {@code i} of the
	 * string and answers {@code c}.
	 *
	 * <p>
	 * A SOURCE LITERAL is refused here. The literal is the constant in the program text,
	 * shared by every evaluation of its form, so a write into it would rewrite the source
	 * -- which is exactly what the compiled backends never do, their
	 * {@code %schar-set-runtime} rebuilding the string and {@code setq}ing it back into
	 * the place. {@code LispEvaluator} performs that same rebind before reaching here
	 * whenever the place is a variable; when it is not there is nowhere to put the
	 * result, and the compiled backends refuse such a place outright, so this refuses it
	 * too ({@code .kb/string-write-runtime.md}).
	 * <p>
	 * A string that is no string, a subscript that is no integer and a value that is no
	 * character are the {@code operator}'s type-errors -- {@code (SETF CHAR)} for a
	 * {@code char} place -- or unnamed ones without it.
	 * @param args the string, the index and the character
	 * @param rebindPlace how to store a rebuilt string back into the place the string
	 * came from, or {@code null} when the place cannot take one
	 * @param operator the store's reported name, or null
	 * @return the character written
	 */
	static LispVal scharSet(List<LispVal> args, @Nullable Consumer<LispString> rebindPlace, @Nullable String operator) {
		requireArgCount(LispNames.SCHAR_SET, args, 3);
		if (!(args.get(0) instanceof LispString str)) {
			throw operator == null ? OperandTypeException.of(args.get(0), OperandTypes.Kind.STRING)
					: OperandTypeException.of(args.get(0), OperandTypes.Kind.STRING, operator);
		}
		int index = requireStringIndex(operator, LispNames.SCHAR_SET, args.get(1));
		// The value before the bounds, as the compiled expansion checks it ahead of the
		// runtime defun's write.
		if (!(args.get(2) instanceof LispChar c)) {
			throw operator == null ? OperandTypeException.of(args.get(2), OperandTypes.Kind.CHARACTER)
					: OperandTypeException.of(args.get(2), OperandTypes.Kind.CHARACTER, operator);
		}
		// Capacity, not the fill pointer: a (setf (char s i) c) past the fill pointer
		// writes an inactive slot in CL and on all three compile backends, and the
		// fill pointer bounds the sequence view only (.kb/adjustable-arrays.md).
		if (index < 0 || index >= str.capacity()) {
			throw new LispEvalException(
					LispNames.SCHAR_SET + ": index " + index + " out of bounds for string of length " + str.capacity());
		}
		if (str.sourceLiteral()) {
			if (rebindPlace == null) {
				throw new LispEvalException("setf on " + LispNames.SCHAR + "/" + LispNames.CHAR
						+ " requires a variable string place when the string is a literal, got " + str.print());
			}
			rebindPlace.accept(str.withCharAt(index, c.codePoint()));
			return c;
		}
		str.setCharAt(index, c.codePoint());
		return c;
	}

	private static void registerCharacters(Environment env) {
		env.defineFunction(LispNames.CHAR, new LispFunction(LispNames.CHAR, args -> charRef(LispNames.CHAR, args)));
		env.defineFunction(LispNames.SCHAR, new LispFunction(LispNames.SCHAR, args -> charRef(LispNames.SCHAR, args)));
		// %schar-set: the (setf (schar s i) c) lowering -- mutate in place, return c.
		// One indexed slot holds one full code point (including supplementary code
		// points), matching the JVM and WASM char-vec representations.
		env.defineFunction(LispNames.SCHAR_SET,
				new LispFunction(LispNames.SCHAR_SET, args -> scharSet(args, null, null)));
		env.defineFunction(LispNames.CHAR_CODE, new LispFunction(LispNames.CHAR_CODE, args -> {
			requireArgCount(LispNames.CHAR_CODE, args, 1);
			return new LispInteger(requireChar(LispNames.CHAR_CODE, args.get(0)).codePoint());
		}));
		env.defineFunction(LispNames.CODE_CHAR, new LispFunction(LispNames.CODE_CHAR, args -> {
			requireArgCount(LispNames.CODE_CHAR, args, 1);
			return new LispChar((int) asLong(args.get(0)));
		}));
		env.defineFunction(LispNames.CHAR_EQ,
				new LispFunction(LispNames.CHAR_EQ, args -> charCompareChain(LispNames.CHAR_EQ, args, 0, 0)));
		env.defineFunction(LispNames.CHAR_LT,
				new LispFunction(LispNames.CHAR_LT, args -> charCompareChain(LispNames.CHAR_LT, args, -1, -1)));
		env.defineFunction(LispNames.CHAR_LE,
				new LispFunction(LispNames.CHAR_LE, args -> charCompareChain(LispNames.CHAR_LE, args, -1, 0)));
		env.defineFunction(LispNames.CHAR_GT,
				new LispFunction(LispNames.CHAR_GT, args -> charCompareChain(LispNames.CHAR_GT, args, 1, 1)));
		env.defineFunction(LispNames.CHAR_GE,
				new LispFunction(LispNames.CHAR_GE, args -> charCompareChain(LispNames.CHAR_GE, args, 0, 1)));
		env.defineFunction(LispNames.CHAR_EQUAL, new LispFunction(LispNames.CHAR_EQUAL, args -> {
			requireMinArgCount(LispNames.CHAR_EQUAL, args, 1);
			for (int i = 0; i + 1 < args.size(); i++) {
				int a = Character.toLowerCase(requireChar(LispNames.CHAR_EQUAL, args.get(i)).codePoint());
				int b = Character.toLowerCase(requireChar(LispNames.CHAR_EQUAL, args.get(i + 1)).codePoint());
				if (a != b) {
					return LispNil.INSTANCE;
				}
			}
			return LispTrue.INSTANCE;
		}));
		env.defineFunction(LispNames.CHAR_NE, new LispFunction(LispNames.CHAR_NE, args -> {
			requireMinArgCount(LispNames.CHAR_NE, args, 1);
			// char/= is true when ALL arguments are pairwise distinct (not just
			// adjacent pairs), per CL.
			for (int i = 0; i < args.size(); i++) {
				for (int j = i + 1; j < args.size(); j++) {
					if (requireChar(LispNames.CHAR_NE, args.get(i))
						.codePoint() == requireChar(LispNames.CHAR_NE, args.get(j)).codePoint()) {
						return LispNil.INSTANCE;
					}
				}
			}
			return LispTrue.INSTANCE;
		}));
		env.defineFunction(LispNames.CHAR_UPCASE, new LispFunction(LispNames.CHAR_UPCASE, args -> {
			requireArgCount(LispNames.CHAR_UPCASE, args, 1);
			return new LispChar(Character.toUpperCase(requireChar(LispNames.CHAR_UPCASE, args.get(0)).codePoint()));
		}));
		env.defineFunction(LispNames.CHAR_DOWNCASE, new LispFunction(LispNames.CHAR_DOWNCASE, args -> {
			requireArgCount(LispNames.CHAR_DOWNCASE, args, 1);
			return new LispChar(Character.toLowerCase(requireChar(LispNames.CHAR_DOWNCASE, args.get(0)).codePoint()));
		}));
		env.defineFunction(LispNames.CHARACTERP, new LispFunction(LispNames.CHARACTERP, args -> {
			requireArgCount(LispNames.CHARACTERP, args, 1);
			return args.get(0) instanceof LispChar ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.ALPHA_CHAR_P, new LispFunction(LispNames.ALPHA_CHAR_P, args -> {
			requireArgCount(LispNames.ALPHA_CHAR_P, args, 1);
			return Character.isLetter(requireChar(LispNames.ALPHA_CHAR_P, args.get(0)).codePoint()) ? LispTrue.INSTANCE
					: LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.DIGIT_CHAR_P, new LispFunction(LispNames.DIGIT_CHAR_P, args -> {
			requireMinArgCount(LispNames.DIGIT_CHAR_P, args, 1);
			int radix = args.size() > 1 ? (int) asLong(args.get(1)) : 10;
			int weight = Character.digit(requireChar(LispNames.DIGIT_CHAR_P, args.get(0)).codePoint(), radix);
			return weight < 0 ? LispNil.INSTANCE : new LispInteger(weight);
		}));
		env.defineFunction(LispNames.LOWER_CASE_P, new LispFunction(LispNames.LOWER_CASE_P, args -> {
			requireArgCount(LispNames.LOWER_CASE_P, args, 1);
			int cp = requireChar(LispNames.LOWER_CASE_P, args.get(0)).codePoint();
			// A character is lowercase exactly when upcasing changes it.
			return cp != Character.toUpperCase(cp) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.UPPER_CASE_P, new LispFunction(LispNames.UPPER_CASE_P, args -> {
			requireArgCount(LispNames.UPPER_CASE_P, args, 1);
			int cp = requireChar(LispNames.UPPER_CASE_P, args.get(0)).codePoint();
			// A character is uppercase exactly when downcasing changes it.
			return cp != Character.toLowerCase(cp) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.STREAMP, new LispFunction(LispNames.STREAMP, args -> {
			requireArgCount(LispNames.STREAMP, args, 1);
			// Every stream is a self-describing VALUE -- an open stream
			// (LispLayout.STREAM) or a synonym stream (LispLayout.SYNONYM_STREAM) -- and
			// the standard-output designator t counts as a stream too (lite):
			// *standard-output* is bound to t. An INTEGER is a number, not a stream,
			// which is what lets a library dispatch a file descriptor against a Lisp
			// stream. A Gray instance is added on top of this by LispEvaluator's wrap.
			LispVal v = args.get(0);
			return (isStreamValue(v) || v instanceof LispTrue || isSynonymStream(v)) ? LispTrue.INSTANCE
					: LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.SIMPLE_STRING_P, new LispFunction(LispNames.SIMPLE_STRING_P, args -> {
			requireArgCount(LispNames.SIMPLE_STRING_P, args, 1);
			// The same answer (typep x 'simple-string) gives, as CL requires the two to
			// agree: a string with no fill pointer, not adjustable and not displaced.
			return args.get(0) instanceof LispString str && str.fillPointer() < 0 && !str.adjustable()
					&& str.displacedTo() == null ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.PATHNAMEP, new LispFunction(LispNames.PATHNAMEP, args -> {
			requireArgCount(LispNames.PATHNAMEP, args, 1);
			// A pathname is an instance of the fixed LispLayout.PATHNAME layout;
			// a string is NOT one. Agrees with (typep x 'pathname), as CL
			// requires.
			return args.get(0) instanceof LispInstance inst && inst.layout().kind() == LispLayout.Kind.PATHNAME
					? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// make-pathname is NOT defined here: it is prelude Lisp
		// (LispPreludeLibrary.MAKE_PATHNAME), so the interpreter and the three compiled
		// backends run the same definition rather than a Java one here and a spliced
		// Lisp one there. PathnameOps.makePathname stays -- it is what
		// cli/CompileTimePathnameFolder folds the literal shapes with, and
		// LispPreludeLibraryTest pins the two renderings against each other.
		env.defineFunction(LispNames.CHAR_NAME, new LispFunction(LispNames.CHAR_NAME, args -> {
			requireArgCount(LispNames.CHAR_NAME, args, 1);
			int cp = requireChar(LispNames.CHAR_NAME, args.get(0)).codePoint();
			String name = switch (cp) {
				case ' ' -> "Space";
				case '\n' -> "Newline";
				case '\t' -> "Tab";
				case '\r' -> "Return";
				case '\f' -> "Page";
				case '\b' -> "Backspace";
				case 0 -> "Null";
				case 127 -> "Rubout";
				default -> cp < 32 || cp > 126 ? String.format("U+%04X", cp) : null;
			};
			return name == null ? LispNil.INSTANCE : new LispString(name);
		}));
		// %octets-to-string: the native mirror of the prelude's lenient UTF-8 decoder
		// (LispPreludeLibrary.OCTETS_TO_STRING_INTERNAL), the char-name arrangement --
		// the compile paths compile the Lisp definition, the interpreter finds this one
		// first and never loads it. It is what read-all decodes a fetched body with, so
		// it has to be native here: a per-byte interpreted loop over a document-sized
		// reply is not a cost a fetch may carry. Arm for arm the same rule -- a byte
		// that leads no valid sequence, and a sequence the vector truncates, answer
		// their own characters -- pinned against the Lisp one by
		// LispPreludeLibraryTest.
		//
		// The Lisp source reads its argument through plain length/aref, so it accepts ANY
		// rank-1 array of small integers, not only a packed byte vector; the compile
		// paths inherit that for free (their %octets-to-string-packed native declines
		// a general array and the same generic loop runs). This native mirror used to
		// require a LispIntVector outright, so a general array -- exactly what a general
		// array's subseq answered before .todo/698 fixed it -- signaled here while the
		// compile paths quietly decoded it: the interpreter/compiled-backend asymmetry
		// .todo/698 found. asOctetVector closes it by widening to the same rank-1 array
		// acceptance the Lisp source has, so only a genuinely non-array argument still
		// signals.
		String octetsToString = LispNames.OCTETS_TO_STRING_INTERNAL_QUALIFIED;
		env.defineFunction(octetsToString, new LispFunction(octetsToString, args -> {
			requireArgCount(LispNames.OCTETS_TO_STRING_INTERNAL, args, 1);
			LispIntVector v = asOctetVector(LispNames.OCTETS_TO_STRING_INTERNAL, args.get(0));
			String strict = decodeUtf8Strict(v);
			return new LispString(strict != null ? strict : decodeUtf8Leniently(v));
		}));
		// %octets-to-string-packed: the NATIVE half, on every backend, so the prelude's
		// lenient definition hands a packed octet vector -- every HTTP body -- to native
		// code, malformed bytes included, and walks a byte at a time only through a
		// general array. Present here as its own binding (rather than only folded into
		// the mirror above) because the compile paths call it BY NAME from the spliced
		// defun, and LispPreludeLibraryTest evaluates that defun to pin the two
		// renderings against each other. A value that is not a packed octet vector
		// answers nil, and the caller's loop decides; it never signals.
		String octetsToStringPacked = LispNames.OCTETS_TO_STRING_PACKED_INTERNAL_QUALIFIED;
		env.defineFunction(octetsToStringPacked, new LispFunction(octetsToStringPacked, args -> {
			requireArgCount(LispNames.OCTETS_TO_STRING_PACKED_INTERNAL, args, 1);
			if (!(args.get(0) instanceof LispIntVector v) || v.width() != 8) {
				return LispNil.INSTANCE;
			}
			String strict = decodeUtf8Strict(v);
			return new LispString(strict != null ? strict : decodeUtf8Leniently(v));
		}));
		env.defineFunction(LispNames.CONSTANTP, new LispFunction(LispNames.CONSTANTP, args -> {
			requireMinArgCount(LispNames.CONSTANTP, args, 1);
			LispVal v = args.get(0);
			boolean constant = v instanceof LispInteger || v instanceof LispBigInteger || v instanceof LispRatio
					|| v instanceof LispDouble || v instanceof LispString || v instanceof LispChar
					|| v instanceof LispTrue || v instanceof LispNil
					|| (v instanceof LispSymbol s && s.name().startsWith(":"))
			// A quoted constant variable ('pi) is constant too (SBCL answers t).
					|| (v instanceof LispSymbol s && ClConstants.isSpelling(s.name()))
					|| (v instanceof LispCons c && c.car() instanceof LispSymbol h && LispNames.QUOTE.equals(h.name()));
			return constant ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.MAKE_STRING, new LispFunction(LispNames.MAKE_STRING, args -> {
			requireMinArgCount(LispNames.MAKE_STRING, args, 1);
			int n = requireIndex(LispNames.MAKE_STRING, args.get(0));
			int fill = ' ';
			for (int i = 1; i + 1 < args.size(); i += 2) {
				if (args.get(i) instanceof LispSymbol key) {
					if (LispNames.INITIAL_ELEMENT_KEYWORD.equals(key.name())) {
						fill = requireChar(LispNames.MAKE_STRING, args.get(i + 1)).codePoint();
					}
					else if (!LispNames.ELEMENT_TYPE_KEYWORD.equals(key.name())) {
						throw new LispEvalException("make-string: unsupported keyword " + key.name());
					}
				}
			}
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < n; i++) {
				sb.appendCodePoint(fill);
			}
			return new LispString(sb.toString());
		}));
		env.defineFunction(LispNames.REPLACE, new LispFunction(LispNames.REPLACE, args -> {
			requireMinArgCount(LispNames.REPLACE, args, 2);
			LispVal target = args.get(0);
			LispVal source = args.get(1);
			int end1 = sequenceLength(LispNames.REPLACE, target);
			int end2 = sequenceLength(LispNames.REPLACE, source);
			int start1 = 0;
			int start2 = 0;
			// A nil bound keeps its default (nil :end = the sequence's length, as in CL).
			for (int i = 2; i + 1 < args.size(); i += 2) {
				if (args.get(i) instanceof LispSymbol key && !(args.get(i + 1) instanceof LispNil)) {
					switch (key.name()) {
						case LispNames.START1_KEYWORD -> start1 = requireIndex(LispNames.REPLACE, args.get(i + 1));
						case LispNames.END1_KEYWORD -> end1 = requireIndex(LispNames.REPLACE, args.get(i + 1));
						case LispNames.START2_KEYWORD -> start2 = requireIndex(LispNames.REPLACE, args.get(i + 1));
						case LispNames.END2_KEYWORD -> end2 = requireIndex(LispNames.REPLACE, args.get(i + 1));
						default -> throw new LispEvalException("replace: unsupported keyword " + key.name());
					}
				}
			}
			int copied = Math.min(end1 - start1, end2 - start2);
			// Common Lisp REPLACE is destructive: mutate the target sequence in place and
			// return it, so buffers filled by successive REPLACE calls (cl-who's
			// string-list-to-string, uax-15's canonical-ordering's
			// (setf (subseq vec beg end) sorted)) accumulate correctly.
			if (target instanceof LispString targetStr) {
				String s2 = requireString(LispNames.REPLACE, source);
				// ... except into a SOURCE LITERAL, which is never written on any
				// backend (.kb/string-write-runtime.md): the write lands on a fresh
				// copy and reaches the program only through the return value, since a
				// function call has no place to rebind. That is what the three compile
				// paths already answer -- their functional branch builds the new string
				// and the source constant never moves.
				LispString into = targetStr.sourceLiteral() ? targetStr.copyForBulkWrite() : targetStr;
				into.replaceInPlace(start1, s2, start2, copied);
				return into;
			}
			// All three destructive arms below read the source the same way, so one
			// cursor serves them all: a list source is walked once rather than indexed
			// from its head per element, every other representation is the slot read it
			// always was, and an exhausted list source still signals (above).
			SequenceSourceCursor reader = new SequenceSourceCursor(source);
			int from = start2;
			IntFunction<LispVal> element = k -> reader.read(from + k);
			if (target == source && start1 > start2 && copied > 0) {
				// One object, and the writes would land ahead of the reads: CLHS asks for
				// the
				// result of copying the whole source region first, so read it out now.
				LispVal[] region = new LispVal[copied];
				for (int k = 0; k < copied; k++) {
					region[k] = reader.read(start2 + k);
				}
				element = k -> region[k];
			}
			if (target instanceof LispArray targetArr && targetArr.dimensions().length == 1) {
				for (int k = 0; k < copied; k++) {
					targetArr.writeFlat(start1 + k, element.apply(k));
				}
				return targetArr;
			}
			if (target instanceof LispIntVector targetIv) {
				// Mask-store each element (a non-integer source element is a type error).
				for (int k = 0; k < copied; k++) {
					targetIv.setElement(start1 + k, exactIntElement(LispNames.REPLACE, element.apply(k)));
				}
				return targetIv;
			}
			if (target instanceof LispCons || target instanceof LispNil) {
				// A list target: walk to start1 and destructively rewrite copied cars.
				int idx = 0;
				LispVal cur = target;
				while (cur instanceof LispCons cell && idx < start1) {
					cur = cell.cdr();
					idx++;
				}
				for (int k = 0; k < copied && cur instanceof LispCons cell; k++) {
					cell.setCar(element.apply(k));
					cur = cell.cdr();
				}
				return target;
			}
			throw new LispEvalException(
					LispNames.REPLACE + ": target must be a string, vector, or list, got: " + target.print());
		}));
		env.defineFunction(LispNames.FILL, new LispFunction(LispNames.FILL, args -> {
			requireMinArgCount(LispNames.FILL, args, 2);
			LispVal target = args.get(0);
			LispVal item = args.get(1);
			int start = 0;
			int end = sequenceLength(LispNames.FILL, target);
			// A nil bound keeps its default, as in replace.
			for (int i = 2; i + 1 < args.size(); i += 2) {
				if (args.get(i) instanceof LispSymbol key && !(args.get(i + 1) instanceof LispNil)) {
					switch (key.name()) {
						case LispNames.START_KEYWORD -> start = requireIndex(LispNames.FILL, args.get(i + 1));
						case LispNames.END_KEYWORD -> end = requireIndex(LispNames.FILL, args.get(i + 1));
						default -> throw new LispEvalException("fill: unsupported keyword " + key.name());
					}
				}
			}
			// Destructive, like replace: the sequence itself comes back, so a buffer
			// cleared between uses stays the same object (chipz's code-length tables,
			// salza2's bitstream reset).
			if (target instanceof LispString targetStr) {
				int codePoint = requireChar(LispNames.FILL, item).codePoint();
				// A SOURCE LITERAL is never written: the fill lands on a fresh copy,
				// exactly as in replace above (.kb/string-write-runtime.md).
				LispString into = targetStr.sourceLiteral() ? targetStr.copyForBulkWrite() : targetStr;
				for (int k = start; k < end; k++) {
					into.setCharAt(k, codePoint);
				}
				return into;
			}
			if (target instanceof LispIntVector targetIv) {
				long masked = exactIntElement(LispNames.FILL, item);
				for (int k = start; k < end; k++) {
					targetIv.setElement(k, masked);
				}
				return targetIv;
			}
			if (target instanceof LispFloatArray targetFa) {
				double value = asDouble(item);
				for (int k = start; k < end; k++) {
					targetFa.setElement(k, value);
				}
				return targetFa;
			}
			if (target instanceof LispArray targetArr) {
				for (int k = start; k < end; k++) {
					targetArr.writeFlat(k, item);
				}
				return targetArr;
			}
			if (target instanceof LispCons || target instanceof LispNil) {
				int idx = 0;
				LispVal cur = target;
				while (cur instanceof LispCons cellToSkip && idx < start) {
					cur = cellToSkip.cdr();
					idx++;
				}
				while (cur instanceof LispCons cellToFill && idx < end) {
					cellToFill.setCar(item);
					cur = cellToFill.cdr();
					idx++;
				}
				return target;
			}
			throw new LispEvalException(
					LispNames.FILL + ": target must be a string, vector, or list, got: " + target.print());
		}));
	}

	private static LispVal charRef(String name, java.util.List<LispVal> args) {
		requireArgCount(name, args, 2);
		// The subscript first, as the compiled backends check it ahead of the read.
		int index = requireStringIndex(name, args.get(1));
		if (!(args.get(0) instanceof LispString s)) {
			throw OperandTypeException.of(args.get(0), OperandTypes.Kind.STRING, name);
		}
		// Indexing is by CHARACTER (Unicode code point), not by UTF-16 code unit -- a
		// supplementary code point is one indexed character, not two, matching every
		// other backend and Common Lisp's contract. The backing store is already one code
		// point per slot, so this reads the slot: going through the Java String (rebuild
		// the whole string, count its code points, walk to the index) made a single
		// (char s i) cost O(length), and any left-to-right scan of a long string
		// quadratic -- 64,000 characters took 3.3 s that way.
		//
		// The bound is the CAPACITY, not the fill pointer: char / schar / aref all
		// ignore fill pointers in CL ("it is permissible to use aref to access any
		// array element, whether active or not"), which is the invariant
		// .kb/adjustable-arrays.md states and all three compile backends already
		// honour. Reading `value()` made this the fill pointer and left the
		// interpreter the only backend that could not see an inactive slot.
		int cpLen = s.capacity();
		if (index < 0 || index >= cpLen) {
			throw new LispEvalException(name + ": index " + index + " out of bounds for string of length " + cpLen);
		}
		return new LispChar(s.codePointAt(index));
	}

	private static LispChar requireChar(String name, LispVal val) {
		if (val instanceof LispChar c) {
			return c;
		}
		throw new LispEvalException(name + " expects a character, got: " + val.print());
	}

	// Variadic character comparison, mirroring compareChain for numbers: true when
	// every adjacent pair's code-point comparison falls within [low, high].
	private static LispVal charCompareChain(String name, java.util.List<LispVal> args, int low, int high) {
		requireMinArgCount(name, args, 1);
		for (int i = 0; i + 1 < args.size(); i++) {
			int a = requireChar(name, args.get(i)).codePoint();
			int b = requireChar(name, args.get(i + 1)).codePoint();
			int cmp = Integer.compare(a, b);
			int normalized = cmp < 0 ? -1 : (cmp > 0 ? 1 : 0);
			if (normalized < low || normalized > high) {
				return LispNil.INSTANCE;
			}
		}
		return LispTrue.INSTANCE;
	}

	private static void registerPredicates(Environment env) {
		env.defineFunction(LispNames.NULL, new LispFunction(LispNames.NULL, args -> {
			requireArgCount(LispNames.NULL, args, 1);
			return args.get(0) instanceof LispNil ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.NOT, new LispFunction(LispNames.NOT, args -> {
			requireArgCount(LispNames.NOT, args, 1);
			return args.get(0) instanceof LispNil ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.ATOM, new LispFunction(LispNames.ATOM, args -> {
			requireArgCount(LispNames.ATOM, args, 1);
			return !(args.get(0) instanceof LispCons) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.NUMBERP, new LispFunction(LispNames.NUMBERP, args -> {
			requireArgCount(LispNames.NUMBERP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispInteger || arg instanceof LispBigInteger || arg instanceof LispRatio
					|| arg instanceof LispDouble || arg instanceof LispComplex) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.REALP, new LispFunction(LispNames.REALP, args -> {
			requireArgCount(LispNames.REALP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispInteger || arg instanceof LispBigInteger || arg instanceof LispRatio
					|| arg instanceof LispDouble) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.COMPLEXP, new LispFunction(LispNames.COMPLEXP, args -> {
			requireArgCount(LispNames.COMPLEXP, args, 1);
			return args.get(0) instanceof LispComplex ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.INTEGERP, new LispFunction(LispNames.INTEGERP, args -> {
			requireArgCount(LispNames.INTEGERP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispInteger || arg instanceof LispBigInteger) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.FLOATP, new LispFunction(LispNames.FLOATP, args -> {
			requireArgCount(LispNames.FLOATP, args, 1);
			return args.get(0) instanceof LispDouble ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.RATIONALP, new LispFunction(LispNames.RATIONALP, args -> {
			requireArgCount(LispNames.RATIONALP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispInteger || arg instanceof LispBigInteger || arg instanceof LispRatio)
					? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.SYMBOLP, new LispFunction(LispNames.SYMBOLP, args -> {
			requireArgCount(LispNames.SYMBOLP, args, 1);
			// nil and t are symbols in CL.
			LispVal v = args.get(0);
			return (v instanceof LispSymbol || v instanceof LispNil || v instanceof LispTrue) ? LispTrue.INSTANCE
					: LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.STRINGP, new LispFunction(LispNames.STRINGP, args -> {
			requireArgCount(LispNames.STRINGP, args, 1);
			return args.get(0) instanceof LispString ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.LISTP, new LispFunction(LispNames.LISTP, args -> {
			requireArgCount(LispNames.LISTP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispCons || arg instanceof LispNil) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.CONSP, new LispFunction(LispNames.CONSP, args -> {
			requireArgCount(LispNames.CONSP, args, 1);
			return args.get(0) instanceof LispCons ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.KEYWORDP, new LispFunction(LispNames.KEYWORDP, args -> {
			requireArgCount(LispNames.KEYWORDP, args, 1);
			return args.get(0) instanceof LispSymbol sym && sym.isKeyword() ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.ZEROP, new LispFunction(LispNames.ZEROP, args -> {
			requireArgCount(LispNames.ZEROP, args, 1);
			if (args.get(0) instanceof LispComplex c) {
				return isZeroReal(c.real()) && isZeroReal(c.imag()) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (hasDouble(args)) {
				return asDouble(args.get(0)) == 0.0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			// A normalized LispBigInteger is always outside the long range, hence
			// non-zero, and a normalized LispRatio is never an integer, hence non-zero.
			if (args.get(0) instanceof LispBigInteger || args.get(0) instanceof LispRatio) {
				return LispNil.INSTANCE;
			}
			return asLong(args.get(0)) == 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.PLUSP, new LispFunction(LispNames.PLUSP, args -> {
			requireArgCount(LispNames.PLUSP, args, 1);
			requireRealOperand(LispNames.PLUSP, args.get(0));
			if (hasDouble(args)) {
				return asDouble(args.get(0)) > 0.0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispRatio r) {
				return r.numerator().signum() > 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().signum() > 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) > 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.MINUSP, new LispFunction(LispNames.MINUSP, args -> {
			requireArgCount(LispNames.MINUSP, args, 1);
			requireRealOperand(LispNames.MINUSP, args.get(0));
			if (hasDouble(args)) {
				return asDouble(args.get(0)) < 0.0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispRatio r) {
				return r.numerator().signum() < 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().signum() < 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) < 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.EVENP, new LispFunction(LispNames.EVENP, args -> {
			requireArgCount(LispNames.EVENP, args, 1);
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().testBit(0) ? LispNil.INSTANCE : LispTrue.INSTANCE;
			}
			return asLong(args.get(0)) % 2 == 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.ODDP, new LispFunction(LispNames.ODDP, args -> {
			requireArgCount(LispNames.ODDP, args, 1);
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().testBit(0) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) % 2 != 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
	}

	private static void registerListOps(Environment env) {
		env.defineFunction(LispNames.CONS, new LispFunction(LispNames.CONS, args -> {
			requireArgCount(LispNames.CONS, args, 2);
			return new LispCons(args.get(0), args.get(1));
		}));
		env.defineFunction(LispNames.CAR, new LispFunction(LispNames.CAR, args -> {
			requireArgCount(LispNames.CAR, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.car();
			}
			if (args.get(0) instanceof LispNil) {
				return LispNil.INSTANCE;
			}
			throw OperandTypeException.of(args.get(0), OperandTypes.Kind.LIST);
		}));
		env.defineFunction(LispNames.CDR, new LispFunction(LispNames.CDR, args -> {
			requireArgCount(LispNames.CDR, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.cdr();
			}
			if (args.get(0) instanceof LispNil) {
				return LispNil.INSTANCE;
			}
			throw OperandTypeException.of(args.get(0), OperandTypes.Kind.LIST);
		}));
		env.defineFunction(LispNames.FIRST, new LispFunction(LispNames.FIRST, args -> {
			requireArgCount(LispNames.FIRST, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.car();
			}
			if (args.get(0) instanceof LispNil) {
				return LispNil.INSTANCE;
			}
			throw OperandTypeException.of(args.get(0), OperandTypes.Kind.LIST);
		}));
		env.defineFunction(LispNames.REST, new LispFunction(LispNames.REST, args -> {
			requireArgCount(LispNames.REST, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.cdr();
			}
			if (args.get(0) instanceof LispNil) {
				return LispNil.INSTANCE;
			}
			throw OperandTypeException.of(args.get(0), OperandTypes.Kind.LIST);
		}));
		env.defineFunction(LispNames.NTH, new LispFunction(LispNames.NTH, args -> {
			requireArgCount(LispNames.NTH, args, 2);
			return nthValue(asLong(args.get(0)), args.get(1));
		}));
		env.defineFunction(LispNames.SECOND, new LispFunction(LispNames.SECOND, args -> {
			requireArgCount(LispNames.SECOND, args, 1);
			return nthValue(1, args.get(0));
		}));
		env.defineFunction(LispNames.THIRD, new LispFunction(LispNames.THIRD, args -> {
			requireArgCount(LispNames.THIRD, args, 1);
			return nthValue(2, args.get(0));
		}));
		env.defineFunction(LispNames.FOURTH, new LispFunction(LispNames.FOURTH, args -> {
			requireArgCount(LispNames.FOURTH, args, 1);
			return nthValue(3, args.get(0));
		}));
		env.defineFunction(LispNames.FIFTH, new LispFunction(LispNames.FIFTH, args -> {
			requireArgCount(LispNames.FIFTH, args, 1);
			return nthValue(4, args.get(0));
		}));
		env.defineFunction(LispNames.SIXTH, new LispFunction(LispNames.SIXTH, args -> {
			requireArgCount(LispNames.SIXTH, args, 1);
			return nthValue(5, args.get(0));
		}));
		env.defineFunction(LispNames.SEVENTH, new LispFunction(LispNames.SEVENTH, args -> {
			requireArgCount(LispNames.SEVENTH, args, 1);
			return nthValue(6, args.get(0));
		}));
		env.defineFunction(LispNames.EIGHTH, new LispFunction(LispNames.EIGHTH, args -> {
			requireArgCount(LispNames.EIGHTH, args, 1);
			return nthValue(7, args.get(0));
		}));
		env.defineFunction(LispNames.NINTH, new LispFunction(LispNames.NINTH, args -> {
			requireArgCount(LispNames.NINTH, args, 1);
			return nthValue(8, args.get(0));
		}));
		env.defineFunction(LispNames.TENTH, new LispFunction(LispNames.TENTH, args -> {
			requireArgCount(LispNames.TENTH, args, 1);
			return nthValue(9, args.get(0));
		}));
		env.defineFunction(LispNames.LIST, new LispFunction(LispNames.LIST, args -> {
			LispVal result = LispNil.INSTANCE;
			for (int i = args.size() - 1; i >= 0; i--) {
				result = new LispCons(args.get(i), result);
			}
			return result;
		}));
		// values: yields its primary value ((values) yields nil) and publishes the
		// extra values to the %mv-spill global, so a multiple-value consumer in a
		// CALLER reads them back across the function boundary (the syntactic
		// consumers still recognize a literal (values ...) producer before
		// evaluation; the spill covers every other route, including funcall).
		env.defineFunction(LispNames.VALUES, new LispFunction(LispNames.VALUES, args -> {
			if (args.isEmpty()) {
				// No value at all: the channel says so, the primary a one-value caller
				// reads is nil.
				env.publishSpill(LispMacroExpander.MV_ZERO_VALUES);
				return LispNil.INSTANCE;
			}
			LispVal extras = LispNil.INSTANCE;
			for (int i = args.size() - 1; i >= 1; i--) {
				extras = new LispCons(args.get(i), extras);
			}
			env.publishSpill(extras);
			return args.get(0);
		}, true));
		// values-list: (values-list '(1 2)) == (values 1 2) -- the first element is
		// the primary value, the rest go to the spill channel; an empty list is no
		// value at all.
		env.defineFunction(LispNames.VALUES_LIST, new LispFunction(LispNames.VALUES_LIST, args -> {
			requireArgCount(LispNames.VALUES_LIST, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				env.publishSpill(cons.cdr());
				return cons.car();
			}
			env.publishSpill(LispMacroExpander.MV_ZERO_VALUES);
			return LispNil.INSTANCE;
		}, true));
		env.defineFunction(LispNames.NTHCDR, new LispFunction(LispNames.NTHCDR, args -> {
			requireArgCount(LispNames.NTHCDR, args, 2);
			long n = asLong(args.get(0));
			LispVal list = args.get(1);
			for (long i = 0; i < n; i++) {
				if (list instanceof LispCons cons) {
					list = cons.cdr();
				}
				else if (list instanceof LispNil) {
					return LispNil.INSTANCE;
				}
				else {
					// A walk that meets a non-list before the count runs out: 5 in
					// (nthcdr 1 5), the 2 of (nthcdr 2 '(1 . 2)).
					throw OperandTypeException.of(list, OperandTypes.Kind.LIST, LispNames.NTHCDR);
				}
			}
			return list;
		}));
		// endp: t for nil, nil for a cons, a type-error for anything else -- also
		// dolist's, whose expansion checks the list's end once after its loop.
		env.defineFunction(LispNames.ENDP, new LispFunction(LispNames.ENDP, args -> {
			requireArgCount(LispNames.ENDP, args, 1);
			return switch (args.get(0)) {
				case LispNil nil -> LispTrue.INSTANCE;
				case LispCons cons -> LispNil.INSTANCE;
				default -> throw OperandTypeException.of(args.get(0), OperandTypes.Kind.LIST, LispNames.ENDP);
			};
		}));
		// (%check-list x 'op): x when it is a list, else OP's LIST type-error -- the
		// check an expansion makes on its operator's behalf (last, maplist, loop's
		// for-in under endp).
		env.defineFunction(LispNames.CHECK_LIST_INTERNAL, new LispFunction(LispNames.CHECK_LIST_INTERNAL, args -> {
			requireArgCount(LispNames.CHECK_LIST_INTERNAL, args, 2);
			return requireListArgument(((LispSymbol) args.get(1)).name(), args.get(0));
		}));
		env.defineFunction(LispNames.RPLACA, new LispFunction(LispNames.RPLACA, args -> {
			requireArgCount(LispNames.RPLACA, args, 2);
			if (args.get(0) instanceof LispCons cons) {
				cons.setCar(args.get(1));
				return cons;
			}
			throw OperandTypeException.of(args.get(0), OperandTypes.Kind.CONS, LispNames.RPLACA);
		}));
		env.defineFunction(LispNames.RPLACD, new LispFunction(LispNames.RPLACD, args -> {
			requireArgCount(LispNames.RPLACD, args, 2);
			if (args.get(0) instanceof LispCons cons) {
				cons.setCdr(args.get(1));
				return cons;
			}
			throw OperandTypeException.of(args.get(0), OperandTypes.Kind.CONS, LispNames.RPLACD);
		}));
		env.defineFunction(LispNames.REMF_TAIL, new LispFunction(LispNames.REMF_TAIL, args -> {
			requireArgCount(LispNames.REMF_TAIL, args, 2);
			LispVal current = args.get(0);
			LispVal indicator = args.get(1);
			while (current instanceof LispCons currentCons) {
				LispVal valueCellVal = currentCons.cdr();
				if (!(valueCellVal instanceof LispCons valueCell)) {
					return LispNil.INSTANCE;
				}
				LispVal nextKeyCellVal = valueCell.cdr();
				if (!(nextKeyCellVal instanceof LispCons nextKeyCell)) {
					return LispNil.INSTANCE;
				}
				LispVal key = nextKeyCell.car();
				boolean match;
				if (key instanceof LispCons || indicator instanceof LispCons) {
					match = key == indicator;
				}
				else if (key instanceof LispNil && indicator instanceof LispNil) {
					match = true;
				}
				else if (key instanceof LispNil || indicator instanceof LispNil) {
					match = false;
				}
				else {
					match = key.equals(indicator);
				}
				if (match) {
					LispVal rest = nextKeyCell.cdr();
					if (rest instanceof LispCons restCons) {
						valueCell.setCdr(restCons.cdr());
					}
					else {
						valueCell.setCdr(LispNil.INSTANCE);
					}
					return LispTrue.INSTANCE;
				}
				current = nextKeyCell;
			}
			return LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.APPEND, new LispFunction(LispNames.APPEND, args -> {
			if (args.isEmpty()) {
				return LispNil.INSTANCE;
			}
			LispVal result = args.getLast();
			for (int i = args.size() - 2; i >= 0; i--) {
				result = appendTwo(args.get(i), result);
			}
			return result;
		}));
	}

	/**
	 * Walks {@code n} cdrs and returns the car, matching the macro expansion
	 * {@code (car (nthcdr n list))}: walking off the end yields nil, whose car is nil.
	 */
	private static LispVal nthValue(long n, LispVal list) {
		LispVal cur = list;
		for (long i = 0; i < n; i++) {
			if (cur instanceof LispCons cons) {
				cur = cons.cdr();
			}
			else if (cur instanceof LispNil) {
				return LispNil.INSTANCE;
			}
			else {
				throw OperandTypeException.of(cur, OperandTypes.Kind.LIST, LispNames.NTHCDR);
			}
		}
		if (cur instanceof LispCons cons) {
			return cons.car();
		}
		if (cur instanceof LispNil) {
			return LispNil.INSTANCE;
		}
		throw OperandTypeException.of(cur, OperandTypes.Kind.LIST, LispNames.CAR);
	}

	private static LispVal appendTwo(LispVal list, LispVal tail) {
		if (list instanceof LispNil) {
			return tail;
		}
		if (!(list instanceof LispCons head)) {
			throw OperandTypeException.of(list, OperandTypes.Kind.LIST, LispNames.APPEND);
		}
		// Iterative (.todo/749): the recursive spelling allocated its result by
		// recursing once per element, so a long first argument was a
		// StackOverflowError rather than a slow call. Walk forward, cons as you go,
		// and patch the last cdr to the shared tail; the result is unchanged (a
		// fresh spine, the tail shared) and an improper first argument still
		// signals, naming the tail it met.
		LispCons result = new LispCons(head.car(), LispNil.INSTANCE);
		LispCons last = result;
		LispVal cursor = head.cdr();
		while (cursor instanceof LispCons cell) {
			LispCons fresh = new LispCons(cell.car(), LispNil.INSTANCE);
			last.setCdr(fresh);
			last = fresh;
			cursor = cell.cdr();
		}
		requireListEnd(LispNames.APPEND, cursor, OperandTypes.Kind.LIST);
		last.setCdr(tail);
		return result;
	}

	/**
	 * The integer {@code op}
	 * ({@code floor}/{@code ceiling}/{@code round}/{@code truncate}) rounds one real to
	 * -- the one-argument quotient, one value.
	 * @param op the rounding operator
	 * @param arg the real to round
	 * @return the integer quotient
	 */
	static LispVal roundToInteger(String op, LispVal arg) {
		// Real-only by contract: a complex signals a catchable type-error (SBCL parity);
		// any other non-number keeps the historical message below.
		requireRealOperand(op, arg);
		if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
			return arg;
		}
		int mode = ExactRounding.mode(op);
		if (arg instanceof LispDouble d) {
			return ExactRounding.floatToInteger(d.value(), mode);
		}
		if (arg instanceof LispRatio r) {
			return normalizeBig(switch (op) {
				case LispNames.FLOOR -> r.floor();
				case LispNames.CEILING -> r.ceiling();
				case LispNames.ROUND -> r.round();
				default -> r.truncate();
			});
		}
		throw OperandTypeException.of(arg, OperandTypes.Kind.REAL).named(op);
	}

	/**
	 * The integer quotient {@code op} makes of {@code dividend / divisor}: exact at any
	 * magnitude, a float operand included ({@link ExactRounding#quotient}).
	 */
	private static LispVal floorFamilyQuotient(Environment env, String op, LispVal dividend, LispVal divisor) {
		try {
			LispVal exact = ExactRounding.quotient(dividend, divisor, ExactRounding.mode(op));
			if (exact != null) {
				return exact;
			}
			return roundToInteger(op, callGlobal(env, LispNames.DIV, dividend, divisor));
		}
		catch (OperandTypeException e) {
			// The division is this operator's own step, not a call the program made.
			throw e.named(op);
		}
	}

	/**
	 * A floor-family function call: the quotient, with the remainder published as the
	 * second value -- the same expressions the syntactic lowering emits
	 * ({@code LispMacroExpander.floorFamilyRemainder}): with no divisor the number minus
	 * its quotient (one rounding), with one the {@code rem}/{@code mod} CLHS defines the
	 * family by, exact at every magnitude.
	 * @param env the global environment
	 * @param name the function's own name (for the arity message)
	 * @param op the integer rounding operator
	 * @param floatQuotient whether the quotient is floated ({@code ffloor} and twins)
	 * @param args the number and the optional divisor
	 * @return the quotient
	 */
	private static LispVal floorFamilyValues(Environment env, String name, String op, boolean floatQuotient,
			List<LispVal> args) {
		requireArgCountBetween(name, args, 1, 2);
		LispVal dividend = args.get(0);
		LispVal quotient;
		LispVal remainder;
		if (args.size() == 1) {
			quotient = roundToInteger(op, dividend);
			remainder = callGlobal(env, LispNames.SUB, dividend, quotient);
		}
		else {
			LispVal divisor = args.get(1);
			quotient = floorFamilyQuotient(env, op, dividend, divisor);
			remainder = switch (op) {
				case LispNames.TRUNCATE -> callGlobal(env, LispNames.REM, dividend, divisor);
				case LispNames.FLOOR -> callGlobal(env, LispNames.MOD, dividend, divisor);
				case LispNames.CEILING -> ceilingRemainder(env, dividend, divisor);
				default -> {
					// round lands on floor's quotient or ceiling's; its remainder is the
					// one that belongs to whichever it chose.
					LispVal floor = floorFamilyQuotient(env, LispNames.FLOOR, dividend, divisor);
					yield callGlobal(env, LispNames.EQ, quotient, floor) instanceof LispNil
							? ceilingRemainder(env, dividend, divisor)
							: callGlobal(env, LispNames.MOD, dividend, divisor);
				}
			};
		}
		env.publishSpill(new LispCons(remainder, LispNil.INSTANCE));
		return floatQuotient ? callGlobal(env, LispNames.FLOAT, quotient) : quotient;
	}

	/**
	 * Ceiling's remainder: the negated {@code mod} of the negated dividend, or
	 * {@code rem}'s zero when that is zero (negating would flip a zero's sign).
	 */
	private static LispVal ceilingRemainder(Environment env, LispVal dividend, LispVal divisor) {
		LispVal m = callGlobal(env, LispNames.MOD, callGlobal(env, LispNames.SUB, dividend), divisor);
		if (!(callGlobal(env, LispNames.ZEROP, m) instanceof LispNil)) {
			return callGlobal(env, LispNames.REM, dividend, divisor);
		}
		return callGlobal(env, LispNames.SUB, m);
	}

	/** Calls an already-registered global built-in on evaluated arguments. */
	private static LispVal callGlobal(Environment env, String name, LispVal... args) {
		if (!(env.lookupFunctionOrNull(name) instanceof LispFunction fn)) {
			throw new IllegalStateException(name + " must be registered");
		}
		return fn.body().apply(List.of(args));
	}

	private static void registerTypeConversion(Environment env) {
		env.defineFunction(LispNames.FLOAT, new LispFunction(LispNames.FLOAT, args -> {
			// The optional second argument is a CL prototype selecting the float
			// subtype; the runtime has a single float representation, so it is ignored.
			requireArgCountBetween(LispNames.FLOAT, args, 1, 2);
			LispVal arg = args.get(0);
			// A complex has no float coercion (SBCL signals a type-error); any other
			// non-number keeps the historical message below.
			requireRealOperand(LispNames.FLOAT, arg);
			if (arg instanceof LispDouble) {
				return arg;
			}
			if (arg instanceof LispInteger i) {
				return new LispDouble((double) i.value());
			}
			if (arg instanceof LispBigInteger b) {
				return new LispDouble(b.value().doubleValue());
			}
			if (arg instanceof LispRatio r) {
				return new LispDouble(r.doubleValue());
			}
			throw OperandTypeException.of(arg, OperandTypes.Kind.REAL).named(LispNames.FLOAT);
		}));
		// floor/ceiling/round/truncate and their float-quotient twins as FUNCTION objects
		// ((funcall #'floor 7 2), (mapcar #'truncate xs ys)): an optional divisor, and
		// the
		// remainder published as the second value -- a function call is a multiple-value
		// producer like any other, and the compile paths' wrappers publish the same
		// (.kb/multiple-values.md, "The floor family as a function object"). The call
		// position does not come here: LispEvaluator rounds it directly, one value.
		for (String op : List.of(LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING, LispNames.ROUND)) {
			env.defineFunction(op, new LispFunction(op, args -> floorFamilyValues(env, op, op, false, args), true));
		}
		for (String op : List.of(LispNames.FTRUNCATE, LispNames.FFLOOR, LispNames.FCEILING, LispNames.FROUND)) {
			String intOp = switch (op) {
				case LispNames.FFLOOR -> LispNames.FLOOR;
				case LispNames.FCEILING -> LispNames.CEILING;
				case LispNames.FROUND -> LispNames.ROUND;
				default -> LispNames.TRUNCATE;
			};
			env.defineFunction(op, new LispFunction(op, args -> floorFamilyValues(env, op, intOp, true, args), true));
		}
		env.defineFunction(LispNames.NUMERATOR, new LispFunction(LispNames.NUMERATOR, args -> {
			requireArgCount(LispNames.NUMERATOR, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispRatio r) {
				return normalizeBig(r.numerator());
			}
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return arg;
			}
			throw OperandTypeException.of(arg, OperandTypes.Kind.RATIONAL).named(LispNames.NUMERATOR);
		}));
		env.defineFunction(LispNames.DENOMINATOR, new LispFunction(LispNames.DENOMINATOR, args -> {
			requireArgCount(LispNames.DENOMINATOR, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispRatio r) {
				return normalizeBig(r.denominator());
			}
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return new LispInteger(1);
			}
			throw OperandTypeException.of(arg, OperandTypes.Kind.RATIONAL).named(LispNames.DENOMINATOR);
		}));
		env.defineFunction(LispNames.RATIONAL, new LispFunction(LispNames.RATIONAL, args -> {
			// The exact rational the real IS: identity for integers and ratios,
			// the exact binary value for a float. A complex signals through the
			// real funnel; any other non-real is a type-error (ANSI
			// rational.error.4 checks every *mini-universe* member this way).
			requireArgCount(LispNames.RATIONAL, args, 1);
			LispVal arg = args.get(0);
			requireRealOperand(LispNames.RATIONAL, arg);
			if (arg instanceof LispInteger || arg instanceof LispBigInteger || arg instanceof LispRatio) {
				return arg;
			}
			if (arg instanceof LispDouble d) {
				return rationalOfDouble(d.value());
			}
			throw OperandTypeException.of(arg, OperandTypes.Kind.REAL);
		}));
	}

	/**
	 * Returns the text that {@code princ} would print for the value (no quotes on
	 * strings). Mirrors the numeric special-casing of the princ builtin.
	 */
	static String displayString(LispVal val) {
		if (val instanceof LispInteger i) {
			return Long.toString(i.value());
		}
		if (val instanceof LispDouble d) {
			return FloatText.doubleText(d.value());
		}
		return val.display();
	}

	/**
	 * Returns the text that {@code prin1} would print for the value (readable form,
	 * strings quoted). Mirrors the numeric special-casing of the prin1 builtin.
	 */
	private static String printString(LispVal val) {
		if (val instanceof LispInteger i) {
			return Long.toString(i.value());
		}
		if (val instanceof LispDouble d) {
			return FloatText.doubleText(d.value());
		}
		return val.print();
	}

	private static long asLong(LispVal val) {
		if (val instanceof LispInteger i) {
			return i.value();
		}
		throw OperandTypeException.of(val, OperandTypes.Kind.INTEGER);
	}

	private static double asDouble(LispVal val) {
		if (val instanceof LispDouble d) {
			return d.value();
		}
		if (val instanceof LispInteger i) {
			return (double) i.value();
		}
		if (val instanceof LispBigInteger b) {
			return b.value().doubleValue();
		}
		if (val instanceof LispRatio r) {
			return r.doubleValue();
		}
		throw OperandTypeException.of(val, OperandTypes.Kind.NUMBER);
	}

	/** Whether every argument is a {@code long}-range integer (the bitwise fast path). */
	private static boolean allFixnums(List<LispVal> args) {
		for (LispVal arg : args) {
			if (!(arg instanceof LispInteger)) {
				return false;
			}
		}
		return true;
	}

	private static BigInteger asBigInteger(LispVal val) {
		if (val instanceof LispInteger i) {
			return BigInteger.valueOf(i.value());
		}
		if (val instanceof LispBigInteger b) {
			return b.value();
		}
		throw OperandTypeException.of(val, OperandTypes.Kind.INTEGER);
	}

	/**
	 * Normalizes a {@link BigInteger} result, demoting it back to a {@link LispInteger}
	 * when it fits in a {@code long} so that fixnum-range values keep a single
	 * representation.
	 */
	private static LispVal normalizeBig(BigInteger value) {
		// bitLength() < 64 holds exactly for the signed long range [-2^63, 2^63-1].
		return value.bitLength() < 64 ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	/**
	 * Returns the exact rational a finite double IS, from its raw IEEE 754 bits: a normal
	 * value is {@code (2^52 + mantissa) * 2^(biased-1075)}, a subnormal (or zero) is
	 * {@code mantissa * 2^-1074}. The sign rides on the mantissa, so a zero mantissa
	 * answers plain zero regardless of the sign bit. A NaN or an infinity has no exact
	 * rational and signals (ANSI never feeds one -- *floats* holds no non-finite values
	 * -- so the message is free).
	 * @param value the double to convert
	 * @return the exact rational value
	 */
	private static LispVal rationalOfDouble(double value) {
		if (!Double.isFinite(value)) {
			throw new LispEvalException("rational of a non-finite float is undefined");
		}
		long bits = Double.doubleToRawLongBits(value);
		int rawExp = (int) ((bits >>> 52) & 0x7FF);
		BigInteger mant;
		int exp;
		if (rawExp == 0) {
			mant = BigInteger.valueOf(bits & 0xFFFFFFFFFFFFFL);
			exp = -1074;
		}
		else {
			mant = BigInteger.valueOf(bits & 0xFFFFFFFFFFFFFL).setBit(52);
			exp = rawExp - 1075;
		}
		if (bits < 0) {
			mant = mant.negate();
		}
		if (mant.signum() == 0) {
			return new LispInteger(0);
		}
		if (exp >= 0) {
			return normalizeBig(mant.shiftLeft(exp));
		}
		return LispRatio.valueOf(mant, BigInteger.ONE.shiftLeft(-exp));
	}

	private static LispVal addBig(List<LispVal> args) {
		BigInteger result = BigInteger.ZERO;
		for (LispVal arg : args) {
			result = result.add(asBigInteger(arg));
		}
		return normalizeBig(result);
	}

	private static LispVal subBig(List<LispVal> args) {
		if (args.size() == 1) {
			return normalizeBig(asBigInteger(args.get(0)).negate());
		}
		BigInteger result = asBigInteger(args.get(0));
		for (int i = 1; i < args.size(); i++) {
			result = result.subtract(asBigInteger(args.get(i)));
		}
		return normalizeBig(result);
	}

	private static LispVal mulBig(List<LispVal> args) {
		BigInteger result = BigInteger.ONE;
		for (LispVal arg : args) {
			result = result.multiply(asBigInteger(arg));
		}
		return normalizeBig(result);
	}

	/**
	 * Returns the numerator of a rational value (an integer is treated as itself over
	 * one).
	 */
	private static BigInteger numeratorOf(LispVal val) {
		if (val instanceof LispRatio r) {
			return r.numerator();
		}
		return asBigInteger(val);
	}

	/**
	 * Returns the denominator of a rational value (one for integers).
	 */
	private static BigInteger denominatorOf(LispVal val) {
		if (val instanceof LispRatio r) {
			return r.denominator();
		}
		if (val instanceof LispInteger || val instanceof LispBigInteger) {
			return BigInteger.ONE;
		}
		throw OperandTypeException.of(val, OperandTypes.Kind.NUMBER);
	}

	/**
	 * The float arm's ZERO remainder, shared by {@code mod} and {@code rem}. CLHS defines
	 * {@code rem} as the remainder of {@code truncate} and {@code mod} as the remainder
	 * of {@code floor}, and both of those are {@code number -
	 * divisor*quotient} with an exact INTEGER quotient -- NOT IEEE {@code fmod}, whose
	 * zero takes the sign of the dividend whatever the divisor. The two readings differ
	 * only when the remainder is zero: everywhere else Java's {@code %} is the exact
	 * value that formula denotes (and is exact where evaluating the formula in f64 is
	 * not), so only the zero is re-derived. A zero dividend has an exact quotient of
	 * {@code +0}, leaving {@code divisor*quotient} with the DIVISOR's sign; a nonzero
	 * dividend cancels against itself, and IEEE makes {@code x - x} a {@code +0.0}.
	 */
	private static double integerQuotientZero(double a, double b, double r) {
		if (r != 0.0) {
			return r; // nonzero, or NaN
		}
		return a == 0.0 ? a - Math.copySign(0.0, b) : 0.0;
	}

	/**
	 * The rational arm shared by {@code mod} and {@code rem}. With a = an/ad and b =
	 * bn/bd the quotient a/b is (an*bd)/(ad*bn), so the integer remainder of THAT
	 * division, read over the common denominator ad*bd, is the answer -- the same
	 * computation the integer arm does, one level up. {@code divisorSigned} corrects the
	 * remainder to the divisor's sign, which is what makes it {@code mod}.
	 */
	private static LispVal rationalRemainder(LispVal a, LispVal b, boolean divisorSigned) {
		BigInteger aDen = denominatorOf(a);
		BigInteger bDen = denominatorOf(b);
		BigInteger quotientDen = aDen.multiply(numeratorOf(b));
		if (quotientDen.signum() == 0) {
			throw LispEvalException.ofClass(ClosRegistry.DIVISION_BY_ZERO_CLASS_NAME, "Division by zero");
		}
		BigInteger r = numeratorOf(a).multiply(bDen).remainder(quotientDen);
		// Denominators are positive, so quotientDen carries the divisor's sign.
		if (divisorSigned && r.signum() != 0 && r.signum() != quotientDen.signum()) {
			r = r.add(quotientDen);
		}
		return LispRatio.valueOf(r, aDen.multiply(bDen));
	}

	private static LispVal addRational(List<LispVal> args) {
		BigInteger num = BigInteger.ZERO;
		BigInteger den = BigInteger.ONE;
		for (LispVal arg : args) {
			BigInteger argDen = denominatorOf(arg);
			num = num.multiply(argDen).add(numeratorOf(arg).multiply(den));
			den = den.multiply(argDen);
		}
		return LispRatio.valueOf(num, den);
	}

	private static LispVal subRational(List<LispVal> args) {
		if (args.size() == 1) {
			return LispRatio.valueOf(numeratorOf(args.get(0)).negate(), denominatorOf(args.get(0)));
		}
		BigInteger num = numeratorOf(args.get(0));
		BigInteger den = denominatorOf(args.get(0));
		for (int i = 1; i < args.size(); i++) {
			BigInteger argDen = denominatorOf(args.get(i));
			num = num.multiply(argDen).subtract(numeratorOf(args.get(i)).multiply(den));
			den = den.multiply(argDen);
		}
		return LispRatio.valueOf(num, den);
	}

	private static LispVal mulRational(List<LispVal> args) {
		BigInteger num = BigInteger.ONE;
		BigInteger den = BigInteger.ONE;
		for (LispVal arg : args) {
			num = num.multiply(numeratorOf(arg));
			den = den.multiply(denominatorOf(arg));
		}
		return LispRatio.valueOf(num, den);
	}

	/**
	 * Compares two rational values by cross-multiplication (denominators are always
	 * positive, so the comparison direction is preserved).
	 */
	private static int compareRational(LispVal a, LispVal b) {
		return numeratorOf(a).multiply(denominatorOf(b)).compareTo(numeratorOf(b).multiply(denominatorOf(a)));
	}

	/**
	 * Result of {@link #compareNumeric} when the operands do not compare (IEEE 754
	 * "unordered": either side is NaN). The value 2 falls outside every
	 * {@code [loSign, hiSign]} window of {@link #compareChain}, so any comparison
	 * involving NaN is false -- and {@code /=}, which expands to {@code (not (= ...))},
	 * is true.
	 */
	private static final int UNORDERED = 2;

	/**
	 * Compares two numbers, returning -1, 0, 1 or {@link #UNORDERED}. A float compared
	 * against an exact number (integer or ratio) compares EXACT values: the float's exact
	 * binary value (as {@code rational} answers it) against the exact operand, so
	 * {@code (= 1.0 (+ 1 tiny-ratio))} is false even though the ratio floats back to
	 * {@code 1.0}. Two floats compare per IEEE 754: {@code -0.0} equals {@code 0.0}, and
	 * NaN is unordered against everything (not {@code Double.compare}'s total order). An
	 * infinity sits beyond every exact number. A complex operand never reaches here:
	 * {@code =} compares complexes part-wise in {@link #compareChain}, and every other
	 * comparison signals -- so one slipping through is a real-operand complaint.
	 */
	private static int compareNumeric(LispVal a, LispVal b) {
		if (a instanceof LispComplex complex) {
			throw realOperandError(complex);
		}
		if (b instanceof LispComplex complex) {
			throw realOperandError(complex);
		}
		if (a instanceof LispDouble || b instanceof LispDouble) {
			return compareFloat(a, b);
		}
		if (a instanceof LispRatio || b instanceof LispRatio) {
			return Integer.signum(compareRational(a, b));
		}
		if (a instanceof LispBigInteger || b instanceof LispBigInteger) {
			return Integer.signum(asBigInteger(a).compareTo(asBigInteger(b)));
		}
		return Integer.signum(Long.compare(asLong(a), asLong(b)));
	}

	/**
	 * Compares a pair of which at least one side is a float. Two floats compare per IEEE
	 * 754 ({@code -0.0} equals {@code 0.0}, NaN is {@link #UNORDERED}). A float against
	 * an exact number compares the float's exact binary value (the
	 * {@link #rationalOfDouble} decomposition, so {@code -0.0} is zero) against the exact
	 * operand by cross-multiplication; a NaN is unordered, and an infinity outweighs
	 * every exact number on its side.
	 */
	private static int compareFloat(LispVal a, LispVal b) {
		if (a instanceof LispDouble da && b instanceof LispDouble db) {
			double x = da.value();
			double y = db.value();
			if (x < y) {
				return -1;
			}
			if (x > y) {
				return 1;
			}
			return x == y ? 0 : UNORDERED;
		}
		boolean aIsFloat = a instanceof LispDouble;
		LispVal other = aIsFloat ? b : a;
		if (!(other instanceof LispInteger) && !(other instanceof LispBigInteger) && !(other instanceof LispRatio)) {
			// The float arm's funnel: a non-number beside a float is what asDouble
			// throws (.kb/error-handling.md, "A non-number reaching arithmetic signals a
			// catchable type-error").
			throw OperandTypeException.of(other, OperandTypes.Kind.NUMBER);
		}
		double v = ((LispDouble) (aIsFloat ? a : b)).value();
		if (Double.isNaN(v)) {
			return UNORDERED;
		}
		if (Double.isInfinite(v)) {
			return (v > 0) == aIsFloat ? 1 : -1;
		}
		int sign = Integer.signum(compareRational(rationalOfDouble(v), aIsFloat ? b : a));
		return aIsFloat ? sign : -sign;
	}

	/**
	 * Evaluates a variadic numeric comparison: true when, for every adjacent pair, the
	 * sign of the comparison falls within {@code [loSign, hiSign]}. A single argument is
	 * trivially true. An {@link #UNORDERED} pair fails every window. {@code =} over a
	 * complex compares part-wise (a real counts as a zero-imagined complex, so
	 * {@code (= 2.0 #C(2.0 0.0))} is true); every other comparison over a complex signals
	 * a catchable type-error through {@link #compareNumeric}.
	 */
	private static LispVal compareChain(String name, List<LispVal> args, int loSign, int hiSign) {
		requireMinArgCount(name, args, 1);
		if (LispNames.EQ.equals(name) && hasComplex(args)) {
			for (int i = 1; i < args.size(); i++) {
				if (!complexEqual(args.get(i - 1), args.get(i))) {
					return LispNil.INSTANCE;
				}
			}
			return LispTrue.INSTANCE;
		}
		for (int i = 1; i < args.size(); i++) {
			int sign = compareNumeric(args.get(i - 1), args.get(i));
			if (sign < loSign || sign > hiSign) {
				return LispNil.INSTANCE;
			}
		}
		return LispTrue.INSTANCE;
	}

	private static boolean hasRatio(List<LispVal> args) {
		for (LispVal arg : args) {
			if (arg instanceof LispRatio) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasBigInteger(List<LispVal> args) {
		for (LispVal arg : args) {
			if (arg instanceof LispBigInteger) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasDouble(List<LispVal> args) {
		for (LispVal arg : args) {
			if (arg instanceof LispDouble) {
				return true;
			}
		}
		return false;
	}

	// ---- Complex arithmetic ----
	//
	// A complex operand routes + - * / (and 1+/1-, expt, the unary math) through
	// the helpers below, never through asDouble on the whole value. Exactness
	// follows SBCL: all-rational parts compute exactly over BigInteger
	// numerator/denominator pairs, while a float anywhere coerces the whole
	// computation to doubles.

	private static boolean hasComplex(List<LispVal> args) {
		for (LispVal arg : args) {
			if (arg instanceof LispComplex) {
				return true;
			}
		}
		return false;
	}

	private static LispEvalException realOperandError(LispVal complex) {
		return OperandTypeException.of(complex, OperandTypes.Kind.REAL);
	}

	// Signals for a complex operand only; any other value falls through to the
	// caller's own funnel, so a non-number keeps its existing message.
	private static void requireRealOperand(String name, LispVal val) {
		if (val instanceof LispComplex) {
			throw OperandTypeException.of(val, OperandTypes.Kind.REAL).named(name);
		}
	}

	private static LispVal requireReal(String name, LispVal val) {
		if (val instanceof LispInteger || val instanceof LispBigInteger || val instanceof LispRatio
				|| val instanceof LispDouble) {
			return val;
		}
		throw OperandTypeException.of(val, OperandTypes.Kind.NUMBER).named(name);
	}

	private static boolean isZeroReal(LispVal val) {
		if (val instanceof LispDouble d) {
			return d.value() == 0.0;
		}
		if (val instanceof LispRatio) {
			// A normalized ratio is never zero.
			return false;
		}
		if (val instanceof LispBigInteger b) {
			return b.value().signum() == 0;
		}
		if (val instanceof LispInteger i) {
			return i.value() == 0;
		}
		throw OperandTypeException.of(val, OperandTypes.Kind.NUMBER);
	}

	// A real value as a double; a non-number signals through the float funnel.
	private static double realToDouble(LispVal val) {
		if (val instanceof LispDouble d) {
			return d.value();
		}
		if (val instanceof LispInteger i) {
			return (double) i.value();
		}
		if (val instanceof LispBigInteger b) {
			return b.value().doubleValue();
		}
		if (val instanceof LispRatio r) {
			return r.doubleValue();
		}
		throw OperandTypeException.of(val, OperandTypes.Kind.NUMBER);
	}

	// The real part of a real-or-complex operand (a real answers itself; anything
	// else is left for the caller's funnel to complain about).
	private static LispVal complexReal(LispVal val) {
		if (val instanceof LispComplex c) {
			return c.real();
		}
		return val;
	}

	// The imaginary part: the complex's own, a double zero for a float, an integer
	// zero for any other real.
	private static LispVal complexImag(LispVal val) {
		if (val instanceof LispComplex c) {
			return c.imag();
		}
		if (val instanceof LispDouble) {
			return new LispDouble(0.0);
		}
		if (val instanceof LispInteger || val instanceof LispBigInteger || val instanceof LispRatio) {
			return new LispInteger(0);
		}
		return val;
	}

	private static double[] complexDoubleParts(LispVal val) {
		if (val instanceof LispComplex c) {
			return new double[] { realToDouble(c.real()), realToDouble(c.imag()) };
		}
		return new double[] { realToDouble(val), 0.0 };
	}

	private static boolean hasComplexDoublePart(List<LispVal> args) {
		for (LispVal arg : args) {
			if (arg instanceof LispDouble) {
				return true;
			}
			if (arg instanceof LispComplex c && (c.real() instanceof LispDouble || c.imag() instanceof LispDouble)) {
				return true;
			}
		}
		return false;
	}

	private static LispVal exactAdd(LispVal a, LispVal b) {
		return LispRatio.valueOf(
				numeratorOf(a).multiply(denominatorOf(b)).add(numeratorOf(b).multiply(denominatorOf(a))),
				denominatorOf(a).multiply(denominatorOf(b)));
	}

	private static LispVal exactSub(LispVal a, LispVal b) {
		return LispRatio.valueOf(
				numeratorOf(a).multiply(denominatorOf(b)).subtract(numeratorOf(b).multiply(denominatorOf(a))),
				denominatorOf(a).multiply(denominatorOf(b)));
	}

	private static LispVal exactMul(LispVal a, LispVal b) {
		return LispRatio.valueOf(numeratorOf(a).multiply(numeratorOf(b)), denominatorOf(a).multiply(denominatorOf(b)));
	}

	private static LispVal exactDiv(LispVal a, LispVal b) {
		if (numeratorOf(b).signum() == 0) {
			throw LispEvalException.ofClass(ClosRegistry.DIVISION_BY_ZERO_CLASS_NAME, "Division by zero");
		}
		return LispRatio.valueOf(numeratorOf(a).multiply(denominatorOf(b)), denominatorOf(a).multiply(numeratorOf(b)));
	}

	private static LispVal exactNeg(LispVal a) {
		return LispRatio.valueOf(numeratorOf(a).negate(), denominatorOf(a));
	}

	private static LispVal negateReal(LispVal val) {
		if (val instanceof LispDouble d) {
			return new LispDouble(-d.value());
		}
		return exactNeg(val);
	}

	private static LispVal addComplex(List<LispVal> args) {
		if (hasDouble(args) || hasComplexDoublePart(args)) {
			double re = 0.0;
			double im = 0.0;
			boolean first = true;
			for (LispVal arg : args) {
				double[] p = complexDoubleParts(arg);
				if (first) {
					re = p[0];
					im = p[1];
					first = false;
				}
				else {
					re += p[0];
					im += p[1];
				}
			}
			return LispComplex.valueOf(new LispDouble(re), new LispDouble(im));
		}
		LispVal re = new LispInteger(0);
		LispVal im = new LispInteger(0);
		for (LispVal arg : args) {
			re = exactAdd(re, complexReal(arg));
			im = exactAdd(im, complexImag(arg));
		}
		return LispComplex.valueOf(re, im);
	}

	private static LispVal subComplex(List<LispVal> args) {
		if (hasDouble(args) || hasComplexDoublePart(args)) {
			double[] first = complexDoubleParts(args.get(0));
			if (args.size() == 1) {
				return LispComplex.valueOf(new LispDouble(-first[0]), new LispDouble(-first[1]));
			}
			double re = first[0];
			double im = first[1];
			for (int i = 1; i < args.size(); i++) {
				double[] p = complexDoubleParts(args.get(i));
				re -= p[0];
				im -= p[1];
			}
			return LispComplex.valueOf(new LispDouble(re), new LispDouble(im));
		}
		if (args.size() == 1) {
			return LispComplex.valueOf(exactNeg(complexReal(args.get(0))), exactNeg(complexImag(args.get(0))));
		}
		LispVal re = complexReal(args.get(0));
		LispVal im = complexImag(args.get(0));
		for (int i = 1; i < args.size(); i++) {
			re = exactSub(re, complexReal(args.get(i)));
			im = exactSub(im, complexImag(args.get(i)));
		}
		return LispComplex.valueOf(re, im);
	}

	private static LispVal mulComplex(List<LispVal> args) {
		if (hasDouble(args) || hasComplexDoublePart(args)) {
			double re = 1.0;
			double im = 0.0;
			for (LispVal arg : args) {
				double[] p = complexDoubleParts(arg);
				double next = re * p[0] - im * p[1];
				im = re * p[1] + im * p[0];
				re = next;
			}
			return LispComplex.valueOf(new LispDouble(re), new LispDouble(im));
		}
		LispVal re = new LispInteger(1);
		LispVal im = new LispInteger(0);
		for (LispVal arg : args) {
			LispVal c = complexReal(arg);
			LispVal d = complexImag(arg);
			LispVal next = exactSub(exactMul(re, c), exactMul(im, d));
			im = exactAdd(exactMul(re, d), exactMul(im, c));
			re = next;
		}
		return LispComplex.valueOf(re, im);
	}

	private static LispVal divComplex(List<LispVal> args) {
		if (hasDouble(args) || hasComplexDoublePart(args)) {
			double[] first = complexDoubleParts(args.get(0));
			double re = first[0];
			double im = first[1];
			int start = 1;
			if (args.size() == 1) {
				re = 1.0;
				im = 0.0;
				start = 0;
			}
			for (int i = start; i < args.size(); i++) {
				double[] p = complexDoubleParts(args.get(i));
				double[] quotient = smithDivide(re, im, p[0], p[1]);
				re = quotient[0];
				im = quotient[1];
			}
			return LispComplex.valueOf(new LispDouble(re), new LispDouble(im));
		}
		LispVal re = new LispInteger(1);
		LispVal im = new LispInteger(0);
		int start = 1;
		if (args.size() == 1) {
			start = 0;
		}
		else {
			re = complexReal(args.get(0));
			im = complexImag(args.get(0));
		}
		for (int i = start; i < args.size(); i++) {
			LispVal[] divided = exactDivComplex(re, im, complexReal(args.get(i)), complexImag(args.get(i)));
			re = divided[0];
			im = divided[1];
		}
		return LispComplex.valueOf(re, im);
	}

	// (a+bi)/(c+di) in FLOATS by Smith's form: fold on whichever divisor part is
	// larger, so the only quantity ever squared is the smaller part over the larger
	// and nothing intermediate leaves the range the operands themselves live in. The
	// c^2+d^2 denominator this replaced overflows above |c| ~ 1.3e154 and flushes to
	// zero below ~1.5e-162, where both operands are perfectly representable:
	// (/ #c(1d200 1d200) #c(1d200 1d200)) answered #C(NaN NaN) and is 1.0 here.
	// A REAL divisor makes d, and with it r, zero and den c, so both parts reduce to
	// ONE rounded division instead of three -- which is why (log -8d0 2d0) lands on
	// SBCL's #C(3.0 4.532360141827194) where the denominator form answered
	// 2.9999999999999996 for the real part. SBCL 2.2.9 computes this same form
	// (measured on linux/amd64, 2026-09-11, every row of .kb/jvm-complex.md's table).
	// A zero FLOAT divisor keeps the NaN the denominator form answered -- r is 0/0
	// and every part follows -- rather than the part-wise infinity a d == 0.0 special
	// case would produce: SBCL signals DIVISION-BY-ZERO there because its FPU traps,
	// this runtime does not trap, and a non-answer is the honest image of a signal.
	// The compiled twins are JvmComplexRuntimeBuilder.buildDiv's float tail and
	// WasmComplexRuntimeBuilder.buildDivBody's float arm; all three must agree.
	private static double[] smithDivide(double a, double b, double c, double d) {
		if (Math.abs(c) >= Math.abs(d)) {
			double r = d / c;
			double den = c + d * r;
			return new double[] { (a + b * r) / den, (b - a * r) / den };
		}
		double r = c / d;
		double den = c * r + d;
		return new double[] { (a * r + b) / den, (b * r - a) / den };
	}

	// (a+bi)/(c+di) exactly: the denominator c^2+d^2 is real, so both parts divide
	// by it. Exact rationals neither overflow nor round, so the float arm's Smith
	// fold buys nothing here. A zero divisor signals division-by-zero, like real
	// (/ x 0).
	private static LispVal[] exactDivComplex(LispVal a, LispVal b, LispVal c, LispVal d) {
		LispVal denom = exactAdd(exactMul(c, c), exactMul(d, d));
		if (isZeroReal(denom)) {
			throw LispEvalException.ofClass(ClosRegistry.DIVISION_BY_ZERO_CLASS_NAME, "Division by zero");
		}
		return new LispVal[] { exactDiv(exactAdd(exactMul(a, c), exactMul(b, d)), denom),
				exactDiv(exactSub(exactMul(b, c), exactMul(a, d)), denom) };
	}

	// Whether two numbers are = across the complex boundary: every part pair
	// compares equal under the real comparison (a real counts as a zero-imagined
	// complex). A non-number part signals through the real funnel.
	private static boolean complexEqual(LispVal a, LispVal b) {
		return compareNumeric(complexReal(a), complexReal(b)) == 0
				&& compareNumeric(complexImag(a), complexImag(b)) == 0;
	}

	// z^w for a complex operand: an int-range integer exponent over rational parts
	// stays exact by repeated squaring (a negative one through the exact
	// reciprocal); anything else goes through exp(w*log(z)) in floats.
	private static LispVal exptComplex(LispVal base, LispVal exp) {
		double[] z = complexDoubleParts(base);
		boolean exactBase = !hasComplexDoublePart(List.of(base)) && !(base instanceof LispDouble);
		if (exactBase && exp instanceof LispInteger i && i.value() >= -Integer.MAX_VALUE
				&& i.value() <= Integer.MAX_VALUE) {
			long power = i.value();
			LispVal re = new LispInteger(1);
			LispVal im = new LispInteger(0);
			LispVal bRe = complexReal(base);
			LispVal bIm = complexImag(base);
			if (power < 0) {
				LispVal[] reciprocal = exactDivComplex(new LispInteger(1), new LispInteger(0), bRe, bIm);
				bRe = reciprocal[0];
				bIm = reciprocal[1];
				power = -power;
			}
			while (power > 0) {
				if ((power & 1) == 1) {
					LispVal next = exactSub(exactMul(re, bRe), exactMul(im, bIm));
					im = exactAdd(exactMul(re, bIm), exactMul(im, bRe));
					re = next;
				}
				LispVal nextBase = exactSub(exactMul(bRe, bRe), exactMul(bIm, bIm));
				bIm = exactAdd(exactMul(bRe, bIm), exactMul(bIm, bRe));
				bRe = nextBase;
				power >>= 1;
			}
			return LispComplex.valueOf(re, im);
		}
		double[] w = complexDoubleParts(exp);
		double[] l = complexLog(z[0], z[1]);
		double[] e = complexExp(w[0] * l[0] - w[1] * l[1], w[0] * l[1] + w[1] * l[0]);
		return LispComplex.valueOf(new LispDouble(e[0]), new LispDouble(e[1]));
	}

	/**
	 * Whether {@code base^power} over two REAL floats leaves the real line: a negative
	 * base raised to a power that is not an integer. Everything else -- a non-negative
	 * base, an integer-valued power (a float {@code 2d0} included, whose parity
	 * {@code StrictMath.pow} already honours) and either operand NaN or the power
	 * infinite -- has a real answer {@code StrictMath.pow} gives.
	 * @param base the base as a float
	 * @param power the exponent as a float
	 * @return whether the answer is complex
	 */
	static boolean escapesToPlane(double base, double power) {
		return base < 0.0 && Double.isFinite(power) && power != Math.rint(power);
	}

	// (expt x y) for a NEGATIVE real x and a non-integer y: the modulus |x|^y turned
	// through y*pi radians. This is NOT exp(y*log x) and NOT exptComplex over (x, 0):
	// a real base's phase is EXACTLY pi, so the modulus costs one pow instead of a log
	// and an exp, and (expt -2d0 0.5d0)'s imaginary part comes out exactly (sqrt 2)
	// where the logarithmic form loses a ulp. The two therefore disagree in the last
	// bits -- (expt #c(-8d0 0d0) 1/3) is not (expt -8d0 1/3) -- which is SBCL's split
	// too, and correct: the real base carries information the complex one does not.
	private static LispVal negativeBasePow(double base, double power) {
		double modulus = StrictMath.pow(-base, power);
		double theta = power * Math.PI;
		return LispComplex.valueOf(new LispDouble(modulus * StrictMath.cos(theta)),
				new LispDouble(modulus * StrictMath.sin(theta)));
	}

	// The principal square root of (re, im) in floats.
	private static double[] complexSqrt(double re, double im) {
		if (re == 0.0 && im == 0.0) {
			return new double[] { re, im };
		}
		double t = Math.sqrt((Math.abs(re) + StrictMath.hypot(re, im)) / 2);
		if (re >= 0) {
			return new double[] { t, im / (2 * t) };
		}
		return new double[] { Math.abs(im) / (2 * t), Math.copySign(t, im) };
	}

	private static double[] complexExp(double re, double im) {
		double e = StrictMath.exp(re);
		return new double[] { e * StrictMath.cos(im), e * StrictMath.sin(im) };
	}

	private static double[] complexLog(double re, double im) {
		return new double[] { StrictMath.log(StrictMath.hypot(re, im)), StrictMath.atan2(im, re) };
	}

	private static double[] complexSin(double re, double im) {
		return new double[] { StrictMath.sin(re) * StrictMath.cosh(im), StrictMath.cos(re) * StrictMath.sinh(im) };
	}

	private static double[] complexCos(double re, double im) {
		return new double[] { StrictMath.cos(re) * StrictMath.cosh(im), -StrictMath.sin(re) * StrictMath.sinh(im) };
	}

	private static double[] complexTan(double re, double im) {
		double[] s = complexSin(re, im);
		double[] c = complexCos(re, im);
		double denom = c[0] * c[0] + c[1] * c[1];
		return new double[] { (s[0] * c[0] + s[1] * c[1]) / denom, (s[1] * c[0] - s[0] * c[1]) / denom };
	}

	// asin(z) = (atan2(re, Re(u*v)), asinh(Im(conj(u)*v))) with u = sqrt(1-z) and
	// v = sqrt(1+z) -- Kahan's form, and SBCL's. It fixes the branch cut and the real
	// axis at once. THE CUT: the 1-/1+ subtractions are taken against an imaginary
	// +0.0, so an imaginary zero of EITHER sign leaves u and v on the same sheet and
	// the side of the cut is decided by the real part alone, as CLHS requires
	// (quadrant IV above +1, quadrant II below -1). THE AXIS: for a real argument
	// inside [-1, 1] both roots are real, so asinh's argument is a difference of
	// zeros and the result is exactly real -- where -i*log(i*z + sqrt(1 - z^2))
	// leaked 2^-53 out of the logarithm.
	private static double[] complexAsin(double re, double im) {
		double[] u = complexSqrt(1 - re, 0.0 - im);
		double[] v = complexSqrt(1 + re, 0.0 + im);
		return new double[] { StrictMath.atan2(re, u[0] * v[0] - u[1] * v[1]), asinhReal(u[0] * v[1] - u[1] * v[0]) };
	}

	// acos(z) = (2*atan2(Re(u), Re(v)), asinh(Im(conj(v)*u))) over the same two roots
	// -- NOT pi/2 - asin(z), which would carry asin's real part (and its error) into
	// a quantity that is exactly 0 or pi on the cut.
	private static double[] complexAcos(double re, double im) {
		double[] u = complexSqrt(1 - re, 0.0 - im);
		double[] v = complexSqrt(1 + re, 0.0 + im);
		return new double[] { 2 * StrictMath.atan2(u[0], v[0]), asinhReal(v[0] * u[1] - v[1] * u[0]) };
	}

	// atan(z) = (i/2)*(log(1-i*z) - log(1+i*z)).
	private static double[] complexAtan(double re, double im) {
		double[] l1 = complexLog(1 + im, -re);
		double[] l2 = complexLog(1 - im, re);
		return new double[] { (l2[1] - l1[1]) / 2, (l1[0] - l2[0]) / 2 };
	}

	private static double[] complexSinh(double re, double im) {
		return new double[] { StrictMath.sinh(re) * StrictMath.cos(im), StrictMath.cosh(re) * StrictMath.sin(im) };
	}

	private static double[] complexCosh(double re, double im) {
		return new double[] { StrictMath.cosh(re) * StrictMath.cos(im), StrictMath.sinh(re) * StrictMath.sin(im) };
	}

	private static double[] complexTanh(double re, double im) {
		double[] s = complexSinh(re, im);
		double[] c = complexCosh(re, im);
		double denom = c[0] * c[0] + c[1] * c[1];
		return new double[] { (s[0] * c[0] + s[1] * c[1]) / denom, (s[1] * c[0] - s[0] * c[1]) / denom };
	}

	// java.lang.Math has no inverse hyperbolics -- these are the real arms the JVM
	// _cu1 helper bytecodes term for term (identical Math calls, identical bits), and
	// they are overflow-safe: the small branch (|x| <= 1) keeps log1p's accuracy near
	// zero, the large branch never squares past the float range. It is also the
	// imaginary part of every complex asin/acos, so its grouping is their accuracy:
	// ONE log over the sum (which cancels nothing above 1, and whose hypot cannot
	// overflow) beats log a + log(1 + hypot(1/a, 1)), whose two roundings land a ulp
	// high on 18% of the arguments above 1 (measured 2026-09-11). Only the sum can
	// overflow, so the huge rung is acosh's, at the same 8.5e307.
	private static double asinhReal(double x) {
		double a = Math.abs(x);
		if (a <= 1.0) {
			return Math.copySign(StrictMath.log1p(a + (a * a) / (1.0 + StrictMath.hypot(a, 1.0))), x);
		}
		if (a < 8.5e307) {
			return Math.copySign(StrictMath.log(a + StrictMath.hypot(a, 1.0)), x);
		}
		return Math.copySign(StrictMath.log(a) + StrictMath.log(2.0), x);
	}

	// acosh for x >= 1 only (the caller routes x < 1 into the plane). Three branches:
	// near 1 the log1p form (no cancellation, no loss for x = nextUp(1)); mid-range
	// log(2x) + log1p((r-1)/2) with r = sqrt(1 - 1/x^2) (the glibc grouping, the only
	// one whose double bits are correctly rounded over the whole table); huge x a bare
	// log(x) + log 2 so 2*x cannot overflow.
	private static double acoshReal(double x) {
		if (x < 2.0) {
			double xm = x - 1.0;
			return StrictMath.log1p(xm + Math.sqrt(xm * (x + 1.0)));
		}
		if (x < 8.5e307) {
			double inv = 1.0 / x;
			double r = Math.sqrt(1.0 - inv * inv);
			return StrictMath.log(2.0 * x) + StrictMath.log1p((r - 1.0) / 2.0);
		}
		return StrictMath.log(x) + StrictMath.log(2.0);
	}

	// Whether a real argument keeps the REAL arm of asin, acos and atanh -- inside
	// [-1, 1], their shared real domain. Written as two NEGATED comparisons so a NaN
	// answers true and comes back out as itself rather than as a complex NaN pair.
	// An INFINITY does the same, for the same reason: it has no complex value either
	// (the formula would manufacture a #C(NaN Infinity), and SBCL signals
	// FLOATING-POINT-INVALID rather than answer one), so StrictMath.asin's NaN is the
	// honest
	// answer. log is the opposite case and is not on this predicate: (log -Infinity)
	// is #C(Infinity pi), a real point of the plane, and SBCL answers it.
	private static boolean insideUnit(double x) {
		return !(x > 1.0) && !(x < -1.0) || Double.isInfinite(x);
	}

	// atanh for |x| <= 1 only (the caller routes |x| > 1 into the plane). The log1p
	// difference is accurate for small x and answers +-Infinity at +-1; a signed zero
	// in answers the signed zero out (odd, like StrictMath.sinh's small branch).
	private static double atanhReal(double x) {
		return (StrictMath.log1p(x) - StrictMath.log1p(-x)) * 0.5;
	}

	// cis(z) = exp(i*z) = (e^-im*cos(re), e^-im*sin(re)).
	private static double[] complexCis(double re, double im) {
		double e = StrictMath.exp(-im);
		return new double[] { e * StrictMath.cos(re), e * StrictMath.sin(re) };
	}

	// asinh(z) = log(z + sqrt(z^2 + 1)). The +0.0 on the imaginary part of z^2
	// normalizes the -0.0 a zero times a negative factor leaves: the cut sqrt must
	// take its +i root there, or (asinh #c(0 -4)) lands on the wrong sheet
	// (SBCL's #C(-2.06... -pi/2) becomes #C(2.06... -pi/2)). When the sum z + s
	// cancels to magnitude < 1, log(z+s) = -log(s-z) (their product is s^2 - z^2 = 1)
	// and s - z adds without cancellation -- what keeps (asinh #c(0 -4)) on SBCL's bit.
	private static double[] complexAsinh(double re, double im) {
		double z2re = re * re - im * im;
		double z2im = 2 * re * im + 0.0;
		double[] s = complexSqrt(z2re + 1, z2im);
		double w0 = re + s[0];
		double w1 = im + s[1];
		if (w0 * w0 + w1 * w1 < 1.0) {
			double[] l = complexLog(s[0] - re, s[1] - im);
			return new double[] { -l[0], -l[1] };
		}
		return complexLog(w0, w1);
	}

	// acosh(z) = 2*log(sqrt((z+1)/2) + sqrt((z-1)/2)) -- the ANSI form; the naive
	// log(z + sqrt(z^2 - 1)) puts the branch cut in the wrong place. The /2 keeps
	// the sign of a signed-zero imaginary part, which is what picks the sheet at
	// the cut.
	private static double[] complexAcosh(double re, double im) {
		double[] s1 = complexSqrt((re + 1) / 2, im / 2);
		double[] s2 = complexSqrt((re - 1) / 2, im / 2);
		double[] l = complexLog(s1[0] + s2[0], s1[1] + s2[1]);
		return new double[] { 2 * l[0], 2 * l[1] };
	}

	// atanh(z) = (log(1+z) - log(1-z)) / 2, the difference of the principal logs
	// (the log of the quotient would answer the other edge of the cut: (atanh 2)
	// needs its imaginary part at +pi/2, not -pi/2).
	private static double[] complexAtanh(double re, double im) {
		double[] l1 = complexLog(1 + re, im);
		double[] l2 = complexLog(1 - re, -im);
		return new double[] { (l1[0] - l2[0]) / 2, (l1[1] - l2[1]) / 2 };
	}

	/**
	 * Classifies an evaluated {@code open} element-type argument
	 * ({@link StreamElementType#of}): a character type opens a text stream, an integer
	 * type a binary stream of the width SBCL gives it; anything else is rejected. The
	 * interpreter reads EVERY element type this way, computed or literal -- the compile
	 * paths take a wide one only as a literal ({@code .kb/read-load-streams.md}).
	 * @param spec the evaluated element type specifier
	 * @return the classification
	 */
	private static StreamElementType streamElementType(LispVal spec) {
		StreamElementType type = StreamElementType.of(spec);
		if (type == null) {
			throw new LispEvalException(LispNames.OPEN
					+ " :element-type supports character and bounded integer types, got " + spec.print());
		}
		return type;
	}

	/**
	 * The integer a wide element's octets spell: little-endian, two's complement when
	 * signed.
	 */
	private static LispVal composeElement(byte[] octets, boolean signed) {
		byte[] bigEndian = new byte[octets.length + 1];
		for (int i = 0; i < octets.length; i++) {
			bigEndian[octets.length - i] = octets[i];
		}
		BigInteger value = new BigInteger(bigEndian);
		if (signed && value.testBit(8 * octets.length - 1)) {
			value = value.subtract(BigInteger.ONE.shiftLeft(8 * octets.length));
		}
		return value.bitLength() < 64 ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	/**
	 * The name of an evaluated {@code :if-exists} / {@code :if-does-not-exist} value:
	 * {@code "NIL"} for a false one, the keyword's own name otherwise.
	 * @param value the evaluated option value
	 * @return the name the guard switches on
	 */
	private static String openOptionName(LispVal value) {
		if (value instanceof LispNil) {
			return "NIL";
		}
		if (value instanceof LispSymbol sym) {
			return sym.name();
		}
		throw new LispEvalException(LispNames.OPEN + ": unsupported option value " + value.print());
	}

	/**
	 * The {@code file-error} an {@code :if-exists :error} / {@code :if-does-not-exist
	 * :error} refusal signals, carrying the designator as given -- the same pair of texts
	 * {@code LispMacroExpander}'s existence guard emits on the compile paths.
	 */
	private static LispEvalException openGuardError(LispVal designator, String message) {
		return new LispEvalException(message, ClosRegistry.newFileErrorCondition(designator, new LispString(message)));
	}

	/**
	 * The five characters CL's standard readtable calls whitespace -- the set
	 * {@code peek-char}'s {@code t} peek-type skips. Kept in step with
	 * {@code LispMacroExpander.whitespaceCharTest}, which is the compiled backends' copy.
	 */
	private static boolean isLispWhitespace(int codePoint) {
		return codePoint == ' ' || codePoint == '\t' || codePoint == '\n' || codePoint == '\r' || codePoint == '\f';
	}

	/**
	 * The end-of-file signal of the read family: a TYPED condition, so a
	 * {@code (handler-case ... (end-of-file (e) ...))} around a reader loop fires -- the
	 * shape real CL lexers are written in. The compiled backends reach the same class
	 * through {@code LispMacroExpander.expandReadEofSignal}, so the message and the
	 * catchable type are identical everywhere. The condition carries the offending stream
	 * (what {@code stream-error-stream} reads back).
	 * @param stream the stream the read ran out on
	 */
	private static LispEvalException endOfFile(LispVal stream) {
		return new LispEvalException(ClosRegistry.END_OF_FILE_MESSAGE, ClosRegistry.newEndOfFileCondition(stream));
	}

	/**
	 * A string-input stream value over the given text, for the {@code stream} slot of a
	 * runtime-read condition -- what a {@code read-from-string} failure hands
	 * {@code stream-error-stream}, since the string itself is not a stream.
	 * @param streams the stream table
	 * @param nextStreamHandle the handle allocator
	 * @param input the offending text
	 * @return the stream value
	 */
	private static LispVal errorStream(Map<Long, Closeable> streams, AtomicLong nextStreamHandle, String input) {
		long handle = nextStreamHandle.getAndIncrement();
		streams.put(handle, new RontoStringInputStream(input));
		return streamValue(handle, LispLayout.Kinds.STRING_INPUT);
	}

	/**
	 * Whether a value is a SYNONYM STREAM -- an instance of the fixed
	 * {@link LispLayout#SYNONYM_STREAM} layout, the one stream that is a value rather
	 * than a handle.
	 * @param value any value
	 * @return true for a synonym stream
	 */
	static boolean isSynonymStream(@Nullable LispVal value) {
		return value instanceof LispInstance inst && inst.hasTag(LispLayout.SYNONYM_STREAM_TAG);
	}

	/**
	 * The raw HANDLE a stream operation actually acts on. An OPEN stream
	 * ({@link LispLayout#STREAM}) answers its handle slot; a synonym stream answers the
	 * CURRENT value of the variable it names, by calling the reader closure it carries
	 * beside its declared slot, and the walk repeats so a synonym over a synonym (or over
	 * an open stream) resolves too. Anything else is itself.
	 *
	 * <p>
	 * This is the interpreter's half of the resolution the compile paths get from
	 * {@code %STREAM-TARGET} ({@code StreamDesignators.throughStream}); the reader is
	 * dynamic-binding aware on both. A cycle -- a variable holding a synonym stream over
	 * itself -- is broken by the depth bound rather than hanging.
	 * @param designator the stream designator as written, possibly null (omitted)
	 * @return the resolved designator
	 */
	/**
	 * {@code file-position} of a STRING stream, or null when the entry is not one
	 * ({@code .kb/read-load-streams.md}, "String streams"). An input stream counts the
	 * characters read from its own start and seeks to a character index, {@code :start}
	 * or {@code :end} -- never past the end, which answers nil. An output stream counts
	 * the characters written since it was last emptied and cannot be repositioned except
	 * where it already is.
	 * @param entry the stream-table entry
	 * @param args the {@code file-position} arguments
	 * @return the answer, or null for any other stream
	 */
	static @Nullable LispVal stringStreamPosition(@Nullable Closeable entry, List<LispVal> args) {
		if (entry instanceof RontoStringInputStream input) {
			if (args.size() < 2) {
				return new LispInteger(input.position());
			}
			LispVal spec = args.get(1);
			long target = spec instanceof LispSymbol s && ":START".equals(s.name()) ? 0
					: spec instanceof LispSymbol s && ":END".equals(s.name()) ? -1
							: spec instanceof LispInteger n ? requireNonNegativePosition(n.value()) : -2;
			if (target == -2) {
				throw new LispEvalException(LispNames.FILE_POSITION + " expects an integer position");
			}
			return input.seek(target) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}
		if (entry instanceof StringWriter output) {
			StringBuffer written = output.getBuffer();
			long length = written.codePointCount(0, written.length());
			if (args.size() < 2) {
				return new LispInteger(length);
			}
			LispVal spec = args.get(1);
			boolean here = spec instanceof LispSymbol s
					&& (":START".equals(s.name()) ? length == 0 : ":END".equals(s.name()))
					|| spec instanceof LispInteger n && requireNonNegativePosition(n.value()) == length;
			return here ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}
		return null;
	}

	/**
	 * A stream designator's direction as a bit set -- 1 input, 2 output -- resolved
	 * through synonym streams. The interpreter's reading of the compile paths'
	 * {@code %stream-direction}: a string stream, a request body and a standard stream
	 * answer their kind's direction, a socket both, a FILE stream what its table entry
	 * can do (nothing once closed), the {@code t} designator both, and anything that is
	 * not a stream neither.
	 * @param designator the argument as given
	 * @param streams the stream table
	 * @param outputOnlyCursorStreams the {@code :overwrite} handles opened for output
	 * alone
	 * @return the direction bits
	 */
	static int streamDirection(LispVal designator, Map<Long, Closeable> streams, Set<Long> outputOnlyCursorStreams) {
		LispVal target = synonymTarget(designator);
		if (target instanceof LispTrue) {
			return 3;
		}
		if (!(target instanceof LispInstance inst && inst.hasTag(LispLayout.STREAM_TAG)
				&& inst.slot(0) instanceof LispInteger handle && inst.slot(1) instanceof LispSymbol kind)) {
			return 0;
		}
		return switch (kind.name()) {
			case LispLayout.Kinds.STRING_INPUT, LispLayout.Kinds.BODY -> 1;
			case LispLayout.Kinds.STRING_OUTPUT -> 2;
			case LispLayout.Kinds.STANDARD -> handle.value() == 0 ? 1 : 2;
			case LispLayout.Kinds.FILE -> switch (streams.get(handle.value())) {
				case RontoIoFileStream ignored -> outputOnlyCursorStreams.contains(handle.value()) ? 2 : 3;
				case Reader ignored -> 1;
				case InputStream ignored -> 1;
				case Writer ignored -> 2;
				case OutputStream ignored -> 2;
				case null, default -> 0;
			};
			default -> 3;
		};
	}

	private static long requireNonNegativePosition(long position) {
		if (position < 0) {
			throw new LispEvalException(LispNames.FILE_POSITION + ": position must be non-negative");
		}
		return position;
	}

	static LispVal streamTarget(LispVal designator) {
		LispVal resolved = synonymTarget(designator);
		return resolved instanceof LispInstance inst && inst.hasTag(LispLayout.STREAM_TAG) ? inst.slot(0) : resolved;
	}

	/**
	 * The synonym half of {@link #streamTarget}: the value a synonym stream forwards to,
	 * recursively, WITHOUT unwrapping an open stream at the end. This is what the Gray
	 * dispatch wraps resolve with -- they need the stream VALUE, not its handle, to tell
	 * a Gray instance from an open stream.
	 * @param designator the stream designator as written
	 * @return the designator a synonym stream forwards to, or the designator itself
	 */
	static LispVal synonymTarget(LispVal designator) {
		LispVal current = designator;
		for (int depth = 0; depth < SYNONYM_DEPTH_LIMIT; depth++) {
			if (!(current instanceof LispInstance inst) || !inst.hasTag(LispLayout.SYNONYM_STREAM_TAG)) {
				return current;
			}
			if (!(inst.slot(1) instanceof LispFunction reader)) {
				throw new LispEvalException("not a synonym stream reader: " + inst.slot(1).print());
			}
			current = reader.body().apply(List.of());
		}
		throw new LispEvalException("synonym stream forwards to itself");
	}

	/**
	 * Whether a value is an OPEN stream -- an instance of the fixed
	 * {@link LispLayout#STREAM} layout, which is what {@code open}, the string-stream
	 * constructors and the socket constructors answer.
	 * @param value any value
	 * @return true for an open stream value
	 */
	static boolean isStreamValue(@Nullable LispVal value) {
		return value instanceof LispInstance inst && inst.hasTag(LispLayout.STREAM_TAG);
	}

	/**
	 * A stream VALUE over a backend handle: what every producer built-in answers.
	 * @param handle the stream table index
	 * @param kind one of {@code LispLayout.Kinds}
	 * @return the stream value
	 */
	static LispVal streamValue(long handle, String kind) {
		return StreamDesignators.streamValue(handle, kind);
	}

	/**
	 * {@link #streamTarget(LispVal)} over a designator that may be ABSENT, which is how
	 * the print/read families spell an omitted stream argument.
	 * @param designator the stream designator as written, or null when omitted
	 * @return the resolved designator, null when it was absent
	 */
	@Nullable static LispVal streamTargetOrNull(@Nullable LispVal designator) {
		return designator == null ? null : streamTarget(designator);
	}

	/** How deep a chain of synonym streams may nest before it is called a cycle. */
	private static final int SYNONYM_DEPTH_LIMIT = 64;

	/**
	 * Fills code points {@code [start, end)} of a character buffer off a reader, block by
	 * block, and answers the position the fill stopped at.
	 *
	 * <p>
	 * Each block asks for exactly as many UTF-16 units as there are code points still
	 * wanted, which can never overshoot the buffer: a code point is one unit or two, so N
	 * units hold at most N of them. A surrogate pair SPLIT by the end of a block is
	 * completed by one more read behind a mark, exactly as {@code read-char} completes
	 * one -- a high half followed by anything else is its own character and the unit
	 * after it is put back.
	 * @param reader the character source
	 * @param buf the destination character buffer
	 * @param start the first code-point index to fill
	 * @param end the index after the last one
	 * @return the index of the first element not updated (end, or less at end of file)
	 * @throws IOException if the reader does
	 */
	private static int readCodePoints(Reader reader, LispString buf, int start, int end) throws IOException {
		char[] block = new char[Math.min(end - start, 8192)];
		int at = start;
		while (at < end) {
			int n = reader.read(block, 0, Math.min(block.length, end - at));
			if (n < 0) {
				break;
			}
			int k = 0;
			while (k < n && at < end) {
				char c = block[k++];
				if (!Character.isHighSurrogate(c)) {
					buf.setCharAt(at++, c);
					continue;
				}
				int low;
				if (k < n) {
					low = block[k];
					if (Character.isLowSurrogate((char) low)) {
						k++;
					}
				}
				else {
					reader.mark(1);
					low = reader.read();
					if (low >= 0 && !Character.isLowSurrogate((char) low)) {
						reader.reset();
					}
				}
				buf.setCharAt(at++,
						low >= 0 && Character.isLowSurrogate((char) low) ? Character.toCodePoint(c, (char) low) : c);
			}
		}
		return at;
	}

	private static void requireArgCount(String name, List<LispVal> args, int expected) {
		if (args.size() != expected) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					name + " expects " + expected + " arguments, got " + args.size());
		}
	}

	private static void requireMinArgCount(String name, List<LispVal> args, int min) {
		if (args.size() < min) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					name + " expects at least " + min + " arguments, got " + args.size());
		}
	}

	private static void requireArgCountBetween(String name, List<LispVal> args, int min, int max) {
		if (args.size() < min || args.size() > max) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					name + " expects " + min + " to " + max + " arguments, got " + args.size());
		}
	}

	/**
	 * Coerces {@code seq} into a {@link LispIntVector} of {@code %octets-to-string}'s
	 * octets, accepting any rank-1 array -- a packed byte vector already is one, and a
	 * general (boxed) array of integers is read element by element, matching the plain
	 * {@code length}/{@code aref} the Lisp source decodes through. Only a genuinely
	 * non-array argument signals.
	 * @param fn the operator name, for the type error's message
	 * @param seq the octets argument
	 * @return the octets as a packed {@code (unsigned-byte 8)} vector
	 */
	private static LispIntVector asOctetVector(String fn, LispVal seq) {
		if (seq instanceof LispIntVector iv) {
			return iv;
		}
		if (seq instanceof LispArray arr && arr.dimensions().length == 1) {
			int len = arr.effectiveLength();
			long[] data = new long[len];
			for (int i = 0; i < len; i++) {
				data[i] = exactIntElement(fn, arr.readFlat(i));
			}
			return new LispIntVector(8, data);
		}
		throw new LispEvalException(fn + " expects an (unsigned-byte 8) vector, got: " + seq.print());
	}

	/**
	 * The lenient UTF-8 decode of an {@code (unsigned-byte 8)} vector, arm for arm the
	 * prelude's {@code rontolisp::%octets-to-string}: a byte that leads no valid
	 * sequence, a sequence the vector truncates, and one that assembles a code point
	 * outside the Unicode range answer their own characters, so malformed input never
	 * signals. The malformed half -- {@link #decodeUtf8Strict} takes every well-formed
	 * input before this runs.
	 * @param v the octets
	 * @return the decoded string
	 */
	static String decodeUtf8Leniently(LispIntVector v) {
		int n = v.length();
		StringBuilder out = new StringBuilder(n);
		int i = 0;
		while (i < n) {
			int b = (int) v.elementAt(i);
			int b1 = i + 1 < n ? (int) v.elementAt(i + 1) : -1;
			int b2 = i + 2 < n ? (int) v.elementAt(i + 2) : -1;
			int b3 = i + 3 < n ? (int) v.elementAt(i + 3) : -1;
			if (b < 0x80) {
				out.append((char) b);
				i += 1;
			}
			else if (b >= 0xC0 && b < 0xE0 && b1 >= 0) {
				out.appendCodePoint(((b & 0x1F) << 6) | (b1 & 0x3F));
				i += 2;
			}
			else if (b >= 0xE0 && b < 0xF0 && b1 >= 0 && b2 >= 0) {
				out.appendCodePoint(((b & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F));
				i += 3;
			}
			else if (b >= 0xF0 && b < 0xF8 && b1 >= 0 && b2 >= 0 && b3 >= 0 && Character
				.isValidCodePoint(((b & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F))) {
				out.appendCodePoint(((b & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F));
				i += 4;
			}
			else {
				out.append((char) b);
				i += 1;
			}
		}
		return out.toString();
	}

	/**
	 * The STRICT UTF-8 decode of an {@code (unsigned-byte 8)} vector: the string its
	 * bytes spell when they are valid UTF-8, {@code null} when they are not. The fast
	 * half of {@code rontolisp::%octets-to-string} -- the platform decoder answers a
	 * well-formed body without the per-byte walk, and only bytes it refuses reach
	 * {@link #decodeUtf8Leniently}. A vector of any other element width answers
	 * {@code null} too: the fast path is a fast path, and the general loop stays the one
	 * that has to handle everything.
	 * @param v the octets
	 * @return the decoded string, or {@code null} when the bytes are not valid UTF-8
	 */
	@Nullable static String decodeUtf8Strict(LispIntVector v) {
		if (v.width() != 8) {
			return null;
		}
		long[] data = v.data();
		byte[] bytes = new byte[data.length];
		for (int i = 0; i < data.length; i++) {
			bytes[i] = (byte) data[i];
		}
		try {
			// REPORT is CharsetDecoder's default for both malformed input and
			// unmappable characters, so a byte sequence UTF-8 does not spell raises
			// rather than turning into U+FFFD -- which is the whole question being
			// asked here.
			return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException ex) {
			return null;
		}
	}

}
