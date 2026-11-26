// The statements of the notes, in plain JDBC. Each method asks DriverManager for a
// connection and closes it: under Spin that takes one out of the host's pool and gives it
// back, so the program keeps none.

package com.example.notes;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

final class Notes {

    record Note(long id, String title, LocalDate added) {
    }

    private final String url;

    private final Properties info;

    Notes(String url, Properties info) {
        this.url = url;
        this.info = info;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(this.url, this.info);
    }

    void createTable() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists note (
                        id bigint generated always as identity primary key,
                        title text not null check (title <> ''),
                        added date not null default current_date)""");
        }
        catch (SQLException e) {
            // Two instances started at once: the other one created it.
            if (!"23505".equals(e.getSQLState()) && !"42P07".equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    // "PostgreSQL 17.12 over TLSv1.3", or "in the clear"
    String server() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("""
                        select current_setting('server_version'),
                               (select version from pg_stat_ssl where pid = pg_backend_pid() and ssl)""")) {
            row.next();
            String tls = row.getString(2);
            return "PostgreSQL " + row.getString(1) + (tls != null ? " over " + tls : " in the clear");
        }
    }

    // Every note, or those whose title holds `word` (no case), in the order written.
    List<Note> find(Optional<String> word) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement select = connection.prepareStatement("""
                        select id, title, added from note
                        where ?::text is null or strpos(lower(title), lower(?)) > 0
                        order by id""")) {
            select.setString(1, word.orElse(null));
            select.setString(2, word.orElse(null));
            List<Note> notes = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    notes.add(new Note(rows.getLong("id"), rows.getString("title"),
                            rows.getObject("added", LocalDate.class)));
                }
            }
            return notes;
        }
    }

    // Adds every title or none: one transaction, rolled back when a title is refused.
    List<Note> add(List<String> titles) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("insert into note (title) values (?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                List<Note> added = new ArrayList<>();
                for (String title : titles) {
                    insert.setString(1, title);
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        keys.next();
                        added.add(new Note(keys.getLong("id"), title, keys.getObject("added", LocalDate.class)));
                    }
                }
                connection.commit();
                return added;
            }
            catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

}
