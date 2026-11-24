package com.activityassistant.api;

import com.activityassistant.service.ClassChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ClassChatController {
    private final ClassChatService chat;

    public ClassChatController(ClassChatService chat) {
        this.chat = chat;
    }

    @GetMapping("/rooms")
    Map<String, Object> rooms(Authentication authentication) {
        return Map.of("rooms", chat.rooms(AuthController.required(authentication)));
    }

    @GetMapping("/rooms/{classId}/messages")
    Map<String, Object> messages(Authentication authentication, @PathVariable String classId,
                                 @RequestParam(required = false) Long after, @RequestParam(required = false) Long before) {
        return Map.of("messages", chat.messages(classId, AuthController.required(authentication), after, before));
    }

    @PostMapping("/rooms/{classId}/messages")
    Map<String, Object> send(Authentication authentication, @PathVariable String classId,
                             @Valid @RequestBody MessageRequest request) {
        return chat.send(classId, AuthController.required(authentication), request.clientMessageId(), request.body());
    }

    record MessageRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String clientMessageId,
                          @NotBlank @Size(max = 1000) String body) {}
}
