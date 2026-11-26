// Shiori: a row of a result, read into a value.
package com.example.shiori.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

public interface RowMapper<T> {

    T map(ResultSet row) throws SQLException;

}
