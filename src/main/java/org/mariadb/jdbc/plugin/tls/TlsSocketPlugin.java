/*
 *
 * MariaDB Client for Java
 *
 * Copyright (c) 2012-2014 Monty Program Ab.
 * Copyright (c) 2015-2020 MariaDB Corporation Ab.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to Monty Program Ab info@montyprogram.com.
 *
 */

package org.mariadb.jdbc.plugin.tls;

import java.io.IOException;
import java.net.Socket;
import java.sql.SQLException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.util.exceptions.ExceptionFactory;

public interface TlsSocketPlugin {

  /**
   * plugin type.
   *
   * @return plugin type
   */
  String type();

  /**
   * Get socket factory.
   *
   * @param conf connection string option. Non standard option are stored in `nonMappedOptions` if
   *     any specific option is needed.
   * @param exceptionFactory exception handler
   * @return custom SSL socket factory
   * @throws SQLException if socket factory configuration failed.
   */
  SSLSocketFactory getSocketFactory(Configuration conf, ExceptionFactory exceptionFactory)
      throws SQLException;

  /**
   * Returns a socket layered over an existing socket negotiating the use of SSL over an existing
   * socket.
   *
   * @param socket existing socket
   * @param sslSocketFactory SSL socket factory
   * @return SSL socket
   * @throws IOException if any socket error occurs.
   */
  default SSLSocket createSocket(Socket socket, SSLSocketFactory sslSocketFactory)
      throws IOException {
    return (SSLSocket)
        sslSocketFactory.createSocket(
            socket,
            socket.getInetAddress() == null ? null : socket.getInetAddress().getHostAddress(),
            socket.getPort(),
            true);
  }

  /**
   * Host name verifier implementation.
   *
   * @param host hostname
   * @param sslSession ssl session
   * @param conf connection string option. Non standard option are stored in * `nonMappedOptions` if
   *     any specific option is needed.
   * @param serverThreadId current server threadId
   * @throws SSLException if verification fail
   */
  void verify(String host, SSLSession sslSession, Configuration conf, long serverThreadId)
      throws SSLException;
}
