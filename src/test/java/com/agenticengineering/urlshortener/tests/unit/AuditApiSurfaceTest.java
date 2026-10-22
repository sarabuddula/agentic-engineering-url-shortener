package com.agenticengineering.urlshortener.tests.unit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * AUD-01 evidence (FR-030; constitution audit retention): the API offers no way to update or delete audit or workflow
 * records - no PUT, PATCH or DELETE endpoint exists in the application. Evidence test for the N12 check, written after
 * the controllers (not red-first).
 */
class AuditApiSurfaceTest {

    private static final JavaClasses APP = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.agenticengineering.urlshortener");

    @Test
    void noEndpointUpdatesOrDeletesRecords() {
        noMethods().should().beAnnotatedWith(PutMapping.class).orShould().beAnnotatedWith(PatchMapping.class)
                .orShould().beAnnotatedWith(DeleteMapping.class).allowEmptyShould(true).check(APP);
    }
}
