package am.ik.rontolisp.codegen.wasm;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.macro.LispMacroExpander.StructDefinedNames;
import org.jspecify.annotations.Nullable;

/**
 * The functions the program's {@code defstruct}s define, for the scalar backend, which
 * has no structure and so makes one only where nothing reads it
 * ({@code .kb/no-gc-scalar-wasm.md}, "A structure nothing reads"): a constructor
 * evaluates its arguments and answers a value that is only ever tested for nil, a slot
 * write evaluates its value, and every operation that reads an instance -- an accessor,
 * the predicate, the copier -- is refused by name. The names are those the defstruct
 * expansion defines, in every spelling a reference can take
 * ({@link LispMacroExpander#defstructDefinedNames}).
 */
final class NoGcStructures {

	/** What a function a {@code defstruct} defines does with an instance. */
	enum Role {

		/** Makes an instance. */
		CONSTRUCTOR,

		/** Reads or writes one slot; only the write compiles. */
		ACCESSOR,

		/** Reads the instance some other way: the predicate, the copier. */
		READER

	}

	/**
	 * A function a {@code defstruct} defines.
	 *
	 * @param structure the structure's name
	 * @param role what it does with an instance
	 */
	record Operation(String structure, Role role) {
	}

	private final Map<String, StructDefinedNames> definitions = new HashMap<>();

	private final Map<String, Operation> operations = new HashMap<>();

	/**
	 * Records what a top-level {@code defstruct} defines: its constructors, predicate,
	 * copier and the accessors of every slot it has, an {@code :include}d structure's
	 * among them (a structure is defined after the one it includes).
	 * @param form a top-level form
	 * @return whether the form was a {@code defstruct}
	 */
	boolean define(LispVal form) {
		StructDefinedNames names = LispMacroExpander.defstructDefinedNames(form);
		if (names == null) {
			return false;
		}
		String structure = names.structName();
		this.definitions.put(structure, names);
		for (String defined : names.definedNames()) {
			if (!spells(defined, structure)) {
				Role role = names.instantiatorNames().contains(defined) ? Role.CONSTRUCTOR : Role.READER;
				this.operations.put(defined, new Operation(structure, role));
			}
		}
		for (StructDefinedNames level = names; level != null; level = included(level)) {
			for (String slot : level.ownSlotBases()) {
				for (String accessor : names.accessorSpellings(slot)) {
					this.operations.put(accessor, new Operation(structure, Role.ACCESSOR));
				}
			}
		}
		return true;
	}

	/**
	 * The function a name calls, when a {@code defstruct} defines it.
	 * @param name the name in call position
	 * @return what it does, or {@code null} for a name no {@code defstruct} defines
	 */
	@Nullable Operation operation(String name) {
		return this.operations.get(name);
	}

	private @Nullable StructDefinedNames included(StructDefinedNames names) {
		String parent = names.includeParent();
		return parent == null ? null : this.definitions.get(parent);
	}

	/** Whether a spelling names the structure itself, in either package spelling. */
	private static boolean spells(String spelling, String structure) {
		PackageRegistry.QualifiedName a = PackageRegistry.splitQualified(spelling);
		PackageRegistry.QualifiedName b = PackageRegistry.splitQualified(structure);
		if (a == null || b == null) {
			return spelling.equals(structure);
		}
		return Objects.equals(a.pkg(), b.pkg()) && a.member().equals(b.member());
	}

}
