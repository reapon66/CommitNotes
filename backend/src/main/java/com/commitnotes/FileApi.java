package com.commitnotes;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

@RestController @RequestMapping("/api")
class FileApi {
  static final List<String> EXTENSIONS=List.of(".pdf",".docx",".txt",".png",".jpg",".jpeg",".webp");
  private final Core c; private final JdbcTemplate db; private final Path root; private final long maxBytes;
  FileApi(Core c,JdbcTemplate db,@Value("${app.files-dir}") String dir,@Value("${app.max-upload-mb}") int maxUploadMb) throws IOException {
    this.c=c;this.db=db;this.root=Paths.get(dir).toAbsolutePath().normalize();Files.createDirectories(root);this.maxBytes=maxUploadMb*1024L*1024L;
  }
  private record Checked(String mime,String text) {}
  private Checked check(String name,byte[] bytes) {
    String lower=name.toLowerCase(Locale.ROOT);
    if(bytes.length==0) throw Core.bad("Arquivo vazio");
    if(bytes.length>maxBytes) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Arquivo acima do limite de "+(maxBytes/1024/1024)+" MB");
    try {
      if(lower.endsWith(".pdf") && bytes.length>5 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) {
        try(var pdf=Loader.loadPDF(bytes)) { String text=new PDFTextStripper().getText(pdf); return new Checked("application/pdf",text.substring(0,Math.min(100000,text.length()))); }
      }
      if(lower.endsWith(".docx") && bytes.length>4 && bytes[0]=='P' && bytes[1]=='K') {
        try(var doc=new XWPFDocument(new ByteArrayInputStream(bytes)); var extractor=new XWPFWordExtractor(doc)) {
          String text=extractor.getText();
          return new Checked("application/vnd.openxmlformats-officedocument.wordprocessingml.document",text.substring(0,Math.min(100000,text.length())));
        }
      }
      if(lower.endsWith(".txt")) {
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return new Checked("text/plain",text.substring(0,Math.min(100000,text.length())));
      }
      if(lower.endsWith(".png") && bytes.length>8 && bytes[0]==(byte)137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71) return new Checked("image/png","");
      if((lower.endsWith(".jpg") || lower.endsWith(".jpeg")) && bytes.length>3 && bytes[0]==(byte)255 && bytes[1]==(byte)216) return new Checked("image/jpeg","");
      if(lower.endsWith(".webp") && bytes.length>12 && new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP")) return new Checked("image/webp","");
    } catch(Exception e) { throw Core.bad("Arquivo inválido ou corrompido"); }
    throw Core.bad("Formato não permitido; use PDF, DOCX, TXT, PNG, JPG ou WEBP");
  }
  @GetMapping("/files/limits") Map<String,Object> limits(HttpServletRequest req) { c.userId(req); return Map.of("maxBytes",maxBytes,"extensions",EXTENSIONS); }
  @GetMapping("/projects/{project}/files") Object list(@PathVariable UUID project,@RequestParam(defaultValue="") String q,HttpServletRequest req) {
    c.role(req,project); String query="%"+q.toLowerCase(Locale.ROOT)+"%";
    return db.queryForList("SELECT f.id,f.project_id,f.name,f.mime,f.size_bytes,f.created_at,u.name AS uploaded_by_name FROM stored_file f JOIN app_user u ON u.id=f.uploaded_by WHERE f.project_id=? AND (lower(f.name) LIKE ? OR lower(f.extracted_text) LIKE ?) ORDER BY f.created_at DESC LIMIT 500",project,query,query);
  }
  @GetMapping("/files/{id}") Object file(@PathVariable UUID id,HttpServletRequest req) {
    UUID project=c.projectOf("stored_file",id); c.role(req,project);
    Map<String,Object> file=new LinkedHashMap<>(c.one("SELECT f.id,f.project_id,f.name,f.mime,f.size_bytes,f.created_at,u.name AS uploaded_by_name FROM stored_file f JOIN app_user u ON u.id=f.uploaded_by WHERE f.id=?",id));
    file.put("notes",db.queryForList("SELECT DISTINCT n.id,n.title FROM note n LEFT JOIN file_link l ON l.note_id=n.id AND l.file_id=? WHERE n.project_id=? AND n.deleted_at IS NULL AND (l.file_id IS NOT NULL OR strpos(n.body,?)>0) ORDER BY n.title",id,project,"[[file:"+id));
    file.put("tasks",db.queryForList("SELECT t.id,t.title FROM task t JOIN file_link l ON l.task_id=t.id WHERE l.file_id=? ORDER BY t.title",id));
    return file;
  }
  @PostMapping(value="/projects/{project}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional Object upload(@PathVariable UUID project,@RequestParam("file") MultipartFile file,
      @RequestParam(required=false) UUID noteId,@RequestParam(required=false) UUID taskId,HttpServletRequest req) throws IOException {
    c.edit(req,project); if(noteId!=null && taskId!=null) throw Core.bad("Vincule a uma nota ou tarefa");
    if(noteId!=null && !project.equals(c.projectOf("note",noteId))) throw Core.bad("Nota de outro projeto");
    if(taskId!=null && !project.equals(c.projectOf("task",taskId))) throw Core.bad("Tarefa de outro projeto");
    String name=Core.required(file.getOriginalFilename(),255,"Nome do arquivo").replaceAll("[\\r\\n\\\\/]","_");
    byte[] bytes=file.getBytes(); Checked checked=check(name,bytes); UUID id=Core.id(),key=Core.id(),user=c.userId(req);
    Path path=root.resolve(key.toString()); Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
    try {
      db.update("INSERT INTO stored_file(id,project_id,name,mime,size_bytes,storage_key,extracted_text,uploaded_by) VALUES(?,?,?,?,?,?,?,?)",id,project,name,checked.mime,bytes.length,key,checked.text,user);
      if(noteId!=null || taskId!=null) db.update("INSERT INTO file_link(file_id,note_id,task_id) VALUES(?,?,?)",id,noteId,taskId);
    } catch(Exception e) { Files.deleteIfExists(path); throw e; }
    c.activity(project,user,"file",id,"uploaded",name);
    if(taskId!=null) { c.activity(project,user,"task",taskId,"file_attached",name); c.notifyTask(taskId,user,"anexou "+name,Set.of()); }
    return c.one("SELECT id,name,mime,size_bytes,created_at FROM stored_file WHERE id=?",id);
  }
  @PostMapping("/files/{id}/link") Object link(@PathVariable UUID id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
    UUID project=c.projectOf("stored_file",id); c.edit(req,project); UUID note=Core.nullableUuid(b.get("noteId")),task=Core.nullableUuid(b.get("taskId"));
    if((note==null)==(task==null)) throw Core.bad("Escolha uma nota ou tarefa");
    if(note!=null && !project.equals(c.projectOf("note",note))) throw Core.bad("Nota de outro projeto");
    if(task!=null && !project.equals(c.projectOf("task",task))) throw Core.bad("Tarefa de outro projeto");
    db.update("INSERT INTO file_link(file_id,note_id,task_id) VALUES(?,?,?)",id,note,task); c.activity(project,c.userId(req),"file",id,"linked","");
    return c.one("SELECT id,name,mime FROM stored_file WHERE id=?",id);
  }
  @DeleteMapping("/files/{id}/link") Map<String,Boolean> unlink(@PathVariable UUID id,@RequestParam(required=false) UUID noteId,@RequestParam(required=false) UUID taskId,HttpServletRequest req) {
    UUID project=c.projectOf("stored_file",id); c.edit(req,project); UUID user=c.userId(req);
    if((noteId==null)==(taskId==null)) throw Core.bad("Escolha uma nota ou tarefa");
    int removed=noteId!=null?db.update("DELETE FROM file_link WHERE file_id=? AND note_id=?",id,noteId):db.update("DELETE FROM file_link WHERE file_id=? AND task_id=?",id,taskId);
    if(removed>0 && taskId!=null) {
      String name=(String)c.one("SELECT name FROM stored_file WHERE id=?",id).get("name");
      c.activity(project,user,"task",taskId,"file_detached",name); c.notifyTask(taskId,user,"removeu o anexo "+name,Set.of());
    }
    return Map.of("ok",true);
  }
  @GetMapping("/files/{id}/content") ResponseEntity<byte[]> content(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean download,HttpServletRequest req) throws IOException {
    Map<String,Object> f=c.one("SELECT * FROM stored_file WHERE id=?",id); c.role(req,(UUID)f.get("project_id"));
    Path path=root.resolve(f.get("storage_key").toString()); if(!Files.exists(path)) throw Core.missing();
    String mime=(String)f.get("mime"); boolean inline=!download && (mime.startsWith("image/") || mime.equals("application/pdf") || mime.equals("text/plain"));
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,(inline?"inline":"attachment")+"; filename*=UTF-8''"+java.net.URLEncoder.encode((String)f.get("name"),StandardCharsets.UTF_8).replace("+","%20"))
      .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"private, max-age=3600")
      .contentType(MediaType.parseMediaType(mime.equals("text/plain")?"text/plain;charset=UTF-8":mime)).body(Files.readAllBytes(path));
  }
  @DeleteMapping("/files/{id}") Map<String,Boolean> delete(@PathVariable UUID id,HttpServletRequest req) throws IOException {
    Map<String,Object> f=c.one("SELECT project_id,storage_key,name FROM stored_file WHERE id=?",id); UUID project=(UUID)f.get("project_id"); c.edit(req,project);
    db.update("DELETE FROM stored_file WHERE id=?",id); Files.deleteIfExists(root.resolve(f.get("storage_key").toString()));
    c.activity(project,c.userId(req),"file",id,"deleted",(String)f.get("name")); return Map.of("ok",true);
  }
}
