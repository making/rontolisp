package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispJavaObject;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.Clojure;
import am.ik.rontolisp.clojure.ClojureRunTime;
import am.ik.rontolisp.clojure.ClojureTopLevel;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * A whole Clojure program read for a compiled target that lowers as the program runs --
 * the JVM: its forms, and the lowering a program whose run-time code reaches a lowering
 * verb over computed arguments ({@code eval}, a computed {@code resolve} or
 * {@code require} ...) keeps for its run. The compile path reads the program through
 * here, and so does the compiled class when it first runs such a verb
 * ({@code cli/JvmRunTimeLowering}): the same read of the same text through the same file
 * answers takes the same decisions, so the lowering the class rebuilds names the
 * functions and globals the class holds.
 *
 * <p>
 * A verb's arguments reach here as the plain data the class's run-time library carries
 * them as ({@code ClojureLibrary}'s transport): vectors, maps and sets as tagged lists, a
 * collection's metadata beside it, and every value no datum spells as the index of a
 * constant the class keeps. They are read back into the values the lowering decodes, as
 * the interpreter holds them.
 *
 * <p>
 * The {@code clojure} package's types stay behind it, so the command line names no
 * language package ({@code .kb/architecture.md}).
 */
public final class ClojureProgramLowering {

	private final List<LispVal> forms;

	private final @Nullable ClojureRunTime runTime;

	private ClojureProgramLowering(List<LispVal> forms, @Nullable ClojureRunTime runTime) {
		this.forms = forms;
		this.runTime = runTime;
	}

	/**
	 * Reads and lowers a whole Clojure program.
	 * @param source the program text
	 * @param features the target's reader features
	 * @param file the entry file, or {@code null}
	 * @param standards what the program is read against
	 * @param loader where the files the program names are read from
	 * @return the program, with its lowering where it lowers at run time
	 */
	public static ClojureProgramLowering read(String source, Features features, @Nullable String file,
			SourceStandards standards, SourceLoader loader) {
		Clojure.Program program = SourceLanguage.CLOJURE.readClojure(source, features, file, standards, loader);
		return new ClojureProgramLowering(program.forms(), program.runTime());
	}

	/**
	 * {@return the program's lowered forms}
	 */
	public List<LispVal> forms() {
		return this.forms;
	}

	/**
	 * {@return whether the program lowers as it runs} -- its run-time code reaches a
	 * lowering verb over computed arguments, and its lowering is kept
	 */
	public boolean lowersAtRunTime() {
		return this.runTime != null;
	}

	/**
	 * The running class's half of the values a lowering verb takes: the form evaluating
	 * to a value it embeds.
	 */
	@FunctionalInterface
	public interface Constants {

		/**
		 * The core form evaluating to the class's {@code index}th embedded value.
		 * @param index the constant's index
		 * @return the form
		 */
		LispVal constant(int index);

	}

	/**
	 * One entry of a lowered verb call.
	 *
	 * @param forms the core forms to run, in order
	 * @param echoes whether the value of the last one is the call's
	 */
	public record Entry(List<LispVal> forms, boolean echoes) {
	}

	/**
	 * One call of a lowering verb as the program runs, lowered in the namespace
	 * {@code *ns*} names; what it defines joins the lowering, so a later call sees it.
	 * @param verb the verb ({@code eval}, {@code resolve}, {@code require} ...)
	 * @param transported the arguments, as the run-time library carries them
	 * @param namespace the name of the namespace {@code *ns*} holds
	 * @param constants the forms evaluating to the values the arguments embed
	 * @return the entries to run, in order
	 * @throws LispReadException when the call does not lower
	 * @throws IllegalStateException when the program keeps no lowering
	 */
	public List<Entry> lower(String verb, List<LispVal> transported, String namespace, Constants constants) {
		ClojureRunTime kept = this.runTime;
		if (kept == null) {
			throw new IllegalStateException("the program keeps no lowering for its run");
		}
		Map<LispVal, LispVal> metas = new IdentityHashMap<>();
		List<LispVal> args = new ArrayList<>(transported.size());
		for (LispVal arg : transported) {
			args.add(received(arg, metas));
		}
		List<ClojureTopLevel> lowered = kept.lower(verb, args, namespace, new ClojureRunTime.Host() {
			@Override
			public List<LispVal> metasOf(List<LispVal> values) {
				List<LispVal> out = new ArrayList<>(values.size());
				for (LispVal value : values) {
					out.add(metas.getOrDefault(value, LispNil.INSTANCE));
				}
				return out;
			}

			@Override
			public LispVal constant(LispVal value) {
				if (value instanceof LispJavaObject host && host.ref() instanceof Embedded embedded) {
					return constants.constant(embedded.index());
				}
				// an atom no datum spells the way the lowering takes it (a symbol of no
				// namespace the lowering knows) stands as itself
				return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
			}
		});
		List<Entry> out = new ArrayList<>(lowered.size());
		for (ClojureTopLevel top : lowered) {
			out.add(new Entry(top.forms(), top.echoes()));
		}
		return out;
	}

	/** The index of an embedded value, standing where the value stood. */
	private record Embedded(int index) {
	}

	/**
	 * A transported value as the interpreter holds it: a vector a vector, a map an
	 * {@code equal} table, a set its {@code (:C%SET table)} wrapper, an embedded value
	 * the marker of its index; a collection's metadata recorded beside it.
	 */
	private static LispVal received(LispVal value, Map<LispVal, LispVal> metas) {
		if (!(value instanceof LispCons cons)) {
			return value;
		}
		if (cons.car() instanceof LispSymbol tag) {
			switch (tag.name()) {
				case ":C%T-VECTOR" -> {
					List<LispVal> items = itemsOf(cons.cdr());
					LispVal[] data = new LispVal[items.size()];
					for (int i = 0; i < data.length; i++) {
						data[i] = received(items.get(i), metas);
					}
					return new LispArray(new int[] { data.length }, data);
				}
				case ":C%T-MAP" -> {
					LispHashTable table = new LispHashTable();
					List<LispVal> items = itemsOf(cons.cdr());
					for (int i = 0; i + 1 < items.size(); i += 2) {
						table.put(received(items.get(i), metas), received(items.get(i + 1), metas));
					}
					return table;
				}
				case ":C%T-SET" -> {
					LispHashTable table = new LispHashTable();
					for (LispVal item : itemsOf(cons.cdr())) {
						LispVal member = received(item, metas);
						table.put(member, member);
					}
					return new LispCons(new LispSymbol(":C%SET"), new LispCons(table, LispNil.INSTANCE));
				}
				case ":C%T-META" -> {
					List<LispVal> items = itemsOf(cons.cdr());
					LispVal datum = received(items.get(0), metas);
					metas.put(datum, received(items.get(1), metas));
					return datum;
				}
				case ":C%T-EMBED" -> {
					List<LispVal> items = itemsOf(cons.cdr());
					return new LispJavaObject(new Embedded((int) ((LispInteger) items.get(0)).value()));
				}
				case ":C%SYMBOL" -> {
					List<LispVal> items = itemsOf(cons.cdr());
					return new LispCons(tag,
							new LispCons(items.get(0), new LispCons(received(items.get(1), metas), LispNil.INSTANCE)));
				}
				default -> {
					if (tag.name().startsWith(":")) {
						// a keyword, an instant, a UUID or a decimal: data as it is
						return cons;
					}
				}
			}
		}
		List<LispVal> out = new ArrayList<>();
		LispVal run = cons;
		while (run instanceof LispCons cell) {
			out.add(received(cell.car(), metas));
			run = cell.cdr();
		}
		LispVal tail = run instanceof LispNil ? LispNil.INSTANCE : received(run, metas);
		for (int i = out.size() - 1; i >= 0; i--) {
			tail = new LispCons(out.get(i), tail);
		}
		return tail;
	}

	private static List<LispVal> itemsOf(LispVal list) {
		List<LispVal> items = new ArrayList<>();
		LispVal run = list;
		while (run instanceof LispCons cell) {
			items.add(cell.car());
			run = cell.cdr();
		}
		return items;
	}

}
