package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses -- and prunes -- a component-model <strong>import block</strong>: the run of
 * component type / import / alias sections that declares a fixed set of imported
 * interfaces, as produced by {@code wasm-tools component new} and embedded verbatim by a
 * wrapper that assembles the rest of the component itself.
 * <p>
 * Language-independent, like the rest of {@code am.ik.wasm}. A wrapper knows, after
 * {@link WasmTreeShaker} has run, which members of the block's interfaces anything still
 * binds; this class turns that knowledge into a smaller block: an interface nothing
 * reaches leaves whole, and an interface that stays declares only the members asked for
 * and the types those reach.
 *
 * <h2>The grammar it relies on</h2>
 *
 * The block is a flat run of sections of exactly three kinds, which fall into
 * per-interface <strong>groups</strong> of the shape
 * <code>[alias]* [type] [import]</code>:
 * <ul>
 * <li>an <em>alias</em> section (id 6) projects a type out of an ALREADY-IMPORTED
 * instance ({@code alias type (instance i) "name"}) -- this is how one interface's
 * instance type names another's type;</li>
 * <li>a <em>type</em> section (id 7) with exactly one entry: the interface's instance
 * type, a list of declarators -- a type definition, an {@code alias outer} of a
 * projection just above it, or an {@code export} of a type or a function;</li>
 * <li>an <em>import</em> section (id 10) with exactly one entry: the instance import
 * itself, naming the interface and the instance type.</li>
 * </ul>
 *
 * Every index immediate in the block is one of four kinds, and pruning rewrites all four.
 * Three point OUT of the entry they sit in: the alias section's instance index, the
 * {@code alias outer} declarator's type index, and the import's instance-type index. The
 * rest -- a value type's reference, an {@code own}/{@code borrow} payload, an export
 * declarator's {@code eq} bound or function type -- index the instance type's OWN type
 * space, which a type definition, an {@code alias outer} and a type export each extend by
 * one.
 * <p>
 * The walk that establishes this is complete: every byte of the block is classified, and
 * an unrecognized tag throws rather than emit a component that validates while naming the
 * wrong instance.
 *
 * <h2>What a prune keeps</h2>
 *
 * Within an interface the roots are the exports asked for by name; a declarator stays
 * exactly when a root reaches it through the indices above. A surviving
 * {@code alias outer} keeps its projection, and the projection keeps the export it names
 * in the interface it projects from -- so the closure runs across interfaces, and an
 * interface nobody asked for stays for the one type another borrows. Survivors keep their
 * order and are renumbered; sizes and counts are encoded again.
 *
 * <h2>What the caller still owes</h2>
 *
 * The instance index space the block opens is CONTINUED by the wrapper's own sections
 * after it -- its {@code alias func (instance i) "..."} entries, its user-import
 * instances, its exported instances. {@link Pruned#instanceOf()} is therefore not a
 * courtesy: a wrapper that keeps its own hardcoded instance numbers after pruning emits a
 * component that validates and binds the wrong interface. And a member the wrapper
 * aliases out of an instance must be among the ones it asked for.
 */
public final class ComponentImportBlock {

	private static final int SEC_ALIAS = 6;

	private static final int SEC_TYPE = 7;

	private static final int SEC_IMPORT = 10;

	/**
	 * What an index immediate indexes, which decides how it is renumbered and encoded.
	 */
	private enum Space {

		/** A component instance index (an alias section's target). */
		INSTANCE,
		/** A component type index (an {@code alias outer}, an import's instance type). */
		TYPE,
		/** An index into the enclosing instance type's own type space, unsigned. */
		LOCAL,
		/** The same, in a value type position: a signed LEB that must be non-negative. */
		LOCAL_VALUE

	}

	/** One index immediate, at an offset relative to the block's start. */
	private record Site(int offset, int length, Space space, int index) {
	}

	/**
	 * One entry of an alias section: {@code alias type (instance i) "name"}.
	 *
	 * @param start offset of the entry's first byte
	 * @param end end offset (exclusive)
	 * @param instance the instance index immediate
	 * @param name the projected export's name
	 * @param typeIndex the component type index the projection takes
	 */
	private record Projection(int start, int end, Site instance, String name, int typeIndex) {
	}

	/**
	 * One declarator of an instance type.
	 *
	 * @param start offset of the declarator's tag byte
	 * @param end end offset (exclusive)
	 * @param defines the index it takes in the instance type's own type space, or
	 * {@code -1} for a function export, which takes none
	 * @param export the name it exports, or the empty string for a definition or an alias
	 * @param sites the index immediates inside it
	 */
	private record Declarator(int start, int end, int defines, String export, List<Site> sites) {
	}

	/**
	 * One interface's declarations: its {@code [alias]* [type] [import]} sections.
	 *
	 * @param interfaceId the imported interface's fully-qualified id
	 * @param aliasSections the projections, one list per alias section
	 * @param declarators the instance type's declarators, in order
	 * @param definers the position in {@code declarators} of the one defining each index
	 * of the instance type's own type space
	 * @param typeIndex the component type index the instance type takes
	 * @param instanceIndex the component instance index the group's import takes
	 */
	private record Group(String interfaceId, List<List<Projection>> aliasSections, List<Declarator> declarators,
			List<Integer> definers, int typeIndex, int instanceIndex) {

		private int typeCount() {
			int count = 1;
			for (List<Projection> section : this.aliasSections) {
				count += section.size();
			}
			return count;
		}

		private LinkedHashSet<String> exports() {
			LinkedHashSet<String> names = new LinkedHashSet<>();
			for (Declarator declarator : this.declarators) {
				if (!declarator.export().isEmpty()) {
					names.add(declarator.export());
				}
			}
			return names;
		}
	}

	/**
	 * The outcome of pruning: the new block bytes plus what every downstream reference
	 * has to be rebased on.
	 *
	 * @param bytes the pruned block
	 * @param instanceOf the surviving interfaces' NEW component instance indices, in
	 * block order
	 * @param typeCount how many component types the pruned block defines, i.e. the first
	 * free component type index for whatever the wrapper emits next
	 * @param members the names each surviving interface still exports -- functions and
	 * types, in declaration order
	 */
	public record Pruned(byte[] bytes, LinkedHashMap<String, Integer> instanceOf, int typeCount,
			LinkedHashMap<String, LinkedHashSet<String>> members) {
	}

	private final byte[] block;

	private final List<Group> groups;

	private ComponentImportBlock(byte[] block, List<Group> groups) {
		this.block = block;
		this.groups = groups;
	}

	/**
	 * Parses a block.
	 * @param block the raw component type/import/alias section bytes (no 8-byte component
	 * preamble)
	 * @return the parsed block
	 */
	public static ComponentImportBlock parse(byte[] block) {
		return new ComponentImportBlock(block, new Parser(block).parse());
	}

	/**
	 * The imported interfaces, in block order. Note that this is NOT the declaration
	 * order of the WIT world the block was generated from: {@code wasm-tools} hoists an
	 * interface ahead of the one that {@code use}s it, so a caller must read the names
	 * rather than assume them.
	 * @return the interface ids
	 */
	public LinkedHashSet<String> interfaceIds() {
		LinkedHashSet<String> ids = new LinkedHashSet<>();
		for (Group g : this.groups) {
			ids.add(g.interfaceId());
		}
		return ids;
	}

	/**
	 * The names every interface exports -- its functions and its types -- in the UNPRUNED
	 * block. Handing this to {@link #prune} keeps the block as it is.
	 * @return the interface ids mapped to their export names, in block order
	 */
	public LinkedHashMap<String, LinkedHashSet<String>> members() {
		LinkedHashMap<String, LinkedHashSet<String>> map = new LinkedHashMap<>();
		for (Group g : this.groups) {
			map.put(g.interfaceId(), g.exports());
		}
		return map;
	}

	/**
	 * The component instance index each interface's import takes in the UNPRUNED block.
	 * @return the interface ids mapped to their instance indices, in block order
	 */
	public LinkedHashMap<String, Integer> instanceOf() {
		LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
		for (Group g : this.groups) {
			map.put(g.interfaceId(), g.instanceIndex());
		}
		return map;
	}

	/**
	 * How many component types the unpruned block defines.
	 * @return the type count
	 */
	public int typeCount() {
		int count = 0;
		for (Group g : this.groups) {
			count += g.typeCount();
		}
		return count;
	}

	/**
	 * Cuts the block down to the given members: each interface in {@code keep} stays,
	 * exporting the names given for it and the types those reach, and so does every
	 * interface one of them projects a type out of, exporting that type. Everything else
	 * leaves, and the survivors are renumbered.
	 * @param keep the interfaces to keep, each with the export names -- functions and
	 * types -- to keep in it; an interface or a name the block does not have is an
	 * {@link IllegalArgumentException}
	 * @return the pruned block and the index maps
	 */
	public Pruned prune(Map<String, ? extends Set<String>> keep) {
		Map<String, Integer> byId = new LinkedHashMap<>();
		for (int g = 0; g < this.groups.size(); g++) {
			byId.put(this.groups.get(g).interfaceId(), g);
		}
		// What each surviving group has to export, by its position in the block.
		Map<Integer, LinkedHashSet<String>> wanted = new LinkedHashMap<>();
		keep.forEach((id, names) -> {
			Integer g = byId.get(id);
			if (g == null) {
				throw new IllegalArgumentException("ComponentImportBlock: the block does not import '" + id
						+ "' (it imports " + byId.keySet() + ")");
			}
			LinkedHashSet<String> exports = this.groups.get(g).exports();
			for (String name : names) {
				if (!exports.contains(name)) {
					throw new IllegalArgumentException("ComponentImportBlock: '" + id + "' does not export '" + name
							+ "' (it exports " + exports + ")");
				}
			}
			wanted.put(g, new LinkedHashSet<>(names));
		});
		// A projection points at an EARLIER group, so one pass from the last group to the
		// first closes the set: by the time a group is walked, every later group has said
		// what it projects out of it.
		Map<Integer, boolean[]> live = new LinkedHashMap<>();
		Map<Integer, Set<Integer>> projected = new LinkedHashMap<>();
		for (int g = this.groups.size() - 1; g >= 0; g--) {
			LinkedHashSet<String> names = wanted.get(g);
			if (names == null) {
				continue;
			}
			Group group = this.groups.get(g);
			boolean[] marks = new boolean[group.declarators().size()];
			// The component types the surviving declarators alias, and the projections
			// of this group that define them.
			Set<Integer> named = new LinkedHashSet<>();
			Set<Integer> kept = new LinkedHashSet<>();
			for (int d = 0; d < marks.length; d++) {
				if (names.contains(group.declarators().get(d).export())) {
					mark(group, d, marks, named);
				}
			}
			for (List<Projection> section : group.aliasSections()) {
				for (Projection projection : section) {
					if (!named.contains(projection.typeIndex())) {
						continue;
					}
					int owner = projection.instance().index();
					if (owner >= g || !this.groups.get(owner).exports().contains(projection.name())) {
						throw new IllegalStateException(
								"ComponentImportBlock: '" + group.interfaceId() + "' projects '" + projection.name()
										+ "' out of instance " + owner + ", which no earlier group exports");
					}
					wanted.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(projection.name());
					kept.add(projection.typeIndex());
				}
			}
			if (!kept.equals(named)) {
				named.removeAll(kept);
				throw new IllegalStateException("ComponentImportBlock: '" + group.interfaceId()
						+ "' aliases the component types " + named + ", which its own alias sections do not project");
			}
			live.put(g, marks);
			projected.put(g, kept);
		}
		// New numbering, in block order.
		Map<Integer, Integer> instanceRemap = new LinkedHashMap<>();
		Map<Integer, Integer> typeRemap = new LinkedHashMap<>();
		LinkedHashMap<String, Integer> instanceOf = new LinkedHashMap<>();
		LinkedHashMap<String, LinkedHashSet<String>> members = new LinkedHashMap<>();
		ByteArrayOutputStream out = new UnsynchronizedByteArrayOutputStream();
		for (int g = 0; g < this.groups.size(); g++) {
			boolean[] marks = live.get(g);
			Set<Integer> kept = projected.get(g);
			if (marks == null || kept == null) {
				continue;
			}
			Group group = this.groups.get(g);
			for (List<Projection> section : group.aliasSections()) {
				List<byte[]> entries = new ArrayList<>();
				for (Projection projection : section) {
					if (!kept.contains(projection.typeIndex())) {
						continue;
					}
					entries.add(renumbered(projection.start(), projection.end(), List.of(projection.instance()),
							instanceRemap, typeRemap, Map.of()));
					typeRemap.put(projection.typeIndex(), typeRemap.size());
				}
				if (!entries.isEmpty()) {
					section(out, SEC_ALIAS, vector(entries));
				}
			}
			Map<Integer, Integer> localRemap = new LinkedHashMap<>();
			List<byte[]> declarators = new ArrayList<>();
			LinkedHashSet<String> exports = new LinkedHashSet<>();
			for (int d = 0; d < group.declarators().size(); d++) {
				if (!marks[d]) {
					continue;
				}
				Declarator declarator = group.declarators().get(d);
				declarators.add(renumbered(declarator.start(), declarator.end(), declarator.sites(), instanceRemap,
						typeRemap, localRemap));
				if (declarator.defines() >= 0) {
					localRemap.put(declarator.defines(), localRemap.size());
				}
				if (!declarator.export().isEmpty()) {
					exports.add(declarator.export());
				}
			}
			ByteArrayOutputStream type = new UnsynchronizedByteArrayOutputStream();
			writeU(type, 1);
			type.write(0x42);
			type.writeBytes(vector(declarators));
			section(out, SEC_TYPE, type.toByteArray());
			int instanceType = typeRemap.size();
			typeRemap.put(group.typeIndex(), instanceType);
			ByteArrayOutputStream imported = new UnsynchronizedByteArrayOutputStream();
			writeU(imported, 1);
			imported.write(0x00);
			byte[] name = group.interfaceId().getBytes(StandardCharsets.UTF_8);
			writeU(imported, name.length);
			imported.writeBytes(name);
			imported.write(0x05);
			writeU(imported, instanceType);
			section(out, SEC_IMPORT, imported.toByteArray());
			instanceRemap.put(group.instanceIndex(), instanceOf.size());
			instanceOf.put(group.interfaceId(), instanceOf.size());
			members.put(group.interfaceId(), exports);
		}
		return new Pruned(out.toByteArray(), instanceOf, typeRemap.size(), members);
	}

	// Marks a declarator and everything its indices reach: the declarators defining the
	// local types it names, and (collected into `named`) the component types it aliases.
	private static void mark(Group group, int declarator, boolean[] marks, Set<Integer> named) {
		if (marks[declarator]) {
			return;
		}
		marks[declarator] = true;
		for (Site site : group.declarators().get(declarator).sites()) {
			if (site.space() == Space.TYPE) {
				named.add(site.index());
			}
			else {
				mark(group, group.definers().get(site.index()), marks, named);
			}
		}
	}

	// block[from, to) with every index immediate in it renumbered and encoded again.
	private byte[] renumbered(int from, int to, List<Site> sites, Map<Integer, Integer> instanceRemap,
			Map<Integer, Integer> typeRemap, Map<Integer, Integer> localRemap) {
		ByteArrayOutputStream out = new UnsynchronizedByteArrayOutputStream();
		int at = from;
		for (Site site : sites) {
			out.write(this.block, at, site.offset() - at);
			Integer replacement = switch (site.space()) {
				case INSTANCE -> instanceRemap.get(site.index());
				case TYPE -> typeRemap.get(site.index());
				case LOCAL, LOCAL_VALUE -> localRemap.get(site.index());
			};
			if (replacement == null) {
				throw new IllegalStateException("ComponentImportBlock: a surviving declaration still names the "
						+ site.space() + " index " + site.index() + ", which pruning dropped");
			}
			if (site.space() == Space.LOCAL_VALUE) {
				writeS(out, replacement);
			}
			else {
				writeU(out, replacement);
			}
			at = site.offset() + site.length();
		}
		out.write(this.block, at, to - at);
		return out.toByteArray();
	}

	private static void section(ByteArrayOutputStream out, int id, byte[] body) {
		out.write(id);
		writeU(out, body.length);
		out.writeBytes(body);
	}

	private static byte[] vector(List<byte[]> entries) {
		ByteArrayOutputStream out = new UnsynchronizedByteArrayOutputStream();
		writeU(out, entries.size());
		for (byte[] entry : entries) {
			out.writeBytes(entry);
		}
		return out.toByteArray();
	}

	private static void writeU(ByteArrayOutputStream out, int value) {
		int rest = value;
		while (rest >= 0x80) {
			out.write((rest & 0x7f) | 0x80);
			rest >>>= 7;
		}
		out.write(rest);
	}

	// A non-negative value as a signed LEB: one byte holds 0..63, since bit 6 is the
	// sign.
	private static void writeS(ByteArrayOutputStream out, int value) {
		int rest = value;
		while (rest >= 0x40) {
			out.write((rest & 0x7f) | 0x80);
			rest >>>= 7;
		}
		out.write(rest);
	}

	// --- Parsing ---

	private static final class Parser {

		private final byte[] b;

		private int p;

		private int nextType;

		private int nextInstance;

		// The index immediates of the declarator being read, and how many types the
		// instance type it sits in has defined so far.
		private List<Site> sites = new ArrayList<>();

		private int localTypes;

		private Parser(byte[] block) {
			this.b = block;
		}

		private List<Group> parse() {
			List<Group> groups = new ArrayList<>();
			int groupEnd = 0;
			List<List<Projection>> aliasSections = new ArrayList<>();
			List<Declarator> declarators = null;
			int typeIndex = -1;
			while (this.p < this.b.length) {
				int id = this.b[this.p++] & 0xff;
				int size = readU();
				int bodyStart = this.p;
				int bodyEnd = bodyStart + size;
				switch (id) {
					case SEC_ALIAS -> {
						if (declarators != null) {
							throw new IllegalStateException(
									"ComponentImportBlock: an alias section follows an interface's type section");
						}
						aliasSections.add(aliasSection(bodyEnd));
					}
					case SEC_TYPE -> {
						if (declarators != null) {
							throw new IllegalStateException(
									"ComponentImportBlock: two type sections in one interface group");
						}
						declarators = typeSection(bodyEnd);
						typeIndex = this.nextType++;
					}
					case SEC_IMPORT -> {
						if (declarators == null) {
							throw new IllegalStateException(
									"ComponentImportBlock: an import section with no instance type before it");
						}
						String name = importSection(typeIndex, bodyEnd);
						List<Integer> definers = new ArrayList<>();
						for (int d = 0; d < declarators.size(); d++) {
							if (declarators.get(d).defines() >= 0) {
								definers.add(d);
							}
						}
						groups.add(new Group(name, List.copyOf(aliasSections), List.copyOf(declarators),
								List.copyOf(definers), typeIndex, this.nextInstance++));
						groupEnd = bodyEnd;
						aliasSections = new ArrayList<>();
						declarators = null;
					}
					default -> throw new IllegalStateException("ComponentImportBlock: unexpected section id " + id);
				}
				if (this.p != bodyEnd) {
					throw new IllegalStateException(
							"ComponentImportBlock: section " + id + " did not end where its size said");
				}
			}
			if (declarators != null || !aliasSections.isEmpty() || groupEnd != this.b.length) {
				throw new IllegalStateException("ComponentImportBlock: the block does not end with an import section");
			}
			return groups;
		}

		// alias section: `03 00 <instanceidx> <name>` per entry, each defining one
		// component type (the projection).
		private List<Projection> aliasSection(int bodyEnd) {
			List<Projection> projections = new ArrayList<>();
			int count = readU();
			for (int i = 0; i < count; i++) {
				int start = this.p;
				expect(0x03, "alias sort (only `type` occurs in an import block)");
				expect(0x00, "alias target (only `instance export` occurs in an alias section)");
				Site instance = index(Space.INSTANCE);
				String name = readName();
				projections.add(new Projection(start, this.p, instance, name, this.nextType++));
			}
			require(this.p == bodyEnd, "alias section has trailing bytes");
			return projections;
		}

		// type section: exactly one instance type, itself one component type.
		private List<Declarator> typeSection(int bodyEnd) {
			require(readU() == 1, "a type section in an import block declares exactly one type");
			require((this.b[this.p] & 0xff) == 0x42, "a type section in an import block declares an instance type");
			this.p++;
			List<Declarator> declarators = new ArrayList<>();
			this.localTypes = 0;
			int count = readU();
			for (int i = 0; i < count; i++) {
				declarators.add(instanceDecl());
			}
			require(this.p == bodyEnd, "type section has trailing bytes");
			return declarators;
		}

		// import section: exactly one instance import, taking one component instance
		// index, of the instance type just above it.
		private String importSection(int typeIndex, int bodyEnd) {
			require(readU() == 1, "an import section in an import block declares exactly one import");
			expect(0x00, "import name tag");
			String name = readName();
			expect(0x05, "import extern descriptor (only `instance` occurs in an import block)");
			require(readU() == typeIndex, "an import in an import block is of the instance type just above it");
			require(this.p == bodyEnd, "import section has trailing bytes");
			return name;
		}

		private Declarator instanceDecl() {
			int start = this.p;
			this.sites = new ArrayList<>();
			String export = "";
			boolean defines = true;
			int tag = this.b[this.p++] & 0xff;
			switch (tag) {
				case 0x01 -> defType();
				case 0x02 -> {
					expect(0x03, "instance alias sort (only `type` occurs)");
					expect(0x02, "instance alias target (only `outer` occurs inside an instance type)");
					require(readU() == 1, "an `alias outer` inside an import block's instance type has count 1");
					this.sites.add(index(Space.TYPE));
				}
				case 0x04 -> {
					expect(0x00, "export declarator name tag");
					export = readName();
					require(!export.isEmpty(), "an export declarator has a name");
					defines = externDesc();
				}
				default -> throw new IllegalStateException("ComponentImportBlock: unhandled instance declarator 0x"
						+ Integer.toHexString(tag) + " at offset " + (this.p - 1));
			}
			return new Declarator(start, this.p, defines ? this.localTypes++ : -1, export, List.copyOf(this.sites));
		}

		// A type DEFINITION: a function type, an instance type, or a value type.
		private void defType() {
			int tag = this.b[this.p] & 0xff;
			switch (tag) {
				case 0x40, 0x43 -> { // functype / async functype
					this.p++;
					int params = readU();
					for (int i = 0; i < params; i++) {
						skipName();
						valType();
					}
					int result = this.b[this.p++] & 0xff;
					if (result == 0x00) {
						valType();
					}
					else if (result == 0x01) {
						require(readU() == 0, "a named-result function type is not part of an import block's grammar");
					}
					else {
						throw new IllegalStateException("ComponentImportBlock: unhandled function result form 0x"
								+ Integer.toHexString(result));
					}
				}
				case 0x41, 0x42,
						0x3f ->
					throw new IllegalStateException(
							"ComponentImportBlock: a nested component/instance/resource type definition (0x"
									+ Integer.toHexString(tag) + ") is not part of an import block's grammar");
				default -> valType();
			}
		}

		// A value type: either a definition (a negative single-byte tag) or a reference
		// to a type in the enclosing instance type's OWN index space.
		private void valType() {
			int tag = this.b[this.p] & 0xff;
			if (tag < 0x64 || tag > 0x7f) {
				// A type index, encoded as a signed LEB whose value must be non-negative;
				// primitives are the negative encodings handled below.
				this.sites.add(index(Space.LOCAL_VALUE));
				return;
			}
			this.p++;
			switch (tag) {
				case 0x72 -> { // record
					int fields = readU();
					for (int i = 0; i < fields; i++) {
						skipName();
						valType();
					}
				}
				case 0x71 -> { // variant
					int cases = readU();
					for (int i = 0; i < cases; i++) {
						skipName();
						optionalValType();
						expect(0x00, "variant case `refines` field (always absent since it was removed)");
					}
				}
				case 0x70, 0x6b -> valType(); // list / option
				case 0x6f -> { // tuple
					int elements = readU();
					for (int i = 0; i < elements; i++) {
						valType();
					}
				}
				case 0x6e, 0x6d -> { // flags / enum
					int labels = readU();
					for (int i = 0; i < labels; i++) {
						skipName();
					}
				}
				case 0x6a -> { // result
					optionalValType();
					optionalValType();
				}
				case 0x69, 0x68 -> this.sites.add(index(Space.LOCAL)); // own / borrow
				case 0x66, 0x65 -> optionalValType(); // stream / future
				case 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x7b, 0x7c, 0x7d, 0x7e, 0x7f -> {
					// a primitive: string, char, f64, f32, u64, s64, u32, s32, u16, s16,
					// u8, s8, bool -- the tag byte is the whole encoding
				}
				default -> throw new IllegalStateException("ComponentImportBlock: unhandled value type 0x"
						+ Integer.toHexString(tag) + " at offset " + (this.p - 1));
			}
		}

		private void optionalValType() {
			int present = this.b[this.p++] & 0xff;
			if (present == 0x01) {
				valType();
			}
			else {
				require(present == 0x00, "an optional value type is present (0x01) or absent (0x00)");
			}
		}

		// An export's descriptor; answers whether the export extends the type space (a
		// type does, a function does not).
		private boolean externDesc() {
			int tag = this.b[this.p++] & 0xff;
			switch (tag) {
				case 0x01 -> {
					this.sites.add(index(Space.LOCAL)); // func: its type
					return false;
				}
				case 0x03 -> { // type bound
					int bound = this.b[this.p++] & 0xff;
					if (bound == 0x00) {
						this.sites.add(index(Space.LOCAL)); // eq
					}
					else {
						require(bound == 0x01, "a type bound is `eq` (0x00) or `sub resource` (0x01)");
					}
					return true;
				}
				default -> throw new IllegalStateException("ComponentImportBlock: unhandled extern descriptor 0x"
						+ Integer.toHexString(tag) + " inside an instance type (only functions and types occur)");
			}
		}

		// Reads one index immediate, with where it sits. A local index names a type the
		// instance type has already defined, which is what lets a prune find the
		// declarator behind it.
		private Site index(Space space) {
			int offset = this.p;
			long value = space == Space.LOCAL_VALUE ? readS() : readU();
			require(value >= 0, "a value type is either a primitive tag or a non-negative type index");
			if (space == Space.LOCAL || space == Space.LOCAL_VALUE) {
				require(value < this.localTypes, "a type index names a type declared above it");
			}
			return new Site(offset, this.p - offset, space, (int) value);
		}

		private void expect(int tag, String what) {
			int actual = this.b[this.p++] & 0xff;
			if (actual != tag) {
				throw new IllegalStateException(
						"ComponentImportBlock: expected " + what + " 0x" + Integer.toHexString(tag) + " but read 0x"
								+ Integer.toHexString(actual) + " at offset " + (this.p - 1));
			}
		}

		private void require(boolean condition, String what) {
			if (!condition) {
				throw new IllegalStateException("ComponentImportBlock: " + what + " (at offset " + this.p + ")");
			}
		}

		private void skipName() {
			int length = readU();
			this.p += length;
		}

		private String readName() {
			int len = readU();
			String name = new String(this.b, this.p, len, StandardCharsets.UTF_8);
			this.p += len;
			return name;
		}

		private int readU() {
			int value = 0;
			int shift = 0;
			while (true) {
				int x = this.b[this.p++] & 0xff;
				value |= (x & 0x7f) << shift;
				if ((x & 0x80) == 0) {
					return value;
				}
				shift += 7;
			}
		}

		private long readS() {
			long value = 0;
			int shift = 0;
			int x;
			do {
				x = this.b[this.p++] & 0xff;
				value |= ((long) (x & 0x7f)) << shift;
				shift += 7;
			}
			while ((x & 0x80) != 0);
			if (shift < 64 && (x & 0x40) != 0) {
				value |= -(1L << shift);
			}
			return value;
		}

	}

}
