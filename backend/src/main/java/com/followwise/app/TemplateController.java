package com.followwise.app;

import com.followwise.appdef.TemplateRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateRegistry templateRegistry;

    public TemplateController(TemplateRegistry templateRegistry) {
        this.templateRegistry = templateRegistry;
    }

    @GetMapping
    public ResponseEntity<List<TemplateSummaryResponse>> listTemplates() {
        List<TemplateSummaryResponse> list = templateRegistry.getAllTemplates().entrySet().stream()
                .map(e -> new TemplateSummaryResponse(
                        e.getKey(),
                        e.getValue().hasNonNull("name") ? e.getValue().get("name").asText() : e.getKey()
                ))
                .sorted(Comparator.comparing(TemplateSummaryResponse::key))
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{key}")
    public ResponseEntity<JsonNode> getTemplate(@PathVariable("key") String key) {
        if (key == null || !key.matches("^[a-z0-9-]+$")) {
            throw new AppNotFoundException("Template not found");
        }
        JsonNode template = templateRegistry.getTemplate(key)
                .orElseThrow(() -> new AppNotFoundException("Template not found"));
        return ResponseEntity.ok(template);
    }
}
