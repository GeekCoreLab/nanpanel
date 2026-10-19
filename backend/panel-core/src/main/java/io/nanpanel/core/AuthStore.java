package io.nanpanel.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.*;
import java.sql.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.sql.DataSource;

@ApplicationScoped
public class AuthStore {
  @Inject DataSource db;
  static String token() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
  static String digest(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(
              value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
  static String hash(String password) {
    String salt = token();
    return salt + ":" + derive(password, salt);
  }
  static String derive(String password, String salt) {
    try {
      var spec = new PBEKeySpec(
          password.toCharArray(),
          salt.getBytes(java.nio.charset.StandardCharsets.UTF_8), 600000, 256);
      try {
        return Base64.getEncoder().encodeToString(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .getEncoded());
      } finally {
        spec.clearPassword();
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
  static boolean verify(String password, String hash) {
    String[] p = hash.split(":");
    return p.length == 2 &&
        MessageDigest.isEqual(derive(password, p[0]).getBytes(),
                              p[1].getBytes());
  }
  static String audit(Connection c, String actorId, String action,
                      String resourceType, String resourceId, String outcome,
                      String clientIp, String summary) throws SQLException {
    String id = UUID.randomUUID().toString();
    update(
        c,
        "INSERT INTO audit_event "
            + "(id,occurred_at,actor_id,action,resource_type,resource_id,"
            +
            "outcome,trace_id,client_ip,summary) VALUES (?,?,?,?,?,?,?,?,?,?)",
        id, System.currentTimeMillis(), actorId, action, resourceType,
        resourceId, outcome, UUID.randomUUID().toString(), clientIp, summary);
    return id;
  }
  interface Work<T> {
    T run(Connection c) throws Exception;
  }
  synchronized<T> T transaction(Work<T> work) {
    try (var c = db.getConnection()) {
      c.setAutoCommit(false);
      try {
        T result = work.run(c);
        c.commit();
        return result;
      } catch (Exception e) {
        c.rollback();
        if (e instanceof RuntimeException r)
          throw r;
        throw new IllegalStateException(e);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
  static int update(Connection c, String sql, Object... values)
      throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++)
        s.setObject(i + 1, values[i]);
      return s.executeUpdate();
    }
  }
  static List<Map<String, Object>> query(Connection c, String sql,
                                         Object... values) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++)
        s.setObject(i + 1, values[i]);
      try (var r = s.executeQuery()) {
        var rows = new ArrayList<Map<String, Object>>();
        while (r.next()) {
          var row = new HashMap<String, Object>();
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
            row.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),
                    r.getObject(i));
          rows.add(row);
        }
        return rows;
      }
    }
  }
}
