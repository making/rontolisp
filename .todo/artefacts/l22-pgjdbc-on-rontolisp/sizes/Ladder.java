import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

// The measurement's second probe: what "done" asks of a driver -- the four logins, CRUD,
// a prepared statement run twice, non-ASCII text.
public class Ladder {

    static final String URL = "jdbc:postgresql://127.0.0.1:55432/postgres";

    static void probe(String user, String password) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, user, password);
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select current_user, 42, 'hello'")) {
            while (r.next()) {
                System.out.println(r.getString(1) + " " + r.getInt(2) + " " + r.getString(3));
            }
        }
    }

    static void rows(Statement s, String table) throws SQLException {
        try (ResultSet r = s.executeQuery("select id, name from " + table + " order by id")) {
            StringBuilder line = new StringBuilder();
            while (r.next()) {
                line.append('(').append(r.getInt(1)).append(' ').append(r.getString(2)).append(')');
            }
            System.out.println(line);
        }
    }

    public static void main(String[] args) throws Exception {
        probe("trustuser", null);
        probe("passuser", "passpw");
        probe("md5user", "md5pw");
        probe("scramuser", "scrampw");
        String table = args.length > 0 ? args[0] : "items_jdk";
        try (Connection c = DriverManager.getConnection(URL, "trustuser", null); Statement s = c.createStatement()) {
            s.execute("drop table if exists " + table);
            s.execute("create table " + table + " (id integer primary key, name text)");
            System.out.println(s.executeUpdate("insert into " + table + " (id, name) values (1, 'alpha'), (2, 'beta')"));
            rows(s, table);
            System.out.println(s.executeUpdate("update " + table + " set name = 'gamma' where id = 2"));
            rows(s, table);
            System.out.println(s.executeUpdate("delete from " + table + " where id = 1"));
            rows(s, table);
            try (PreparedStatement p = c.prepareStatement("select id from " + table + " where name = ?")) {
                for (String name : new String[] { "gamma", "nope" }) {
                    p.setString(1, name);
                    try (ResultSet r = p.executeQuery()) {
                        System.out.println(r.next() ? "hit " + r.getInt(1) : "miss");
                    }
                }
            }
            try (PreparedStatement p = c.prepareStatement("insert into " + table + " values (?, ?)")) {
                p.setInt(1, 3);
                p.setString(2, "お茶🍵");
                System.out.println(p.executeUpdate());
            }
            s.executeUpdate("insert into " + table + " values (4, 'こんにちは')");
            rows(s, table);
            try (ResultSet r = s.executeQuery("select id, length(name) from " + table + " order by id")) {
                while (r.next()) {
                    System.out.println(r.getInt(1) + " " + r.getInt(2));
                }
            }
            s.execute("drop table " + table);
        }
    }
}
