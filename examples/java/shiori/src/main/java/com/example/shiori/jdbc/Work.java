// Shiori: what is done with a connection.
package com.example.shiori.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

interface Work<T> {

    T run(Connection connection) throws SQLException;

}
