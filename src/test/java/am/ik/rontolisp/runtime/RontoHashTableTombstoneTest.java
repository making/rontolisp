package am.ik.rontolisp.runtime;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the tombstone representation a JVM {@code remhash} leaves behind
 * ({@code .todo/855}): a removed pair keeps its slot in the insertion-order list with a
 * tombstone for a key, counted under {@code #dead}, until half the list is dead.
 */
class RontoHashTableTombstoneTest {

	@Test
	void removalsTombstoneAndCompactAtHalfDead() {
		Map<Object, Object> table = RontoHashTable.newTable();
		assertThat(RontoHashTable.deadCount(table)).isZero();
		assertThat(RontoHashTable.liveCount(table)).isZero();
		RontoHashTable.put(table, "a", 1);
		RontoHashTable.put(table, "b", 2);
		RontoHashTable.put(table, "c", 3);
		RontoHashTable.put(table, "d", 4);
		assertThat(RontoHashTable.liveCount(table)).isEqualTo(4);

		List<Object> ord = RontoHashTable.order(table);
		RontoHashTable.tombstone(table, (Object[]) ord.get(1));
		assertThat(RontoHashTable.deadCount(table)).isEqualTo(1);
		assertThat(RontoHashTable.liveCount(table)).isEqualTo(3);
		assertThat(RontoHashTable.liveValues(table)).hasSize(3);
		// Below the half-dead threshold nothing compacts: the list keeps its size.
		RontoHashTable.maybeCompact(table);
		assertThat(RontoHashTable.order(table)).hasSize(4);
		assertThat(RontoHashTable.deadCount(table)).isEqualTo(1);

		RontoHashTable.tombstone(table, (Object[]) ord.get(0));
		RontoHashTable.maybeCompact(table);
		assertThat(RontoHashTable.deadCount(table)).isZero();
		assertThat(RontoHashTable.liveCount(table)).isEqualTo(2);
		// The survivors keep insertion order.
		Object[] live = RontoHashTable.liveValues(table);
		assertThat(((Object[]) live[0])[0]).isEqualTo("c");
		assertThat(((Object[]) live[1])[0]).isEqualTo("d");
		assertThat(RontoHashTable.order(table)).hasSize(2);
	}

	@Test
	void aTableThatNeverRemovesCarriesNoDead() {
		Map<Object, Object> table = RontoHashTable.newTable();
		RontoHashTable.put(table, "a", 1);
		assertThat(RontoHashTable.liveValues(table)).hasSize(1);
		assertThat(RontoHashTable.deadCount(table)).isZero();
	}

}
