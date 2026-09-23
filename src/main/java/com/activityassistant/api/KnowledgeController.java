package com.activityassistant.api;

import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.KnowledgeService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class KnowledgeController {
    private final KnowledgeService knowledge;

    public KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @PostMapping("/knowledge/documents")
    Map<String, Object> add(Authentication authentication,
                            @Valid @RequestBody ApiModels.KnowledgeDocumentRequest body) {
        AccountPrincipal account = AuthController.required(authentication);
        return knowledge.add(account, body.source(), body.content(), body.metadata());
    }

    @PostMapping("/knowledge/search")
    JsonNode search(Authentication authentication,
                    @Valid @RequestBody ApiModels.KnowledgeSearchRequest body) {
        AccountPrincipal account = AuthController.required(authentication);
        return knowledge.search(account, body.query(), body.topK() == null ? 5 : body.topK());
    }
}
