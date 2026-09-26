package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.UncaughtReport;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * The location lines under a wasm-GC module's uncaught report
 * ({@code --report-locations}): where the condition happened, noted on its way OUT of
 * each user function, as the interpreter notes it ({@code eval/ConditionTrace}).
 *
 * <p>
 * <b>Why on the way out.</b> A module cannot inspect its own stack, and the entry's
 * landing pad runs after the unwind. So every user function whose code was read from a
 * file (a <em>frame</em>) wraps its body in {@code try_table (catch $lisp-cond)}, and a
 * condition passing through calls the note helper with what the frame knows -- its file,
 * its line, its name, and for an async body the hop it opens -- which rethrows the same
 * payload. A tail call leaves the try_table, so inside a frame only one into another
 * frame or through a function value stays a {@code return_call} ({@link #tailCallOp}).
 *
 * <p>
 * <b>The note is keyed by payload identity</b> (a module global holding the payload it
 * describes): a condition a {@code handler-case} caught leaves nothing a later one could
 * misreport, and a rethrow -- an unmatched clause, an {@code await} re-signalling a
 * rejected future -- keeps what the frames below it noted. The rules are the
 * interpreter's: the innermost frame with a known line gives the location, and its name
 * the function that code is written in (a lambda's frame is named after the function
 * around it, so no tail call can take the answer away); an async body the condition
 * escapes closes that and opens a hop, whose await site is the next frame with a known
 * line.
 *
 * <p>
 * <b>Texts ride with their frame.</b> A frame's name (and an async body's hop text) is a
 * string literal its catch builds, so the tree shaker drops it with the frame; only the
 * files are a table -- an i31 id into a quoted list the render reads, complete before the
 * entry body compiles (a scan of the program) and sealed there.
 *
 * <p>
 * <b>Granularity.</b> {@link WasmReportLocations#FUNCTION} passes each function's
 * definition line as a constant; {@link WasmReportLocations#LINE} keeps the line in a
 * local set on entry to every located form whose line differs from what it holds, and
 * restored after it -- a compile-time track of what the local holds, so a run of forms on
 * one line sets it once. A top-level frame's forms come from any file, so its file id
 * rides in a local too.
 */
final class WasmUncaughtLocations {

	/** File ids an i31 can index; the table stops growing past it. */
	private static final int MAX_FILES = 1 << 20;

	/**
	 * {@link #enterForm}'s answer when it emitted nothing, so leave has nothing to do.
	 */
	static final long UNCHANGED = Long.MIN_VALUE;

	/**
	 * {@link #enterForm}'s answer for the form a top-level frame noted under
	 * {@link WasmReportLocations#FUNCTION}: leaving it lets the next one be noted.
	 */
	private static final long OUTERMOST = Long.MIN_VALUE + 1;

	/** Note helper locals: the payload rides in the env slot. */
	private static final int NOTE_PAYLOAD = 0;

	private static final int NOTE_FILE = 1;

	private static final int NOTE_LINE = 2;

	private static final int NOTE_NAME = 3;

	private static final int NOTE_HOP = 4;

	private static final int NOTE_HOP_CELL = 5;

	private WasmUncaughtLocations() {
	}

	/**
	 * What a frame notes: its file id (0: the file rides in a local, a top level), the
	 * line its definition starts on, the function its code is written in ({@code null}:
	 * none), and -- for an async body -- the hop line's text ({@code null}: not an async
	 * body).
	 */
	record Spec(int fileId, int baseLine, @Nullable String name, @Nullable String hop) {

		static final Spec TOP_LEVEL = new Spec(0, 0, null, null);

		boolean topLevel() {
			return this.fileId == 0;
		}

		Spec asAsyncBody(String hopText) {
			return new Spec(this.fileId, this.baseLine, null, hopText);
		}

	}

	/** One compiled function's frame: its spec, its locals and what they hold here. */
	static final class Frame {

		final Spec spec;

		final int fileSlot;

		final int lineSlot;

		/** The line the line local holds at the current emission point; 0 = null. */
		int curLine;

		/** The file id the file local holds at the current emission point; 0 = null. */
		int curFile;

		/**
		 * The body form the function returns from: nothing runs after it, so leaving it
		 * restores nothing.
		 */
		@Nullable LispVal tail;

		/**
		 * Under {@link WasmReportLocations#FUNCTION}, whether a top-level frame is inside
		 * the located form it noted -- only the outermost one of each statement is.
		 */
		boolean inNoted;

		Frame(Spec spec, int fileSlot, int lineSlot) {
			this.spec = spec;
			this.fileSlot = fileSlot;
			this.lineSlot = lineSlot;
		}

	}

	/** The compilation-wide state, shared by every context of one module. */
	static final class Module {

		final WasmReportLocations granularity;

		private final List<String> files = new ArrayList<>(List.of(""));

		private final Map<String, Integer> fileIds = new HashMap<>();

		/** Each registered lambda's spec, by funcId, for Pass 2c. */
		final Map<Integer, Spec> lambdaSpecs = new HashMap<>();

		/**
		 * The function each registered lambda's code is written in, by funcId, for Pass
		 * 2c's {@code Ctx.ucWrittenIn}: what the lambdas IT builds are written in.
		 */
		final Map<Integer, String> lambdaWrittenIn = new HashMap<>();

		/**
		 * The lambdas nested {@code defun}s install, with their names
		 * ({@link #nestedDefun}).
		 */
		final Map<LispVal, String> nestedDefunNames = new IdentityHashMap<>();

		/** The defuns that are frames ({@link #tailCallOp}). */
		final Set<String> framedFunctions = new HashSet<>();

		/** Whether an async body can exist, so the note and the render carry hops. */
		final boolean hopsPossible;

		/** Set once the render has read the file table: nothing can join it after. */
		private boolean sealed;

		private int payloadGlobal = -1;

		private int fileGlobal = -1;

		private int lineGlobal = -1;

		private int nameGlobal = -1;

		private int hopsGlobal = -1;

		private int lastHopGlobal = -1;

		private int noteFuncIndex = -1;

		private int digitsFuncIndex = -1;

		Module(WasmReportLocations granularity, List<LispVal> program, boolean hopsPossible) {
			this.granularity = granularity;
			this.hopsPossible = hopsPossible;
			// Every file a located cons of the program came from: a rewrite during
			// Pass 2 inherits the position of a cons already here, so no later frame
			// can name a file this missed.
			Set<LispVal> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			Deque<LispVal> pending = new ArrayDeque<>(program);
			while (!pending.isEmpty()) {
				LispVal val = pending.pop();
				while (val instanceof LispCons cons && seen.add(cons)) {
					SourceLocation location = SourceProvenance.locate(cons);
					if (location != null && location.file() != null) {
						fileId(location.file());
					}
					pending.push(cons.car());
					val = cons.cdr();
				}
			}
		}

		/** Whether anything in the program was read from a file. */
		boolean anyFile() {
			return this.files.size() > 1;
		}

		/** The file's id, 0 when the table is sealed or full without it. */
		int fileId(String file) {
			Integer id = this.fileIds.get(file);
			if (id != null) {
				return id;
			}
			if (this.sealed || this.files.size() >= MAX_FILES) {
				return 0;
			}
			this.fileIds.put(file, this.files.size());
			this.files.add(file);
			return this.files.size() - 1;
		}

		/** The globals and the two helpers, on first need. */
		private void ensureRuntime(WasmLispCompiler.Ctx ctx) {
			if (this.noteFuncIndex >= 0) {
				return;
			}
			// Null-initialized (mut (ref null eq)) globals, appended after every
			// fixed-index global like the quoted-datum constants they are allocated
			// beside; each key is a fresh object no quote site can share.
			this.payloadGlobal = ctx.quoteGlobals.indexFor(new LispString("uncaught-payload"));
			this.fileGlobal = ctx.quoteGlobals.indexFor(new LispString("uncaught-file"));
			this.lineGlobal = ctx.quoteGlobals.indexFor(new LispString("uncaught-line"));
			this.nameGlobal = ctx.quoteGlobals.indexFor(new LispString("uncaught-name"));
			if (this.hopsPossible) {
				this.hopsGlobal = ctx.quoteGlobals.indexFor(new LispString("uncaught-hops"));
				this.lastHopGlobal = ctx.quoteGlobals.indexFor(new LispString("uncaught-last-hop"));
			}
			// Raw helpers in the lambda table, reached only by direct calls: the line
			// (resp. the payload) rides in the env slot.
			this.digitsFuncIndex = ctx.userFuncBase + ctx.numDefuns + ctx.lambdaDecls.size();
			ctx.lambdaDecls.add(new WasmLispCompiler.LambdaInfo(ctx.nextFuncId[0]++, "_uncaught_digits", List.of(),
					false, List.of(), List.of(), this.digitsFuncIndex, digitsBody()));
			this.noteFuncIndex = ctx.userFuncBase + ctx.numDefuns + ctx.lambdaDecls.size();
			ctx.lambdaDecls.add(new WasmLispCompiler.LambdaInfo(ctx.nextFuncId[0]++, "_uncaught_note",
					List.of("%file", "%line", "%name", "%hop"), false, List.of(), List.of(), this.noteFuncIndex,
					noteBody(ctx.usesIdentityHashTables)));
		}

		/**
		 * {@code _uncaught_digits}, {@code (line) -> string}: a positive i31 in decimal,
		 * assembled on the scratch heap and built with {@code _str_fresh} -- the one
		 * number the render prints, where the general value printer is kilobytes a module
		 * that never prints would otherwise pull in.
		 */
		private static byte[] digitsBody() {
			ByteArrayOutputStream out = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
			WasmWriter w = new WasmWriter(out);
			int number = 0;
			int heap = 1;
			int cursor = 2;
			int n = 3;
			w.write(1);
			w.writeUnsignedLeb128(3);
			w.write(Type.I32);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
			w.write(Instruction.I32_LOAD, 0x02, 0x00);
			set(w, heap);
			get(w, number);
			WasmEmitHelper.castI31GetS(w);
			set(w, n);
			// The digits framed in quotes, written back to front from heap + 11 (ten
			// digits at most); HEAP_PTR is not advanced -- _str_fresh copies the bytes.
			get(w, heap);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(11);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.TEE_LOCAL);
			w.writeUnsignedLeb128(cursor);
			storeByte(w, '"');
			w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY);
			stepBack(w, cursor);
			get(w, n);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(10);
			w.write(Instruction.I32_REM_U);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128('0');
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_STORE8, 0x00, 0x00);
			get(w, n);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(10);
			w.write(Instruction.I32_DIV_U);
			w.write(Instruction.TEE_LOCAL);
			w.writeUnsignedLeb128(n);
			w.write(Instruction.BR_IF, 0);
			w.write(Instruction.END);
			stepBack(w, cursor);
			storeByte(w, '"');
			get(w, cursor);
			get(w, heap);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(12);
			w.write(Instruction.I32_ADD);
			get(w, cursor);
			w.write(Instruction.I32_SUB);
			WasmEmitHelper.emitStrFreshCall(w);
			w.write(Instruction.END);
			return out.toByteArray();
		}

		/** {@code cursor -= 1}, leaving the new cursor on the stack. */
		private static void stepBack(WasmWriter w, int cursor) {
			get(w, cursor);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_SUB);
			w.write(Instruction.TEE_LOCAL);
			w.writeUnsignedLeb128(cursor);
		}

		/** Stores {@code value} at the address on the stack. */
		private static void storeByte(WasmWriter w, char value) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(value);
			w.write(Instruction.I32_STORE8, 0x00, 0x00);
		}

		/**
		 * The note helper, {@code (payload file line name hop) -> never}: records what
		 * one frame knows about the payload, then rethrows it.
		 */
		private byte[] noteBody(boolean identityHash) {
			ByteArrayOutputStream out = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
			WasmWriter w = new WasmWriter(out);
			// one extra (ref null eq) local: a new hop cell
			w.write(1);
			w.writeUnsignedLeb128(1);
			w.writeRefType(true, Type.EQ.code());
			// A payload not seen before starts a fresh note.
			get(w, NOTE_PAYLOAD);
			getGlobal(w, this.payloadGlobal);
			w.write(Instruction.REF_EQ);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			get(w, NOTE_PAYLOAD);
			setGlobal(w, this.payloadGlobal);
			clearGlobal(w, this.fileGlobal);
			clearGlobal(w, this.nameGlobal);
			if (this.hopsPossible) {
				clearGlobal(w, this.hopsGlobal);
				clearGlobal(w, this.lastHopGlobal);
			}
			w.write(Instruction.END);
			if (this.hopsPossible) {
				getGlobal(w, this.hopsGlobal);
				w.write(Instruction.REF_IS_NULL);
				w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			}
			// Segment 0: the first frame with a line is the location, and its name the
			// function the code is written in.
			getGlobal(w, this.fileGlobal);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ifKnown(w, NOTE_LINE);
			get(w, NOTE_FILE);
			setGlobal(w, this.fileGlobal);
			get(w, NOTE_LINE);
			setGlobal(w, this.lineGlobal);
			get(w, NOTE_NAME);
			setGlobal(w, this.nameGlobal);
			w.write(Instruction.END);
			w.write(Instruction.END);
			if (this.hopsPossible) {
				w.write(Instruction.ELSE);
				fillAwaitSite(w, identityHash);
				w.write(Instruction.END);
				openHop(w, identityHash);
			}
			get(w, NOTE_PAYLOAD);
			w.write(Instruction.THROW);
			w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
			w.write(Instruction.END);
			return out.toByteArray();
		}

		/**
		 * Past an async boundary: the first frame with a line is the await site of the
		 * innermost hop, {@code (file-id . "line")}.
		 */
		private void fillAwaitSite(WasmWriter w, boolean identityHash) {
			ifKnown(w, NOTE_LINE);
			getGlobal(w, this.lastHopGlobal);
			consField(w, 0);
			consField(w, 1);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			getGlobal(w, this.lastHopGlobal);
			consField(w, 0);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			get(w, NOTE_FILE);
			get(w, NOTE_LINE);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(this.digitsFuncIndex);
			WasmEmitHelper.emitNewCons(w, identityHash);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.END);
			w.write(Instruction.END);
		}

		/**
		 * An async body the condition leaves opens a hop, {@code (text . await-site)},
		 * appended so the list reads innermost first.
		 */
		private void openHop(WasmWriter w, boolean identityHash) {
			ifKnown(w, NOTE_HOP);
			get(w, NOTE_HOP);
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
			WasmEmitHelper.emitNewCons(w, identityHash);
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
			WasmEmitHelper.emitNewCons(w, identityHash);
			set(w, NOTE_HOP_CELL);
			getGlobal(w, this.lastHopGlobal);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			get(w, NOTE_HOP_CELL);
			setGlobal(w, this.hopsGlobal);
			w.write(Instruction.ELSE);
			getGlobal(w, this.lastHopGlobal);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			get(w, NOTE_HOP_CELL);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.END);
			get(w, NOTE_HOP_CELL);
			setGlobal(w, this.lastHopGlobal);
			w.write(Instruction.END);
		}

		/** Opens {@code if} over "the local holds a value"; the caller closes it. */
		private static void ifKnown(WasmWriter w, int local) {
			get(w, local);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		}

		private static void consField(WasmWriter w, int field) {
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			w.writeUnsignedLeb128(field);
		}

		private static void set(WasmWriter w, int local) {
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(local);
		}

		private static void clearGlobal(WasmWriter w, int global) {
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
			setGlobal(w, global);
		}

	}

	/**
	 * The spec of a function frame, or {@code null} when no code of its OWN was read from
	 * a file -- library source, a macro's output, or a wrapper whose only located code is
	 * a lambda it builds (a Preview 1 async function's {@code (%async-run (lambda ...))},
	 * which the interpreter never has on the awaiter's stack). Such a function is not a
	 * frame: a condition passing through it is noted by the frames around it, as the
	 * interpreter's frames without a located form note nothing. The file is that of the
	 * body's located code; the definition line the defining form's when it was read from
	 * the same file, else the body's first located line. The name is the one the program
	 * spelled ({@link UncaughtReport#functionName}: a method body reports its generic).
	 * @param module the module state, or {@code null} when the option is off
	 * @param name the name the function its code is written in was defined under -- its
	 * own, or for a lambda the one around it -- or {@code null} for none
	 * @param form the defining form, or {@code null}
	 * @param body the body forms
	 * @return the spec, or {@code null}
	 */
	static @Nullable Spec functionSpec(@Nullable Module module, @Nullable String name, @Nullable LispVal form,
			List<LispVal> body) {
		if (module == null) {
			return null;
		}
		SourceLocation own = null;
		for (int i = 0; own == null && i < body.size(); i++) {
			own = firstOwnFileLocation(body.get(i));
		}
		if (own == null) {
			return null;
		}
		String file = Objects.requireNonNull(own.file());
		int fileId = module.fileId(file);
		if (fileId == 0) {
			return null;
		}
		SourceLocation defined = fileLocation(form);
		int baseLine = defined != null && file.equals(defined.file()) ? defined.line() : own.line();
		return new Spec(fileId, baseLine, name == null ? null : UncaughtReport.functionName(name), null);
	}

	/**
	 * The spec of an async body compiled as its own function (a {@code --component}
	 * resume): anonymous -- the interpreter runs the body without its defun's frame --
	 * and opening a hop named after the async function when a condition leaves it.
	 * @param module the module state, or {@code null} when the option is off
	 * @param name the async function's name, or {@code null} for an async lambda
	 * @param form the defining form, or {@code null}
	 * @param body the body forms
	 * @return the spec, or {@code null}
	 */
	static @Nullable Spec asyncBodySpec(@Nullable Module module, @Nullable String name, @Nullable LispVal form,
			List<LispVal> body) {
		Spec spec = functionSpec(module, null, form, body);
		return spec == null ? null : spec.asAsyncBody(hopText(name));
	}

	/**
	 * The spec of a top-level frame, whose forms may come from any file: {@code null}
	 * when nothing in the program was read from one.
	 * @param module the module state, or {@code null} when the option is off
	 * @return the spec, or {@code null}
	 */
	static @Nullable Spec topLevelSpec(@Nullable Module module) {
		return module != null && module.anyFile() ? Spec.TOP_LEVEL : null;
	}

	/**
	 * What a hop line says between {@code in } and the await site: the async function the
	 * body belongs to -- the interpreter names the function whose body calls
	 * {@code %async-run} -- or that it was an async lambda.
	 * @param asyncFunction the async function's name, or {@code null} in a lambda
	 * @return the text
	 */
	static String hopText(@Nullable String asyncFunction) {
		String line = UncaughtReport
			.asyncLine(asyncFunction == null ? null : UncaughtReport.functionName(asyncFunction), null, 0);
		return line.substring(line.indexOf("in ") + "in ".length());
	}

	/**
	 * Records the spec of a lambda Pass 2c will compile ({@link #functionSpec}), named
	 * after the function its code is written in: the one around it -- the interpreter's
	 * rule, which names where a form is WRITTEN, whatever frames called it -- a nested
	 * {@code defun}'s own name, or none in an async body ({@code %async-run}'s thunk,
	 * marked by {@code Ctx.ucPendingHop} and consumed here).
	 * @param funcId the lambda's funcId
	 * @param form the lambda form
	 * @param body its body forms
	 * @param ctx the enclosing context
	 */
	static void registerLambda(int funcId, LispVal form, List<LispVal> body, WasmLispCompiler.Ctx ctx) {
		String hop = ctx.ucPendingHop;
		ctx.ucPendingHop = null;
		Module module = ctx.uncaughtLocations;
		if (module == null || ctx.injectedRuntimeBody) {
			return;
		}
		String nested = module.nestedDefunNames.remove(form);
		String writtenIn = hop != null ? null : nested != null ? nested : ctx.ucWrittenIn;
		if (writtenIn != null) {
			module.lambdaWrittenIn.put(funcId, writtenIn);
		}
		Spec spec = functionSpec(module, writtenIn, form, body);
		if (spec != null) {
			module.lambdaSpecs.put(funcId, hop != null ? spec.asAsyncBody(hop) : spec);
		}
	}

	/**
	 * Names the lambda a nested {@code defun}'s lowering installs, as the interpreter's
	 * nested defun is a named function ({@link UncaughtReport#nestedDefun}); a no-op
	 * without the option.
	 * @param lowered the {@code (setq name (lambda ...))} the defun lowered to
	 * @param ctx the context compiling it
	 */
	static void nestedDefun(LispVal lowered, WasmLispCompiler.Ctx ctx) {
		Module module = ctx.uncaughtLocations;
		UncaughtReport.NestedDefun nested = module == null ? null : UncaughtReport.nestedDefun(lowered);
		if (module != null && nested != null) {
			module.nestedDefunNames.put(nested.lambda(), nested.name());
		}
	}

	/**
	 * Opens a frame over the function body about to compile into {@code ctx}; a no-op
	 * without a spec. Pair with {@link #close}.
	 * @param ctx the function's context
	 * @param spec the frame's spec, or {@code null}
	 */
	static void open(WasmLispCompiler.Ctx ctx, @Nullable Spec spec) {
		Module module = ctx.uncaughtLocations;
		ctx.ucFrame = null;
		if (module == null || spec == null) {
			return;
		}
		module.ensureRuntime(ctx);
		int fileSlot = spec.topLevel() ? ctx.allocTemp() : -1;
		int lineSlot = spec.topLevel() || module.granularity == WasmReportLocations.LINE ? ctx.allocTemp() : -1;
		WasmWriter w = ctx.writer;
		w.write(Instruction.BLOCK);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.TRY_TABLE, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.CATCH);
		w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		w.writeUnsignedLeb128(0);
		ctx.wasmCtrlDepth += 2;
		// The line local starts null: until a located form runs, the frame knows no line
		// and leaves the location to the frames around it, as the interpreter's does.
		ctx.ucFrame = new Frame(spec, fileSlot, lineSlot);
	}

	/**
	 * Closes the frame {@link #open} opened: the body's value leaves through a
	 * {@code return}, and the catch's payload goes to the note helper, which rethrows it.
	 * The caller still writes the function's final {@code end}.
	 * @param ctx the function's context
	 */
	static void close(WasmLispCompiler.Ctx ctx) {
		Frame frame = ctx.ucFrame;
		Module module = ctx.uncaughtLocations;
		if (frame == null || module == null) {
			return;
		}
		ctx.ucFrame = null;
		WasmWriter w = ctx.writer;
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // try_table
		ctx.wasmCtrlDepth--;
		// Only the catch reaches the block's end, with the payload (see
		// WasmUncaughtReportCompiler.emitEpilogue for the same shape).
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		ctx.wasmCtrlDepth--;
		if (frame.fileSlot >= 0) {
			get(w, frame.fileSlot);
		}
		else {
			i31(w, frame.spec.fileId());
		}
		if (frame.lineSlot >= 0) {
			get(w, frame.lineSlot);
		}
		else {
			i31(w, frame.spec.baseLine());
		}
		text(ctx, frame.spec.name());
		text(ctx, frame.spec.hop());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(module.noteFuncIndex);
		w.write(Instruction.UNREACHABLE);
	}

	/**
	 * A string the frame hands the note, or nil. Built without recording its spelling: a
	 * function's name as a literal would otherwise read as a designator the program
	 * spells and keep the function dispatchable
	 * ({@link WasmEmitHelper#compileUnspelledLiteral}).
	 */
	private static void text(WasmLispCompiler.Ctx ctx, @Nullable String text) {
		if (text == null) {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		else {
			WasmEmitHelper.compileUnspelledLiteral(new LispString(text).literal(), ctx);
		}
	}

	/**
	 * The opcode of a call to the defun {@code callee} in tail position: a
	 * {@code return_call} leaves the frame's try_table, which is only right when the
	 * callee is a frame itself -- it notes the condition as the interpreter's trampolined
	 * frame would, from the callee's forms -- or when the callee is unknown (a call
	 * through a function value, whose constant stack a Scheme loop depends on,
	 * {@code .kb/wasm-tail-calls.md}). A tail call into anything else -- a library
	 * function, a function a macro wrote -- is a plain {@code call} inside a frame, so
	 * the call site and the function holding it are still noted.
	 * @param ctx the calling function's context
	 * @param tail whether the call is in tail position
	 * @param callee the defun called directly, or {@code null} for a call through a value
	 * @return {@code return_call} or {@code call}
	 */
	static int tailCallOp(WasmLispCompiler.Ctx ctx, boolean tail, @Nullable String callee) {
		if (!tail) {
			return Instruction.CALL;
		}
		Module module = ctx.uncaughtLocations;
		if (ctx.ucFrame == null || module == null || callee == null || module.framedFunctions.contains(callee)) {
			return Instruction.RETURN_CALL;
		}
		return Instruction.CALL;
	}

	/**
	 * Sets the frame's locals to a located form's position before it compiles, when they
	 * hold something else here: every such form under {@link WasmReportLocations#LINE};
	 * under {@link WasmReportLocations#FUNCTION} only a top-level frame's outermost ones
	 * -- each top-level form is its own definition there, while a function's line is its
	 * definition's constant.
	 * @param form the form about to compile
	 * @param ctx the context
	 * @return what {@link #leaveForm} restores, or {@link #UNCHANGED}
	 */
	static long enterForm(LispCons form, WasmLispCompiler.Ctx ctx) {
		Frame frame = ctx.ucFrame;
		if (frame == null) {
			return UNCHANGED;
		}
		Module module = Objects.requireNonNull(ctx.uncaughtLocations);
		boolean perLine = module.granularity == WasmReportLocations.LINE;
		if (!perLine && (frame.fileSlot < 0 || frame.inNoted)) {
			return UNCHANGED;
		}
		SourceLocation location = fileLocation(form);
		if (location == null) {
			return UNCHANGED;
		}
		int file = module.fileId(Objects.requireNonNull(location.file()));
		// A function's lines are its own file's: a form spliced in from elsewhere (a
		// macro template another file defined) keeps the line the local has.
		if (file == 0 || (frame.fileSlot < 0 && file != frame.spec.fileId())) {
			return UNCHANGED;
		}
		int line = location.line();
		if (!perLine) {
			track(ctx.writer, frame, file, line);
			frame.inNoted = true;
			return OUTERMOST;
		}
		if (line == frame.curLine && (frame.fileSlot < 0 || file == frame.curFile)) {
			return UNCHANGED;
		}
		long token = ((long) frame.curFile << 32) | (frame.curLine & 0xFFFFFFFFL);
		track(ctx.writer, frame, file, line);
		return token;
	}

	/**
	 * Marks the body form the function returns from ({@link Frame#tail}).
	 * @param ctx the function's context
	 * @param form its last body form
	 */
	static void tailForm(WasmLispCompiler.Ctx ctx, LispVal form) {
		Frame frame = ctx.ucFrame;
		if (frame != null) {
			frame.tail = form;
		}
	}

	/**
	 * Restores what {@link #enterForm} changed, so the enclosing form's own work after
	 * this one reports the enclosing form's line.
	 * @param token {@link #enterForm}'s answer
	 * @param form the form {@link #enterForm} was given
	 * @param ctx the context
	 */
	static void leaveForm(long token, LispCons form, WasmLispCompiler.Ctx ctx) {
		Frame frame = ctx.ucFrame;
		if (token == UNCHANGED || frame == null) {
			return;
		}
		if (token == OUTERMOST) {
			frame.inNoted = false;
			return;
		}
		if (form != frame.tail) {
			track(ctx.writer, frame, (int) (token >>> 32), (int) token);
		}
	}

	/** Sets whichever of the frame's locals differ from {@code file} / {@code line}. */
	private static void track(WasmWriter w, Frame frame, int file, int line) {
		if (frame.fileSlot >= 0 && file != frame.curFile) {
			setI31(w, frame.fileSlot, file);
			frame.curFile = file;
		}
		if (line != frame.curLine) {
			setI31(w, frame.lineSlot, line);
			frame.curLine = line;
		}
	}

	/**
	 * Writes the location lines under the report the entry's landing pad just wrote, from
	 * what the frames noted for {@code payloadSlot}'s payload; nothing when this module
	 * has no frame. Seals the file table.
	 *
	 * <p>
	 * The note is read here, in raw instructions, into pseudo-locals the render reads as
	 * Lisp -- the line already text through {@code _uncaught_digits} -- so the render
	 * needs nothing heavier than {@code nth} over the quoted file list and
	 * {@code %string-concat}.
	 * @param ctx the entry function's context
	 * @param payloadSlot the local holding the caught payload
	 */
	static void emitReportLines(WasmLispCompiler.Ctx ctx, int payloadSlot) {
		Module module = ctx.uncaughtLocations;
		if (module == null || !module.anyFile()) {
			return;
		}
		module.ensureRuntime(ctx);
		module.sealed = true;
		WasmWriter w = ctx.writer;
		int fileSlot = ctx.allocTemp();
		int lineSlot = ctx.allocTemp();
		int nameSlot = ctx.allocTemp();
		int hopsSlot = module.hopsPossible ? ctx.allocTemp() : -1;
		// Read the note only when it describes THIS payload.
		get(w, payloadSlot);
		getGlobal(w, module.payloadGlobal);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		getGlobal(w, module.fileGlobal);
		setLocal(w, fileSlot);
		getGlobal(w, module.lineGlobal);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(module.digitsFuncIndex);
		setLocal(w, lineSlot);
		getGlobal(w, module.nameGlobal);
		setLocal(w, nameSlot);
		if (hopsSlot >= 0) {
			getGlobal(w, module.hopsGlobal);
			setLocal(w, hopsSlot);
		}
		w.write(Instruction.END);
		String fileVar = "__uc_file$" + fileSlot;
		String lineVar = "__uc_line$" + lineSlot;
		String nameVar = "__uc_name$" + nameSlot;
		String hopsVar = "__uc_hops$" + hopsSlot;
		ctx.locals.put(fileVar, fileSlot);
		ctx.locals.put(lineVar, lineSlot);
		ctx.locals.put(nameVar, nameSlot);
		if (hopsSlot >= 0) {
			ctx.locals.put(hopsVar, hopsSlot);
		}
		try {
			LispVal files = quoted(module.files);
			// (if file (%warn " at FILE:LINE[ in NAME]"))
			LispVal in = list(sym(LispNames.IF), sym(nameVar), concat(new LispString(" in "), sym(nameVar)),
					new LispString(""));
			LispVal at = list(sym(LispNames.IF), sym(fileVar),
					warn(concat(new LispString("  at "),
							concat(nth(sym(fileVar), files), concat(new LispString(":"), concat(sym(lineVar), in))))),
					LispNil.INSTANCE);
			WasmExprCompiler.compileExpr(at, ctx);
			w.write(Instruction.DROP);
			if (hopsSlot >= 0) {
				WasmExprCompiler.compileExpr(hopLines(sym(hopsVar), files), ctx);
				w.write(Instruction.DROP);
			}
		}
		finally {
			ctx.locals.remove(fileVar);
			ctx.locals.remove(lineVar);
			ctx.locals.remove(nameVar);
			ctx.locals.remove(hopsVar);
		}
	}

	/**
	 * {@code (dolist (h hops) (%warn "  in TEXT[, awaited at FILE:LINE]"))}: each hop is
	 * {@code (text . (file-id . "line"))}, innermost first, as the interpreter prints
	 * them.
	 */
	private static LispVal hopLines(LispSymbol hops, LispVal files) {
		LispSymbol hop = sym("__uc_hop");
		LispSymbol site = sym("__uc_site");
		LispVal awaited = list(sym(LispNames.IF), site,
				concat(new LispString(", awaited at "), concat(nth(list(sym(LispNames.CAR), site), files),
						concat(new LispString(":"), list(sym(LispNames.CDR), site)))),
				new LispString(""));
		LispVal body = list(sym(LispNames.LET), list(list(site, list(sym(LispNames.CDR), hop))),
				warn(concat(new LispString("  in "), concat(list(sym(LispNames.CAR), hop), awaited))));
		return list(sym(LispNames.DOLIST), list(hop, hops), body, LispNil.INSTANCE);
	}

	private static LispVal nth(LispVal index, LispVal list) {
		return list(sym(LispNames.NTH), index, list);
	}

	private static LispVal warn(LispVal text) {
		return list(sym(LispNames.WARN_INTERNAL), text);
	}

	private static LispVal concat(LispVal a, LispVal b) {
		return list(sym(LispNames.STRING_CONCAT), a, b);
	}

	private static LispVal quoted(List<String> texts) {
		List<LispVal> items = new ArrayList<>(texts.size());
		for (String text : texts) {
			items.add(new LispString(text));
		}
		return list(sym(LispNames.QUOTE), list(items.toArray(new LispVal[0])));
	}

	/** The form's location when it was read from a named file. */
	private static @Nullable SourceLocation fileLocation(@Nullable LispVal form) {
		SourceLocation location = SourceProvenance.locate(form);
		return location == null || location.file() == null ? null : location;
	}

	/**
	 * The first cons under {@code form}, depth first, read from a named file -- outside
	 * the lambdas it builds, whose code is their own frames'.
	 */
	private static @Nullable SourceLocation firstOwnFileLocation(LispVal form) {
		Set<LispVal> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<LispVal> pending = new ArrayDeque<>();
		pending.push(form);
		while (!pending.isEmpty()) {
			LispVal val = pending.pop();
			if (!(val instanceof LispCons cons) || !seen.add(cons)
					|| cons.car() instanceof LispSymbol head && LispNames.LAMBDA.equals(head.name())) {
				continue;
			}
			SourceLocation location = fileLocation(cons);
			if (location != null) {
				return location;
			}
			pending.push(cons.cdr());
			pending.push(cons.car());
		}
		return null;
	}

	private static void get(WasmWriter w, int local) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void setLocal(WasmWriter w, int local) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void getGlobal(WasmWriter w, int global) {
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(global);
	}

	private static void setGlobal(WasmWriter w, int global) {
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(global);
	}

	private static void i31(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	/** {@code local = value}, where 0 is null: "not known here". */
	private static void setI31(WasmWriter w, int slot, int value) {
		if (value == 0) {
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
		}
		else {
			i31(w, value);
		}
		setLocal(w, slot);
	}

	private static LispSymbol sym(String name) {
		return new LispSymbol(name);
	}

	private static LispVal list(LispVal... items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			result = new LispCons(items[i], result);
		}
		return result;
	}

}
