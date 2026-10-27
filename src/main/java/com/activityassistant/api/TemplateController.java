package com.activityassistant.api;

import com.activityassistant.service.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class TemplateController {
    private final TemplateService templates;
    public TemplateController(TemplateService templates){this.templates=templates;}
    @GetMapping("/business-templates")
    public Map<String,Object> list(Authentication auth){return Map.of("templates",templates.list(AuthController.required(auth)));}
    @GetMapping("/business-templates/sources")
    public Map<String,Object> sources(Authentication auth){return templates.sources(AuthController.required(auth));}
    @GetMapping("/business-templates/{id}")
    public Map<String,Object> get(Authentication auth,@PathVariable String id){return templates.get(AuthController.required(auth),id);}
    @PostMapping("/business-templates")
    public Map<String,Object> create(Authentication auth,@RequestBody JsonNode input){return templates.create(AuthController.required(auth),input);}
    @PutMapping("/business-templates/{id}")
    public Map<String,Object> update(Authentication auth,@PathVariable String id,@RequestBody JsonNode input){return templates.update(AuthController.required(auth),id,input);}
    @DeleteMapping("/business-templates/{id}")
    public Map<String,Object> delete(Authentication auth,@PathVariable String id,@RequestParam int expectedVersion){return templates.delete(AuthController.required(auth),id,expectedVersion);}
    @PostMapping("/business-templates/{id}/apply")
    public Map<String,Object> apply(Authentication auth,@PathVariable String id,@RequestBody JsonNode input){return templates.apply(AuthController.required(auth),id,input);}
}
