package com.commitnotes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties={"APP_SECRET=integration-test-secret-at-least-32-characters", "app.files-dir=target/test-files", "app.max-upload-mb=1"})
@AutoConfigureMockMvc
class ApiFlowIT {
  static EmbeddedPostgres postgres;
  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
    try { postgres=EmbeddedPostgres.start(); } catch(Exception e) { throw new IllegalStateException("Embedded PostgreSQL could not start",e); }
    registry.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));
    registry.add("spring.datasource.username",()->"postgres");
    registry.add("spring.datasource.password",()->"");
  }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  private JsonNode parse(MvcResult result) throws Exception {return json.readTree(result.getResponse().getContentAsString());}
  private Cookie register(String name) throws Exception {
    String email=name+UUID.randomUUID()+"@example.test";
    MvcResult result=mvc.perform(post("/api/auth/register").header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("name",name,"email",email,"password","correct-horse-battery-staple"))))
      .andExpect(status().isOk()).andReturn();
    return result.getResponse().getCookie("cn_session");
  }
  private MvcResult createProject(Cookie cookie) throws Exception {
    return mvc.perform(post("/api/projects").cookie(cookie).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Equipe\"}"))
      .andExpect(status().isOk()).andReturn();
  }
  private ResultActions send(MockHttpServletRequestBuilder request,Cookie cookie,Object payload) throws Exception {
    return mvc.perform(request.cookie(cookie).header("X-Requested-With","XMLHttpRequest").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)));
  }
  private JsonNode ok(MockHttpServletRequestBuilder request,Cookie cookie,Object payload) throws Exception { return parse(send(request,cookie,payload).andExpect(status().isOk()).andReturn()); }
  private JsonNode read(String path,Cookie cookie) throws Exception { return parse(mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk()).andReturn()); }
  private String id(Cookie cookie) throws Exception { return read("/api/auth/me",cookie).get("id").asText(); }
  private int unread(Cookie cookie) throws Exception { return read("/api/notifications",cookie).get("unread").asInt(); }
  private void join(String projectId,Cookie owner,Cookie member,String role) throws Exception {
    ok(post("/api/projects/"+projectId+"/members"),owner,Map.of("email",read("/api/auth/me",member).get("email").asText(),"role",role));
  }
  @Test void projectPermissionsNotesVersioningSearchTasksAndReminders() throws Exception {
    Cookie owner=register("owner"),reader=register("reader");
    JsonNode project=parse(createProject(owner));String projectId=project.get("id").asText();
    String readerEmail=parse(mvc.perform(get("/api/auth/me").cookie(reader)).andExpect(status().isOk()).andReturn()).get("email").asText();
    mvc.perform(post("/api/projects/"+projectId+"/members").cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("email",readerEmail,"role","READER"))))
      .andExpect(status().isOk());
    String noteBody="{\"title\":\"Decisões\",\"body\":\"Primeira versão\",\"tagIds\":[]}";
    JsonNode note=parse(mvc.perform(post("/api/projects/"+projectId+"/notes").cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content(noteBody)).andExpect(status().isOk()).andReturn());
    String noteId=note.get("id").asText();
    mvc.perform(get("/api/notes/"+noteId).cookie(reader)).andExpect(status().isOk());
    mvc.perform(put("/api/notes/"+noteId).cookie(reader).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Não\",\"body\":\"\",\"version\":1}"))
      .andExpect(status().isForbidden());
    String update="{\"title\":\"Decisões\",\"body\":\"Atualizada\",\"version\":1,\"tagIds\":[]}";
    mvc.perform(put("/api/notes/"+noteId).cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isOk());
    mvc.perform(put("/api/notes/"+noteId).cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isConflict());
    assertEquals(2,parse(mvc.perform(get("/api/notes/"+noteId+"/versions").cookie(owner)).andExpect(status().isOk()).andReturn()).size());
    MockMultipartFile file=new MockMultipartFile("file","plano.txt","text/plain","texto indexável do projeto".getBytes(StandardCharsets.UTF_8));
    mvc.perform(multipart("/api/projects/"+projectId+"/files").file(file).cookie(owner).header("X-Requested-With","XMLHttpRequest"))
      .andExpect(status().isOk());
    assertEquals(1,parse(mvc.perform(get("/api/projects/"+projectId+"/files").param("q","indexável").cookie(reader)).andExpect(status().isOk()).andReturn()).size());
    try(PDDocument pdf=new PDDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
      PDPage page=new PDPage();pdf.addPage(page);
      try(PDPageContentStream content=new PDPageContentStream(pdf,page)) {
        content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
        content.newLineAtOffset(30,700);content.showText("criterio pdf pesquisavel");content.endText();
      }
      pdf.save(bytes);
      mvc.perform(multipart("/api/projects/"+projectId+"/files").file(new MockMultipartFile("file","requisitos.pdf","application/pdf",bytes.toByteArray()))
        .cookie(owner).header("X-Requested-With","XMLHttpRequest")).andExpect(status().isOk());
    }
    assertEquals(1,parse(mvc.perform(get("/api/projects/"+projectId+"/files").param("q","criterio pdf").cookie(reader)).andExpect(status().isOk()).andReturn()).size());
    try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
      doc.createParagraph().createRun().setText("conteudo docx rastreavel");doc.write(bytes);
      mvc.perform(multipart("/api/projects/"+projectId+"/files").file(new MockMultipartFile("file","plano.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes.toByteArray()))
        .cookie(owner).header("X-Requested-With","XMLHttpRequest")).andExpect(status().isOk());
    }
    assertEquals(1,parse(mvc.perform(get("/api/projects/"+projectId+"/files").param("q","rastreavel").cookie(reader)).andExpect(status().isOk()).andReturn()).size());
    mvc.perform(multipart("/api/projects/"+projectId+"/files").file(new MockMultipartFile("file","script.exe","application/octet-stream",new byte[]{1,2,3}))
      .cookie(owner).header("X-Requested-With","XMLHttpRequest")).andExpect(status().isBadRequest());
    JsonNode boards=parse(mvc.perform(get("/api/projects/"+projectId+"/boards").cookie(owner)).andExpect(status().isOk()).andReturn());
    String boardId=boards.get(0).get("id").asText();
    String columnId=parse(mvc.perform(get("/api/boards/"+boardId+"/view").cookie(owner)).andExpect(status().isOk()).andReturn()).get("columns").get(0).get("id").asText();
    JsonNode task=parse(mvc.perform(post("/api/boards/"+boardId+"/tasks").cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("columnId",columnId,"title","Implementar API"))))
      .andExpect(status().isOk()).andReturn());
    String taskId=task.get("id").asText();
    JsonNode step=parse(mvc.perform(post("/api/tasks/"+taskId+"/steps").cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Revisar\"}"))
      .andExpect(status().isOk()).andReturn()).get("steps").get(0);
    mvc.perform(patch("/api/steps/"+step.get("id").asText()).cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
      .andExpect(status().isOk());
    JsonNode taskView=parse(mvc.perform(get("/api/boards/"+boardId+"/view").cookie(owner)).andExpect(status().isOk()).andReturn());
    assertEquals(1,taskView.get("tasks").get(0).get("done_count").asInt());
    String doneColumn=taskView.get("columns").get(2).get("id").asText();
    mvc.perform(patch("/api/columns/"+doneColumn).cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Entregue\"}"))
      .andExpect(status().isOk());
    JsonNode completed=parse(mvc.perform(patch("/api/tasks/"+taskId).cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("columnId",doneColumn))))
      .andExpect(status().isOk()).andReturn());
    assertFalse(completed.get("completed_at").isNull());
    JsonNode reminder=parse(mvc.perform(post("/api/reminders").cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("title","Reunião","dueAt",Instant.now().plusSeconds(3600).toString(),"projectId",projectId))))
      .andExpect(status().isOk()).andReturn());
    assertEquals(1,parse(mvc.perform(get("/api/reminders").cookie(owner)).andExpect(status().isOk()).andReturn()).size());
    mvc.perform(patch("/api/reminders/"+reminder.get("id").asText()).cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"snooze\",\"minutes\":10}"))
      .andExpect(status().isOk());
    mvc.perform(patch("/api/reminders/"+reminder.get("id").asText()).cookie(owner).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"complete\"}"))
      .andExpect(status().isOk());
  }
  @Test void privateProjectDoesNotOpenForUnrelatedUser() throws Exception {
    Cookie owner=register("privateowner"),outsider=register("outsider");
    String projectId=parse(createProject(owner)).get("id").asText();
    mvc.perform(get("/api/projects/"+projectId+"/notes").cookie(outsider)).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/users").cookie(owner)).andExpect(status().isForbidden());
  }
  @Test void adminCannotReadPrivateNotesAndPasswordResetRevokesSessions() throws Exception {
    Cookie admin=register("admin"),privateUser=register("privateuser");
    JsonNode adminMe=parse(mvc.perform(get("/api/auth/me").cookie(admin)).andExpect(status().isOk()).andReturn());
    JsonNode privateMe=parse(mvc.perform(get("/api/auth/me").cookie(privateUser)).andExpect(status().isOk()).andReturn());
    String privateProject=parse(createProject(privateUser)).get("id").asText();
    JsonNode privateNote=parse(mvc.perform(post("/api/projects/"+privateProject+"/notes").cookie(privateUser).header("X-Requested-With","XMLHttpRequest")
      .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Privada\",\"body\":\"Conteúdo reservado\"}"))
      .andExpect(status().isOk()).andReturn());
    db.update("UPDATE app_user SET platform_admin=true WHERE id=?",UUID.fromString(adminMe.get("id").asText()));
    mvc.perform(get("/api/admin/users").cookie(admin)).andExpect(status().isOk());
    mvc.perform(get("/api/notes/"+privateNote.get("id").asText()).cookie(admin)).andExpect(status().isForbidden());
    String token=UUID.randomUUID().toString()+UUID.randomUUID();
    db.update("INSERT INTO reset_token(id,user_id,token_hash,expires_at) VALUES(?,?,?,?)",UUID.randomUUID(),UUID.fromString(privateMe.get("id").asText()),Core.hash(token),java.sql.Timestamp.from(Instant.now().plusSeconds(600)));
    mvc.perform(post("/api/auth/reset").header("X-Requested-With","XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(java.util.Map.of("token",token,"password","new-secure-password-123"))))
      .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").cookie(privateUser)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").header("X-Requested-With","XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(java.util.Map.of("email",privateMe.get("email").asText(),"password","new-secure-password-123"))))
      .andExpect(status().isOk());
  }
  @Test void everyTaskUpdateReachesAllAssignees() throws Exception {
    Cookie owner=register("lead"),editor=register("dev"),reader=register("viewer"),outsider=register("stranger");
    String projectId=parse(createProject(owner)).get("id").asText(),ownerId=id(owner),editorId=id(editor);
    join(projectId,owner,editor,"EDITOR"); join(projectId,owner,reader,"READER");
    String boardId=read("/api/projects/"+projectId+"/boards",owner).get(0).get("id").asText();
    JsonNode columns=read("/api/boards/"+boardId+"/view",owner).get("columns");
    JsonNode task=ok(post("/api/boards/"+boardId+"/tasks"),owner,Map.of("columnId",columns.get(0).get("id").asText(),"title","Integrar pagamentos","assigneeIds",List.of(ownerId,editorId)));
    String taskId=task.get("id").asText();
    assertEquals(2,task.get("assignees").size());
    assertEquals(2,read("/api/boards/"+boardId+"/view",owner).get("tasks").get(0).get("assignees").size());
    JsonNode inbox=read("/api/notifications",editor);
    assertEquals(1,inbox.get("unread").asInt());
    assertTrue(inbox.get("items").get(0).get("body").asText().contains("adicionou você"));
    assertEquals("/board?task="+taskId,inbox.get("items").get(0).get("url").asText());
    ok(patch("/api/tasks/"+taskId),owner,Map.of("columnId",columns.get(1).get("id").asText()));
    assertEquals(2,unread(editor));
    assertEquals(0,unread(owner));
    JsonNode commented=ok(post("/api/tasks/"+taskId+"/comments"),editor,Map.of("body","Integração revisada"));
    assertEquals("comment",commented.get("feed").get(0).get("kind").asText());
    assertEquals("Integração revisada",commented.get("feed").get(0).get("detail").asText());
    assertTrue(read("/api/notifications",owner).get("items").get(0).get("body").asText().contains("comentou"));
    JsonNode feed=read("/api/tasks/"+taskId,reader).get("feed");
    assertTrue(feed.size()>=4);
    send(post("/api/tasks/"+taskId+"/comments"),reader,Map.of("body","Posso comentar?")).andExpect(status().isForbidden());
    send(patch("/api/tasks/"+taskId),owner,Map.of("assigneeIds",List.of(id(outsider)))).andExpect(status().isBadRequest());
    ok(patch("/api/tasks/"+taskId),owner,Map.of("assigneeIds",List.of(ownerId)));
    assertTrue(read("/api/notifications",editor).get("items").get(0).get("body").asText().contains("removeu você"));
    int editorUnread=unread(editor);
    ok(post("/api/tasks/"+taskId+"/steps"),owner,Map.of("label","Testar"));
    assertEquals(editorUnread,unread(editor));
    assertEquals(0,ok(post("/api/notifications/read"),editor,Map.of("all",true)).get("unread").asInt());
    ok(patch("/api/tasks/"+taskId),owner,Map.of("dueAt",Instant.now().plusSeconds(7200).toString()));
    assertEquals(1,parse(mvc.perform(get("/api/projects/"+projectId+"/schedule").param("person",ownerId).cookie(owner)).andExpect(status().isOk()).andReturn()).size());
    assertEquals(0,parse(mvc.perform(get("/api/projects/"+projectId+"/schedule").param("person",editorId).cookie(owner)).andExpect(status().isOk()).andReturn()).size());
    mvc.perform(delete("/api/projects/"+projectId+"/members/"+ownerId).cookie(owner).header("X-Requested-With","XMLHttpRequest")).andExpect(status().isBadRequest());
  }
  @Test void boardColumnsCanBeColoredReorderedAndRemoved() throws Exception {
    Cookie owner=register("planner");
    String projectId=parse(createProject(owner)).get("id").asText();
    String boardId=ok(post("/api/projects/"+projectId+"/boards"),owner,Map.of("name","Sprint","columns",List.of(Map.of("name","Backlog"),Map.of("name","Feito","isDone",true,"color","#22c55e")))).get("id").asText();
    JsonNode view=ok(post("/api/boards/"+boardId+"/columns"),owner,Map.of("name","Revisão","color","#F59E0B"));
    assertEquals(3,view.get("columns").size());
    assertEquals("#f59e0b",view.get("columns").get(2).get("color").asText());
    send(post("/api/boards/"+boardId+"/columns"),owner,Map.of("name","Inválida","color","red")).andExpect(status().isBadRequest());
    String backlog=view.get("columns").get(0).get("id").asText(),done=view.get("columns").get(1).get("id").asText(),review=view.get("columns").get(2).get("id").asText();
    view=ok(patch("/api/columns/"+review),owner,Map.of("position",1));
    assertEquals("Revisão",view.get("columns").get(1).get("name").asText());
    ok(post("/api/boards/"+boardId+"/tasks"),owner,Map.of("columnId",review,"title","Revisar PR"));
    mvc.perform(delete("/api/columns/"+review).cookie(owner).header("X-Requested-With","XMLHttpRequest")).andExpect(status().isConflict());
    view=parse(mvc.perform(delete("/api/columns/"+review).param("moveTo",done).cookie(owner).header("X-Requested-With","XMLHttpRequest")).andExpect(status().isOk()).andReturn());
    assertEquals(2,view.get("columns").size());
    assertEquals(done,view.get("tasks").get(0).get("column_id").asText());
    assertFalse(view.get("tasks").get(0).get("completed_at").isNull());
    String first=ok(post("/api/boards/"+boardId+"/tasks"),owner,Map.of("columnId",backlog,"title","Primeira")).get("id").asText();
    String second=ok(post("/api/boards/"+boardId+"/tasks"),owner,Map.of("columnId",backlog,"title","Segunda")).get("id").asText();
    ok(patch("/api/tasks/"+second),owner,Map.of("position",0));
    JsonNode tasks=read("/api/boards/"+boardId+"/view",owner).get("tasks");
    List<String> backlogOrder=new java.util.ArrayList<>();
    for(JsonNode t:tasks) if(t.get("column_id").asText().equals(backlog)) backlogOrder.add(t.get("id").asText());
    assertEquals(List.of(second,first),backlogOrder);
    assertEquals("Entrega",ok(patch("/api/boards/"+boardId),owner,Map.of("name","Entrega")).get("name").asText());
    mvc.perform(delete("/api/boards/"+boardId).cookie(owner).header("X-Requested-With","XMLHttpRequest")).andExpect(status().isOk());
    assertEquals(1,read("/api/projects/"+projectId+"/boards",owner).size());
  }
  @Test void tagsColorTheOverviewAndFilesExposeReferences() throws Exception {
    Cookie owner=register("writer");
    String projectId=parse(createProject(owner)).get("id").asText();
    JsonNode tag=ok(post("/api/projects/"+projectId+"/tags"),owner,Map.of("name","Arquitetura"));
    assertTrue(tag.get("color").asText().matches("#[0-9a-f]{6}"));
    String tagId=tag.get("id").asText();
    assertEquals("#0ea5e9",ok(patch("/api/tags/"+tagId),owner,Map.of("color","#0EA5E9")).get("color").asText());
    String first=ok(post("/api/projects/"+projectId+"/notes"),owner,Map.of("title","Decisão A","body","Contexto **importante**","tagIds",List.of(tagId))).get("id").asText();
    String fileId=parse(mvc.perform(multipart("/api/projects/"+projectId+"/files").file(new MockMultipartFile("file","plano.txt","text/plain","Plano de ação".getBytes(StandardCharsets.UTF_8)))
      .cookie(owner).header("X-Requested-With","XMLHttpRequest")).andExpect(status().isOk()).andReturn()).get("id").asText();
    ok(post("/api/projects/"+projectId+"/notes"),owner,Map.of("title","Decisão B","body","Ver [["+first+"|Decisão A]] e [[file:"+fileId+"|plano.txt]]","tagIds",List.of()));
    JsonNode graph=read("/api/projects/"+projectId+"/graph",owner);
    assertEquals(2,graph.get("nodes").size());
    assertEquals(1,graph.get("links").size());
    assertEquals("#0ea5e9",graph.get("tags").get(0).get("color").asText());
    JsonNode nodeA=graph.get("nodes").get(0);
    assertEquals(tagId,nodeA.get("tag_ids").get(0).asText());
    assertTrue(nodeA.get("excerpt").asText().contains("Contexto"));
    assertFalse(nodeA.get("created_by_name").asText().isEmpty());
    assertEquals(1,parse(mvc.perform(get("/api/projects/"+projectId+"/notes").param("tag",tagId).cookie(owner)).andExpect(status().isOk()).andReturn()).size());
    JsonNode summary=read("/api/projects/"+projectId+"/summary",owner);
    assertEquals(2,summary.get("notes").asInt());
    assertEquals(1,summary.get("untagged_notes").asInt());
    assertEquals(1,summary.get("tags").get(0).get("notes").asInt());
    String extra=ok(post("/api/projects/"+projectId+"/tags"),owner,Map.of("name","Temporária")).get("id").asText();
    String draft=ok(post("/api/projects/"+projectId+"/notes"),owner,Map.of("title","Rascunho","body","","tagIds",List.of(extra))).get("id").asText();
    mvc.perform(delete("/api/tags/"+extra).cookie(owner).header("X-Requested-With","XMLHttpRequest")).andExpect(status().isOk());
    JsonNode saved=ok(put("/api/notes/"+draft),owner,Map.of("title","Rascunho","body","[[0123456789abcdef0123456789abcdef0123]] texto","tagIds",List.of(extra),"version",1));
    assertEquals(0,saved.get("tags").size());
    JsonNode file=read("/api/files/"+fileId,owner);
    assertEquals("Decisão B",file.get("notes").get(0).get("title").asText());
    MvcResult text=mvc.perform(get("/api/files/"+fileId+"/content").cookie(owner)).andExpect(status().isOk()).andReturn();
    assertTrue(text.getResponse().getContentType().toLowerCase().contains("charset=utf-8"));
    assertTrue(mvc.perform(get("/api/files/"+fileId+"/content").param("download","true").cookie(owner)).andExpect(status().isOk()).andReturn()
      .getResponse().getHeader("Content-Disposition").startsWith("attachment"));
    assertEquals(1024L*1024,read("/api/files/limits",owner).get("maxBytes").asLong());
    mvc.perform(multipart("/api/projects/"+projectId+"/files").file(new MockMultipartFile("file","grande.txt","text/plain",new byte[1024*1024+1]))
      .cookie(owner).header("X-Requested-With","XMLHttpRequest")).andExpect(status().isPayloadTooLarge());
  }
}
