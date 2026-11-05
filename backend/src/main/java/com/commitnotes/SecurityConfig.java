package com.commitnotes;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class SecurityConfig {
  @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable()).cors(c -> c.disable())
      .authorizeHttpRequests(a -> a.anyRequest().permitAll())
      .addFilterBefore(new OncePerRequestFilter() {
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
          if (req.getRequestURI().startsWith("/api/") && !req.getMethod().equals("GET") && !req.getMethod().equals("HEAD")
              && !"XMLHttpRequest".equals(req.getHeader("X-Requested-With"))) {
            res.sendError(403, "Cabeçalho de solicitação necessário"); return;
          }
          chain.doFilter(req,res);
        }
      }, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class).build();
  }
}
