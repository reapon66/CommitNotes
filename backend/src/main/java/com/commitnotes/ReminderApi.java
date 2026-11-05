package com.commitnotes;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Security;
import java.sql.Timestamp;
import java.time.Instant;
import java.net.URI;
import java.util.*;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/reminders")
class ReminderApi {
  private final Core c; private final JdbcTemplate db;
  ReminderApi(Core c,JdbcTemplate db) {this.c=c;this.db=db;}
  @GetMapping Object list(HttpServletRequest req) {
    return db.queryForList("SELECT r.*,p.name AS project_name,n.title AS note_title FROM reminder r LEFT JOIN project p ON p.id=r.project_id LEFT JOIN note n ON n.id=r.note_id WHERE r.user_id=? ORDER BY r.due_at DESC LIMIT 500",c.userId(req));
  }
  @GetMapping("/due") Object due(HttpServletRequest req) {
    return db.queryForList("SELECT * FROM reminder WHERE user_id=? AND completed_at IS NULL AND due_at<=now() ORDER BY due_at",c.userId(req));
  }
  @PostMapping Object create(@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID user=c.userId(req),project=Core.nullableUuid(b.get("projectId")),note=Core.nullableUuid(b.get("noteId"));
    if(project!=null) c.role(req,project);
    if(note!=null) { UUID noteProject=c.projectOf("note",note); c.role(req,noteProject); if(project!=null && !project.equals(noteProject)) throw Core.bad("Nota de outro projeto"); project=noteProject; }
    UUID id=Core.id(); db.update("INSERT INTO reminder(id,user_id,project_id,note_id,title,description,due_at) VALUES(?,?,?,?,?,?,?)",
      id,user,project,note,Core.required(b.get("title"),200,"Lembrete"),Core.optional(b.get("description"),1000,"Descrição"),Timestamp.from(Core.instant(b.get("dueAt"))));
    return c.one("SELECT * FROM reminder WHERE id=?",id);
  }
  @PatchMapping("/{id}") Object update(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    Map<String,Object> old=c.one("SELECT * FROM reminder WHERE id=? AND user_id=?",id,c.userId(req));
    String action=Core.str(b.get("action"));
    switch(action) {
      case "complete" -> db.update("UPDATE reminder SET completed_at=now() WHERE id=?",id);
      case "snooze" -> {
        int minutes=b.get("minutes") instanceof Number n?n.intValue():0;
        if(minutes<1 || minutes>10080) throw Core.bad("Adiamento deve ser de 1 a 10080 minutos");
        db.update("UPDATE reminder SET due_at=?,notified_at=NULL,push_attempts=0,push_retry_at=NULL WHERE id=?",Timestamp.from(Instant.now().plusSeconds(minutes*60L)),id);
      }
      default -> throw Core.bad("Ação inválida");
    }
    return c.one("SELECT * FROM reminder WHERE id=?",id);
  }
  @DeleteMapping("/{id}") Map<String,Boolean> delete(@PathVariable UUID id,HttpServletRequest req) {
    if(db.update("DELETE FROM reminder WHERE id=? AND user_id=?",id,c.userId(req))==0) throw Core.missing(); return Map.of("ok",true);
  }
}

@RestController @RequestMapping("/api/push")
class PushApi {
  private final Core c; private final JdbcTemplate db;
  @Value("${app.vapid-public}") String publicKey;
  PushApi(Core c,JdbcTemplate db) {this.c=c;this.db=db;}
  @GetMapping("/key") Map<String,String> key(HttpServletRequest req) { c.userId(req); return Map.of("publicKey",publicKey); }
  @PostMapping("/subscriptions") Map<String,Boolean> subscribe(@RequestBody Map<String,Object> b,HttpServletRequest req) {
    String endpoint=Core.required(b.get("endpoint"),2000,"Endpoint"),p256dh=Core.required(b.get("p256dh"),300,"Chave"),auth=Core.required(b.get("auth"),100,"Autenticação");
    URI uri; try { uri=URI.create(endpoint); } catch(Exception e) { throw Core.bad("Endpoint push inválido"); }
    String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
    if(!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getPort()!=-1 ||
      !(host.equals("fcm.googleapis.com") || host.equals("updates.push.services.mozilla.com") || host.equals("web.push.apple.com") || host.endsWith(".notify.windows.com")))
      throw Core.bad("Provedor push não permitido");
    try {
      if(Base64.getUrlDecoder().decode(p256dh).length!=65 || Base64.getUrlDecoder().decode(auth).length<16) throw Core.bad("Chaves push inválidas");
    } catch(IllegalArgumentException e) { throw Core.bad("Chaves push inválidas"); }
    db.update("INSERT INTO push_subscription(id,user_id,endpoint,p256dh,auth_secret) VALUES(?,?,?,?,?) ON CONFLICT(endpoint) DO UPDATE SET user_id=excluded.user_id,p256dh=excluded.p256dh,auth_secret=excluded.auth_secret",
      Core.id(),c.userId(req),endpoint,p256dh,auth); return Map.of("ok",true);
  }
  @DeleteMapping("/subscriptions") Map<String,Boolean> unsubscribe(@RequestBody Map<String,Object> b,HttpServletRequest req) {
    db.update("DELETE FROM push_subscription WHERE user_id=? AND endpoint=?",c.userId(req),Core.str(b.get("endpoint"))); return Map.of("ok",true);
  }
}

@Component
class PushSender {
  static { if(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)==null) Security.addProvider(new BouncyCastleProvider()); }
  private final JdbcTemplate db; private final ObjectMapper json=new ObjectMapper();
  private static final Logger log=LoggerFactory.getLogger(PushSender.class);
  @Value("${app.vapid-public}") String publicKey;
  @Value("${app.vapid-private}") String privateKey;
  @Value("${app.vapid-subject}") String subject;
  PushSender(JdbcTemplate db) {this.db=db;}
  boolean ready(UUID user) {
    return !publicKey.isBlank() && !privateKey.isBlank() && db.queryForObject("SELECT count(*) FROM push_subscription WHERE user_id=?",Integer.class,user)>0;
  }
  /** Sends one Web Push message to every browser of the user; true when at least one push service accepted it. */
  boolean send(UUID user,Map<String,Object> message) {
    boolean delivered=false; PushService push;
    try { push=new PushService(publicKey,privateKey,subject); } catch(Exception e) { log.error("VAPID keys are invalid",e); return false; }
    for(Map<String,Object> sub:db.queryForList("SELECT id,endpoint,p256dh,auth_secret FROM push_subscription WHERE user_id=?",user)) try {
      var response=push.send(new Notification((String)sub.get("endpoint"),(String)sub.get("p256dh"),(String)sub.get("auth_secret"),json.writeValueAsString(message)));
      int code=response.getStatusLine().getStatusCode();
      if(code>=200 && code<300) delivered=true;
      if(code==404 || code==410) db.update("DELETE FROM push_subscription WHERE id=?",sub.get("id"));
    } catch(Exception e) { log.warn("Push delivery failed for user {}",user,e); }
    return delivered;
  }
}

@Component
class ReminderWorker {
  private final JdbcTemplate db; private final PushSender push;
  ReminderWorker(JdbcTemplate db,PushSender push) {this.db=db;this.push=push;}
  @Scheduled(fixedDelay=10000)
  @Transactional
  void sendDue() {
    List<Map<String,Object>> due=db.queryForList("SELECT id,user_id,title,description,push_attempts FROM reminder WHERE due_at<=now() AND notified_at IS NULL AND completed_at IS NULL AND (push_retry_at IS NULL OR push_retry_at<=now()) ORDER BY due_at LIMIT 50 FOR UPDATE SKIP LOCKED");
    for(Map<String,Object> row:due) {
      UUID id=(UUID)row.get("id"),user=(UUID)row.get("user_id");
      if(!push.ready(user)) { db.update("UPDATE reminder SET notified_at=now() WHERE id=?",id); continue; }
      boolean delivered=push.send(user,Map.of("id",id.toString(),"title",row.get("title"),"body",row.get("description"),"url","/reminders?focus="+id,"tag","reminder-"+id));
      if(delivered || ((Integer)row.get("push_attempts"))>=2) db.update("UPDATE reminder SET notified_at=now(),push_retry_at=NULL WHERE id=?",id);
      else db.update("UPDATE reminder SET push_attempts=push_attempts+1,push_retry_at=now()+interval '5 minutes' WHERE id=?",id);
    }
  }
}
