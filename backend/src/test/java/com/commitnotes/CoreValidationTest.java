package com.commitnotes;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CoreValidationTest {
  @Test void validatesInputLimitsAndDates() {
    assertEquals("Olá",Core.required("  Olá  ",10,"Nome"));
    assertThrows(ResponseStatusException.class,()->Core.required(" ",10,"Nome"));
    assertThrows(ResponseStatusException.class,()->Core.optional("abcdefghijk",10,"Texto"));
    assertThrows(ResponseStatusException.class,()->Core.uuid("../../other"));
    assertThrows(ResponseStatusException.class,()->Core.instant("amanhã"));
  }
  @Test void hashesResetTokensWithoutStoringThemInPlainText() {
    assertEquals(64,Core.hash("token").length());
    assertNotEquals("token",Core.hash("token"));
    assertEquals(Core.hash("token"),Core.hash("token"));
  }
}
