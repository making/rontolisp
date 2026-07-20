package am.ik.rontolisp.eval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispPackageException;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import org.jspecify.annotations.Nullable;

/**
 * The compile-path library tree-shaker: an AST-level pre-pass that drops spliced library
 * definitions ({@code defun}/{@code defparameter}/{@code defvar} from
 * {@link LinalgLibrary}, {@link VecLibrary}, {@link JsonLibrary}, {@link UrlLibrary} and
 * {@link LispPreludeLibrary}) that are unreachable from the user program. The library
 * pre-passes splice each library <em>whole</em> (linalg.lisp alone is ~100 defuns of
 * which a typical program calls a handful), and the {@code --optimize} bytecode shakers
 * can only trim after the complete class/module has been serialized once -- which is how
 * the ci-spec corpus class approached the JVM 65535 constant-pool ceiling
 * ({@code .kb/library-defun-pruning.md}). Pruning before Pass 1 keeps the pool (and the
 * un-optimized artifact) small for every program.
 *
 * <p>
 * Reachability is deliberately over-approximate ("carve-out" semantics):
 * <ul>
 * <li>a reference is ANY occurrence of a definition's name anywhere in a kept form --
 * operator position, argument position, inside {@code quote}d data, inside
 * {@code (function ...)} -- plus any string literal that contains the name as a substring
 * (so {@code (intern "linalg:norm")} / {@code read-from-string} idioms keep their
 * target);</li>
 * <li>the fixpoint starts from every non-library top-level form (the user program is
 * never pruned) and follows references transitively through kept library
 * definitions;</li>
 * <li>the one reference the later macro expansion synthesizes out of thin air,
 * {@code (setf (vec:aref ...))} -> {@code vec:aset}, is a hardcoded edge; usocket is
 * excluded from pruning entirely because its {@code with-*} built-in macros synthesize
 * {@code usocket:socket-close}/{@code usocket::%usock-guard} calls that are not textually
 * present;</li>
 * <li>the pass bails (prunes nothing) when a runtime {@code load}/{@code require}
 * survives the {@code LoadInliner} -- loaded code can call anything by name. The CLI
 * additionally skips the pass under {@code --dynamic} (late binding can resolve any name
 * at runtime) and {@code --no-prune}.</li>
 * </ul>
 *
 * The documented limitation of the carve-out: a program that forges a library function's
 * qualified name at runtime from computed strings and invokes it through the eval family
 * ({@code eval}/{@code apply}/computed {@code fboundp}) gets the ordinary "undefined
 * function" error for a pruned function; compile with {@code --dynamic} (or
 * {@code --no-prune}) to keep every library definition.
 *
 * <p>
 * Analysis runs on a {@link PackageResolver#resolveProgram(List) resolved} copy of the
 * program (index-aligned 1:1) so user-source spellings ({@code in-package}, nicknames,
 * bare exported names) match the libraries' canonical fixed-point names; forms are
 * removed from the ORIGINAL list, so surviving forms stay byte-identical (the resolver
 * fixed-point invariants of the library sources are untouched). The interpreter path is
 * unaffected: it lazy-loads libraries whole at runtime, where nothing is pruned.
 */
public final class LibraryDefunPruner {

	@Nullable private static volatile Set<String> prunableNames;

	private LibraryDefunPruner() {
	}

	/**
	 * Drops spliced library definitions unreachable from the user program. Returns the
	 * program unchanged when nothing can be pruned (no library definitions present, or a
	 * runtime {@code load}/{@code require} survives).
	 * @param program the top-level forms, after load inlining, user-macro expansion and
	 * every library splice pre-pass
	 * @return the program with unreachable library definitions removed
	 */
	public static List<LispVal> prune(List<LispVal> program) {
		Set<String> prunable = prunableNames();
		// The spliced library forms are canonical (a resolver fixed point), so matching
		// their raw names against the prunable set is exact. A user form whose raw
		// spelling differs from its canonical name can only fail to match -- staying a
		// root, never pruned -- which errs on the safe side.
		Map<String, List<Integer>> defsByName = new HashMap<>();
		Map<Integer, String> nameByIndex = new HashMap<>();
		for (int i = 0; i < program.size(); i++) {
			String name = definitionName(program.get(i));
			if (name != null && prunable.contains(name)) {
				defsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
				nameByIndex.put(i, name);
			}
		}
		if (defsByName.isEmpty()) {
			return program;
		}
		// A runtime load/require (a non-literal one the LoadInliner could not inline)
		// can call any function by name, so reachability is unknowable: keep everything.
		if (usesAnySymbol(program, Set.of(LispNames.LOAD, LispNames.REQUIRE))) {
			return program;
		}
		// Analyze a resolved copy (index-aligned 1:1 with the original) so user-source
		// spellings match the libraries' canonical names. Resolution errors are not this
		// pass's to report: the compiler runs the identical resolution first thing.
		List<LispVal> resolved;
		try {
			resolved = new PackageResolver().resolveProgram(program);
		}
		catch (LispPackageException ex) {
			return program;
		}
		// Fixpoint from the roots (every non-library top-level form): a library
		// definition is kept iff its name is referenced from a kept form, transitively.
		Set<String> live = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		Set<String> roots = new LinkedHashSet<>();
		for (int i = 0; i < resolved.size(); i++) {
			if (!nameByIndex.containsKey(i)) {
				collectReferences(resolved.get(i), prunable, roots);
			}
		}
		for (String name : roots) {
			if (live.add(name)) {
				queue.add(name);
			}
		}
		while (!queue.isEmpty()) {
			String name = queue.remove();
			List<Integer> indexes = defsByName.get(name);
			if (indexes == null) {
				continue;
			}
			for (int index : indexes) {
				Set<String> refs = new LinkedHashSet<>();
				collectReferences(resolved.get(index), prunable, refs);
				for (String ref : refs) {
					if (live.add(ref)) {
						queue.add(ref);
					}
				}
			}
		}
		List<LispVal> out = new ArrayList<>(program.size());
		for (int i = 0; i < program.size(); i++) {
			String name = nameByIndex.get(i);
			if (name != null && !live.contains(name)) {
				continue;
			}
			out.add(program.get(i));
		}
		return out.size() == program.size() ? program : out;
	}

	/**
	 * The union of every definition name in the prunable libraries (linalg, vec, json +
	 * its {@code #'} wrappers, url, prelude). usocket is deliberately absent: its
	 * {@code with-*} built-in macros synthesize calls not textually present in the
	 * pre-expansion AST.
	 */
	private static Set<String> prunableNames() {
		Set<String> cached = prunableNames;
		if (cached == null) {
			synchronized (LibraryDefunPruner.class) {
				cached = prunableNames;
				if (cached == null) {
					Set<String> names = new HashSet<>();
					collectDefinitionNames(LinalgLibrary.forms(), names);
					collectDefinitionNames(VecLibrary.forms(), names);
					collectDefinitionNames(JsonLibrary.forms(), names);
					collectDefinitionNames(JsonLibrary.wrapperForms(), names);
					collectDefinitionNames(UrlLibrary.forms(), names);
					for (String name : LispPreludeLibrary.names()) {
						collectDefinitionNames(LispPreludeLibrary.formsFor(name), names);
					}
					prunableNames = Set.copyOf(names);
					cached = prunableNames;
				}
			}
		}
		return cached;
	}

	private static void collectDefinitionNames(List<LispVal> forms, Set<String> out) {
		for (LispVal form : forms) {
			String name = definitionName(form);
			if (name != null) {
				out.add(name);
			}
		}
	}

	/**
	 * Returns the defined name of a top-level {@code defun}/{@code defparameter}/
	 * {@code defvar} form, or {@code null} for anything else (which is then a root). A
	 * {@code (defun (setf N) ...)} writer is keyed under N, the same name every
	 * {@code (setf (N ...))} place and {@code #'(setf N)} reference contains textually.
	 */
	@Nullable private static String definitionName(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !(cons.cdr() instanceof LispCons rest)) {
			return null;
		}
		String operator = op.name();
		if (!LispNames.DEFUN.equals(operator) && !LispNames.DEFPARAMETER.equals(operator)
				&& !LispNames.DEFVAR.equals(operator)) {
			return null;
		}
		return switch (rest.car()) {
			case LispSymbol name -> name.name();
			// (defun (setf N) ...) -> keyed under N (kept iff N is referenced).
			case LispCons designator when LispNames.DEFUN.equals(operator)
					&& designator.car() instanceof LispSymbol setf && LispNames.SETF.equals(setf.name())
					&& designator.cdr() instanceof LispCons placeCell && placeCell.car() instanceof LispSymbol place ->
				place.name();
			default -> null;
		};
	}

	/**
	 * Collects every reference to a prunable name inside {@code form}: any symbol
	 * occurrence (any position, including quoted data) plus any string literal containing
	 * a prunable name as a substring. The one synthesized edge -- the later
	 * {@code (setf (vec:aref ...))} expansion calls {@code vec:aset} -- is added
	 * alongside {@code vec:aref}.
	 */
	private static void collectReferences(LispVal form, Set<String> prunable, Set<String> out) {
		switch (form) {
			case LispSymbol sym -> {
				String name = sym.name();
				if (prunable.contains(name)) {
					out.add(name);
				}
				if (LispNames.VEC_QUALIFIED_AREF.equals(name)) {
					out.add(LispNames.VEC_QUALIFIED_ASET);
				}
			}
			case LispString str -> {
				// Match case-insensitively: the reader upcases, so a lowercase source
				// string like "linalg:ndim" fed to read-from-string names LINALG:NDIM.
				String value = str.value().toUpperCase(java.util.Locale.ROOT);
				for (String name : prunable) {
					if (value.contains(name)) {
						out.add(name);
					}
				}
			}
			case LispCons cons -> {
				collectReferences(cons.car(), prunable, out);
				collectReferences(cons.cdr(), prunable, out);
			}
			default -> {
			}
		}
	}

	/**
	 * Returns whether any of the given symbol names occurs anywhere in the program,
	 * compared by member name so pre-resolution spellings like {@code cl:load} match.
	 */
	private static boolean usesAnySymbol(List<LispVal> program, Set<String> names) {
		for (LispVal form : program) {
			if (usesAnySymbol(form, names)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesAnySymbol(LispVal form, Set<String> names) {
		return switch (form) {
			case LispSymbol sym -> names.contains(member(sym.name()));
			case LispCons cons -> usesAnySymbol(cons.car(), names) || usesAnySymbol(cons.cdr(), names);
			default -> false;
		};
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
