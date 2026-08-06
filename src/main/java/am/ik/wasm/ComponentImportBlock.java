package am.ik.wasm;

import java.io.ByteArrayOutputStream;
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
 * {@link WasmTreeShaker} has run, which of the block's interfaces anything still reaches;
 * this class turns that knowledge into a smaller block, dropping whole interface groups
 * and renumbering what survives.
 *
 * <h2>The grammar it relies on</h2>
 *
 * The block is a flat run of sections of exactly three kinds, which fall into
 * per-interface <strong>groups</strong> of the shape
 * <code>[alias]* [type] [import]</code>:
 * <ul>
 * <li>an <em>alias</em> section (id 6) projects a type out of an ALREADY-IMPORTED
 * instance ({@code alias type (instance i) "name"}) -- this is how one interface's
 * instance type names another's resource;</li>
 * <li>a <em>type</em> section (id 7) with exactly one entry: the interface's instance
 * type, whose declarators may {@code alias outer} the projections just above it;</li>
 * <li>an <em>import</em> section (id 10) with exactly one entry: the instance import
 * itself, naming the interface and the instance type.</li>
 * </ul>
 *
 * Only <strong>three</strong> index immediates in the whole block point OUT of the entry
 * they sit in, and they are exactly what pruning has to rewrite: the alias section's
 * instance index, the {@code alias outer} declarator's type index, and the import's
 * instance-type index. Everything else -- {@code own}/{@code borrow} payloads, an export
 * declarator's {@code eq} bound, a function type reference -- indexes the instance type's
 * OWN space and is untouched by dropping a neighbouring group.
 * <p>
 * The walk that establishes this is complete: every byte of the block is classified, and
 * an unrecognized tag (or an index that no longer fits one LEB byte) throws rather than
 * emit a component that validates while naming the wrong instance.
 *
 * <h2>What the caller still owes</h2>
 *
 * The instance index space the block opens is CONTINUED by the wrapper's own sections
 * after it -- its {@code alias func (instance i) "..."} entries, its user-import
 * instances, its exported instances. {@link Pruned#instanceOf()} is therefore not a
 * courtesy: a wrapper that keeps its own hardcoded instance numbers after pruning emits a
 * component that validates and binds the wrong interface.
 */
public final class ComponentImportBlock {

	private static final int SEC_ALIAS = 6;

	private static final int SEC_TYPE = 7;

	private static final int SEC_IMPORT = 10;

	/** How a rewritable index immediate is interpreted. */
	private enum Space {

		/** A component instance index (an alias section's target). */
		INSTANCE,
		/** A component type index (an {@code alias outer}, an import's instance type). */
		TYPE

	}

	/** One index immediate to rewrite, at an offset relative to the block's start. */
	private record Site(int offset, Space space, int index) {
	}

	/**
	 * One interface's declarations: the byte range of its
	 * {@code [alias]* [type] [import]} sections, the component type indices they define,
	 * and the component instance index the import takes.
	 *
	 * @param interfaceId the imported interface's fully-qualified id
	 * @param start offset of the group's first section within the block
	 * @param end end offset (exclusive) of the group's last section
	 * @param firstTypeIndex the first component type index the group defines
	 * @param typeCount how many component types the group defines (its alias projections
	 * plus its one instance type)
	 * @param instanceIndex the component instance index the group's import takes
	 * @param sites the index immediates within {@code [start, end)} that pruning rewrites
	 */
	private record Group(String interfaceId, int start, int end, int firstTypeIndex, int typeCount, int instanceIndex,
			List<Site> sites) {
	}

	/**
	 * The outcome of pruning: the new block bytes plus the index maps every downstream
	 * reference has to be rebased on.
	 *
	 * @param bytes the pruned block
	 * @param instanceOf the surviving interfaces' NEW component instance indices, in
	 * block order
	 * @param typeCount how many component types the pruned block defines, i.e. the first
	 * free component type index for whatever the wrapper emits next
	 */
	public record Pruned(byte[] bytes, LinkedHashMap<String, Integer> instanceOf, int typeCount) {
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
	 * Drops every interface not in {@code keep} nor reached from it, and renumbers the
	 * survivors. An interface whose instance type projects a type out of another's
	 * instance pulls that other one in -- a resource is its defining interface's type, so
	 * the projection cannot outlive it.
	 * @param keep the interfaces to keep; unknown names are an
	 * {@link IllegalArgumentException}
	 * @return the pruned block and the index maps
	 */
	public Pruned prune(Set<String> keep) {
		Map<String, Group> byId = new LinkedHashMap<>();
		Map<Integer, Group> byInstance = new LinkedHashMap<>();
		Map<Integer, Group> byType = new LinkedHashMap<>();
		for (Group g : this.groups) {
			byId.put(g.interfaceId(), g);
			byInstance.put(g.instanceIndex(), g);
			for (int t = 0; t < g.typeCount(); t++) {
				byType.put(g.firstTypeIndex() + t, g);
			}
		}
		for (String id : keep) {
			if (!byId.containsKey(id)) {
				throw new IllegalArgumentException("ComponentImportBlock: the block does not import '" + id
						+ "' (it imports " + byId.keySet() + ")");
			}
		}
		// Closure over the projection edges: a kept group needs every group whose
		// instance
		// it aliases a type out of, and whose type its instance type names.
		Set<String> live = new LinkedHashSet<>(keep);
		boolean grew = true;
		while (grew) {
			grew = false;
			for (Group g : this.groups) {
				if (!live.contains(g.interfaceId())) {
					continue;
				}
				for (Site site : g.sites()) {
					Group owner = site.space() == Space.INSTANCE ? byInstance.get(site.index())
							: byType.get(site.index());
					if (owner == null) {
						throw new IllegalStateException("ComponentImportBlock: " + site.space() + " index "
								+ site.index() + " named by '" + g.interfaceId() + "' belongs to no group");
					}
					if (owner != g && live.add(owner.interfaceId())) {
						grew = true;
					}
				}
			}
		}
		// New numbering, in block order.
		Map<Integer, Integer> instanceRemap = new LinkedHashMap<>();
		Map<Integer, Integer> typeRemap = new LinkedHashMap<>();
		LinkedHashMap<String, Integer> instanceOf = new LinkedHashMap<>();
		int nextInstance = 0;
		int nextType = 0;
		for (Group g : this.groups) {
			if (!live.contains(g.interfaceId())) {
				continue;
			}
			instanceRemap.put(g.instanceIndex(), nextInstance);
			instanceOf.put(g.interfaceId(), nextInstance);
			nextInstance++;
			for (int t = 0; t < g.typeCount(); t++) {
				typeRemap.put(g.firstTypeIndex() + t, nextType++);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Group g : this.groups) {
			if (!live.contains(g.interfaceId())) {
				continue;
			}
			byte[] bytes = new byte[g.end() - g.start()];
			System.arraycopy(this.block, g.start(), bytes, 0, bytes.length);
			for (Site site : g.sites()) {
				Integer replacement = (site.space() == Space.INSTANCE ? instanceRemap : typeRemap).get(site.index());
				if (replacement == null) {
					throw new IllegalStateException("ComponentImportBlock: '" + g.interfaceId() + "' still names a "
							+ site.space() + " index (" + site.index() + ") that pruning dropped");
				}
				// Every index in a generated block fits one LEB byte, and pruning only
				// lowers them; a wider one would need the section size re-encoded, so it
				// throws instead of silently truncating.
				if (replacement >= 0x80) {
					throw new IllegalStateException("ComponentImportBlock: rewritten " + site.space() + " index "
							+ replacement + " no longer fits one LEB byte; the block needs a re-encoding pass");
				}
				bytes[site.offset() - g.start()] = (byte) replacement.intValue();
			}
			out.writeBytes(bytes);
		}
		return new Pruned(out.toByteArray(), instanceOf, nextType);
	}

	// --- Parsing ---

	private static final class Parser {

		private final byte[] b;

		private int p;

		private int nextType;

		private int nextInstance;

		private Parser(byte[] block) {
			this.b = block;
		}

		private List<Group> parse() {
			List<Group> groups = new ArrayList<>();
			int groupStart = 0;
			int groupFirstType = 0;
			List<Site> sites = new ArrayList<>();
			boolean sawType = false;
			while (this.p < this.b.length) {
				int id = this.b[this.p++] & 0xff;
				int size = readU();
				int bodyStart = this.p;
				int bodyEnd = bodyStart + size;
				switch (id) {
					case SEC_ALIAS -> {
						if (sawType) {
							throw new IllegalStateException(
									"ComponentImportBlock: an alias section follows an interface's type section");
						}
						aliasSection(sites, bodyEnd);
					}
					case SEC_TYPE -> {
						if (sawType) {
							throw new IllegalStateException(
									"ComponentImportBlock: two type sections in one interface group");
						}
						typeSection(sites, bodyEnd);
						sawType = true;
					}
					case SEC_IMPORT -> {
						if (!sawType) {
							throw new IllegalStateException(
									"ComponentImportBlock: an import section with no instance type before it");
						}
						String id2 = importSection(sites, bodyEnd);
						groups.add(new Group(id2, groupStart, bodyEnd, groupFirstType, this.nextType - groupFirstType,
								this.nextInstance++, List.copyOf(sites)));
						groupStart = bodyEnd;
						groupFirstType = this.nextType;
						sites = new ArrayList<>();
						sawType = false;
					}
					default -> throw new IllegalStateException("ComponentImportBlock: unexpected section id " + id);
				}
				if (this.p != bodyEnd) {
					throw new IllegalStateException(
							"ComponentImportBlock: section " + id + " did not end where its size said");
				}
			}
			if (sawType || !sites.isEmpty() || groupStart != this.b.length) {
				throw new IllegalStateException("ComponentImportBlock: the block does not end with an import section");
			}
			return groups;
		}

		// alias section: `03 00 <instanceidx> <name>` per entry, each defining one
		// component type (the projection).
		private void aliasSection(List<Site> sites, int bodyEnd) {
			int count = readU();
			for (int i = 0; i < count; i++) {
				expect(0x03, "alias sort (only `type` occurs in an import block)");
				expect(0x00, "alias target (only `instance export` occurs in an alias section)");
				sites.add(new Site(this.p, Space.INSTANCE, readU1()));
				skipName();
				this.nextType++;
			}
			require(this.p == bodyEnd, "alias section has trailing bytes");
		}

		// type section: exactly one instance type, itself one component type.
		private void typeSection(List<Site> sites, int bodyEnd) {
			require(readU() == 1, "a type section in an import block declares exactly one type");
			require((this.b[this.p] & 0xff) == 0x42, "a type section in an import block declares an instance type");
			this.p++;
			int decls = readU();
			for (int i = 0; i < decls; i++) {
				instanceDecl(sites);
			}
			this.nextType++;
			require(this.p == bodyEnd, "type section has trailing bytes");
		}

		// import section: exactly one instance import, taking one component instance
		// index.
		private String importSection(List<Site> sites, int bodyEnd) {
			require(readU() == 1, "an import section in an import block declares exactly one import");
			expect(0x00, "import name tag");
			String name = readName();
			expect(0x05, "import extern descriptor (only `instance` occurs in an import block)");
			sites.add(new Site(this.p, Space.TYPE, readU1()));
			require(this.p == bodyEnd, "import section has trailing bytes");
			return name;
		}

		private void instanceDecl(List<Site> sites) {
			int tag = this.b[this.p++] & 0xff;
			switch (tag) {
				case 0x01 -> defType();
				case 0x02 -> {
					expect(0x03, "instance alias sort (only `type` occurs)");
					expect(0x02, "instance alias target (only `outer` occurs inside an instance type)");
					require(readU() == 1, "an `alias outer` inside an import block's instance type has count 1");
					sites.add(new Site(this.p, Space.TYPE, readU1()));
				}
				case 0x04 -> {
					expect(0x00, "export declarator name tag");
					skipName();
					externDesc();
				}
				default -> throw new IllegalStateException("ComponentImportBlock: unhandled instance declarator 0x"
						+ Integer.toHexString(tag) + " at offset " + (this.p - 1));
			}
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
		// to
		// a type in the enclosing instance type's OWN index space (which pruning leaves
		// alone).
		private void valType() {
			int tag = this.b[this.p] & 0xff;
			if (tag < 0x64 || tag > 0x7f) {
				// A type index, encoded as a signed LEB whose value must be non-negative;
				// primitives are the negative encodings handled below.
				long index = readS();
				require(index >= 0, "a value type is either a primitive tag or a non-negative type index");
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
				case 0x69, 0x68 -> readU(); // own / borrow: a resource in the LOCAL space
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

		private void externDesc() {
			int tag = this.b[this.p++] & 0xff;
			switch (tag) {
				case 0x01 -> readU(); // func: a type in the LOCAL space
				case 0x03 -> { // type bound
					int bound = this.b[this.p++] & 0xff;
					if (bound == 0x00) {
						readU(); // eq: a type in the LOCAL space
					}
					else {
						require(bound == 0x01, "a type bound is `eq` (0x00) or `sub resource` (0x01)");
					}
				}
				default -> throw new IllegalStateException("ComponentImportBlock: unhandled extern descriptor 0x"
						+ Integer.toHexString(tag) + " inside an instance type (only functions and types occur)");
			}
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

		// An index that pruning rewrites in place, so it must occupy exactly one byte.
		private int readU1() {
			int value = this.b[this.p] & 0xff;
			require(value < 0x80, "a rewritable index fits one LEB byte");
			this.p++;
			return value;
		}

		private void skipName() {
			int length = readU();
			this.p += length;
		}

		private String readName() {
			int len = readU();
			String name = new String(this.b, this.p, len, java.nio.charset.StandardCharsets.UTF_8);
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
