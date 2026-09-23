package com.activityassistant.api;

import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.ClassroomService;
import com.activityassistant.service.StorageService;
import com.activityassistant.support.ApiException;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
public class ClassroomController {
    private final ClassroomService classroom;
    private final Db db;
    private final StorageService storage;

    public ClassroomController(ClassroomService classroom, Db db, StorageService storage) {
        this.classroom = classroom;
        this.db = db;
        this.storage = storage;
    }

    @GetMapping("/portal/dashboard")
    Map<String, Object> dashboard(Authentication authentication) {
        return classroom.dashboard(AuthController.required(authentication));
    }

    @PostMapping("/classes")
    Map<String, Object> createClass(Authentication authentication,
                                    @Valid @RequestBody ApiModels.ClassCreateRequest body) {
        return classroom.createClass(AuthController.required(authentication), body.name());
    }

    @PostMapping("/classes/{classId}/members")
    Map<String, Object> importMembers(Authentication authentication, @PathVariable String classId,
                                      @Valid @RequestBody ApiModels.RosterImportRequest body) {
        return classroom.importRoster(AuthController.required(authentication), classId, body.members());
    }

    @GetMapping("/classes/{classId}/members")
    Map<String, Object> members(Authentication authentication, @PathVariable String classId) {
        return Map.of("members", classroom.classMembers(AuthController.required(authentication), classId));
    }

    @GetMapping("/classes/member-template.xlsx")
    ResponseEntity<ClassPathResource> memberTemplate() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("班级成员导入模板.xlsx", StandardCharsets.UTF_8).build().toString())
                .body(new ClassPathResource("templates/class_roster_template.xlsx"));
    }

    @PostMapping(value = "/classes/{classId}/members/import-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> importExcel(Authentication authentication, @PathVariable String classId,
                                    @RequestPart("file") MultipartFile file) throws IOException {
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase().endsWith(".xlsx"))
            throw ApiException.badRequest("请上传 .xlsx 格式的 Excel 文件");
        return classroom.importRosterExcel(AuthController.required(authentication), classId, file.getBytes());
    }

    @PostMapping("/classes/{classId}/todos")
    Map<String, Object> createTodo(Authentication authentication, @PathVariable String classId,
                                   @Valid @RequestBody ApiModels.TodoCreateRequest body) {
        return classroom.createTodo(AuthController.required(authentication), classId, body);
    }

    @GetMapping("/todos/{todoId}")
    Map<String, Object> todo(Authentication authentication, @PathVariable String todoId) {
        return classroom.todoDetail(AuthController.required(authentication), todoId);
    }

    @PostMapping(value = "/todos/{todoId}/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> submit(Authentication authentication, @PathVariable String todoId,
                               @RequestParam(defaultValue = "") String note,
                               @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        return classroom.submit(AuthController.required(authentication), todoId, note,
                file == null ? null : file.getOriginalFilename(), file == null || file.isEmpty() ? null : file.getBytes());
    }

    @PostMapping("/todos/{todoId}/remind")
    Map<String, Object> remind(Authentication authentication, @PathVariable String todoId) {
        return classroom.remindMissing(AuthController.required(authentication), todoId, false);
    }

    @PostMapping("/todos/{todoId}/reminders")
    Map<String, Object> schedule(Authentication authentication, @PathVariable String todoId,
                                 @Valid @RequestBody ApiModels.TodoReminderRequest body) {
        return classroom.scheduleReminder(AuthController.required(authentication), todoId, body.remindAt());
    }

    @GetMapping("/todos/{todoId}/submissions/{accountId}/file")
    ResponseEntity<ByteArrayResource> download(Authentication authentication, @PathVariable String todoId,
                                                @PathVariable String accountId) {
        AccountPrincipal account = AuthController.required(authentication);
        classroom.todoDetail(account, todoId);
        if (!account.isManager() && !account.id().equals(accountId)) throw ApiException.forbidden("只能下载自己的提交文件");
        Map<String, Object> file = db.one("SELECT storage_key, original_filename, content_type FROM file_assets WHERE todo_id=? AND account_id=? ORDER BY created_at DESC LIMIT 1",
                todoId, accountId).orElseThrow(() -> ApiException.notFound("该成员没有上传文件"));
        byte[] content = storage.read(String.valueOf(file.get("storage_key")));
        String type = file.get("content_type") == null ? "application/octet-stream" : String.valueOf(file.get("content_type"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(String.valueOf(file.get("original_filename")), StandardCharsets.UTF_8).build().toString())
                .body(new ByteArrayResource(content));
    }
}
