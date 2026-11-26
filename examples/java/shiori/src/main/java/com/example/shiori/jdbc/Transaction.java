// Shiori: statements that commit together or not at all.
package com.example.shiori.jdbc;

import java.sql.SQLException;

public interface Transaction<T> {

    T run(JdbcClient jdbc) throws SQLException;

}
