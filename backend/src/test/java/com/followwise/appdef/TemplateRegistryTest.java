package com.followwise.appdef;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRegistryTest {

    private AppDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AppDefinitionValidator();
    }

    @Test
    void registryLoadsAndValidatesBothProductionTemplates() {
        TemplateRegistry registry = new TemplateRegistry(validator);
        registry.init();

        assertThat(registry.getTemplateIds()).containsExactlyInAnyOrder(
                "rental-property-manager",
                "client-document-tracker"
        );

        Optional<JsonNode> rpm = registry.getTemplate("rental-property-manager");
        assertThat(rpm).isPresent();
        assertThat(rpm.get().get("template").asText()).isEqualTo("rental-property-manager");

        Optional<JsonNode> cdt = registry.getTemplate("client-document-tracker");
        assertThat(cdt).isPresent();
        assertThat(cdt.get().get("template").asText()).isEqualTo("client-document-tracker");
    }

    @Test
    void registryReturnsDefensiveCopies() {
        TemplateRegistry registry = new TemplateRegistry(validator);
        registry.init();

        Optional<JsonNode> rpm1 = registry.getTemplate("rental-property-manager");
        assertThat(rpm1).isPresent();
        ((ObjectNode) rpm1.get()).put("mutatedProperty", "hacked");

        Optional<JsonNode> rpm2 = registry.getTemplate("rental-property-manager");
        assertThat(rpm2).isPresent();
        assertThat(rpm2.get().has("mutatedProperty")).isFalse();
    }

    @Test
    void fixtureWithUnknownPropertyThrowsIllegalStateException() {
        TemplateRegistry registry = new TemplateRegistry(validator, "classpath*:fixtures/invalid-templates/*.json");
        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed validation");
    }

    @Test
    void fixtureWithMismatchedKeyThrowsIllegalStateException() {
        TemplateRegistry registry = new TemplateRegistry(validator, "classpath*:fixtures/mismatched-templates/*.json");
        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match filename without .json");
    }
}
