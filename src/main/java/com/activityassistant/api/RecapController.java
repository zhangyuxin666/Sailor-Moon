package com.activityassistant.api;
import com.activityassistant.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class RecapController {
 private final RecapService recaps;private final RecapStore store;
 public RecapController(RecapService recaps,RecapStore store){this.recaps=recaps;this.store=store;}
 @PostMapping("/activities/{id}/recap")
 public Map<String,Object> generate(Authentication auth,@PathVariable String id,@RequestBody(required=false) JsonNode input){String request=input==null?UUID.randomUUID().toString():input.path("request_id").asText();return recaps.generate(id,AuthController.required(auth),request);}
 @GetMapping("/activities/{id}/recaps")
 public Map<String,Object> list(Authentication auth,@PathVariable String id){return Map.of("recaps",store.list(id,AuthController.required(auth)));}
 @GetMapping("/activities/{id}/recaps/{recapId}")
 public Map<String,Object> get(Authentication auth,@PathVariable String id,@PathVariable String recapId){return store.get(id,recapId,AuthController.required(auth));}
 @PostMapping("/activities/{id}/finish")
 public Map<String,Object> finish(Authentication auth,@PathVariable String id,@RequestBody JsonNode input){return store.finish(id,AuthController.required(auth),input);}
}
