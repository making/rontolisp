package am.ik.jdbc.spin.postgres;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A statement's text as the host takes it: its parameters numbered and cast to their
 * types, and what its words tell of whether it answers rows.
 */
class SqlTest {

	@Test
	void aParameterIsNumberedAndCastToItsType() {
		Sql sql = Sql.parse("select * from t where a = ? and b = '?' and c ?? ? -- ?\n and d = $q$?$q$ and \"e?\" = ?",
				true);
		assertThat(sql.parameters()).isEqualTo(3);
		assertThat(sql.text(new String[] { "int4", null, "text::timestamptz" }))
			.isEqualTo("select * from t where a = ($1::int4) and b = '?' and c ? $2 -- ?\n and d = $q$?$q$ and"
					+ " \"e?\" = ($3::text::timestamptz)");
		// Described inside a query that answers none of its rows.
		assertThat(Sql.parse("select ? ; -- c ?\n /* ; */", true).describing(new String[] { "int4" }))
			.isEqualTo("SELECT * FROM (select ($1::int4)) AS described WHERE false");
		assertThat(Sql.parse("select 1 - 1 / 2;\n", true).describing(null))
			.isEqualTo("SELECT * FROM (select 1 - 1 / 2) AS described WHERE false");
		assertThat(Sql.parse("select '?' || ?", false).parameters()).isZero();
		assertThat(Sql.parse("select '?' || ?", false).text(null)).isEqualTo("select '?' || ?");
	}

	@Test
	void theWordsTellWhetherAStatementAnswersRows() {
		for (String rows : new String[] { "select 1", " SELECT 1", "(select 1) union (select 2)", "values (1)",
				"table t", "show timezone", "explain analyze insert into t values (1)",
				"with x as (delete from t returning id) select * from x", "insert into t values (1) returning id",
				"with x as (select 1) insert into t select * from x returning *", "/* c */ select 1" }) {
			assertThat(Sql.parse(rows, true).rows()).as(rows).isTrue();
			// A query is described inside another; a command is not.
			assertThat(Sql.parse(rows, true).query()).as(rows)
				.isEqualTo(!rows.contains("show") && !rows.contains("explain") && !rows.startsWith("insert")
						&& !rows.contains(") insert"));
		}
		for (String count : new String[] { "insert into t values (1)", "update t set a = 'returning'",
				"with x as (delete from t returning id) insert into u select * from x", "create table t (id int)",
				"delete from t where id in (select id from u)", "begin", "set search_path to x", "" }) {
			assertThat(Sql.parse(count, true).rows()).as(count).isFalse();
		}
		assertThat(Sql.parse("BEGIN", false).begins()).isTrue();
		assertThat(Sql.parse("start transaction", false).begins()).isTrue();
		assertThat(Sql.parse("select 'begin'", false).begins()).isFalse();
	}

	@Test
	void theRowsWrittenAreAnsweredByAReturningAdded() {
		Sql insert = Sql.parse("insert into t (a) values (?) ; -- one\n", true);
		assertThat(insert.answering(new String[0]).text(new String[] { "int4" }))
			.isEqualTo("insert into t (a) values (($1::int4)) RETURNING *");
		assertThat(insert.answering(new String[] { "id", "a\"b" }).text(new String[] { null }))
			.isEqualTo("insert into t (a) values ($1) RETURNING \"id\", \"a\"\"b\"");
		assertThat(insert.answering(new String[0]).rows()).isTrue();
		// Nothing is added to a statement that returns its rows already, or writes none.
		Sql returning = Sql.parse("update t set a = 1 returning a", true);
		assertThat(returning.answering(new String[0])).isSameAs(returning);
		Sql select = Sql.parse("select 1", true);
		assertThat(select.answering(new String[0])).isSameAs(select);
	}

}
