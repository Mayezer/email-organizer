package com.mayezer.emailorganizer.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {

    public static Connection getConnection() throws  SQLException {

        String user = System.getenv("POSTGRES_USER");
        String password = System.getenv("POSTGRES_PASSWORD");
        String database = System.getenv("POSTGRES_DB");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5433");
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + port + "/" + database + "?connectTimeout=5";

        return DriverManager.getConnection(jdbcUrl, user, password);
    }
}
