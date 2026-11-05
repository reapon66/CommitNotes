package com.commitnotes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api")
class ProjectApi {
  private final Core c; private final JdbcTemplate db;
  ProjectApi(Core c,JdbcTemplate db) { this.c=c; this.db=db; }
  @GetMapping("/projects") Object projects(HttpServletRequest req) {
    return db.queryForList("SELECT p.*,m.role FROM project p JOIN project_member m ON p.id=m.project_id WHERE m.user_id=? ORDER BY p.created_at DESC",c.userId(req));
  }
  @PostMapping("/projects") @Transactional Object create(@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID id=Core.id(), user=c.userId(req); String name=Core.required(b.get("name"),120,"Projeto");
    db.update("INSERT INTO project(id,name,description,created_by) VALUES(?,?,?,?)",id,name,Core.optional(b.get("description"),2000,"Descrição"),user);
    db.update("INSERT INTO project_member(project_id,user_id,role) VALUES(?,?,?)",id,user,"OWNER");
    UUID board=Core.id(); db.update("INSERT INTO board(id,project_id,name) VALUES(?,?,?)",board,id,"Quadro principal");
    String[] names={"A fazer","Em andamento","Concluído"},colors={"#64748b","#3b82f6","#22c55e"};
    for(int i=0;i<names.length;i++) db.update("INSERT INTO board_column(id,board_id,name,position,is_done,color) VALUES(?,?,?,?,?,?)",Core.id(),board,names[i],i,i==2,colors[i]);
    c.activity(id,user,"project",id,"created",name); return c.one("SELECT p.*,m.role FROM project p JOIN project_member m ON p.id=m.project_id WHERE p.id=? AND m.user_id=?",id,user);
  }
  @GetMapping("/projects/{id}") Object project(@PathVariable UUID id,HttpServletRequest req) {
    c.role(req,id); return c.one("SELECT p.*,m.role FROM project p JOIN project_member m ON p.id=m.project_id WHERE p.id=? AND m.user_id=?",id,c.userId(req));
  }
  @PatchMapping("/projects/{id}") Object update(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    c.owner(req,id); String name=Core.required(b.get("name"),120,"Projeto");
    db.update("UPDATE project SET name=?,description=? WHERE id=?",name,Core.optional(b.get("description"),2000,"Descrição"),id);
    c.activity(id,c.userId(req),"project",id,"updated",name); return project(id,req);
  }
  @GetMapping("/projects/{id}/members") Object members(@PathVariable UUID id,HttpServletRequest req) {
    c.role(req,id); return db.queryForList("SELECT u.id,u.name,u.email,m.role FROM project_member m JOIN app_user u ON u.id=m.user_id WHERE m.project_id=? ORDER BY u.name",id);
  }
  @PostMapping("/projects/{id}/members") Object addMember(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    c.owner(req,id); String email=Core.required(b.get("email"),254,"E-mail").toLowerCase(Locale.ROOT),role=Core.str(b.get("role"));
    if(!Set.of("EDITOR","READER").contains(role)) throw Core.bad("Perfil inválido");
    List<Map<String,Object>> users=db.queryForList("SELECT id FROM app_user WHERE email=? AND active=true",email);
    if(users.isEmpty()) throw Core.missing(); UUID user=(UUID)users.getFirst().get("id");
    if(db.queryForObject("SELECT count(*) FROM project_member WHERE project_id=? AND user_id=?",Integer.class,id,user)>0)
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Já participa do projeto");
    db.update("INSERT INTO project_member(project_id,user_id,role) VALUES(?,?,?)",id,user,role);
    c.activity(id,c.userId(req),"member",user,"added",role); return members(id,req);
  }
  @PatchMapping("/projects/{id}/members/{user}") Object memberRole(@PathVariable UUID id,@PathVariable UUID user,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    c.owner(req,id); String role=Core.str(b.get("role")); if(!Set.of("EDITOR","READER").contains(role)) throw Core.bad("Perfil inválido");
    if(db.update("UPDATE project_member SET role=? WHERE project_id=? AND user_id=? AND role<>'OWNER'",role,id,user)==0) throw Core.bad("Não é possível alterar o proprietário");
    c.activity(id,c.userId(req),"member",user,"role_changed",role); return members(id,req);
  }
  @DeleteMapping("/projects/{id}/members/{user}") @Transactional Object removeMember(@PathVariable UUID id,@PathVariable UUID user,HttpServletRequest req) {
    c.owner(req,id); if(db.update("DELETE FROM project_member WHERE project_id=? AND user_id=? AND role<>'OWNER'",id,user)==0) throw Core.bad("Não é possível remover o proprietário");
    db.update("DELETE FROM task_assignee WHERE user_id=? AND task_id IN (SELECT id FROM task WHERE project_id=?)",user,id);
    c.activity(id,c.userId(req),"member",user,"removed",""); return members(id,req);
  }
  @GetMapping("/projects/{id}/summary") Object summary(@PathVariable UUID id,HttpServletRequest req) {
    c.role(req,id); Map<String,Object> summary=new LinkedHashMap<>(db.queryForMap("SELECT "
      +"(SELECT count(*) FROM note WHERE project_id=? AND deleted_at IS NULL AND archived_at IS NULL AND template=false) AS notes,"
      +"(SELECT count(*) FROM note n WHERE n.project_id=? AND n.deleted_at IS NULL AND n.archived_at IS NULL AND n.template=false AND NOT EXISTS (SELECT 1 FROM note_tag nt WHERE nt.note_id=n.id)) AS untagged_notes,"
      +"(SELECT count(*) FROM task WHERE project_id=? AND completed_at IS NULL) AS open_tasks,"
      +"(SELECT count(*) FROM task WHERE project_id=? AND completed_at IS NOT NULL) AS done_tasks,"
      +"(SELECT count(*) FROM task t JOIN task_assignee ta ON ta.task_id=t.id WHERE t.project_id=? AND ta.user_id=? AND t.completed_at IS NULL) AS my_open_tasks,"
      +"(SELECT count(*) FROM stored_file WHERE project_id=?) AS files",id,id,id,id,id,c.userId(req),id));
    summary.put("tags",db.queryForList("SELECT t.id,t.name,t.color,count(n.id) AS notes FROM tag t LEFT JOIN note_tag nt ON nt.tag_id=t.id "
      +"LEFT JOIN note n ON n.id=nt.note_id AND n.deleted_at IS NULL AND n.archived_at IS NULL AND n.template=false WHERE t.project_id=? GROUP BY t.id ORDER BY count(n.id) DESC,t.name",id));
    return summary;
  }
  @GetMapping("/projects/{id}/folders") Object folders(@PathVariable UUID id,HttpServletRequest req) { c.role(req,id); return db.queryForList("SELECT * FROM folder WHERE project_id=? ORDER BY name",id); }
  @PostMapping("/projects/{id}/folders") Object folder(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    c.edit(req,id); UUID parent=Core.nullableUuid(b.get("parentId"));
    if(parent!=null && !c.projectOf("folder",parent).equals(id)) throw Core.bad("Pasta de outro projeto");
    UUID fid=Core.id(); db.update("INSERT INTO folder(id,project_id,parent_id,name) VALUES(?,?,?,?)",fid,id,parent,Core.required(b.get("name"),100,"Pasta"));
    c.activity(id,c.userId(req),"folder",fid,"created",Core.str(b.get("name"))); return c.one("SELECT * FROM folder WHERE id=?",fid);
  }
  @PatchMapping("/folders/{id}") Object renameFolder(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("folder",id); c.edit(req,project); String name=Core.required(b.get("name"),100,"Pasta");
    db.update("UPDATE folder SET name=? WHERE id=?",name,id); c.activity(project,c.userId(req),"folder",id,"renamed",name); return c.one("SELECT * FROM folder WHERE id=?",id);
  }
  @DeleteMapping("/folders/{id}") Map<String,Boolean> deleteFolder(@PathVariable UUID id,HttpServletRequest req) {
    UUID project=c.projectOf("folder",id); c.edit(req,project); db.update("DELETE FROM folder WHERE id=?",id);
    c.activity(project,c.userId(req),"folder",id,"deleted",""); return Map.of("ok",true);
  }
  @GetMapping("/projects/{id}/tags") Object tags(@PathVariable UUID id,HttpServletRequest req) { c.role(req,id); return db.queryForList("SELECT * FROM tag WHERE project_id=? ORDER BY name",id); }
  private static final String[] TAG_COLORS={"#6366f1","#f97316","#22c55e","#ec4899","#0ea5e9","#eab308","#8b5cf6","#ef4444","#14b8a6","#64748b"};
  @PostMapping("/projects/{id}/tags") Object tag(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    c.edit(req,id); String name=Core.required(b.get("name"),60,"Etiqueta");
    List<Map<String,Object>> existing=db.queryForList("SELECT * FROM tag WHERE project_id=? AND name=?",id,name);
    if(!existing.isEmpty()) return existing.getFirst(); UUID tagId=Core.id();
    int count=db.queryForObject("SELECT count(*) FROM tag WHERE project_id=?",Integer.class,id);
    db.update("INSERT INTO tag(id,project_id,name,color) VALUES(?,?,?,?)",tagId,id,name,Core.color(b.get("color"),TAG_COLORS[count%TAG_COLORS.length]));
    c.activity(id,c.userId(req),"tag",tagId,"created",name); return c.one("SELECT * FROM tag WHERE id=?",tagId);
  }
  @PatchMapping("/tags/{id}") Object updateTag(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("tag",id); c.edit(req,project); Map<String,Object> old=c.one("SELECT name,color FROM tag WHERE id=?",id);
    String name=b.containsKey("name")?Core.required(b.get("name"),60,"Etiqueta"):(String)old.get("name");
    String color=b.containsKey("color")?Core.color(b.get("color"),(String)old.get("color")):(String)old.get("color");
    db.update("UPDATE tag SET name=?,color=? WHERE id=?",name,color,id); c.activity(project,c.userId(req),"tag",id,name.equals(old.get("name"))?"updated":"renamed",name);
    return c.one("SELECT * FROM tag WHERE id=?",id);
  }
  @DeleteMapping("/tags/{id}") Map<String,Boolean> deleteTag(@PathVariable UUID id,HttpServletRequest req) {
    UUID project=c.projectOf("tag",id); c.edit(req,project); db.update("DELETE FROM tag WHERE id=?",id);
    c.activity(project,c.userId(req),"tag",id,"deleted",""); return Map.of("ok",true);
  }
  @GetMapping("/projects/{id}/timeline") Object timeline(@PathVariable UUID id,@RequestParam(required=false) UUID person,
      @RequestParam(required=false) String from,@RequestParam(required=false) String to,HttpServletRequest req) {
    c.role(req,id); java.time.Instant start=from==null?java.time.Instant.EPOCH:Core.instant(from);
    java.time.Instant end=to==null?java.time.Instant.now().plusSeconds(31536000):Core.instant(to);
    if(end.isBefore(start)) throw Core.bad("Período inválido");
    return db.queryForList("SELECT a.*,u.name AS actor_name FROM activity a JOIN app_user u ON u.id=a.actor_id WHERE a.project_id=? AND a.created_at>=? AND a.created_at<=? AND (?::uuid IS NULL OR a.actor_id=?) ORDER BY a.created_at DESC LIMIT 500",
      id,java.sql.Timestamp.from(start),java.sql.Timestamp.from(end),person,person);
  }
}
