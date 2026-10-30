package com.activityassistant.api;
import com.activityassistant.service.ParticipationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class ParticipationController {
 private final ParticipationService service;
 public ParticipationController(ParticipationService service){this.service=service;}
 @GetMapping("/participation/activities") public Map<String,Object> activities(Authentication auth){return service.activities(AuthController.required(auth));}
 @GetMapping("/activities/{id}/participation") public Map<String,Object> management(Authentication auth,@PathVariable String id){return service.management(id,AuthController.required(auth));}
 @GetMapping("/activities/{id}/participation/me") public Map<String,Object> mine(Authentication auth,@PathVariable String id){return service.mine(id,AuthController.required(auth));}
 @PutMapping("/activities/{id}/participation/settings") public Map<String,Object> settings(Authentication auth,@PathVariable String id,@RequestBody JsonNode input){return service.configure(id,AuthController.required(auth),input);}
 @PostMapping("/activities/{id}/check-ins") public Map<String,Object> checkIn(Authentication auth,@PathVariable String id,@RequestBody JsonNode input){return service.checkIn(id,AuthController.required(auth),input);}
 @PostMapping("/activities/{id}/feedback") public Map<String,Object> feedback(Authentication auth,@PathVariable String id,@RequestBody JsonNode input){return service.feedback(id,AuthController.required(auth),input);}
}
