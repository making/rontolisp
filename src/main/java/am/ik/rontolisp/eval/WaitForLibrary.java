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
		String waitFor = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WAIT_FOR);
		boolean referenced = false;
		for (LispVal form : program) {
			if (references(form, waitFor)) {
				referenced = true;
				break;
			}
		}
		if (!referenced || definesWaitFor(program, waitFor)) {
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

	private static boolean references(LispVal form, String name) {
		return switch (form) {
			case LispSymbol sym -> name.equals(sym.name());
			case LispCons cons -> references(cons.car(), name) || references(cons.cdr(), name);
			default -> false;
		};
	}

	// Whether the user program already defines rontolisp:wait-for (a defun of it), so
	// the splice does not collide with it -- the dedup guard every library splice
	// carries.
	private static boolean definesWaitFor(List<LispVal> program, String waitFor) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& (LispNames.DEFUN.equals(head.name()) || LispNames.ASYNC_DEFUN_QUALIFIED.equals(head.name()))
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& waitFor.equals(name.name())) {
				return true;
			}
		}
		return false;
	}

	private static void collectNames(@Nullable LispVal form, Set<String> names) {
		switch (form) {
			case LispSymbol sym -> {
				names.add(sym.name());
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				if (qn != null) {
					names.add(qn.member());
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
					cached = LispReader.readAllFromString(readResource("wait.lisp"));
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
