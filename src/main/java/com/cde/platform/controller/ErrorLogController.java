package com.cde.platform.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Receives frontend error events from RemoteLoggingService.
 * Logs them server-side and optionally forwards to external logging (Datadog, ELK etc.).
 *
 * POST /api/logs/errors
 */
@RestController
@RequestMapping("/api/logs")
public class ErrorLogController {

    @PostMapping("/errors")
    public ResponseEntity<Void> logError(@RequestBody Map<String, Object> event) {
        String level   = String.valueOf(event.getOrDefault("level",   "error"));
        String message = String.valueOf(event.getOrDefault("message", ""));
        String type    = String.valueOf(event.getOrDefault("type",    "unknown"));
        String url     = String.valueOf(event.getOrDefault("url",     ""));
        String user    = String.valueOf(event.getOrDefault("username","anonymous"));
        String ts      = String.valueOf(event.getOrDefault("timestamp", LocalDateTime.now().toString()));

        // Structured log output — can be captured by any log aggregator (ELK, Datadog, Splunk)
        String logLine = String.format(
            "[FRONTEND-%s] user=%s type=%s url=%s ts=%s message=%s",
            level.toUpperCase(), user, type, url, ts, message
        );

        switch (level) {
            case "error"   -> System.err.println(logLine);
            case "warning" -> System.out.println("[WARN]  " + logLine);
            default        -> System.out.println("[INFO]  " + logLine);
        }

        // TODO: forward to external logging
        // logForwarder.send(event);

        return ResponseEntity.accepted().build();
    }
}
