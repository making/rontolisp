package am.ik.rontolisp.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JVM table's shape ({@code .kb/hash-tables.md}, "Representation" and "Copies
 * share structure"): a chained table until its first copy and a trie of hash chains from
 * then on (flat again once cleared), the pairs in insertion order, removals leaving dead
 * slots until half are, and a copy that shares every node with its source while neither
 * sees the other's stores.
 */
class RontoHashTableTest {

	@Test
	void aStoreFindsItsKeyARestoreReplacesOnlyTheValueAndRemovalsKeepTheOrder() {
		RontoHashTable table = RontoHashTable.newTable();
		for (String key : List.of("a", "b", "c", "d")) {
			RontoHashTable.put(table, key, key.toUpperCase());
		}
		RontoHashTable.put(table, "b", "B2");
		assertThat(RontoHashTable.get(table, "b", "absent")).isEqualTo("B2");
		assertThat(RontoHashTable.get(table, "z", "absent")).isEqualTo("absent");
		assertThat(walk(table)).isEqualTo(List.of("a=A", "b=B2", "c=C", "d=D"));

		remove(table, "b");
		assertThat(RontoHashTable.count(table)).isEqualTo(3);
		assertThat(walk(table)).isEqualTo(List.of("a=A", "c=C", "d=D"));
		// The removal left a dead slot rather than moving the entries after it: shifting
		// the order per removal made removing every entry oldest-first quadratic.
		assertThat(List.of(pos(table, "a"), pos(table, "c"), pos(table, "d"))).containsExactly(0, 2, 3);
		// A removed key stored again goes last, as a fresh entry.
		RontoHashTable.put(table, "b", "B3");
		assertThat(walk(table)).isEqualTo(List.of("a=A", "c=C", "d=D", "b=B3"));
		assertThat(pos(table, "b")).isEqualTo(4);
		// Half the slots dead: the table rebuilds, keeping the order.
		remove(table, "a");
		assertThat(pos(table, "d")).isEqualTo(3);
		remove(table, "c");
		assertThat(List.of(pos(table, "d"), pos(table, "b"))).containsExactly(0, 1);
		remove(table, "d");
		assertThat(walk(table)).isEqualTo(List.of("b=B3"));
		RontoHashTable.clear(table);
		assertThat(RontoHashTable.count(table)).isZero();
		assertThat(RontoHashTable.liveValues(table)).isEmpty();
	}

	@Test
	void aCopyAndItsSourceChangeIndependently() {
		RontoHashTable source = RontoHashTable.newTable();
		for (int i = 0; i < 100; i++) {
			RontoHashTable.put(source, (long) i, "v" + i);
		}
		RontoHashTable copy = RontoHashTable.copyEqual(source);
		RontoHashTable.put(copy, 5L, "copy");
		RontoHashTable.put(copy, 100L, "fresh");
		remove(copy, 7L);
		RontoHashTable.put(source, 6L, "source");
		remove(source, 8L);
		RontoHashTable.put(source, 101L, "other");

		assertThat(RontoHashTable.get(source, 5L, "absent")).isEqualTo("v5");
		assertThat(RontoHashTable.get(source, 7L, "absent")).isEqualTo("v7");
		assertThat(RontoHashTable.get(source, 100L, "absent")).isEqualTo("absent");
		assertThat(RontoHashTable.get(copy, 6L, "absent")).isEqualTo("v6");
		assertThat(RontoHashTable.get(copy, 8L, "absent")).isEqualTo("v8");
		assertThat(RontoHashTable.get(copy, 101L, "absent")).isEqualTo("absent");
		assertThat(RontoHashTable.count(source)).isEqualTo(100);
		assertThat(RontoHashTable.count(copy)).isEqualTo(100);
		// The copy walks in its source's order, then its own stores.
		assertThat(walk(copy).subList(0, 6)).isEqualTo(List.of("0=v0", "1=v1", "2=v2", "3=v3", "4=v4", "5=copy"));
		assertThat(walk(copy).get(99)).isEqualTo("100=fresh");
		assertThat(walk(source).get(99)).isEqualTo("101=other");
	}

	// An equalp table's folded pair keeps the key as first stored in its third slot; a
	// store into a table sharing the pair copies all three.
	@Test
	void aFoldedPairKeepsItsKeyAsFirstStoredThroughAStoreIntoASharedTable() {
		RontoHashTable table = RontoHashTable.newTable();
		int hash = "\"KEY\"".hashCode();
		RontoHashTable.add(table, hash, new Object[] { "\"KEY\"", 1L, "\"Key\"" });
		RontoHashTable shared = RontoHashTable.copyEqual(table);
		RontoHashTable.setValue(table, RontoHashTable.find(table, hash), 2L);

		assertThat(Arrays.deepToString(RontoHashTable.liveValues(table))).isEqualTo("[[\"KEY\", 2, \"Key\"]]");
		assertThat(Arrays.deepToString(RontoHashTable.liveValues(shared))).isEqualTo("[[\"KEY\", 1, \"Key\"]]");
	}

	@Test
	void keysSharingAWholeHashChainAndKeysSharingLowBitsSplit() {
		RontoHashTable table = RontoHashTable.newTable();
		// One chain of 2,000 keys of one hash, and keys whose hashes differ only above
		// bit 20 (a double's hashCode often is), each on its own path.
		for (int i = 0; i < 2000; i++) {
			RontoHashTable.put(table, new Key("same" + i, 42), i);
		}
		for (int i = 0; i < 64; i++) {
			RontoHashTable.put(table, new Key("high" + i, i << 26), i);
		}
		RontoHashTable copy = RontoHashTable.copyEqual(table);
		// Changing an entry deep in the chain rebuilds the entries ahead of it, in a copy
		// of the shared chain.
		RontoHashTable.put(copy, new Key("same1000", 42), "changed");
		remove(copy, new Key("same1500", 42));
		remove(copy, new Key("high33", 33 << 26));

		assertThat(RontoHashTable.get(table, new Key("same1000", 42), "absent")).isEqualTo(1000);
		assertThat(RontoHashTable.get(table, new Key("same1500", 42), "absent")).isEqualTo(1500);
		assertThat(RontoHashTable.get(table, new Key("high33", 33 << 26), "absent")).isEqualTo(33);
		assertThat(RontoHashTable.get(copy, new Key("same1000", 42), "absent")).isEqualTo("changed");
		assertThat(RontoHashTable.get(copy, new Key("same1500", 42), "absent")).isEqualTo("absent");
		assertThat(RontoHashTable.get(copy, new Key("same1999", 42), "absent")).isEqualTo(1999);
		assertThat(RontoHashTable.get(copy, new Key("high33", 33 << 26), "absent")).isEqualTo("absent");
		assertThat(RontoHashTable.get(copy, new Key("high63", 63 << 26), "absent")).isEqualTo(63);
		assertThat(RontoHashTable.count(table)).isEqualTo(2064);
		assertThat(RontoHashTable.count(copy)).isEqualTo(2062);
	}

	// Every operation on a family of tables copied from one another, against a
	// LinkedHashMap per table -- whose put keeps a key's place and whose remove then put
	// moves it last, the order maphash walks. Keys collide on purpose: 40 hashes for 400
	// keys, so chains, splits and rebuilds all happen in shared and owned nodes, and in a
	// flat table's buckets, which a clear turns a table back into.
	@Test
	void aRandomWorkloadOverCopiesMatchesOrderedMaps() {
		Random random = new Random(20261011L);
		List<RontoHashTable> tables = new ArrayList<>();
		List<LinkedHashMap<Key, Integer>> models = new ArrayList<>();
		tables.add(RontoHashTable.newTable());
		models.add(new LinkedHashMap<>());
		for (int step = 0; step < 60_000; step++) {
			int which = random.nextInt(tables.size());
			RontoHashTable table = tables.get(which);
			LinkedHashMap<Key, Integer> model = models.get(which);
			int k = random.nextInt(400);
			Key key = new Key("k" + k, k % 40);
			int op = random.nextInt(1000);
			if (op < 550) {
				RontoHashTable.put(table, key, step);
				model.put(key, step);
			}
			else if (op < 850) {
				boolean present = model.remove(key) != null;
				assertThat(remove(table, key)).isEqualTo(present);
			}
			else if (op < 880 && tables.size() < 24) {
				tables.add(RontoHashTable.copyEqual(table));
				models.add(new LinkedHashMap<>(model));
			}
			else if (op < 882) {
				RontoHashTable.clear(table);
				model.clear();
			}
			else {
				assertThat(RontoHashTable.get(table, key, "absent"))
					.isEqualTo(model.containsKey(key) ? model.get(key) : "absent");
			}
			if (step % 997 == 0) {
				for (int i = 0; i < tables.size(); i++) {
					assertThat(walk(tables.get(i))).as("table %d at step %d", i, step).isEqualTo(walk(models.get(i)));
				}
			}
		}
		for (int i = 0; i < tables.size(); i++) {
			assertThat(walk(tables.get(i))).as("table %d", i).isEqualTo(walk(models.get(i)));
			assertThat(RontoHashTable.count(tables.get(i))).isEqualTo(models.get(i).size());
		}
	}

	// A map grown one assoc at a time is a copy and a store per key: O(log n) each here,
	// so 200,000 of them take well under a second. A copy that walks its source makes
	// this 2 * 10^10 entry copies -- A REGRESSION IS A HANG, which the timeout cuts.
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void aTableGrownOneCopyAndOneStoreAtATimeStaysCheap() {
		RontoHashTable table = RontoHashTable.newTable();
		RontoHashTable first = table;
		for (long i = 0; i < 200_000; i++) {
			table = RontoHashTable.copyEqual(table);
			RontoHashTable.put(table, i, i * i);
		}
		assertThat(RontoHashTable.count(table)).isEqualTo(200_000);
		assertThat(RontoHashTable.get(table, 199_999L, "absent")).isEqualTo(199_999L * 199_999L);
		assertThat(RontoHashTable.count(first)).isZero();
	}

	// A table nobody changes is read and copied from many threads at once, the way Ring
	// request threads share a Clojure map: each thread grows its own copy, and the shared
	// table reads the same throughout.
	@Test
	void threadsCopyingAndReadingOneTableLeaveItUnchanged() throws Exception {
		RontoHashTable shared = RontoHashTable.newTable();
		for (long i = 0; i < 5_000; i++) {
			RontoHashTable.put(shared, i, "v" + i);
		}
		List<String> before = walk(shared);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				long offset = 10_000L * (t + 1);
				results.add(pool.submit(() -> {
					RontoHashTable mine = shared;
					for (long i = 0; i < 2_000; i++) {
						mine = RontoHashTable.copyEqual(mine);
						RontoHashTable.put(mine, offset + i, "mine");
						RontoHashTable.put(mine, i, "changed");
						if (!"v4999".equals(RontoHashTable.get(shared, 4_999L, "absent"))) {
							return false;
						}
					}
					return RontoHashTable.count(mine) == 7_000 && RontoHashTable.liveValues(shared).length == 5_000;
				}));
			}
			for (Future<Boolean> result : results) {
				assertThat(result.get(60, TimeUnit.SECONDS)).isTrue();
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(walk(shared)).isEqualTo(before);
	}

	// A key of a chosen hash, compared by both fields.
	private record Key(String name, int hash) {

		@Override
		public int hashCode() {
			return this.hash;
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	private static boolean remove(RontoHashTable table, Object key) {
		for (RontoHashTable.Entry entry = RontoHashTable.find(table,
				key.hashCode()); entry != RontoHashTable.END; entry = entry.next) {
			if (key.equals(entry.pair[0])) {
				RontoHashTable.remove(table, entry);
				return true;
			}
		}
		return false;
	}

	// The key's slot in the insertion order.
	private static int pos(RontoHashTable table, Object key) {
		for (RontoHashTable.Entry entry = RontoHashTable.find(table,
				key.hashCode()); entry != RontoHashTable.END; entry = entry.next) {
			if (key.equals(entry.pair[0])) {
				return entry.pos;
			}
		}
		throw new AssertionError("no entry for " + key);
	}

	private static List<String> walk(RontoHashTable table) {
		List<String> entries = new ArrayList<>();
		for (Object pair : RontoHashTable.liveValues(table)) {
			Object[] entry = (Object[]) pair;
			entries.add(entry[0] + "=" + entry[1]);
		}
		return entries;
	}

	private static List<String> walk(Map<?, ?> model) {
		List<String> entries = new ArrayList<>();
		for (Map.Entry<?, ?> entry : model.entrySet()) {
			entries.add(entry.getKey() + "=" + entry.getValue());
		}
		return entries;
	}

}
