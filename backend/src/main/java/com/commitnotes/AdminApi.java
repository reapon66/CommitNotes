package com.commitnotes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin")
class AdminApi {
  private final Core c; private final JdbcTemplate db; private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
  AdminApi(Core c,JdbcTemplate db) { this.c=c; this.db=db; }
  @GetMapping("/users") Object users(HttpServletRequest req) {
    c.admin(req); return db.queryForList("SELECT id,name,email,active,platform_admin,created_at FROM app_user ORDER BY created_at DESC LIMIT 500");
  }
  @PostMapping("/users") Object create(@RequestBody Map<String,Object> b,HttpServletRequest req) {
    c.admin(req); String name=Core.required(b.get("name"),120,"Nome"),email=Core.required(b.get("email"),254,"E-mail").toLowerCase(Locale.ROOT),password=b.get("password") instanceof String s?s:"";
    if(!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || password.length()<12 || password.length()>128) throw Core.bad("E-mail ou senha inválidos");
    UUID id=Core.id(); db.update("INSERT INTO app_user(id,name,email,password_hash) VALUES(?,?,?,?)",id,name,email,encoder.encode(password)); audit(req,id,"created",""); return users(req);
  }
  @PatchMapping("/users/{id}") Object update(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    c.admin(req); c.one("SELECT id FROM app_user WHERE id=?",id);
    if(id.equals(c.userId(req)) && Boolean.FALSE.equals(b.get("active"))) throw Core.bad("Não é possível suspender a própria conta");
    if(id.equals(c.userId(req)) && Boolean.FALSE.equals(b.get("platformAdmin"))) throw Core.bad("Não é possível remover a própria administração");
    if(b.containsKey("active")) { db.update("UPDATE app_user SET active=? WHERE id=?",Boolean.TRUE.equals(b.get("active")),id); audit(req,id,Boolean.TRUE.equals(b.get("active"))?"activated":"suspended",""); }
    if(b.containsKey("platformAdmin")) { db.update("UPDATE app_user SET platform_admin=? WHERE id=?",Boolean.TRUE.equals(b.get("platformAdmin")),id); audit(req,id,"global_role_changed",String.valueOf(b.get("platformAdmin"))); }
    return users(req);
  }
  @GetMapping("/audit") Object audit(HttpServletRequest req) { c.admin(req); return db.queryForList("SELECT a.*,u.name AS admin_name FROM admin_audit a JOIN app_user u ON u.id=a.admin_id ORDER BY a.created_at DESC LIMIT 500"); }
  private void audit(HttpServletRequest req,UUID target,String action,String detail) {
    db.update("INSERT INTO admin_audit(id,admin_id,target_user_id,action,detail) VALUES(?,?,?,?,?)",Core.id(),c.userId(req),target,action,detail);
  }
}
