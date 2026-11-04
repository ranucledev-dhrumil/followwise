package com.followwise.appdef;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TemplateRegistry {

    private final AppDefinitionValidator validator;
    private final JsonMapper jsonMapper;
    private final String locationPattern;
    private final Map<String, JsonNode> templates = new ConcurrentHashMap<>();

    @Autowired
    public TemplateRegistry(AppDefinitionValidator validator) {
        this(validator, "classpath*:appdefinition/templates/*.json");
    }

    public TemplateRegistry(AppDefinitionValidator validator, String locationPattern) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.jsonMapper = new JsonMapper();
        this.locationPattern = Objects.requireNonNull(locationPattern, "locationPattern must not be null");
    }

    @PostConstruct
    public void init() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(locationPattern);

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".json")) {
                    continue;
                }
                String expectedKey = filename.substring(0, filename.length() - ".json".length());
                String content;
                try (var is = resource.getInputStream()) {
                    content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }

                List<ValidationError> errors = validator.validate(content);
                if (!errors.isEmpty()) {
                    throw new IllegalStateException("Template " + filename + " failed validation: " + errors);
                }

                JsonNode root = jsonMapper.readTree(content);
                if (!root.has("template") || !expectedKey.equals(root.get("template").asText())) {
                    throw new IllegalStateException("Template property in " + filename + " does not match filename without .json");
                }

                templates.put(expectedKey, root);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load templates from pattern " + locationPattern, e);
        }
    }

    public Optional<JsonNode> getTemplate(String templateId) {
        if (templateId == null) {
            return Optional.empty();
        }
        JsonNode node = templates.get(templateId);
        return Optional.ofNullable(node != null ? node.deepCopy() : null);
    }

    public Set<String> getTemplateIds() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    public Map<String, JsonNode> getAllTemplates() {
        Map<String, JsonNode> copy = new HashMap<>();
        templates.forEach((k, v) -> copy.put(k, v.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }
}
