package com.commitnotes;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api")
class TaskApi {
  private final Core c; private final JdbcTemplate db;
  private static final Map<String,String> PRIORITY_NAMES=Map.of("LOW","Baixa","MEDIUM","Média","HIGH","Alta");
  TaskApi(Core c,JdbcTemplate db) {this.c=c;this.db=db;}
  @GetMapping("/projects/{project}/boards") Object boards(@PathVariable UUID project,HttpServletRequest req) {
    c.role(req,project); return db.queryForList("SELECT * FROM board WHERE project_id=? ORDER BY name",project);
  }
  @PostMapping("/projects/{project}/boards") @Transactional Object createBoard(@PathVariable UUID project,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    c.edit(req,project); UUID id=Core.id(); String name=Core.required(b.get("name"),120,"Quadro");
    db.update("INSERT INTO board(id,project_id,name) VALUES(?,?,?)",id,project,name);
    if(b.get("columns") instanceof List<?> columns) {
      if(columns.size()>20) throw Core.bad("Use até 20 colunas no modelo");
      int position=0;
      for(Object raw:columns) if(raw instanceof Map<?,?> col)
        db.update("INSERT INTO board_column(id,board_id,name,position,is_done,color) VALUES(?,?,?,?,?,?)",Core.id(),id,Core.required(col.get("name"),80,"Coluna"),position++,Boolean.TRUE.equals(col.get("isDone")),Core.color(col.get("color"),"#64748b"));
    }
    c.activity(project,c.userId(req),"board",id,"created",name); return c.one("SELECT * FROM board WHERE id=?",id);
  }
  @PatchMapping("/boards/{board}") Object renameBoard(@PathVariable UUID board,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("board",board); c.edit(req,project); String name=Core.required(b.get("name"),120,"Quadro");
    db.update("UPDATE board SET name=? WHERE id=?",name,board); c.activity(project,c.userId(req),"board",board,"renamed",name); return c.one("SELECT * FROM board WHERE id=?",board);
  }
  @DeleteMapping("/boards/{board}") @Transactional Map<String,Boolean> deleteBoard(@PathVariable UUID board,HttpServletRequest req) {
    UUID project=c.projectOf("board",board); c.owner(req,project); String name=(String)c.one("SELECT name FROM board WHERE id=?",board).get("name");
    db.update("DELETE FROM task WHERE board_id=?",board); db.update("DELETE FROM board WHERE id=?",board);
    c.activity(project,c.userId(req),"board",board,"deleted",name); return Map.of("ok",true);
  }
  @GetMapping("/boards/{board}/view") Object view(@PathVariable UUID board,HttpServletRequest req) {
    UUID project=c.projectOf("board",board); c.role(req,project);
    List<Map<String,Object>> tasks=db.queryForList("SELECT t.*,(SELECT count(*) FROM task_step s WHERE s.task_id=t.id) AS step_count,(SELECT count(*) FROM task_step s WHERE s.task_id=t.id AND s.done=true) AS done_count,(SELECT count(*) FROM task_comment m WHERE m.task_id=t.id) AS comment_count,(SELECT count(*) FROM file_link l WHERE l.task_id=t.id) AS file_count FROM task t WHERE t.board_id=? ORDER BY t.position,t.created_at",board);
    Map<Object,List<Map<String,Object>>> assignees=new HashMap<>();
    for(Map<String,Object> row:db.queryForList("SELECT ta.task_id,u.id,u.name FROM task_assignee ta JOIN app_user u ON u.id=ta.user_id JOIN task t ON t.id=ta.task_id WHERE t.board_id=? ORDER BY u.name",board))
      assignees.computeIfAbsent(row.get("task_id"),k->new ArrayList<>()).add(Map.of("id",row.get("id"),"name",row.get("name")));
    for(Map<String,Object> task:tasks) task.put("assignees",assignees.getOrDefault(task.get("id"),List.of()));
    return Map.of("board",c.one("SELECT * FROM board WHERE id=?",board),
      "columns",db.queryForList("SELECT * FROM board_column WHERE board_id=? ORDER BY position",board),"tasks",tasks);
  }
  @PostMapping("/boards/{board}/columns") Object createColumn(@PathVariable UUID board,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("board",board); c.edit(req,project); UUID id=Core.id(); String name=Core.required(b.get("name"),80,"Coluna");
    db.update("INSERT INTO board_column(id,board_id,name,position,is_done,color) VALUES(?,?,?,(SELECT coalesce(max(position),-1)+1 FROM board_column WHERE board_id=?),?,?)",
      id,board,name,board,Boolean.TRUE.equals(b.get("isDone")),Core.color(b.get("color"),"#64748b"));
    c.activity(project,c.userId(req),"column",id,"created",name); return view(board,req);
  }
  @PatchMapping("/columns/{column}") @Transactional Object updateColumn(@PathVariable UUID column,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    Map<String,Object> old=c.one("SELECT board_id,name,is_done,color FROM board_column WHERE id=?",column);
    UUID board=(UUID)old.get("board_id"),project=c.projectOf("board",board); c.edit(req,project); UUID actor=c.userId(req);
    String name=b.containsKey("name")?Core.required(b.get("name"),80,"Coluna"):(String)old.get("name");
    boolean done=b.containsKey("isDone")?Boolean.TRUE.equals(b.get("isDone")):Boolean.TRUE.equals(old.get("is_done"));
    String color=b.containsKey("color")?Core.color(b.get("color"),(String)old.get("color")):(String)old.get("color");
    db.update("UPDATE board_column SET name=?,is_done=?,color=? WHERE id=?",name,done,color,column);
    if(done!=Boolean.TRUE.equals(old.get("is_done"))) {
      List<UUID> tasks=db.queryForList("SELECT id FROM task WHERE column_id=?",UUID.class,column);
      db.update("UPDATE task SET completed_at=CASE WHEN ? THEN coalesce(completed_at,now()) ELSE NULL END WHERE column_id=?",done,column);
      for(UUID task:tasks) { c.activity(project,actor,"task",task,done?"completed":"reopened",name); c.notifyTask(task,actor,(done?"concluiu a tarefa em ":"reabriu a tarefa em ")+name,Set.of()); }
    }
    if(b.get("position") instanceof Number position) {
      List<UUID> order=new ArrayList<>(db.queryForList("SELECT id FROM board_column WHERE board_id=? AND id<>? ORDER BY position",UUID.class,board,column));
      order.add(Math.max(0,Math.min(order.size(),position.intValue())),column); positions("board_column",order);
    }
    c.activity(project,actor,"column",column,"updated",name+(done?" · concluída":"")); return view(board,req);
  }
  @DeleteMapping("/columns/{column}") @Transactional Object deleteColumn(@PathVariable UUID column,@RequestParam(required=false) UUID moveTo,HttpServletRequest req) {
    Map<String,Object> old=c.one("SELECT board_id,name FROM board_column WHERE id=?",column);
    UUID board=(UUID)old.get("board_id"),project=c.projectOf("board",board); c.edit(req,project); UUID actor=c.userId(req);
    List<UUID> tasks=db.queryForList("SELECT id FROM task WHERE column_id=? ORDER BY position,created_at",UUID.class,column);
    if(!tasks.isEmpty()) {
      if(moveTo==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Escolha uma coluna para receber as tarefas antes de excluir");
      Map<String,Object> target=c.one("SELECT board_id,name,is_done FROM board_column WHERE id=?",moveTo);
      if(!board.equals(target.get("board_id")) || moveTo.equals(column)) throw Core.bad("Coluna de destino inválida");
      boolean done=Boolean.TRUE.equals(target.get("is_done")); String targetName=(String)target.get("name");
      List<UUID> order=new ArrayList<>(db.queryForList("SELECT id FROM task WHERE column_id=? ORDER BY position,created_at",UUID.class,moveTo)); order.addAll(tasks);
      db.update("UPDATE task SET column_id=?,updated_at=now(),completed_at=CASE WHEN ? THEN coalesce(completed_at,now()) ELSE NULL END WHERE column_id=?",moveTo,done,column);
      positions("task",order);
      for(UUID task:tasks) { c.activity(project,actor,"task",task,done?"completed":"status_changed",targetName); c.notifyTask(task,actor,"moveu para "+targetName+" (coluna "+old.get("name")+" excluída)",Set.of()); }
    }
    db.update("DELETE FROM board_column WHERE id=?",column);
    positions("board_column",db.queryForList("SELECT id FROM board_column WHERE board_id=? ORDER BY position",UUID.class,board));
    c.activity(project,actor,"column",column,"deleted",(String)old.get("name")); return view(board,req);
  }
  private void positions(String table,List<UUID> order) {
    List<Object[]> rows=new ArrayList<>(); for(int i=0;i<order.size();i++) rows.add(new Object[]{i,order.get(i)});
    db.batchUpdate("UPDATE "+table+" SET position=? WHERE id=?",rows);
  }
  @GetMapping("/tasks/{id}") Object task(@PathVariable UUID id,HttpServletRequest req) {
    c.role(req,c.projectOf("task",id)); Map<String,Object> task=new LinkedHashMap<>(c.one("SELECT t.*,u.name AS created_by_name FROM task t JOIN app_user u ON u.id=t.created_by WHERE t.id=?",id));
    task.put("assignees",db.queryForList("SELECT u.id,u.name FROM task_assignee ta JOIN app_user u ON u.id=ta.user_id WHERE ta.task_id=? ORDER BY u.name",id));
    task.put("steps",db.queryForList("SELECT * FROM task_step WHERE task_id=? ORDER BY position",id));
    task.put("notes",db.queryForList("SELECT n.id,n.title FROM task_note tn JOIN note n ON n.id=tn.note_id WHERE tn.task_id=? AND n.deleted_at IS NULL ORDER BY n.title",id));
    task.put("files",db.queryForList("SELECT f.id,f.name,f.mime,f.size_bytes,f.created_at FROM stored_file f JOIN file_link l ON l.file_id=f.id WHERE l.task_id=? ORDER BY f.created_at",id));
    task.put("feed",db.queryForList("SELECT * FROM (SELECT a.id,'activity' AS kind,a.action,a.detail,a.created_at,a.actor_id,u.name AS actor_name FROM activity a JOIN app_user u ON u.id=a.actor_id WHERE a.entity_type='task' AND a.entity_id=? AND a.action<>'commented' "
      +"UNION ALL SELECT m.id,'comment',NULL,m.body,m.created_at,m.author_id,u.name FROM task_comment m JOIN app_user u ON u.id=m.author_id WHERE m.task_id=?) feed ORDER BY created_at DESC LIMIT 200",id,id));
    return task;
  }
  @DeleteMapping("/tasks/{id}") Map<String,Boolean> deleteTask(@PathVariable UUID id,HttpServletRequest req) {
    UUID project=c.projectOf("task",id); c.edit(req,project); String title=(String)c.one("SELECT title FROM task WHERE id=?",id).get("title");
    db.update("DELETE FROM task WHERE id=?",id); c.activity(project,c.userId(req),"task",id,"deleted",title); return Map.of("ok",true);
  }
  private Set<UUID> assignees(Object raw,UUID project) {
    Set<UUID> ids=new LinkedHashSet<>();
    if(raw instanceof List<?> list) for(Object value:list) ids.add(Core.uuid(value));
    else if(raw!=null) throw Core.bad("Responsáveis inválidos");
    if(ids.size()>50) throw Core.bad("Use até 50 responsáveis");
    for(UUID id:ids) if(db.queryForObject("SELECT count(*) FROM project_member WHERE project_id=? AND user_id=?",Integer.class,project,id)==0) throw Core.bad("Responsável não pertence ao projeto");
    return ids;
  }
  private Timestamp due(Object raw) {return raw==null || Core.str(raw).isEmpty()?null:Timestamp.from(Core.instant(raw));}
  private String priority(Object raw) {String value=Core.str(raw); if(!PRIORITY_NAMES.containsKey(value)) throw Core.bad("Prioridade inválida"); return value;}
  @PostMapping("/boards/{board}/tasks") @Transactional Object createTask(@PathVariable UUID board,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("board",board); c.edit(req,project); UUID column=Core.uuid(b.get("columnId"));
    if(!board.equals(c.one("SELECT board_id FROM board_column WHERE id=?",column).get("board_id"))) throw Core.bad("Coluna de outro quadro");
    UUID id=Core.id(),actor=c.userId(req); String title=Core.required(b.get("title"),200,"Tarefa"); Set<UUID> people=assignees(b.get("assigneeIds"),project);
    db.update("INSERT INTO task(id,project_id,board_id,column_id,title,description,due_at,priority,position,created_by) VALUES(?,?,?,?,?,?,?,?,(SELECT coalesce(max(position),-1)+1 FROM task WHERE column_id=?),?)",
      id,project,board,column,title,Core.optional(b.get("description"),10000,"Descrição"),due(b.get("dueAt")),priority(b.getOrDefault("priority","MEDIUM")),column,actor);
    for(UUID user:people) db.update("INSERT INTO task_assignee(task_id,user_id) VALUES(?,?)",id,user);
    boolean done=doneColumn(column); if(done) db.update("UPDATE task SET completed_at=now() WHERE id=?",id);
    c.activity(project,actor,"task",id,"created",title);
    for(UUID user:people) c.activity(project,actor,"task",id,"assignee_added",c.userName(user));
    if(done) c.activity(project,actor,"task",id,"completed",(String)c.one("SELECT name FROM board_column WHERE id=?",column).get("name"));
    for(UUID user:people) if(!user.equals(actor)) c.notify(user,project,id,actor,title,c.userName(actor)+" adicionou você como responsável");
    return task(id,req);
  }
  @PatchMapping("/tasks/{id}") @Transactional Object updateTask(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    Map<String,Object> old=(Map<String,Object>)task(id,req); UUID project=(UUID)old.get("project_id"); c.edit(req,project); UUID actor=c.userId(req);
    String title=Core.required(b.getOrDefault("title",old.get("title")),200,"Tarefa");
    String description=Core.optional(b.getOrDefault("description",old.get("description")),10000,"Descrição");
    Timestamp dueAt=b.containsKey("dueAt")?due(b.get("dueAt")):(Timestamp)old.get("due_at");
    String priority=b.containsKey("priority")?priority(b.get("priority")):(String)old.get("priority");
    UUID oldColumn=(UUID)old.get("column_id"),column=b.containsKey("columnId")?Core.uuid(b.get("columnId")):oldColumn;
    if(!old.get("board_id").equals(c.one("SELECT board_id FROM board_column WHERE id=?",column).get("board_id"))) throw Core.bad("Coluna de outro quadro");
    boolean done=doneColumn(column);
    db.update("UPDATE task SET title=?,description=?,due_at=?,priority=?,column_id=?,updated_at=now(),completed_at=CASE WHEN ? THEN coalesce(completed_at,now()) ELSE NULL END WHERE id=?",
      title,description,dueAt,priority,column,done,id);
    Integer position=b.get("position") instanceof Number n?n.intValue():null;
    if(position!=null || !column.equals(oldColumn)) {
      List<UUID> order=new ArrayList<>(db.queryForList("SELECT id FROM task WHERE column_id=? AND id<>? ORDER BY position,created_at",UUID.class,column,id));
      order.add(position==null?order.size():Math.max(0,Math.min(order.size(),position)),id); positions("task",order);
    }
    List<String> changes=new ArrayList<>(); Set<UUID> added=new LinkedHashSet<>(),informed=new LinkedHashSet<>();
    if(b.containsKey("assigneeIds")) {
      Set<UUID> next=assignees(b.get("assigneeIds"),project),previous=new LinkedHashSet<>();
      for(Map<String,Object> person:(List<Map<String,Object>>)old.get("assignees")) previous.add((UUID)person.get("id"));
      for(UUID user:previous) if(!next.contains(user)) {
        String name=c.userName(user); db.update("DELETE FROM task_assignee WHERE task_id=? AND user_id=?",id,user);
        c.activity(project,actor,"task",id,"assignee_removed",name); changes.add("removeu "+name+" dos responsáveis");
        if(!user.equals(actor)) c.notify(user,project,id,actor,title,c.userName(actor)+" removeu você dos responsáveis"); informed.add(user);
      }
      for(UUID user:next) if(!previous.contains(user)) {
        String name=c.userName(user); db.update("INSERT INTO task_assignee(task_id,user_id) VALUES(?,?)",id,user);
        c.activity(project,actor,"task",id,"assignee_added",name); changes.add("adicionou "+name+" como responsável"); added.add(user);
      }
    }
    if(!column.equals(oldColumn)) {
      String name=(String)c.one("SELECT name FROM board_column WHERE id=?",column).get("name");
      c.activity(project,actor,"task",id,done?"completed":old.get("completed_at")!=null?"reopened":"status_changed",name); changes.add((done?"concluiu a tarefa em ":"moveu para ")+name);
    }
    if(!Objects.equals(old.get("due_at"),dueAt)) { c.activity(project,actor,"task",id,"due_changed",dueAt==null?"":dueAt.toInstant().toString()); changes.add(dueAt==null?"removeu o prazo":"alterou o prazo"); }
    if(!Objects.equals(old.get("title"),title)) changes.add("renomeou a tarefa");
    if(!Objects.equals(old.get("description"),description)) changes.add("editou a descrição");
    if(!Objects.equals(old.get("priority"),priority)) changes.add("alterou a prioridade para "+PRIORITY_NAMES.get(priority));
    if(!Objects.equals(old.get("title"),title) || !Objects.equals(old.get("description"),description) || !Objects.equals(old.get("priority"),priority)) c.activity(project,actor,"task",id,"updated",title);
    for(UUID user:added) if(!user.equals(actor)) c.notify(user,project,id,actor,title,c.userName(actor)+" adicionou você como responsável");
    informed.addAll(added); if(!changes.isEmpty()) c.notifyTask(id,actor,String.join(", ",changes),informed);
    return task(id,req);
  }
  private boolean doneColumn(UUID column) {return Boolean.TRUE.equals(c.one("SELECT is_done FROM board_column WHERE id=?",column).get("is_done"));}
  @PostMapping("/tasks/{id}/steps") Object addStep(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("task",id); c.edit(req,project); UUID step=Core.id(),actor=c.userId(req); String label=Core.required(b.get("label"),200,"Etapa");
    db.update("INSERT INTO task_step(id,task_id,label,position) VALUES(?,?,?,(SELECT coalesce(max(position),-1)+1 FROM task_step WHERE task_id=?))",step,id,label,id);
    c.activity(project,actor,"task",id,"step_added",label); c.notifyTask(id,actor,"adicionou a etapa “"+label+"”",Set.of()); return task(id,req);
  }
  @PatchMapping("/steps/{step}") Object updateStep(@PathVariable UUID step,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    Map<String,Object> row=c.one("SELECT task_id,label,done FROM task_step WHERE id=?",step); UUID task=(UUID)row.get("task_id"),project=c.projectOf("task",task); c.edit(req,project);
    String label=b.containsKey("label")?Core.required(b.get("label"),200,"Etapa"):(String)row.get("label");
    boolean wasDone=Boolean.TRUE.equals(row.get("done")),done=b.containsKey("done")?Boolean.TRUE.equals(b.get("done")):wasDone; UUID actor=c.userId(req);
    db.update("UPDATE task_step SET label=?,done=? WHERE id=?",label,done,step);
    c.activity(project,actor,"task",task,done && !wasDone?"step_completed":"step_updated",label);
    c.notifyTask(task,actor,(done && !wasDone?"concluiu a etapa “":!done && wasDone?"reabriu a etapa “":"alterou a etapa “")+label+"”",Set.of()); return task(task,req);
  }
  @DeleteMapping("/steps/{step}") Object deleteStep(@PathVariable UUID step,HttpServletRequest req) {
    Map<String,Object> row=c.one("SELECT task_id,label FROM task_step WHERE id=?",step); UUID task=(UUID)row.get("task_id"),project=c.projectOf("task",task); c.edit(req,project); UUID actor=c.userId(req);
    db.update("DELETE FROM task_step WHERE id=?",step); c.activity(project,actor,"task",task,"step_removed",(String)row.get("label"));
    c.notifyTask(task,actor,"removeu a etapa “"+row.get("label")+"”",Set.of()); return task(task,req);
  }
  @PostMapping("/tasks/{id}/notes") Object linkNote(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("task",id); c.edit(req,project); UUID note=Core.uuid(b.get("noteId")),actor=c.userId(req);
    if(!project.equals(c.projectOf("note",note))) throw Core.bad("Nota de outro projeto");
    String title=(String)c.one("SELECT title FROM note WHERE id=?",note).get("title");
    if(db.update("INSERT INTO task_note(task_id,note_id) VALUES(?,?) ON CONFLICT DO NOTHING",id,note)>0) {
      c.activity(project,actor,"task",id,"note_linked",title); c.notifyTask(id,actor,"vinculou a nota “"+title+"”",Set.of());
    }
    return task(id,req);
  }
  @DeleteMapping("/tasks/{id}/notes/{note}") Object unlinkNote(@PathVariable UUID id,@PathVariable UUID note,HttpServletRequest req) {
    UUID project=c.projectOf("task",id); c.edit(req,project); UUID actor=c.userId(req);
    if(db.update("DELETE FROM task_note WHERE task_id=? AND note_id=?",id,note)>0) {
      String title=(String)c.one("SELECT title FROM note WHERE id=?",note).get("title");
      c.activity(project,actor,"task",id,"note_unlinked",title); c.notifyTask(id,actor,"desvinculou a nota “"+title+"”",Set.of());
    }
    return task(id,req);
  }
  @PostMapping("/tasks/{id}/comments") Object comment(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("task",id); c.edit(req,project); UUID actor=c.userId(req); String body=Core.required(b.get("body"),5000,"Comentário");
    db.update("INSERT INTO task_comment(id,task_id,author_id,body,created_at) VALUES(?,?,?,?,clock_timestamp())",Core.id(),id,actor,body);
    c.activity(project,actor,"task",id,"commented",(String)c.one("SELECT title FROM task WHERE id=?",id).get("title"));
    c.notifyTask(id,actor,"comentou: "+Core.clip(body.replaceAll("\\s+"," "),300),Set.of()); return task(id,req);
  }
  @DeleteMapping("/comments/{comment}") Object deleteComment(@PathVariable UUID comment,HttpServletRequest req) {
    Map<String,Object> row=c.one("SELECT task_id,author_id FROM task_comment WHERE id=?",comment); UUID task=(UUID)row.get("task_id");
    String role=c.role(req,c.projectOf("task",task));
    if(!c.userId(req).equals(row.get("author_id")) && !role.equals("OWNER")) throw Core.forbidden();
    db.update("DELETE FROM task_comment WHERE id=?",comment); return task(task,req);
  }
  @GetMapping("/projects/{project}/schedule") Object schedule(@PathVariable UUID project,@RequestParam(required=false) String from,@RequestParam(required=false) String to,
      @RequestParam(required=false) UUID person,HttpServletRequest req) {
    c.role(req,project); Instant start=from==null?Instant.now().minusSeconds(31536000):Core.instant(from),end=to==null?Instant.now().plusSeconds(31536000):Core.instant(to);
    if(end.isBefore(start)) throw Core.bad("Período inválido");
    return db.queryForList("SELECT t.id,t.title,t.due_at,t.completed_at,t.priority,t.board_id,b.name AS board_name,c.name AS column_name,"
      +"(SELECT string_agg(u.name,', ' ORDER BY u.name) FROM task_assignee ta JOIN app_user u ON u.id=ta.user_id WHERE ta.task_id=t.id) AS assignee_names "
      +"FROM task t JOIN board b ON b.id=t.board_id JOIN board_column c ON c.id=t.column_id WHERE t.project_id=? AND t.due_at>=? AND t.due_at<=? "
      +"AND (?::uuid IS NULL OR EXISTS (SELECT 1 FROM task_assignee ta WHERE ta.task_id=t.id AND ta.user_id=?)) ORDER BY t.due_at",
      project,Timestamp.from(start),Timestamp.from(end),person,person);
  }
}
