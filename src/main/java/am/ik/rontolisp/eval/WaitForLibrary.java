package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code --component} implementation of {@code rontolisp:wait-for}: a Lisp-source
 * shim ({@code wait.lisp}) over a wit-imported {@code wasi:clocks/monotonic-clock@0.3.0}
 * surface ({@code clocks.wit}), both on the classpath. The interpreter and the JVM keep
 * their {@code CompletableFuture.completeOnTimeout} timer; Preview 1 has no host timer
 * and keeps the compile error.
 *
 * <p>
 * The host's {@code wait-for} is an {@code async func}, so its binding returns a
 * first-class PENDING future ({@code rontolisp::%subtask-future} over the async-lowered
 * call) that the scheduler settles when the timer subtask returns -- concurrent timers
 * genuinely overlap, matching the interpreter/JVM contract. The interface is part of
 * every GC component variant's fixed WASI surface, so {@code WasmComponentBuilder} binds
 * it FROM the import block's already-declared instance rather than as a user import (a
 * second import of the same interface name would be invalid).
 *
 * <p>
 * Like {@link HttpLibrary}, <strong>it lowers {@code wait.lisp}'s own
 * {@code rontolisp:wit-import} directive ITSELF</strong> (calling
 * {@link WitImportDirective#lower}): {@code eval/WitImportInliner} has already run by the
 * time a library is spliced.
 */
public final class WaitForLibrary {

	private static volatile @Nullable List<LispVal> forms;

	private static volatile @Nullable String witText;

	private WaitForLibrary() {
	}

	/**
	 * The compile-path splice: when a {@code --component} program references
	 * {@code rontolisp:wait-for}, splice {@code wait.lisp} (its {@code wit-import}
	 * directive already lowered). A no-op on every other backend and when the program
	 * does not reference {@code rontolisp:wait-for}.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @return the program, spliced when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend) {
		if (backend != WitExportDirective.Backend.WASM_COMPONENT) {
			return program;
		}
		boolean referenced = false;
		for (LispVal form : program) {
			if (references(form, LispNames.WAIT_FOR)) {
				referenced = true;
				break;
			}
		}
		if (!referenced || definesWaitFor(program)) {
			return program;
		}
		List<LispVal> waitForms = forms();
		// The WIT member filter: every name a wait.lisp defun mentions (one binding,
		// wait-for -- but derived like HttpLibrary's, not hardcoded).
		Set<String> members = new HashSet<>();
		for (LispVal form : waitForms) {
			if (!WitImportDirective.isDirective(form)) {
				collectNames(form, members);
			}
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : waitForms) {
			if (WitImportDirective.isDirective(form)) {
				WitImportDirective.Directive directive = WitImportDirective.parse((LispCons) form);
				out.addAll(WitImportDirective.lower(directive, witText(), directive.path(),
						WitExportDirective.Backend.WASM_COMPONENT, members, members));
				continue;
			}
			out.add(form);
		}
		out.addAll(program);
		return out;
	}

	private static boolean references(LispVal form, String member) {
		return switch (form) {
			case LispSymbol sym -> namesRontolispMember(sym.name(), member);
			case LispCons cons -> references(cons.car(), member) || references(cons.cdr(), member);
			default -> false;
		};
	}

	// Whether the symbol names the given rontolisp-package member, in any source
	// spelling (rontolisp:wait-for, rl:wait-for, rontolisp::wait-for): this scan runs
	// before PackageResolver normalizes the program, so it must normalize itself --
	// splitQualified resolves the built-in nicknames.
	private static boolean namesRontolispMember(String symbolName, String member) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && member.equals(qn.member());
	}

	// A defun head in any source spelling: bare defun, cl:defun and
	// rontolisp:async-defun, the latter two normalized through splitQualified so the
	// built-in nicknames (rl:async-defun) and the :: spelling count too.
	private static boolean isDefunHead(String name) {
		if (LispNames.DEFUN.equals(name)) {
			return true;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn != null && ((LispNames.CL_PKG.equals(qn.pkg()) && LispNames.DEFUN.equals(qn.member()))
				|| (LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.ASYNC_DEFUN.equals(qn.member())));
	}

	// Whether the user program already defines rontolisp:wait-for (a defun of it), so
	// the splice does not collide with it -- the dedup guard every library splice
	// carries.
	private static boolean definesWaitFor(List<LispVal> program) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && isDefunHead(head.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& namesRontolispMember(name.name(), LispNames.WAIT_FOR)) {
				return true;
			}
		}
		return false;
	}

	private static void collectNames(@Nullable LispVal form, Set<String> names) {
		switch (form) {
			case LispSymbol sym -> {
				names.add(sym.name());
				// The reader upcases user spellings while WIT member names are
				// lower-kebab:
				// record the lowercase twin too, so the member filter matches every
				// referenced binding (mirrors WitImportInliner.collectNames).
				names.add(sym.name().toLowerCase(java.util.Locale.ROOT));
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				if (qn != null) {
					names.add(qn.member());
					names.add(qn.member().toLowerCase(java.util.Locale.ROOT));
				}
			}
			case LispCons cons -> {
				collectNames(cons.car(), names);
				collectNames(cons.cdr(), names);
			}
			case null, default -> {
			}
		}
	}

	private static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (WaitForLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readResource("wait.lisp"), Features.INTERPRETER);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String witText() {
		String cached = witText;
		if (cached == null) {
			synchronized (WaitForLibrary.class) {
				cached = witText;
				if (cached == null) {
					cached = readResource("clocks.wit");
					witText = cached;
				}
			}
		}
		return cached;
	}

	private static String readResource(String name) {
		try (InputStream in = WaitForLibrary.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException(name + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
