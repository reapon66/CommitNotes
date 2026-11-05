package com.commitnotes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/notifications")
class NotificationApi {
  private final Core c; private final JdbcTemplate db;
  NotificationApi(Core c,JdbcTemplate db) {this.c=c;this.db=db;}
  private static final String VISIBLE="n.user_id=? AND (n.project_id IS NULL OR EXISTS (SELECT 1 FROM project_member m WHERE m.project_id=n.project_id AND m.user_id=n.user_id))";
  @GetMapping Map<String,Object> list(HttpServletRequest req) {
    UUID user=c.userId(req);
    return Map.of("items",db.queryForList("SELECT n.id,n.project_id,n.task_id,n.title,n.body,n.url,n.read_at,n.created_at,u.name AS actor_name,p.name AS project_name FROM notification n LEFT JOIN app_user u ON u.id=n.actor_id LEFT JOIN project p ON p.id=n.project_id WHERE "+VISIBLE+" ORDER BY n.created_at DESC LIMIT 50",user),
      "unread",db.queryForObject("SELECT count(*) FROM notification n WHERE "+VISIBLE+" AND n.read_at IS NULL",Integer.class,user));
  }
  @PostMapping("/read") Map<String,Object> read(@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID user=c.userId(req);
    if(Boolean.TRUE.equals(b.get("all"))) db.update("UPDATE notification SET read_at=now() WHERE user_id=? AND read_at IS NULL",user);
    else if(b.get("ids") instanceof List<?> ids && ids.size()<=100) for(Object id:ids) db.update("UPDATE notification SET read_at=now() WHERE id=? AND user_id=? AND read_at IS NULL",Core.uuid(id),user);
    else throw Core.bad("Informe as notificações");
    return list(req);
  }
}

@Component
class NotificationWorker {
  private final JdbcTemplate db; private final PushSender push;
  NotificationWorker(JdbcTemplate db,PushSender push) {this.db=db;this.push=push;}
  /** Mirrors new in-app notifications as Web Push; stale ones (over an hour old) are only marked as handled. */
  @Scheduled(fixedDelay=10000)
  @Transactional
  void send() {
    for(Map<String,Object> row:db.queryForList("SELECT id,user_id,task_id,title,body,url,created_at>now()-interval '1 hour' AS fresh FROM notification WHERE pushed_at IS NULL ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED")) {
      UUID id=(UUID)row.get("id"),user=(UUID)row.get("user_id");
      if(Boolean.TRUE.equals(row.get("fresh")) && push.ready(user))
        push.send(user,Map.of("id",id.toString(),"title",row.get("title"),"body",row.get("body"),"url",row.get("url"),"tag",row.get("task_id")==null?"notification-"+id:"task-"+row.get("task_id")));
      db.update("UPDATE notification SET pushed_at=now() WHERE id=?",id);
    }
  }
  @Scheduled(fixedDelay=3600000) void purge() { db.update("DELETE FROM notification WHERE created_at<now()-interval '90 days'"); }
}
