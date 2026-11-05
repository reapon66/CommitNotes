package com.commitnotes;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
class ApiErrors {
  @Value("${app.max-upload-mb}") int maxUploadMb;
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String,String>> status(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", ex.getReason() == null ? "Erro" : ex.getReason()));
  }
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String,String>> max() { return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error","Arquivo acima do limite de "+maxUploadMb+" MB")); }
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Map<String,String>> conflict() { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","Registro duplicado ou vínculo inválido")); }
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String,String>> invalid() { return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error","JSON inválido")); }
}
