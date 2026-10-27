package com.activityassistant.api;

import com.activityassistant.service.ExportService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ExportController {
    private final ExportService exports;
    public ExportController(ExportService exports) { this.exports=exports; }

    private ResponseEntity<byte[]> response(byte[] bytes,String filename) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header("X-Content-Type-Options","nosniff").contentLength(bytes.length).body(bytes);
    }
    @GetMapping("/activities/{id}/exports/registrations.csv")
    public ResponseEntity<byte[]> registrations(Authentication auth,@PathVariable String id) {
        return response(exports.registrations(id,AuthController.required(auth)),"activity-registrations.csv");
    }
    @GetMapping("/activities/{id}/exports/tasks.csv")
    public ResponseEntity<byte[]> tasks(Authentication auth,@PathVariable String id) {
        return response(exports.tasks(id,AuthController.required(auth)),"activity-tasks.csv");
    }
    @GetMapping("/todos/{id}/exports/missing.csv")
    public ResponseEntity<byte[]> missing(Authentication auth,@PathVariable String id) {
        return response(exports.missing(id,AuthController.required(auth)),"todo-missing.csv");
    }
}
