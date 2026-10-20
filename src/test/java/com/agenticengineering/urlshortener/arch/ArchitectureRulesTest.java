package com.agenticengineering.urlshortener.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Plane and layer rules from plan "Allowed dependency directions" (NFR-004, ADR-004). Test classes are imported too,
 * so the T009 sacrificial fixture is analysed until T010 deletes it.
 */
@AnalyzeClasses(packages = "com.agenticengineering.urlshortener")
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule domainAndSharedAreFrameworkFree = noClasses()
            .that().resideInAnyPackage("..urlshortener.shortener.domain..", "..urlshortener.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "org.hibernate..")
            .because("shortener.domain and shared must stay framework-free (plan Project Structure, NFR-004)");

    @ArchTest
    static final ArchRule shortenerNeverDependsOnOrchestration = noClasses()
            .that().resideInAPackage("..urlshortener.shortener..")
            .should().dependOnClassesThat().resideInAPackage("..urlshortener.orchestration..")
            .because("the application plane never depends on the control plane (plan Project Structure, ADR-004)");
}
