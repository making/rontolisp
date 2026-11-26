package am.ik.jdbc.postgresql;

import java.sql.SQLException;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetTest {

	@Test
	void theFormsOfAUrl() throws Exception {
		Properties info = new Properties();
		info.setProperty("user", "alice");
		info.setProperty("password", "from the properties");
		String[][] cases = {
				// the url, the host, the port, the database
				{ "jdbc:postgresql:shop", "localhost", "5432", "shop" },
				{ "jdbc:postgresql:/", "localhost", "5432", "alice" },
				{ "jdbc:postgresql://db.example/shop", "db.example", "5432", "shop" },
				{ "jdbc:postgresql://db.example:6432/", "db.example", "6432", "alice" },
				{ "jdbc:postgresql://db.example:6432", "db.example", "6432", "alice" },
				{ "jdbc:postgresql://[::1]:6432/shop", "::1", "6432", "shop" },
				{ "jdbc:postgresql://[::1]/shop", "::1", "5432", "shop" },
				{ "jdbc:postgresql:///shop?ApplicationName=a", "localhost", "5432", "shop" },
				{ "jdbc:postgresql://db.example/my%20shop", "db.example", "5432", "my shop" } };
		for (String[] one : cases) {
			Target target = Target.parse(one[0], info);
			assertThat(target.host + " " + target.port + " " + target.database).as(one[0])
				.isEqualTo(one[1] + " " + one[2] + " " + one[3]);
			assertThat(target.user).isEqualTo("alice");
		}
		assertThat(Target.parse("jdbc:mysql://db.example/shop", info)).isNull();
	}

	@Test
	void theSettingsOfAUrlWinOverTheProperties() throws Exception {
		Properties info = new Properties();
		info.setProperty("user", "alice");
		info.setProperty("password", "from the properties");
		info.setProperty("socketTimeout", "2");
		Target target = Target.parse("jdbc:postgresql://db.example/shop?user=bob&password=p%26%3Dw+%2B%E8%8C%B6&flag",
				info);
		assertThat(target.user).isEqualTo("bob");
		assertThat(target.password).isEqualTo("p&=w +茶");
		assertThat(target.setting("flag")).isEmpty();
		assertThat(target.millis("socketTimeout")).isEqualTo(2000);
		assertThat(target.millis("connectTimeout")).isZero();
	}

	@Test
	void whatAUrlMayNotName() {
		Properties info = new Properties();
		info.setProperty("user", "alice");
		assertThatThrownBy(() -> Target.parse("jdbc:postgresql://a:5432,b:5432/shop", info))
			.isInstanceOf(SQLException.class)
			.hasMessageContaining("more than one server");
		assertThatThrownBy(() -> Target.parse("jdbc:postgresql://a:port/shop", info)).hasMessageContaining("port");
		assertThatThrownBy(() -> Target.parse("jdbc:postgresql://a/shop?x=%zz", info))
			.hasMessageContaining("percent sign");
		assertThatThrownBy(() -> Target.parse(null, info)).isInstanceOf(SQLException.class);
	}

}
