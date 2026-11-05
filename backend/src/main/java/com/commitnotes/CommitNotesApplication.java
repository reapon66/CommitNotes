package com.commitnotes;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.util.unit.DataSize;

@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class CommitNotesApplication {
  public static void main(String[] args) { SpringApplication.run(CommitNotesApplication.class, args); }
  /** MAX_UPLOAD_MB (1-100) limits each uploaded file; the request may carry 1 MB of multipart overhead. */
  @Bean MultipartConfigElement multipartConfig(@Value("${app.max-upload-mb}") int maxUploadMb) {
    if (maxUploadMb < 1 || maxUploadMb > 100) throw new IllegalStateException("MAX_UPLOAD_MB must be between 1 and 100");
    MultipartConfigFactory factory = new MultipartConfigFactory();
    factory.setMaxFileSize(DataSize.ofMegabytes(maxUploadMb));
    factory.setMaxRequestSize(DataSize.ofMegabytes(maxUploadMb + 1L));
    return factory.createMultipartConfig();
  }
}
