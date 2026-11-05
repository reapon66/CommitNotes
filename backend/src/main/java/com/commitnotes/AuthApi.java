package com.commitnotes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController @RequestMapping("/api/auth")
class AuthApi {
  private final Core c; private final JdbcTemplate db; private final JavaMailSender mail;
  private static final Logger log=LoggerFactory.getLogger(AuthApi.class);
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  @Value("${app.origin}") String origin;
  @Value("${app.mail-from}") String mailFrom;
  @Value("${app.admin-email}") String adminEmail;
  @Value("${app.admin-password}") String adminPassword;
  AuthApi(Core c, JdbcTemplate db, JavaMailSender mail) { this.c=c; this.db=db; this.mail=mail; }
  @EventListener(ApplicationReadyEvent.class) void bootstrap() {
    if (!adminEmail.isBlank() && !adminPassword.isBlank()) {
      if (adminPassword.length()<12) throw new IllegalStateException("ADMIN_PASSWORD needs 12 characters");
      String email=adminEmail.toLowerCase(Locale.ROOT).trim();
      if (db.queryForObject("SELECT count(*) FROM app_user WHERE email=?",Integer.class,email)==0)
        db.update("INSERT INTO app_user(id,name,email,password_hash,platform_admin) VALUES(?,?,?,?,true)", Core.id(),"Administrador",email,encoder.encode(adminPassword));
    }
  }
  private void cookie(HttpServletRequest req,HttpServletResponse res,String value,int age) {
    res.addHeader("Set-Cookie",ResponseCookie.from("cn_session",value).httpOnly(true).secure(req.isSecure())
      .sameSite("Strict").path("/").maxAge(age).build().toString());
  }
  private String throttle(String scope,String key,int limit) {
    String hash=Core.hash(scope+":"+key);
    Integer count=db.queryForObject("INSERT INTO auth_throttle(key_hash,attempt_count,expires_at) VALUES(?,1,now()+interval '15 minutes') ON CONFLICT(key_hash) DO UPDATE SET attempt_count=CASE WHEN auth_throttle.expires_at<now() THEN 1 ELSE auth_throttle.attempt_count+1 END,expires_at=CASE WHEN auth_throttle.expires_at<now() THEN excluded.expires_at ELSE auth_throttle.expires_at END RETURNING attempt_count",Integer.class,hash);
    if(count!=null && count>limit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Muitas tentativas. Aguarde 15 minutos.");
    return hash;
  }
  private String password(Object value) { return value instanceof String s ? s : ""; }
  @Scheduled(fixedDelay=3600000) void purgeThrottle() { db.update("DELETE FROM auth_throttle WHERE expires_at<now()"); }
  @PostMapping("/register") Object register(@RequestBody Map<String,Object> body,HttpServletRequest req,HttpServletResponse res) {
    String name=Core.required(body.get("name"),120,"Nome"), email=Core.required(body.get("email"),254,"E-mail").toLowerCase(Locale.ROOT);
    String password=password(body.get("password"));
    if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || password.length()<12 || password.length()>128)
      throw Core.bad("E-mail inválido ou senha fora do intervalo de 12 a 128 caracteres");
    if (db.queryForObject("SELECT count(*) FROM app_user WHERE email=?",Integer.class,email)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"E-mail já cadastrado");
    UUID id=Core.id(); db.update("INSERT INTO app_user(id,name,email,password_hash) VALUES(?,?,?,?)",id,name,email,encoder.encode(password));
    cookie(req,res,c.session(id),604800); return c.user(reqWithNewCookie(req,id));
  }
  private HttpServletRequest reqWithNewCookie(HttpServletRequest req,UUID id) { return new jakarta.servlet.http.HttpServletRequestWrapper(req) {
    @Override public jakarta.servlet.http.Cookie[] getCookies() { return new jakarta.servlet.http.Cookie[]{new jakarta.servlet.http.Cookie("cn_session",c.session(id))}; }
  }; }
  @PostMapping("/login") Object login(@RequestBody Map<String,Object> body,HttpServletRequest req,HttpServletResponse res) {
    String email=Core.str(body.get("email")).toLowerCase(Locale.ROOT);
    throttle("login-ip",req.getRemoteAddr(),50);
    String attempt=throttle("login",req.getRemoteAddr()+":"+email,10);
    List<Map<String,Object>> rows=db.queryForList("SELECT id,password_hash,active FROM app_user WHERE email=?",email);
    if (rows.isEmpty() || !encoder.matches(password(body.get("password")),(String)rows.getFirst().get("password_hash")) || !Boolean.TRUE.equals(rows.getFirst().get("active")))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Credenciais inválidas");
    db.update("DELETE FROM auth_throttle WHERE key_hash=?",attempt);
    UUID id=(UUID)rows.getFirst().get("id"); cookie(req,res,c.session(id),604800); return c.user(reqWithNewCookie(req,id));
  }
  @PostMapping("/logout") Map<String,Boolean> logout(HttpServletRequest req,HttpServletResponse res) { cookie(req,res,"",0); return Map.of("ok",true); }
  @GetMapping("/me") Object me(HttpServletRequest req) { return c.user(req); }
  @PatchMapping("/preferences") Object preferences(@RequestBody Map<String,Object> b,HttpServletRequest req) {
    String theme=Core.str(b.get("theme")),accent=Core.str(b.get("accent"));
    if (!Set.of("light","dark").contains(theme) || !accent.matches("#[0-9a-fA-F]{6}")) throw Core.bad("Preferências inválidas");
    db.update("UPDATE app_user SET theme=?,accent=? WHERE id=?",theme,accent,c.userId(req)); return c.user(req);
  }
  @PostMapping("/forgot") Map<String,Boolean> forgot(@RequestBody Map<String,Object> b,HttpServletRequest req) {
    throttle("forgot",req.getRemoteAddr(),5);
    String email=Core.optional(b.get("email"),254,"E-mail").toLowerCase(Locale.ROOT);
    List<Map<String,Object>> rows=db.queryForList("SELECT id FROM app_user WHERE email=? AND active=true",email);
    if (!rows.isEmpty()) {
      UUID userId=(UUID)rows.getFirst().get("id"); String token=UUID.randomUUID()+""+UUID.randomUUID();
      db.update("INSERT INTO reset_token(id,user_id,token_hash,expires_at) VALUES(?,?,?,?)",Core.id(),userId,Core.hash(token),java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));
      SimpleMailMessage msg=new SimpleMailMessage(); msg.setFrom(mailFrom); msg.setTo(email); msg.setSubject("Redefinição de acesso - CommitNotes");
      msg.setText("Abra este link em até 30 minutos: "+origin+"/reset?token="+token);
      try { mail.send(msg); } catch (Exception e) { log.error("Password reset e-mail could not be delivered",e); }
    }
    return Map.of("ok",true);
  }
  @PostMapping("/reset") @Transactional Map<String,Boolean> reset(@RequestBody Map<String,Object> b) {
    String token=Core.required(b.get("token"),100,"Token"),password=password(b.get("password"));
    if (password.length()<12 || password.length()>128) throw Core.bad("Senha deve conter entre 12 e 128 caracteres");
    List<Map<String,Object>> rows=db.queryForList("SELECT id,user_id FROM reset_token WHERE token_hash=? AND used_at IS NULL AND expires_at>now() FOR UPDATE",Core.hash(token));
    if (rows.isEmpty()) throw Core.bad("Link inválido ou expirado");
    UUID userId=(UUID)rows.getFirst().get("user_id"); db.update("UPDATE app_user SET password_hash=?,session_epoch=session_epoch+1 WHERE id=?",encoder.encode(password),userId);
    db.update("UPDATE reset_token SET used_at=now() WHERE user_id=?",userId); return Map.of("ok",true);
  }
}
