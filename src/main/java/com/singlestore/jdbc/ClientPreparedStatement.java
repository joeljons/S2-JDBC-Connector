// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab
// Copyright (c) 2021 SingleStore, Inc.

package com.singlestore.jdbc;

import com.singlestore.jdbc.client.result.CompleteResult;
import com.singlestore.jdbc.client.result.Result;
import com.singlestore.jdbc.message.client.*;
import com.singlestore.jdbc.message.server.ColumnDefinitionPacket;
import com.singlestore.jdbc.message.server.Completion;
import com.singlestore.jdbc.message.server.OkPacket;
import com.singlestore.jdbc.util.ClientParser;
import com.singlestore.jdbc.util.ParameterList;
import com.singlestore.jdbc.util.constants.Capabilities;
import com.singlestore.jdbc.util.constants.ServerStatus;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class ClientPreparedStatement extends BasePreparedStatement {
  private final ClientParser parser;

  public ClientPreparedStatement(
      String sql,
      Connection con,
      ReentrantLock lock,
      boolean canUseServerTimeout,
      boolean canUseServerMaxRows,
      int autoGeneratedKeys,
      int resultSetType,
      int resultSetConcurrency,
      int defaultFetchSize)
      throws SQLException {
    super(
        sql,
        con,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        autoGeneratedKeys,
        resultSetType,
        resultSetConcurrency,
        defaultFetchSize);

    boolean noBackslashEscapes =
        (con.getContext().getServerStatus() & ServerStatus.NO_BACKSLASH_ESCAPES) > 0;
    parser = ClientParser.parameterParts(sql, noBackslashEscapes);
    parameters = new ParameterList(parser.getParamCount());
  }

  protected String preSqlCmd() {
    if (queryTimeout != 0 && canUseServerTimeout) {
      return "SET STATEMENT max_statement_time=" + queryTimeout + " FOR ";
    }
    return "";
  }

  private void executeInternal() throws SQLException {
    checkNotClosed();
    validParameters();
    lock.lock();
    try {
      QueryWithParametersPacket query =
          new QueryWithParametersPacket(preSqlCmd(), parser, parameters);
      results =
          con.getClient()
              .execute(
                  query,
                  this,
                  fetchSize,
                  maxRows,
                  resultSetConcurrency,
                  resultSetType,
                  closeOnCompletion);
    } finally {
      lock.unlock();
    }
  }

  private List<Completion> executeInternalPreparedBatch() throws SQLException {
    checkNotClosed();
    long serverCapabilities = con.getContext().getServerCapabilities();
    if (!con.getContext().getConf().allowLocalInfile()
        || (serverCapabilities & Capabilities.LOCAL_FILES) == 0) {
      return executeBatchPipeline();
    } else {
      return executeBatchStd();
    }
  }

  /**
   * Send n * COM_QUERY + n * read answer
   *
   * @throws SQLException if IOException / Command error
   */
  private List<Completion> executeBatchPipeline() throws SQLException {
    ClientMessage[] packets = new ClientMessage[batchParameters.size()];
    for (int i = 0; i < batchParameters.size(); i++) {
      packets[i] = new QueryWithParametersPacket(preSqlCmd(), parser, batchParameters.get(i));
    }
    try {
      results =
          con.getClient()
              .executePipeline(
                  packets,
                  this,
                  0,
                  maxRows,
                  ResultSet.CONCUR_READ_ONLY,
                  ResultSet.TYPE_FORWARD_ONLY,
                  closeOnCompletion);
      return results;
    } catch (SQLException bue) {
      results = null;
      throw bue;
    }
  }

  /**
   * Send n * (COM_QUERY + read answer)
   *
   * @throws SQLException if IOException / Command error
   */
  private List<Completion> executeBatchStd() throws SQLException {
    int i = 0;
    try {
      results = new ArrayList<>();
      for (; i < batchParameters.size(); i++) {
        results.addAll(
            con.getClient()
                .execute(
                    new QueryWithParametersPacket(preSqlCmd(), parser, batchParameters.get(i)),
                    this,
                    0,
                    maxRows,
                    ResultSet.CONCUR_READ_ONLY,
                    ResultSet.TYPE_FORWARD_ONLY,
                    closeOnCompletion));
      }
      return results;
    } catch (SQLException bue) {
      BatchUpdateException exception =
          exceptionFactory().createBatchUpdate(results, batchParameters.size(), bue);
      results = null;
      throw exception;
    }
  }

  /**
   * Executes the SQL statement in this <code>PreparedStatement</code> object, which may be any kind
   * of SQL statement. Some prepared statements return multiple results; the <code>execute</code>
   * method handles these complex statements as well as the simpler form of statements handled by
   * the methods <code>executeQuery</code> and <code>executeUpdate</code>.
   *
   * <p>The <code>execute</code> method returns a <code>boolean</code> to indicate the form of the
   * first result. You must call either the method <code>getResultSet</code> or <code>getUpdateCount
   * </code> to retrieve the result; you must call <code>getMoreResults</code> to move to any
   * subsequent result(s).
   *
   * @return <code>true</code> if the first result is a <code>ResultSet</code> object; <code>false
   *     </code> if the first result is an update count or there is no result
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or an argument is supplied to this method
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   * @see Statement#execute
   * @see Statement#getResultSet
   * @see Statement#getUpdateCount
   * @see Statement#getMoreResults
   */
  @Override
  public boolean execute() throws SQLException {
    executeInternal();
    currResult = results.remove(0);
    return currResult instanceof Result;
  }

  /**
   * Executes the SQL query in this <code>PreparedStatement</code> object and returns the <code>
   * ResultSet</code> object generated by the query.
   *
   * @return a <code>ResultSet</code> object that contains the data produced by the query; never
   *     <code>null</code>
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or the SQL statement does not return a <code>ResultSet
   *     </code> object
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   */
  @Override
  public ResultSet executeQuery() throws SQLException {
    executeInternal();
    currResult = results.remove(0);
    if (currResult instanceof Result) {
      return (Result) currResult;
    }
    return new CompleteResult(new ColumnDefinitionPacket[0], new byte[0][], con.getContext());
  }

  /**
   * Executes the SQL statement in this <code>PreparedStatement</code> object, which must be an SQL
   * Data Manipulation Language (DML) statement, such as <code>INSERT</code>, <code>UPDATE</code> or
   * <code>DELETE</code>; or an SQL statement that returns nothing, such as a DDL statement.
   *
   * @return either (1) the row count for SQL Data Manipulation Language (DML) statements or (2) 0
   *     for SQL statements that return nothing
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or the SQL statement returns a <code>ResultSet</code> object
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   */
  @Override
  public int executeUpdate() throws SQLException {
    return (int) executeLargeUpdate();
  }

  /**
   * Executes the SQL statement in this <code>PreparedStatement</code> object, which must be an SQL
   * Data Manipulation Language (DML) statement, such as <code>INSERT</code>, <code>UPDATE</code> or
   * <code>DELETE</code>; or an SQL statement that returns nothing, such as a DDL statement.
   *
   * <p>This method should be used when the returned row count may exceed {@link Integer#MAX_VALUE}.
   *
   * <p>The default implementation will throw {@code UnsupportedOperationException}
   *
   * @return either (1) the row count for SQL Data Manipulation Language (DML) statements or (2) 0
   *     for SQL statements that return nothing
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or the SQL statement returns a <code>ResultSet</code> object
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   * @since 1.8
   */
  @Override
  public long executeLargeUpdate() throws SQLException {
    executeInternal();
    currResult = results.remove(0);
    if (currResult instanceof Result) {
      throw exceptionFactory()
          .create("the given SQL statement produces an unexpected ResultSet object", "HY000");
    }
    return ((OkPacket) currResult).getAffectedRows();
  }

  /**
   * Adds a set of parameters to this <code>PreparedStatement</code> object's batch of commands.
   *
   * @throws SQLException if a database access error occurs or this method is called on a closed
   *     <code>PreparedStatement</code>
   * @see Statement#addBatch
   * @since 1.2
   */
  @Override
  public void addBatch() throws SQLException {
    validParameters();
    if (batchParameters == null) batchParameters = new ArrayList<>();
    batchParameters.add(parameters);
    parameters = new ParameterList();
  }

  protected void validParameters() throws SQLException {
    for (int i = 0; i < parser.getParamCount(); i++) {
      if (parameters.containsKey(i)) {
        throw exceptionFactory()
            .create("Parameter at position " + (i + 1) + " is not set", "07004");
      }
    }
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    super.setQueryTimeout(seconds);
    if (canUseServerTimeout && prepareResult != null) {
      prepareResult.close(con.getClient());
      prepareResult = null;
    }
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    super.setMaxRows(max);
    if (canUseServerMaxRows && prepareResult != null) {
      prepareResult.close(con.getClient());
      prepareResult = null;
    }
  }

  @Override
  public void setLargeMaxRows(long max) throws SQLException {
    super.setLargeMaxRows(max);
    if (canUseServerMaxRows && prepareResult != null) {
      prepareResult.close(con.getClient());
      prepareResult = null;
    }
  }

  /**
   * Retrieves a <code>ResultSetMetaData</code> object that contains information about the columns
   * of the <code>ResultSet</code> object that will be returned when this <code>PreparedStatement
   * </code> object is executed.
   *
   * <p>Because a <code>PreparedStatement</code> object is precompiled, it is possible to know about
   * the <code>ResultSet</code> object that it will return without having to execute it.
   * Consequently, it is possible to invoke the method <code>getMetaData</code> on a <code>
   * PreparedStatement</code> object rather than waiting to execute it and then invoking the <code>
   * ResultSet.getMetaData</code> method on the <code>ResultSet</code> object that is returned.
   *
   * <p><B>NOTE:</B> Using this method may be expensive for some drivers due to the lack of
   * underlying DBMS support.
   *
   * @return the description of a <code>ResultSet</code> object's columns or <code>null</code> if
   *     the driver cannot return a <code>ResultSetMetaData</code> object
   * @throws SQLException if a database access error occurs or this method is called on a closed
   *     <code>PreparedStatement</code>
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
   * @since 1.2
   */
  @Override
  public ResultSetMetaData getMetaData() throws SQLException {

    // send COM_STMT_PREPARE
    if (prepareResult == null) con.getClient().execute(new PreparePacket(escapeTimeout(sql)), this);
    return new com.singlestore.jdbc.client.result.ResultSetMetaData(
        exceptionFactory(), prepareResult.getColumns(), con.getContext().getConf(), false);
  }

  /**
   * Retrieves the number, types and properties of this <code>PreparedStatement</code> object's
   * parameters.
   *
   * @return a <code>ParameterMetaData</code> object that contains information about the number,
   *     types and properties for each parameter marker of this <code>PreparedStatement</code>
   *     object
   * @throws SQLException if a database access error occurs or this method is called on a closed
   *     <code>PreparedStatement</code>
   * @see ParameterMetaData
   * @since 1.4
   */
  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    // send COM_STMT_PREPARE
    if (prepareResult == null) {
      con.getClient().execute(new PreparePacket(escapeTimeout(sql)), this);
    }
    return new ParameterMetaData(exceptionFactory(), prepareResult.getParameters());
  }

  @Override
  public int[] executeBatch() throws SQLException {
    checkNotClosed();
    if (batchParameters == null || batchParameters.isEmpty()) return new int[0];
    lock.lock();
    try {
      List<Completion> res = executeInternalPreparedBatch();
      results = res;
      int[] updates = new int[batchParameters.size()];
      if (res.size() != batchParameters.size()) {
        for (int i = 0; i < batchParameters.size(); i++) {
          updates[i] = Statement.SUCCESS_NO_INFO;
        }
      } else {
        for (int i = 0; i < Math.min(res.size(), batchParameters.size()); i++) {
          if (res.get(i) instanceof OkPacket) {
            updates[i] = (int) ((OkPacket) res.get(i)).getAffectedRows();
          } else {
            updates[i] = com.singlestore.jdbc.Statement.SUCCESS_NO_INFO;
          }
        }
      }
      currResult = results.remove(0);
      return updates;
    } finally {
      batchParameters.clear();
      lock.unlock();
    }
  }

  @Override
  public long[] executeLargeBatch() throws SQLException {
    checkNotClosed();
    if (batchParameters == null || batchParameters.isEmpty()) return new long[0];
    lock.lock();
    try {
      List<Completion> res = executeInternalPreparedBatch();
      results = res;
      long[] updates = new long[res.size()];
      for (int i = 0; i < res.size(); i++) {
        updates[i] = ((OkPacket) res.get(i)).getAffectedRows();
      }
      currResult = results.remove(0);
      return updates;

    } finally {
      batchParameters.clear();
      lock.unlock();
    }
  }

  @Override
  public void close() throws SQLException {
    if (prepareResult != null) {
      prepareResult.close(this.con.getClient());
    }
    con.fireStatementClosed(this);
    super.close();
  }

  public ClientParser test_getParser() {
    return parser;
  }
}
