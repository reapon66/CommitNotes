package com.commitnotes;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api")
class NoteApi {
  private final Core c; private final JdbcTemplate db;
  private final Pattern linkPattern=Pattern.compile("\\[\\[([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:\\|[^\\]]*)?\\]\\]");
  NoteApi(Core c,JdbcTemplate db) { this.c=c; this.db=db; }
  @GetMapping("/projects/{project}/notes") Object list(@PathVariable UUID project,@RequestParam(defaultValue="active") String state,
      @RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID tag,HttpServletRequest req) {
    c.role(req,project); if(!Set.of("active","archived","trash","templates").contains(state)) throw Core.bad("Filtro inválido");
    String condition=switch(state) { case "archived" -> "n.archived_at IS NOT NULL AND n.deleted_at IS NULL";
      case "trash" -> "n.deleted_at IS NOT NULL"; case "templates" -> "n.template=true AND n.deleted_at IS NULL";
      default -> "n.archived_at IS NULL AND n.deleted_at IS NULL"; };
    String like="%"+q.toLowerCase(Locale.ROOT)+"%";
    List<Map<String,Object>> notes=db.queryForList("SELECT n.id,n.project_id,n.folder_id,n.title,n.favorite,n.template,n.archived_at,n.deleted_at,n.version,n.created_at,n.updated_at,u.name AS updated_by_name FROM note n JOIN app_user u ON u.id=n.updated_by WHERE n.project_id=? AND " + condition
      + " AND (lower(n.title) LIKE ? OR lower(n.body) LIKE ?) AND (?::uuid IS NULL OR EXISTS (SELECT 1 FROM note_tag nt WHERE nt.note_id=n.id AND nt.tag_id=?)) ORDER BY n.favorite DESC,n.updated_at DESC LIMIT 500",
      project,like,like,tag,tag);
    withTags(notes,project); return notes;
  }
  private void withTags(List<Map<String,Object>> notes,UUID project) {
    Map<Object,List<Object>> byNote=new HashMap<>();
    for(Map<String,Object> row:db.queryForList("SELECT nt.note_id,nt.tag_id FROM note_tag nt JOIN tag t ON t.id=nt.tag_id WHERE t.project_id=? ORDER BY t.name",project))
      byNote.computeIfAbsent(row.get("note_id"),k->new ArrayList<>()).add(row.get("tag_id"));
    for(Map<String,Object> note:notes) note.put("tag_ids",byNote.getOrDefault(note.get("id"),List.of()));
  }
  @GetMapping("/notes/{id}") Object get(@PathVariable UUID id,HttpServletRequest req) {
    UUID project=c.projectOf("note",id); c.role(req,project);
    Map<String,Object> note=new LinkedHashMap<>(c.one("SELECT n.*,cu.name AS created_by_name,uu.name AS updated_by_name FROM note n JOIN app_user cu ON cu.id=n.created_by JOIN app_user uu ON uu.id=n.updated_by WHERE n.id=?",id));
    note.put("tags",db.queryForList("SELECT t.* FROM tag t JOIN note_tag nt ON t.id=nt.tag_id WHERE nt.note_id=? ORDER BY t.name",id));
    note.put("backlinks",db.queryForList("SELECT n.id,n.title FROM note_link l JOIN note n ON n.id=l.source_id WHERE l.target_id=? AND n.deleted_at IS NULL",id));
    note.put("links",db.queryForList("SELECT n.id,n.title FROM note_link l JOIN note n ON n.id=l.target_id WHERE l.source_id=? AND n.deleted_at IS NULL",id));
    note.put("files",db.queryForList("SELECT f.id,f.name,f.mime,f.size_bytes,f.created_at FROM stored_file f JOIN file_link l ON l.file_id=f.id WHERE l.note_id=? ORDER BY f.created_at",id));
    return note;
  }
  /** A folder or tag deleted meanwhile (e.g. by another member) is dropped instead of blocking the save. */
  private UUID folder(Map<String,Object> b,UUID project) {
    UUID id=Core.nullableUuid(b.get("folderId")); if(id==null) return null;
    List<UUID> owner=db.queryForList("SELECT project_id FROM folder WHERE id=?",UUID.class,id);
    if(owner.isEmpty()) return null; if(!project.equals(owner.getFirst())) throw Core.bad("Pasta de outro projeto"); return id;
  }
  private void tags(UUID note,UUID project,Object raw) {
    db.update("DELETE FROM note_tag WHERE note_id=?",note);
    if(raw instanceof List<?> list) for(Object value:list) {
      UUID tag=Core.uuid(value); List<UUID> owner=db.queryForList("SELECT project_id FROM tag WHERE id=?",UUID.class,tag);
      if(owner.isEmpty()) continue; if(!project.equals(owner.getFirst())) throw Core.bad("Etiqueta de outro projeto");
      db.update("INSERT INTO note_tag(note_id,tag_id) VALUES(?,?) ON CONFLICT DO NOTHING",note,tag);
    }
  }
  private void links(UUID note,UUID project,String body) {
    db.update("DELETE FROM note_link WHERE source_id=?",note); Matcher m=linkPattern.matcher(body);
    while(m.find()) {
      UUID target=UUID.fromString(m.group(1));
      if(!target.equals(note) && db.queryForObject("SELECT count(*) FROM note WHERE id=? AND project_id=? AND deleted_at IS NULL",Integer.class,target,project)>0)
        db.update("INSERT INTO note_link(source_id,target_id) VALUES(?,?) ON CONFLICT DO NOTHING",note,target);
    }
  }
  @PostMapping("/projects/{project}/notes") @Transactional Object create(@PathVariable UUID project,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    c.edit(req,project); UUID id=Core.id(),user=c.userId(req),folder=folder(b,project);
    String title=Core.required(b.get("title"),200,"Título"),body=Core.optional(b.get("body"),200000,"Conteúdo");
    db.update("INSERT INTO note(id,project_id,folder_id,title,body,favorite,template,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",
      id,project,folder,title,body,Boolean.TRUE.equals(b.get("favorite")),Boolean.TRUE.equals(b.get("template")),user,user);
    db.update("INSERT INTO note_version(id,note_id,version,title,body,edited_by) VALUES(?,?,?,?,?,?)",Core.id(),id,1,title,body,user);
    tags(id,project,b.get("tagIds")); links(id,project,body); c.activity(project,user,"note",id,"created",title); return get(id,req);
  }
  @PutMapping("/notes/{id}") @Transactional Object update(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("note",id); c.edit(req,project);
    Map<String,Object> old=c.one("SELECT version,deleted_at FROM note WHERE id=?",id);
    if(old.get("deleted_at")!=null) throw Core.bad("Restaure a nota antes de editar");
    int version=b.get("version") instanceof Number n?n.intValue():-1;
    if(version<1) throw Core.bad("Versão obrigatória");
    String title=Core.required(b.get("title"),200,"Título"),body=Core.optional(b.get("body"),200000,"Conteúdo");
    int changed=db.update("UPDATE note SET title=?,body=?,folder_id=?,favorite=?,template=?,version=version+1,updated_by=?,updated_at=now() WHERE id=? AND version=?",
      title,body,folder(b,project),Boolean.TRUE.equals(b.get("favorite")),Boolean.TRUE.equals(b.get("template")),c.userId(req),id,version);
    if(changed==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Esta nota foi alterada por outra pessoa. Recarregue antes de salvar.");
    tags(id,project,b.get("tagIds")); links(id,project,body);
    db.update("INSERT INTO note_version(id,note_id,version,title,body,edited_by) VALUES(?,?,?,?,?,?)",Core.id(),id,version+1,title,body,c.userId(req));
    c.activity(project,c.userId(req),"note",id,"updated",title); return get(id,req);
  }
  @PostMapping("/notes/{id}/duplicate") @Transactional Object duplicate(@PathVariable UUID id,HttpServletRequest req) {
    Map<String,Object> old=(Map<String,Object>)get(id,req); UUID project=(UUID)old.get("project_id"); c.edit(req,project);
    List<String> tagIds=((List<Map<String,Object>>)old.get("tags")).stream().map(t->t.get("id").toString()).toList();
    Map<String,Object> copy=new HashMap<>(Map.of("title",Core.clip(old.get("title")+" (cópia)",200),"body",old.get("body"),"favorite",false,"template",old.get("template"),"tagIds",tagIds));
    copy.put("folderId",old.get("folder_id")); return create(project,copy,req);
  }
  @PatchMapping("/notes/{id}/state") Object state(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("note",id); c.edit(req,project); String state=Core.str(b.get("state"));
    switch(state) {
      case "archive" -> db.update("UPDATE note SET archived_at=now(),deleted_at=NULL WHERE id=?",id);
      case "trash" -> db.update("UPDATE note SET deleted_at=now() WHERE id=?",id);
      case "restore" -> db.update("UPDATE note SET deleted_at=NULL,archived_at=NULL WHERE id=?",id);
      default -> throw Core.bad("Estado inválido");
    }
    c.activity(project,c.userId(req),"note",id,state,""); return get(id,req);
  }
  @GetMapping("/notes/{id}/versions") Object versions(@PathVariable UUID id,HttpServletRequest req) {
    c.role(req,c.projectOf("note",id)); return db.queryForList("SELECT v.*,u.name AS editor_name FROM note_version v JOIN app_user u ON u.id=v.edited_by WHERE note_id=? ORDER BY version DESC",id);
  }
  @PostMapping("/notes/{id}/restore-version/{version}") @Transactional Object restoreVersion(@PathVariable UUID id,@PathVariable int version,HttpServletRequest req) {
    UUID project=c.projectOf("note",id); c.edit(req,project); Map<String,Object> historic=c.one("SELECT title,body FROM note_version WHERE note_id=? AND version=?",id,version);
    Map<String,Object> current=(Map<String,Object>)get(id,req);
    Map<String,Object> restored=new HashMap<>(Map.of("version",current.get("version"),"title",historic.get("title"),"body",historic.get("body"),"favorite",current.get("favorite"),"template",current.get("template"),"tagIds",((List<Map<String,Object>>)current.get("tags")).stream().map(t->t.get("id").toString()).toList()));
    restored.put("folderId",current.get("folder_id")); return update(id,restored,req);
  }
  @GetMapping("/notes/{id}/export") ResponseEntity<byte[]> export(@PathVariable UUID id,HttpServletRequest req) {
    Map<String,Object> note=(Map<String,Object>)get(id,req);
    String md="# "+note.get("title")+"\n\n"+note.get("body");
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=note-"+id+".md")
      .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8")).body(md.getBytes(StandardCharsets.UTF_8));
  }
  @GetMapping("/projects/{project}/graph") Object graph(@PathVariable UUID project,HttpServletRequest req) {
    c.role(req,project);
    List<Map<String,Object>> nodes=db.queryForList("SELECT n.id,n.title,left(n.body,600) AS excerpt,n.folder_id,n.favorite,n.archived_at,n.created_at,n.updated_at,cu.name AS created_by_name,uu.name AS updated_by_name FROM note n JOIN app_user cu ON cu.id=n.created_by JOIN app_user uu ON uu.id=n.updated_by WHERE n.project_id=? AND n.deleted_at IS NULL AND n.template=false ORDER BY n.title",project);
    withTags(nodes,project);
    return Map.of("nodes",nodes,
      "links",db.queryForList("SELECT l.source_id,l.target_id FROM note_link l JOIN note n ON n.id=l.source_id JOIN note target ON target.id=l.target_id WHERE n.project_id=? AND n.deleted_at IS NULL AND target.deleted_at IS NULL AND n.template=false AND target.template=false",project),
      "tags",db.queryForList("SELECT id,name,color FROM tag WHERE project_id=? ORDER BY name",project),
      "folders",db.queryForList("SELECT id,name FROM folder WHERE project_id=? ORDER BY name",project));
  }
}
