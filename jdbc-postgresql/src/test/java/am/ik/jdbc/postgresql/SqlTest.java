package am.ik.jdbc.postgresql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTest {

	@Test
	void aQuestionMarkOutsideEveryQuoteIsAParameter() {
		String[][] cases = {
				// what JDBC writes, what the server takes
				{ "select ?, ?", "select $1, $2" }, { "select '?', ?", "select '?', $1" },
				{ "select 'it''s ?', ?", "select 'it''s ?', $1" },
				{ "select \"a?\"\"b\", ?", "select \"a?\"\"b\", $1" },
				{ "select ? -- why?\n, ?", "select $1 -- why?\n, $2" },
				{ "select ? /* a /* nested ? */ ? */, ?", "select $1 /* a /* nested ? */ ? */, $2" },
				{ "select $$?$$, $tag$ ? $$ ? $tag$, ?", "select $$?$$, $tag$ ? $$ ? $tag$, $1" },
				{ "select E'\\'?', ?", "select E'\\'?', $1" }, { "select '\\', ?", "select '\\', $1" },
				{ "select a ?? b, ?", "select a ? b, $1" }, { "select a$1, ?", "select a$1, $1" },
				{ "select ?::int", "select $1::int" } };
		for (String[] one : cases) {
			assertThat(Sql.parse(one[0], true).text).as(one[0]).isEqualTo(one[1]);
		}
		assertThat(Sql.parse("select ?, '?', ?", true).parameters).isEqualTo(2);
		assertThat(Sql.parse("select ?", false).text).isEqualTo("select ?");
	}

	@Test
	void theRowsAStatementWritesAreAskedForWithReturning() {
		Sql insert = Sql.parse("insert into t (a) values (?) ; ", true);
		assertThat(insert.answering(new String[0]).text).isEqualTo("insert into t (a) values ($1) RETURNING *");
		assertThat(insert.answering(new String[] { "id", "odd\"name" }).text)
			.isEqualTo("insert into t (a) values ($1) RETURNING \"id\", \"odd\"\"name\"");
		// One that answers rows already, and one that writes none, stay as they are.
		Sql returning = Sql.parse("/* c */ update t set a = 1 returning a", true);
		assertThat(returning.answering(new String[0])).isSameAs(returning);
		Sql select = Sql.parse("select 'insert returning'", true);
		assertThat(select.answering(new String[0])).isSameAs(select);
		assertThat(Sql.parse("delete from t where a = 'returning'", true).answering(null).text).endsWith("RETURNING *");
	}

}
