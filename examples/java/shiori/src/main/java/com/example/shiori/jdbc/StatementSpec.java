// Shiori: one statement and the values bound to it.
package com.example.shiori.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// The parameters are bound in their order, never spliced into the text, so the server
// parses the statement before it sees a value. A null parameter is SQL NULL, its type the
// server's to infer.
public final class StatementSpec {

    private static final Object[] NONE = {};

    private final Connector connector;

    private final String sql;

    private final Object[] parameters;

    StatementSpec(Connector connector, String sql) {
        this(connector, sql, NONE);
    }

    private StatementSpec(Connector connector, String sql, Object[] parameters) {
        this.connector = connector;
        this.sql = sql;
        this.parameters = parameters;
    }

    public StatementSpec params(Object... values) {
        return new StatementSpec(this.connector, this.sql, values.clone());
    }

    // Every row the statement answers.
    public <T> List<T> list(RowMapper<T> mapper) throws SQLException {
        return this.connector.run(connection -> {
            try (PreparedStatement statement = prepare(connection); ResultSet rows = statement.executeQuery()) {
                List<T> all = new ArrayList<>();
                while (rows.next()) {
                    all.add(mapper.map(rows));
                }
                return all;
            }
        });
    }

    // The first row, if the statement answers one.
    public <T> Optional<T> first(RowMapper<T> mapper) throws SQLException {
        return this.connector.run(connection -> {
            try (PreparedStatement statement = prepare(connection); ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(mapper.map(rows)) : Optional.empty();
            }
        });
    }

    // A statement that answers no rows: how many it changed.
    public int update() throws SQLException {
        return this.connector.run(connection -> {
            try (PreparedStatement statement = prepare(connection)) {
                return statement.executeUpdate();
            }
        });
    }

    // A statement of no parameters sent as it is: the schema's.
    public void execute() throws SQLException {
        this.connector.run(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.execute(this.sql);
            }
        });
    }

    private PreparedStatement prepare(Connection connection) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(this.sql);
        try {
            for (int i = 0; i < this.parameters.length; i++) {
                statement.setObject(i + 1, this.parameters[i]);
            }
            return statement;
        }
        catch (SQLException ex) {
            statement.close();
            throw ex;
        }
    }

}
