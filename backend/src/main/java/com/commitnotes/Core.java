package com.commitnotes;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Core {
  final JdbcTemplate db;
  private final byte[] secret;
  Core(JdbcTemplate db, @Value("${APP_SECRET:}") String appSecret) {
    this.db = db;
    if (appSecret.length() < 32) throw new IllegalStateException("APP_SECRET must contain at least 32 characters");
    this.secret = appSecret.getBytes(StandardCharsets.UTF_8);
  }
  static UUID id() { return UUID.randomUUID(); }
  static String str(Object value) { return value == null ? "" : value.toString().trim(); }
  static String required(Object value, int max, String field) {
    String s = str(value);
    if (s.isEmpty() || s.length() > max) throw bad(field + " deve conter entre 1 e " + max + " caracteres");
    return s;
  }
  static String optional(Object value, int max, String field) {
    String s = str(value);
    if (s.length() > max) throw bad(field + " excede " + max + " caracteres");
    return s;
  }
  static UUID uuid(Object value) {
    try { return UUID.fromString(str(value)); } catch (Exception e) { throw bad("ID inválido"); }
  }
  static UUID nullableUuid(Object value) { return value == null || str(value).isEmpty() ? null : uuid(value); }
  static String color(Object value, String fallback) {
    String s = str(value);
    if (s.isEmpty()) return fallback;
    if (!s.matches("#[0-9a-fA-F]{6}")) throw bad("Cor inválida; use o formato #RRGGBB");
    return s.toLowerCase(Locale.ROOT);
  }
  static String clip(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
  static Instant instant(Object value) {
    try { return Instant.parse(str(value)); } catch (Exception e) { throw bad("Data e hora inválidas; use ISO 8601 com fuso"); }
  }
  static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
  static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso não permitido"); }
  static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Registro não encontrado"); }
  static String hash(String token) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (Exception e) { throw new IllegalStateException(e); }
  }
  private String signature(String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) { throw new IllegalStateException(e); }
  }
  String session(UUID userId) {
    Integer epoch=db.queryForObject("SELECT session_epoch FROM app_user WHERE id=?",Integer.class,userId);
    String payload = userId + "." + Instant.now().plusSeconds(60L * 60 * 24 * 7).getEpochSecond() + "." + epoch;
    return payload + "." + signature(payload);
  }
  Map<String,Object> user(HttpServletRequest req) {
    Cookie[] cookies = req.getCookies();
    if (cookies != null) for (Cookie cookie : cookies) if (cookie.getName().equals("cn_session")) {
      String[] p = cookie.getValue().split("\\.");
      if (p.length == 4) {
        String payload = p[0] + "." + p[1] + "." + p[2];
        if (MessageDigest.isEqual(signature(payload).getBytes(StandardCharsets.US_ASCII), p[3].getBytes(StandardCharsets.US_ASCII))) {
          try {
            if (Long.parseLong(p[1]) > Instant.now().getEpochSecond()) {
              List<Map<String,Object>> users = db.queryForList("SELECT id,name,email,active,platform_admin,theme,accent,session_epoch FROM app_user WHERE id=?", UUID.fromString(p[0]));
              if (!users.isEmpty() && Boolean.TRUE.equals(users.getFirst().get("active")) && Integer.parseInt(p[2]) == (Integer)users.getFirst().get("session_epoch")) {
                Map<String,Object> safe=new LinkedHashMap<>(users.getFirst());safe.remove("session_epoch");return safe;
              }
            }
          } catch (Exception ignored) { }
        }
      }
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Entre para continuar");
  }
  UUID userId(HttpServletRequest req) { return (UUID) user(req).get("id"); }
  void admin(HttpServletRequest req) { if (!Boolean.TRUE.equals(user(req).get("platform_admin"))) throw forbidden(); }
  String role(HttpServletRequest req, UUID projectId) {
    List<Map<String,Object>> rows = db.queryForList("SELECT role FROM project_member WHERE project_id=? AND user_id=?", projectId, userId(req));
    if (rows.isEmpty()) throw forbidden();
    return (String) rows.getFirst().get("role");
  }
  void edit(HttpServletRequest req, UUID projectId) { if (role(req,projectId).equals("READER")) throw forbidden(); }
  void owner(HttpServletRequest req, UUID projectId) { if (!role(req,projectId).equals("OWNER")) throw forbidden(); }
  Map<String,Object> one(String sql, Object... args) {
    List<Map<String,Object>> rows = db.queryForList(sql,args);
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }
  UUID projectOf(String table, UUID id) { return (UUID) one("SELECT project_id FROM " + table + " WHERE id=?",id).get("project_id"); }
  void activity(UUID projectId, UUID actorId, String entity, UUID entityId, String action, String detail) {
    db.update("INSERT INTO activity(id,project_id,actor_id,entity_type,entity_id,action,detail,created_at) VALUES(?,?,?,?,?,?,?,clock_timestamp())",
      id(),projectId,actorId,entity,entityId,action,detail);
  }
  String userName(UUID userId) { return (String) one("SELECT name FROM app_user WHERE id=?", userId).get("name"); }
  /** Creates an in-app notification (later pushed by NotificationWorker) if the user still belongs to the project. */
  void notify(UUID userId, UUID projectId, UUID taskId, UUID actorId, String title, String body) {
    db.update("INSERT INTO notification(id,user_id,project_id,task_id,actor_id,title,body,url,created_at) SELECT ?,?,?,?,?,?,?,?,clock_timestamp() WHERE EXISTS (SELECT 1 FROM project_member WHERE project_id=? AND user_id=?)",
      id(),userId,projectId,taskId,actorId,clip(title,200),clip(body,1000),"/board?task="+taskId,projectId,userId);
  }
  /** Every change to a task reaches its assignees and its creator, except the person who made it. */
  void notifyTask(UUID taskId, UUID actorId, String change, Collection<UUID> skip) {
    Map<String,Object> task = one("SELECT project_id,title,created_by FROM task WHERE id=?", taskId);
    Set<UUID> users = new LinkedHashSet<>(db.queryForList("SELECT user_id FROM task_assignee WHERE task_id=?", UUID.class, taskId));
    users.add((UUID) task.get("created_by")); users.remove(actorId); users.removeAll(skip);
    if (users.isEmpty()) return;
    String body = userName(actorId) + " " + change;
    for (UUID user : users) notify(user,(UUID) task.get("project_id"),taskId,actorId,(String) task.get("title"),body);
  }
}
