// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2023 MariaDB Corporation Ab
// Copyright (c) 2021-2023 SingleStore, Inc.

package com.singlestore.jdbc.plugin.authentication.addon.gssapi;

import com.singlestore.jdbc.client.ReadableByteBuf;
import com.singlestore.jdbc.client.socket.Reader;
import com.singlestore.jdbc.client.socket.Writer;
import com.singlestore.jdbc.util.ThreadUtils;
import com.singlestore.jdbc.util.log.Logger;
import com.singlestore.jdbc.util.log.Loggers;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.security.PrivilegedExceptionAction;
import java.sql.SQLException;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

public class StandardGssapiAuthentication implements GssapiAuth {

  private final Logger logger = Loggers.getLogger(StandardGssapiAuthentication.class);;

  /**
   * Process default GSS plugin authentication.
   *
   * @param out out stream
   * @param in in stream
   * @param servicePrincipalName service principal name
   * @param jaasApplicationName entry name in JAAS Login Configuration File
   * @param mechanisms gssapi mechanism
   * @throws IOException if socket error
   * @throws SQLException in any Exception occur
   */
  public void authenticate(
      final Writer out,
      final Reader in,
      final String servicePrincipalName,
      final String jaasApplicationName,
      String mechanisms)
      throws SQLException, IOException {

    if ("".equals(servicePrincipalName)) {
      throw new SQLException(
          "No principal name defined on server. "
              + "Please set server variable \"gssapi-principal-name\" or set option \"servicePrincipalName\"",
          "28000");
    }
    // set default jaasEntryName to Krb5ConnectorContext
    String jaasEntryName =
        "".equals(jaasApplicationName) ? "Krb5ConnectorContext" : jaasApplicationName;

    if (System.getProperty("java.security.auth.login.config") == null) {
      logger.debug("Using temp jaas.conf as java.security.auth.login.config");
      final File jaasConfFile;
      try {
        jaasConfFile = File.createTempFile("jaas.conf", null);
        try (PrintStream bos = new PrintStream(new FileOutputStream(jaasConfFile))) {
          bos.print(
              "Krb5ConnectorContext {\n"
                  + "com.sun.security.auth.module.Krb5LoginModule required "
                  + "useTicketCache=true "
                  + "debug=true "
                  + "renewTGT=true "
                  + "doNotPrompt=true; };");
        }
        jaasConfFile.deleteOnExit();
      } catch (final IOException ex) {
        throw new UncheckedIOException(ex);
      }

      System.setProperty("java.security.auth.login.config", jaasConfFile.getCanonicalPath());
    } else {
      logger.debug(
          "Using {} as java.security.auth.login.config",
          System.getProperty("java.security.auth.login.config"));
    }
    try {
      LoginContext loginContext = new LoginContext(jaasEntryName);
      // attempt authentication
      loginContext.login();
      final Subject mySubject = loginContext.getSubject();
      if (!mySubject.getPrincipals().isEmpty()) {
        try {
          PrivilegedExceptionAction<Void> action =
              () -> {
                try {
                  Oid krb5Mechanism = new Oid("1.2.840.113554.1.2.2");

                  GSSManager manager = GSSManager.getInstance();
                  GSSName peerName = manager.createName(servicePrincipalName, GSSName.NT_USER_NAME);
                  GSSContext context =
                      manager.createContext(
                          peerName, krb5Mechanism, null, GSSContext.DEFAULT_LIFETIME);
                  context.requestMutualAuth(true);

                  byte[] inToken = new byte[0];
                  byte[] outToken;
                  while (true) {

                    outToken = context.initSecContext(inToken, 0, inToken.length);

                    // Send a token to the peer if one was generated by acceptSecContext
                    if (outToken != null) {
                      out.writeBytes(outToken);
                      out.flush();
                    }
                    if (context.isEstablished()) {
                      break;
                    }
                    ReadableByteBuf buf = in.readReusablePacket();
                    inToken = new byte[buf.readableBytes()];
                    buf.readBytes(inToken);
                  }

                } catch (GSSException le) {
                  throw new SQLException("GSS-API authentication exception", "28000", 1045, le);
                }
                return null;
              };
          ThreadUtils.callAs(mySubject, () -> action);
        } catch (Exception exception) {
          throw new SQLException("GSS-API authentication exception", "28000", 1045, exception);
        }
      } else {
        throw new SQLException(
            "GSS-API authentication exception : no credential cache not found.", "28000", 1045);
      }

    } catch (LoginException le) {
      throw new SQLException("GSS-API authentication exception", "28000", 1045, le);
    }
  }
}
