/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.db;

import java.io.Reader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.h2.test.TestBase;
import org.h2.test.TestDb;
import org.h2.util.Task;

/**
 * Test for deadlocks in the code, and test the deadlock detection mechanism.
 */
public class TestDeadlock extends TestDb {

    /**
     * The first connection.
     */
    Connection c1;

    /**
     * The second connection.
     */
    Connection c2;

    /**
     * The third connection.
     */
    Connection c3;
    private volatile SQLException lastException;

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().testFromMain();
    }

    @Override
    public void test() throws Exception {
        deleteDb("deadlock");
        testTemporaryTablesAndMetaDataLocking();
        testDeadlockInFulltextSearch();
        testConcurrentLobReadAndTempResultTableDelete();
        testNoDeadlock();
        deleteDb("deadlock");
    }

    private void testDeadlockInFulltextSearch() throws SQLException {
        deleteDb("deadlock");
        String url = "deadlock";
        Connection conn, conn2;
        conn = getConnection(url);
        conn2 = getConnection(url);
        final Statement stat = conn.createStatement();
        Statement stat2 = conn2.createStatement();
        stat.execute("create alias if not exists ft_init for " +
                "\"org.h2.fulltext.FullText.init\"");
        stat.execute("call ft_init()");
        stat.execute("create table test(id int primary key, name varchar)");
        stat.execute("call ft_create_index('PUBLIC', 'TEST', null)");
        Task t = new Task() {
            @Override
            public void call() throws Exception {
                while (!stop) {
                    stat.executeQuery("select * from test");
                }
            }
        };
        t.execute();
        long start = System.nanoTime();
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1)) {
            stat2.execute("insert into test values(1, 'Hello')");
            stat2.execute("delete from test");
        }
        t.get();
        conn2.close();
        conn.close();
        conn = getConnection(url);
        conn.createStatement().execute("drop all objects");
        conn.close();
    }

    private void testConcurrentLobReadAndTempResultTableDelete() throws Exception {
        deleteDb("deadlock");
        String url = "deadlock;MAX_MEMORY_ROWS=10";
        Connection conn, conn2;
        Statement stat2;
        conn = getConnection(url);
        conn2 = getConnection(url);
        final Statement stat = conn.createStatement();
        stat2 = conn2.createStatement();
        stat.execute("create table test(id int primary key, name varchar) as " +
                "select x, 'Hello' from system_range(1,20)");
        stat2.execute("create table test_clob(id int primary key, data clob) as " +
                "select 1, space(10000)");
        ResultSet rs2 = stat2.executeQuery("select * from test_clob");
        rs2.next();
        Task t = new Task() {
            @Override
            public void call() throws Exception {
                while (!stop) {
                    stat.execute("select * from (select distinct id from test)");
                }
            }
        };
        t.execute();
        long start = System.nanoTime();
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1)) {
            Reader r = rs2.getCharacterStream(2);
            char[] buff = new char[1024];
            while (true) {
                int x = r.read(buff);
                if (x < 0) {
                    break;
                }
            }
        }
        t.get();
        stat.execute("drop all objects");
        conn.close();
        conn2.close();
    }

    private void initTest() throws SQLException {
        c1 = getConnection("deadlock");
        c2 = getConnection("deadlock");
        c3 = getConnection("deadlock");
        c1.createStatement().execute("SET LOCK_TIMEOUT 1000");
        c2.createStatement().execute("SET LOCK_TIMEOUT 1000");
        c3.createStatement().execute("SET LOCK_TIMEOUT 1000");
        c1.setAutoCommit(false);
        c2.setAutoCommit(false);
        c3.setAutoCommit(false);
        lastException = null;
    }

    private void end() throws SQLException {
        c1.close();
        c2.close();
        c3.close();
    }

    /**
     * This class wraps exception handling to simplify creating small threads
     * that execute a statement.
     */
    abstract class DoIt extends Thread {

        /**
         * The operation to execute.
         */
        abstract void execute() throws SQLException;

        @Override
        public void run() {
            try {
                execute();
            } catch (SQLException e) {
                catchDeadlock(e);
            }
        }
    }

    /**
     * Add the exception to the list of exceptions.
     *
     * @param e the exception
     */
    void catchDeadlock(SQLException e) {
        if (lastException != null) {
            lastException.setNextException(e);
        } else {
            lastException = e;
        }
    }

    private void testNoDeadlock() throws Exception {
        initTest();
        c1.createStatement().execute("CREATE TABLE TEST_A(ID INT PRIMARY KEY)");
        c1.createStatement().execute("CREATE TABLE TEST_B(ID INT PRIMARY KEY)");
        c1.createStatement().execute("CREATE TABLE TEST_C(ID INT PRIMARY KEY)");
        c1.commit();
        c1.createStatement().execute("INSERT INTO TEST_A VALUES(1)");
        c2.createStatement().execute("INSERT INTO TEST_B VALUES(1)");
        c3.createStatement().execute("INSERT INTO TEST_C VALUES(1)");
        DoIt t2 = new DoIt() {
            @Override
            public void execute() throws SQLException {
                c1.createStatement().execute("DELETE FROM TEST_B");
                c1.commit();
            }
        };
        t2.start();
        DoIt t3 = new DoIt() {
            @Override
            public void execute() throws SQLException {
                c2.createStatement().execute("DELETE FROM TEST_C");
                c2.commit();
            }
        };
        t3.start();
        Thread.sleep(500);
        try {
            c3.createStatement().execute("DELETE FROM TEST_C");
            c3.commit();
        } catch (SQLException e) {
            catchDeadlock(e);
        }
        t2.join();
        t3.join();
        if (lastException != null) {
            throw lastException;
        }
        c1.commit();
        c2.commit();
        c3.commit();
        c1.createStatement().execute("DROP TABLE TEST_A, TEST_B, TEST_C");
        end();

    }


    // there was a bug in the meta data locking here
    private void testTemporaryTablesAndMetaDataLocking() throws Exception {
        deleteDb("deadlock");
        Connection conn = getConnection("deadlock");
        Statement stmt = conn.createStatement();
        conn.setAutoCommit(false);
        stmt.execute("CREATE SEQUENCE IF NOT EXISTS SEQ1 START WITH 1000000");
        stmt.execute("CREATE FORCE VIEW V1 AS WITH RECURSIVE TEMP(X) AS " +
                "(SELECT x FROM DUAL) SELECT * FROM TEMP");
        stmt.executeQuery("SELECT NEXT VALUE FOR SEQ1");
        conn.close();
    }

}
