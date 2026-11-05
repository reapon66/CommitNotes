package com.commitnotes;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Paths;
import org.springframework.boot.SpringApplication;

/** Temporary local smoke server: real HTTP API plus disposable PostgreSQL. */
public class DevLauncher {
  public static void main(String[] args) throws Exception {
    EmbeddedPostgres postgres=EmbeddedPostgres.start();
    Runtime.getRuntime().addShutdownHook(new Thread(()->{try{postgres.close();}catch(Exception ignored){}}));
    System.setProperty("DB_URL",postgres.getJdbcUrl("postgres","postgres"));
    System.setProperty("DB_USER","postgres");
    System.setProperty("DB_PASSWORD","");
    System.setProperty("APP_SECRET","temporary-local-test-secret-at-least-32-chars");
    System.setProperty("APP_ORIGIN","http://localhost:5173");
    System.setProperty("FILES_DIR",Paths.get("target/live-files").toAbsolutePath().toString());
    SpringApplication.run(CommitNotesApplication.class,args);
  }
}
