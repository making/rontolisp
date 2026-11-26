package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

import org.jspecify.annotations.Nullable;

/**
 * Record types of the Scheme lowering: a {@code define-record-type} is a
 * {@code defstruct} -- that is what registers the instance layout on every backend
 * ({@code .kb/defstruct.md}) -- at the top level as it stands, hoisted to the top level
 * when it is an internal one, its procedures bound to the generated functions.
 *
 * <p>
 * One slice of {@link SchemeLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class SchemeRecordLowering {

	private SchemeRecordLowering() {
	}

	static void declareRecord(SchemeLowering s, LispCons form, Map<String, Integer> definitions) {
		RecordType type = recordType(s, form);
		for (LispSymbol procedure : recordProcedures(type)) {
			String name = s.name(procedure);
			if (definitions.merge(name, 1, Integer::sum) != 1 || s.assignedNames.contains(name)) {
				throw s.error("a record procedure cannot be redefined or assigned: " + procedure.name(), form);
			}
			s.global.bindings.put(name,
					procedure == type.predicate() ? new SchemeLowering.GlobalPredicate(s.global(procedure))
							: new SchemeLowering.GlobalFunction(s.global(procedure)));
		}
	}

	static List<LispSymbol> recordProcedures(RecordType type) {
		List<LispSymbol> procedures = new ArrayList<>(type.accessors().values());
		procedures.addAll(type.modifiers().values());
		if (type.constructor() != null) {
			procedures.add(type.constructor());
		}
		procedures.add(type.predicate());
		return procedures;
	}

	/**
	 * A parsed {@code define-record-type}.
	 *
	 * @param name the type name
	 * @param constructor the constructor name, or {@code null}
	 * @param constructorFields the fields the constructor takes, in order
	 * @param predicate the predicate name
	 * @param fields every field, in order
	 * @param accessors field to accessor name
	 * @param modifiers field to modifier name
	 */
	record RecordType(LispSymbol name, @Nullable LispSymbol constructor, List<String> constructorFields,
			LispSymbol predicate, List<String> fields, SequencedMap<String, LispSymbol> accessors,
			SequencedMap<String, LispSymbol> modifiers) {
	}

	static RecordType recordType(SchemeLowering s, LispCons form) {
		List<LispVal> parts = s.elements(form, form);
		if (parts.size() < 4) {
			throw s.error("malformed define-record-type", form);
		}
		LispSymbol name = parts.get(1) instanceof LispCons named ? s.identifier(named.car(), form)
				: s.identifier(parts.get(1), form);
		List<String> fields = new ArrayList<>();
		SequencedMap<String, LispSymbol> accessors = new LinkedHashMap<>();
		SequencedMap<String, LispSymbol> modifiers = new LinkedHashMap<>();
		for (LispVal spec : parts.subList(4, parts.size())) {
			List<LispVal> field = spec instanceof LispSymbol ? List.of(spec) : s.elements(spec, form);
			if (field.isEmpty() || field.size() > 3) {
				throw s.error("malformed record field", form);
			}
			String fieldName = s.identifier(field.get(0), form).name();
			if (fields.contains(fieldName)) {
				throw s.error("duplicate record field: " + fieldName, form);
			}
			fields.add(fieldName);
			if (field.size() >= 2) {
				accessors.put(fieldName, s.identifier(field.get(1), form));
			}
			if (field.size() == 3) {
				modifiers.put(fieldName, s.identifier(field.get(2), form));
			}
		}
		LispSymbol constructor = null;
		List<String> constructorFields = new ArrayList<>();
		if (parts.get(2) instanceof LispCons spec) {
			List<LispVal> constructorSpec = s.elements(spec, form);
			constructor = s.identifier(constructorSpec.get(0), form);
			for (LispVal field : constructorSpec.subList(1, constructorSpec.size())) {
				String fieldName = s.identifier(field, form).name();
				if (!fields.contains(fieldName)) {
					throw s.error("the constructor names an unknown field: " + fieldName, form);
				}
				constructorFields.add(fieldName);
			}
		}
		else if (parts.get(2) instanceof LispSymbol bare && bare != SchemeReader.FALSE) {
			constructor = bare;
			constructorFields.addAll(fields);
		}
		return new RecordType(name, constructor, constructorFields, s.identifier(parts.get(3), form), fields, accessors,
				modifiers);
	}

	// A record type is a defstruct: that is what registers the instance layout on every
	// backend (.kb/defstruct.md). Each slot is NAMED after its accessor and the conc-name
	// is empty, so the generated accessor IS the Scheme accessor -- no wrapper call.
	static void recordType(SchemeLowering s, LispCons form, List<LispVal> out) {
		RecordType type = recordType(s, form);
		Map<String, LispSymbol> slots = new HashMap<>();
		List<LispVal> slotList = new ArrayList<>();
		for (String field : type.fields()) {
			LispSymbol accessor = type.accessors().get(field);
			LispSymbol slot = accessor != null ? s.global(accessor) : s.fresh("SLOT");
			slots.put(field, slot);
			slotList.add(slot);
		}
		List<LispVal> constructorParams = new ArrayList<>();
		for (String field : type.constructorFields()) {
			constructorParams.add(slots.get(field));
		}
		LispSymbol constructor = type.constructor() != null ? s.global(type.constructor()) : s.fresh("MAKE");
		LispVal options = SchemeLowering.list(s.global(type.name()),
				SchemeLowering.list(SchemeLowering.symbol(":CONSTRUCTOR"), constructor,
						SchemeLowering.listOf(constructorParams)),
				SchemeLowering.list(SchemeLowering.symbol(":PREDICATE"), s.global(type.predicate())),
				SchemeLowering.list(SchemeLowering.symbol(":COPIER"), LispNil.INSTANCE),
				SchemeLowering.list(SchemeLowering.symbol(":CONC-NAME"), LispNil.INSTANCE));
		out.add(SchemeLowering.inherit(form, new LispCons(SchemeLowering.symbol("DEFSTRUCT"),
				new LispCons(options, SchemeLowering.listOf(slotList)))));
		type.modifiers().forEach((field, modifier) -> {
			LispSymbol record = s.fresh("R");
			LispSymbol newValue = s.fresh("V");
			LispSymbol slot = slots.get(field);
			if (slot == null || !type.accessors().containsKey(field)) {
				throw s.error("a modifier needs an accessor for its field: " + field, form);
			}
			// A modifier answers the unspecified object, like set-car!, so a REPL does
			// not
			// echo the stored value.
			out.add(SchemeLowering
				.inherit(form,
						SchemeLowering.list(SchemeLowering.symbol("DEFUN"), s.global(modifier),
								SchemeLowering.list(record, newValue), SchemeLowering
									.list(SchemeLowering.symbol("SETF"), SchemeLowering.list(slot, record), newValue),
								s.unspecifiedVariable)));
		});
	}

	/**
	 * An internal record type, hoisted to the top level.
	 *
	 * @param procedures what the body binds: each record procedure and the top-level
	 * procedure it means
	 */
	record InternalRecord(List<LocalProcedure> procedures) {
	}

	record LocalProcedure(LispSymbol identifier, SchemeLowering.Binding binding) {
	}

	static void refuseAgainInBody(SchemeLowering s, LispSymbol identifier, java.util.Set<String> recordNames,
			LispCons form) {
		if (recordNames.contains(s.name(identifier))) {
			throw s.error("a body defines " + identifier.name() + " twice", form);
		}
	}

	// An internal define-record-type is a TOP-LEVEL defstruct, hoisted ahead of the
	// top-level form holding it: a defstruct is what registers the layout on every
	// backend, and the compile path refuses one anywhere else. Its name,
	// s%%[<enclosing definition> <type>], is one no identifier mangles to
	// (SchemeNames.libraryPrefix's argument), qualified by the top-level definition it
	// stands in so two procedures' types never meet. The slots are named after the
	// FIELDS -- the expander may have renamed the accessors -- and the accessors are the
	// generated ones through a conc-name; the other procedures are defuns named after
	// the type. The body binds its names to these, so every call stays direct. One type
	// per occurrence, not per evaluation (R7RS leaves generativity unspecified).
	static InternalRecord internalRecord(SchemeLowering s, LispCons form) {
		InternalRecord known = s.internalRecords.get(form);
		if (known != null) {
			return known;
		}
		RecordType type = recordType(s, form);
		String base = s.prefix + SchemeNames.PREFIX + "%[" + SchemeNames.component(s.enclosing) + " "
				+ SchemeNames.component(s.name(type.name()));
		String candidate = base + "]";
		for (int ordinal = 2; !s.internalRecordNames.add(candidate); ordinal++) {
			candidate = base + " " + ordinal + "]";
		}
		String structName = candidate;
		String concName = structName + " ";
		Map<String, LispSymbol> slots = new HashMap<>();
		List<LispVal> slotList = new ArrayList<>();
		for (String field : type.fields()) {
			LispSymbol slot = SchemeLowering.symbol(SchemeNames.mangle(field));
			slots.put(field, slot);
			slotList.add(slot);
		}
		List<LocalProcedure> procedures = new ArrayList<>();
		type.accessors()
			.forEach((field, accessor) -> procedures.add(new LocalProcedure(accessor,
					new SchemeLowering.GlobalFunction(SchemeLowering.symbol(concName + SchemeNames.mangle(field))))));
		List<LispVal> constructorParams = new ArrayList<>();
		for (String field : type.constructorFields()) {
			constructorParams.add(slots.get(field));
		}
		LispSymbol constructor;
		if (type.constructor() != null) {
			constructor = SchemeLowering.symbol(structName + "(" + s.name(type.constructor()) + ")");
			procedures.add(new LocalProcedure(type.constructor(), new SchemeLowering.GlobalFunction(constructor)));
		}
		else {
			constructor = s.fresh("MAKE");
		}
		LispSymbol predicate = SchemeLowering.symbol(structName + "(" + s.name(type.predicate()) + ")");
		procedures.add(new LocalProcedure(type.predicate(), new SchemeLowering.GlobalPredicate(predicate)));
		LispVal options = SchemeLowering.list(SchemeLowering.symbol(structName),
				SchemeLowering.list(SchemeLowering.symbol(":CONSTRUCTOR"), constructor,
						SchemeLowering.listOf(constructorParams)),
				SchemeLowering.list(SchemeLowering.symbol(":PREDICATE"), predicate),
				SchemeLowering.list(SchemeLowering.symbol(":COPIER"), LispNil.INSTANCE),
				SchemeLowering.list(SchemeLowering.symbol(":CONC-NAME"), new LispString(concName)));
		s.hoisted.add(SchemeLowering.inherit(form, new LispCons(SchemeLowering.symbol("DEFSTRUCT"),
				new LispCons(options, SchemeLowering.listOf(slotList)))));
		type.modifiers().forEach((field, modifier) -> {
			LispSymbol name = SchemeLowering.symbol(structName + "(" + s.name(modifier) + ")");
			LispSymbol record = s.fresh("R");
			LispSymbol newValue = s.fresh("V");
			s.hoisted
				.add(SchemeLowering.inherit(form, SchemeLowering.list(SchemeLowering.symbol("DEFUN"), name,
						SchemeLowering.list(record, newValue),
						SchemeLowering.list(SchemeLowering.symbol("SETF"), SchemeLowering
							.list(SchemeLowering.symbol(concName + SchemeNames.mangle(field)), record), newValue),
						s.unspecifiedVariable)));
			procedures.add(new LocalProcedure(modifier, new SchemeLowering.GlobalFunction(name)));
		});
		InternalRecord record = new InternalRecord(List.copyOf(procedures));
		s.internalRecords.put(form, record);
		return record;
	}

}
