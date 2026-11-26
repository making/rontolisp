// Notes kept in PostgreSQL through plain JDBC: a connection from DriverManager, a table,
// rows written by a PreparedStatement and read back into records, an update, a delete, a
// transaction committed and one rolled back, and text outside ASCII.
//
// The program names no class of a driver. DriverManager finds the one on the class path
// (or, compiled by rontolisp, on the source path) that takes a jdbc:postgresql: URL.
//
// DATABASE_URL is where the server is, in either of two forms:
//
//   jdbc:postgresql://127.0.0.1:54329/postgres?user=postgres
//   postgresql://postgres@127.0.0.1:54329/postgres     (the form examples/db/ reads)
//
// The second's user and password become the connection's properties.
package jdbc;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

public class Notes {

    record Note(long id, String title, boolean done, LocalDate due) {
    }

    // The statements of the table, each a method; the connection is the caller's.
    static class NoteRepository {

        private final Connection connection;

        NoteRepository(Connection connection) {
            this.connection = connection;
        }

        // TEMPORARY: the table is the session's and goes when the connection closes, so
        // the program leaves nothing behind and two runs at once never meet.
        void createTable() throws SQLException {
            try (Statement statement = this.connection.createStatement()) {
                statement.execute("""
                        create temporary table note (
                            id bigint generated always as identity primary key,
                            title text not null,
                            done boolean not null default false,
                            due date)""");
            }
        }

        Note create(String title, LocalDate due) throws SQLException {
            try (PreparedStatement insert = this.connection.prepareStatement(
                    "insert into note (title, due) values (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, title);
                if (due == null) {
                    insert.setNull(2, Types.DATE);
                }
                else {
                    insert.setObject(2, due);
                }
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    return new Note(keys.getLong("id"), title, false, due);
                }
            }
        }

        List<Note> findAll() throws SQLException {
            try (PreparedStatement select = this.connection
                .prepareStatement("select id, title, done, due from note order by id")) {
                return notes(select);
            }
        }

        List<Note> findByTitleContaining(String text) throws SQLException {
            try (PreparedStatement select = this.connection.prepareStatement(
                    "select id, title, done, due from note where strpos(title, ?) > 0 order by id")) {
                select.setString(1, text);
                return notes(select);
            }
        }

        Optional<Note> findById(long id) throws SQLException {
            try (PreparedStatement select = this.connection
                .prepareStatement("select id, title, done, due from note where id = ?")) {
                select.setLong(1, id);
                List<Note> found = notes(select);
                return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
            }
        }

        int markDone(long id) throws SQLException {
            try (PreparedStatement update = this.connection
                .prepareStatement("update note set done = true where id = ?")) {
                update.setLong(1, id);
                return update.executeUpdate();
            }
        }

        int deleteDone() throws SQLException {
            try (Statement delete = this.connection.createStatement()) {
                return delete.executeUpdate("delete from note where done");
            }
        }

        private static List<Note> notes(PreparedStatement select) throws SQLException {
            List<Note> notes = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    notes.add(new Note(rows.getLong("id"), rows.getString("title"), rows.getBoolean("done"),
                            rows.getObject("due", LocalDate.class)));
                }
            }
            return notes;
        }

    }

    interface Work {

        void run() throws SQLException;

    }

    // Commits what the work did, or rolls all of it back when a statement fails.
    static void inTransaction(Connection connection, Work work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
            System.out.println("committed");
        }
        catch (SQLException e) {
            connection.rollback();
            System.out.println("rolled back: SQLSTATE " + e.getSQLState());
        }
        finally {
            connection.setAutoCommit(true);
        }
    }

    static Connection connect(String url) throws SQLException {
        if (url.startsWith("jdbc:")) {
            return DriverManager.getConnection(url);
        }
        URI uri = URI.create(url);
        if (!"postgresql".equals(uri.getScheme()) && !"postgres".equals(uri.getScheme())) {
            throw new IllegalArgumentException("DATABASE_URL is neither jdbc: nor postgresql:// : " + url);
        }
        Properties properties = new Properties();
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            properties.setProperty("user", colon < 0 ? userInfo : userInfo.substring(0, colon));
            if (colon >= 0) {
                properties.setProperty("password", userInfo.substring(colon + 1));
            }
        }
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    static void print(List<Note> notes) {
        for (Note note : notes) {
            System.out.println("  " + note);
        }
    }

    public static void main(String[] args) throws SQLException {
        String url = System.getenv("DATABASE_URL");
        if (url == null) {
            System.err.println("DATABASE_URL is not set");
            System.exit(2);
        }
        try (Connection connection = connect(url)) {
            System.out.println("connected to " + connection.getMetaData().getDatabaseProductName());
            NoteRepository notes = new NoteRepository(connection);
            notes.createTable();

            System.out.println(notes.create("Buy milk", LocalDate.of(2026, 12, 1)));
            System.out.println(notes.create("お茶を淹れる🍵", null));
            System.out.println(notes.create("Write the JDBC example", LocalDate.of(2026, 11, 22)));

            System.out.println("marked done: " + notes.markDone(3));
            System.out.println(notes.findById(3).orElseThrow());
            System.out.println("no note 9: " + notes.findById(9).isEmpty());

            // The text comes back as it went: the server counts the characters Java does.
            Note tea = notes.findByTitleContaining("お茶").get(0);
            try (PreparedStatement length = connection
                .prepareStatement("select length(title), octet_length(title) from note where id = ?")) {
                length.setLong(1, tea.id());
                try (ResultSet row = length.executeQuery()) {
                    row.next();
                    System.out.println(tea.title() + ": " + row.getInt(1) + " characters, " + row.getInt(2)
                            + " bytes in UTF-8; Java counts "
                            + tea.title().codePointCount(0, tea.title().length()));
                }
            }

            inTransaction(connection, () -> {
                notes.create("Pay the rent", LocalDate.of(2026, 11, 30));
                notes.markDone(1);
            });
            // The second insert breaks the title's NOT NULL: the first goes with it.
            inTransaction(connection, () -> {
                notes.create("Call Ann", null);
                notes.create(null, null);
            });

            System.out.println("all notes:");
            print(notes.findAll());
            System.out.println("deleted done: " + notes.deleteDone());
            System.out.println("left:");
            print(notes.findAll());
        }
    }

}
