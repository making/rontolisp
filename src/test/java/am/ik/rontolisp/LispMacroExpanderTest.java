package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LispMacroExpanderTest {

	/**
	 * The names appearing in the emitted {@code %subtypep-ancestor-table%}, in emission
	 * order.
	 */
	private static List<String> ancestorTableNames(String source) {
		List<LispVal> expanded = LispMacroExpander.expandTopLevelDefinitions(LispReader.readAllFromString(source),
				new HashMap<>(), new ClosRegistry());
		List<String> names = new ArrayList<>();
		for (LispVal form : expanded) {
			// Only the data forms -- the (defvar %SUBTYPEP-ANCESTOR-TABLE '(...)) and the
			// (setq %SUBTYPEP-ANCESTOR-TABLE (append '(...) ...)) chunks. The dispatch
			// defun also names the table, and carries counter-generated temporaries that
			// legitimately renumber.
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
					|| !(LispNames.DEFVAR.equals(op.name()) || LispNames.SETQ.equals(op.name()))
					|| !(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispSymbol var)
					|| !LispNames.SUBTYPEP_ANCESTOR_TABLE.equals(var.name())) {
				continue;
			}
			collectSymbols(rest.cdr(), names);
		}
		names.removeIf(name -> LispNames.QUOTE.equals(name) || LispNames.APPEND.equals(name)
				|| LispNames.SUBTYPEP_ANCESTOR_TABLE.equals(name));
		return names;
	}

	private static void collectSymbols(LispVal form, List<String> out) {
		switch (form) {
			case LispSymbol sym -> out.add(sym.name());
			case LispCons cons -> {
				collectSymbols(cons.car(), out);
				collectSymbols(cons.cdr(), out);
			}
			default -> {
			}
		}
	}

	@Test
	void theRuntimeSubtypepTableIsEmittedInLatticeDeclarationOrder() {
		// A computed (subtypep a b) makes the compiler bake the whole type lattice as a
		// data table. Its order must be a function of the PROGRAM, never of the JVM run:
		// Map.of/Set.of randomize their iteration order once per process, so a lattice
		// built from one emits a different (still correct) module on every compile and
		// silently destroys byte-identity, this project's standard way of showing that a
		// change leaves unrelated programs alone. The order below is the declaration
		// order of LispMacroExpander.SUBTYPEP_PARENTS; asserting it fails loudly the day
		// the table goes back to a salted collection.
		List<String> names = ancestorTableNames("(defun st (a b) (subtypep a b)) (print (st 'fixnum 'integer))");
		assertThat(names).isNotEmpty();
		assertThat(names.stream().distinct().toList()).startsWith("FIXNUM", "INTEGER", "RATIONAL", "REAL", "NUMBER",
				"BIGNUM");
	}

	// NOTE: only the assertion above can catch a salted collection. ImmutableCollections'
	// SALT is constant for the lifetime of a process, so comparing two expansions inside
	// one JVM (below) cannot see it -- that test guards the other failure mode, a
	// compiler that accumulates state between compilations.
	@Test
	void theRuntimeSubtypepTableDoesNotDependOnWhatWasCompiledBefore() {
		String source = "(defclass sh () ()) (defclass ci (sh) ()) (defun st (a b) (subtypep a b)) (print (st 'ci 'sh))";
		assertThat(ancestorTableNames(source)).isEqualTo(ancestorTableNames(source));
	}

}
