package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The WASM targets' {@code %local-time-offset}: the local time zone behind
 * {@code decode-universal-time}, {@code encode-universal-time} and
 * {@code get-decoded-time} when no time-zone is given. The interpreter and the JVM ask
 * {@code java.time.ZoneId.systemDefault()}; WASI has no time zone interface, so a module
 * gets one of three definitions:
 * <ul>
 * <li>Preview 1 and {@code --component}: {@code localtime.lisp}, which reads the zone as
 * the C library does -- {@code TZ}, the TZif files under {@code /usr/share/zoneinfo} (or
 * {@code TZDIR}), {@code /etc/localtime}, a POSIX rule string -- through the environment
 * and the directories the host grants, and answers UTC when it can read none.</li>
 * <li>{@code --native}: an import of the runner's {@code rltime.local_offset}, which asks
 * the host C library ({@code localtime_r}). The runner preopens {@code /}, but a
 * {@code /etc/localtime} that is a symbolic link to an absolute path -- macOS's and most
 * Linux distributions' -- cannot be followed inside the sandbox, so the module cannot
 * read the zone itself there.</li>
 * <li>{@code --no-wasi}: UTC. The module owns no environment and no file system; the host
 * writes its clock (.kb/wasm-export-no-wasi.md).</li>
 * </ul>
 *
 * <p>
 * The trigger is the SURFACE names (any of the three functions, or the primitive itself);
 * where every mention is a call passing a literal zone, the UTC arm stands in, since
 * nothing can ask for the local zone. The splice runs before
 * {@link LispPreludeLibrary#process}, which then supplies the codec, {@code %local-zone}
 * and whatever the reader is written over; a definition nothing reaches is pruned
 * ({@link LibraryDefunPruner}).
 */
public final class LocalTimeLibrary {

	/** The runner's host module and function ({@code rontolisp-native/runner}). */
	public static final String RUNNER_IMPORT_MODULE = "rltime";

	/** The runner's host function: {@code (i64 unix-seconds) -> i64 encoded offset}. */
	public static final String RUNNER_IMPORT_FIELD = "local_offset";

	private static final Set<String> TRIGGERS = Set.of(LispNames.DECODE_UNIVERSAL_TIME, LispNames.ENCODE_UNIVERSAL_TIME,
			LispNames.GET_DECODED_TIME, LispNames.LOCAL_TIME_OFFSET, LispNames.LOCAL_ZONE_INTERNAL);

	private static final String RUNNER_SOURCE = "(rontolisp:wasm-import '%local-time-offset :from \""
			+ RUNNER_IMPORT_MODULE + "\" :as \"" + RUNNER_IMPORT_FIELD + "\" :params '(:s64) :returns :s64)\n";

	private static final String UTC_SOURCE = "(defun %local-time-offset (unix) (declare (ignore unix)) 0)\n";

	private static volatile @Nullable List<LispVal> forms;

	private static volatile @Nullable List<LispVal> runnerForms;

	private static volatile @Nullable List<LispVal> utcForms;

	private LocalTimeLibrary() {
	}

	/**
	 * The compile-path splice: the definition of {@code %local-time-offset} the target
	 * needs, in front of a WASM program that names one of the triggers; any other
	 * program, and every program compiled for the JVM, as it is. A program whose every
	 * call passes the zone as a literal number never asks for the local zone, so it gets
	 * the UTC arm and imports nothing for it: a server formatting HTTP dates
	 * ({@code (decode-universal-time time 0)}) keeps the imports it had.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @param noWasi whether the module owns no WASI world ({@code --no-wasi})
	 * @param runnerHosted whether the module runs under the {@code --native} runner
	 * @return the program, spliced when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend, boolean noWasi,
			boolean runnerHosted) {
		if ((backend != WitExportDirective.Backend.WASM_GC && backend != WitExportDirective.Backend.WASM_COMPONENT)
				|| defines(program)) {
			return program;
		}
		Use use = new Use();
		for (LispVal form : program) {
			scan(form, use);
		}
		if (!use.named) {
			return program;
		}
		List<LispVal> arm = (!use.local || noWasi) ? utcForms() : runnerHosted ? runnerForms() : forms();
		List<LispVal> out = new ArrayList<>(arm);
		out.addAll(program);
		return out;
	}

	/**
	 * The reader's forms ({@code localtime.lisp}), parsed once.
	 * @return the definitions of {@code localtime.lisp}
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (LocalTimeLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = read(readSource());
					forms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * The {@code --native} arm: the runner import, parsed once.
	 * @return the {@code rontolisp:wasm-import} directive
	 */
	static List<LispVal> runnerForms() {
		List<LispVal> cached = runnerForms;
		if (cached == null) {
			cached = read(RUNNER_SOURCE);
			runnerForms = cached;
		}
		return cached;
	}

	/**
	 * The {@code --no-wasi} arm: UTC, parsed once.
	 * @return the constant definition
	 */
	static List<LispVal> utcForms() {
		List<LispVal> cached = utcForms;
		if (cached == null) {
			cached = read(UTC_SOURCE);
			utcForms = cached;
		}
		return cached;
	}

	private static List<LispVal> read(String source) {
		return List.copyOf(LispReader.readAllFromString(source, Features.INTERPRETER));
	}

	/**
	 * What the scan found: a trigger named at all, and one that can ask for the local
	 * zone.
	 */
	private static final class Use {

		boolean named;

		boolean local;

	}

	// A call of decode-universal-time / encode-universal-time whose zone argument is a
	// literal number cannot reach the local zone; any other mention of a trigger can.
	private static void scan(LispVal form, Use use) {
		LispVal rest = form;
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && isTrigger(head.name())
				&& literalZone(member(head.name()), cons)) {
			use.named = true;
			rest = cons.cdr();
		}
		while (rest instanceof LispCons cons) {
			scan(cons.car(), use);
			rest = cons.cdr();
		}
		if (rest instanceof LispSymbol symbol && isTrigger(symbol.name())) {
			use.named = true;
			use.local = true;
		}
	}

	private static boolean literalZone(String operator, LispCons call) {
		int zoneAt = switch (operator) {
			case LispNames.DECODE_UNIVERSAL_TIME -> 2;
			case LispNames.ENCODE_UNIVERSAL_TIME -> 7;
			default -> -1;
		};
		if (zoneAt < 0 || !call.isProperList()) {
			return false;
		}
		List<LispVal> parts = call.toList();
		return parts.size() == zoneAt + 1 && (parts.get(zoneAt) instanceof LispInteger
				|| parts.get(zoneAt) instanceof LispRatio || parts.get(zoneAt) instanceof LispDouble);
	}

	private static String member(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn == null ? symbolName : qn.member();
	}

	// Whether the symbol names a trigger, bare or cl-qualified: the scan runs before
	// PackageResolver, so it normalizes the qualifier itself.
	private static boolean isTrigger(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		if (qn == null) {
			return TRIGGERS.contains(symbolName);
		}
		return LispNames.CL_PKG.equals(qn.pkg()) && TRIGGERS.contains(qn.member());
	}

	// Whether the program already provides %local-time-offset (a program that spliced
	// it already, or one that defines its own), which the splice must not collide with.
	private static boolean defines(List<LispVal> program) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& cons.cdr() instanceof LispCons rest) {
				LispVal name = rest.car();
				if (name instanceof LispCons quoted && quoted.cdr() instanceof LispCons inner) {
					name = inner.car();
				}
				if ((LispNames.DEFUN.equals(head.name()) || head.name().endsWith(LispNames.WASM_IMPORT))
						&& name instanceof LispSymbol sym && LispNames.LOCAL_TIME_OFFSET.equals(sym.name())) {
					return true;
				}
			}
		}
		return false;
	}

	private static String readSource() {
		try (InputStream in = LocalTimeLibrary.class.getResourceAsStream("localtime.lisp")) {
			if (in == null) {
				throw new IllegalStateException("localtime.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
