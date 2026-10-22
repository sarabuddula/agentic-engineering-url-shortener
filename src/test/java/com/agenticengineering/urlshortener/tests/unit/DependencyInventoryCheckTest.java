package com.agenticengineering.urlshortener.tests.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.policy.DependencyInventoryCheck;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DEP-01 evidence source (T082): every non-test dependency declared in pom.xml must appear, with a pinned version, in
 * the license inventory docs/compliance/licenses.xml. Missing inputs fail closed.
 */
class DependencyInventoryCheckTest {

    static final Path POM = Path.of("pom.xml");
    static final Path INVENTORY = Path.of("docs/compliance/licenses.xml");

    @TempDir
    Path dir;

    @Test
    void theRealBuildPasses() {
        Evidence evidence = DependencyInventoryCheck.evaluate(POM, INVENTORY);

        assertThat(evidence.status()).isEqualTo(Evidence.Status.OK);
        assertThat(evidence.ref()).contains("docs/compliance/licenses.xml");
    }

    @Test
    void anUndeclaredRuntimeDependencyFails() throws IOException {
        Path pom = withExtraDependency("com.example", "undeclared-lib", "1.0.0", null);

        Evidence evidence = DependencyInventoryCheck.evaluate(pom, INVENTORY);

        assertThat(evidence.status()).isEqualTo(Evidence.Status.NOT_OK);
        assertThat(evidence.ref()).contains("com.example:undeclared-lib");
    }

    @Test
    void testScopedDependenciesAreOutOfScope() throws IOException {
        Path pom = withExtraDependency("com.example", "test-only-lib", "1.0.0", "test");

        assertThat(DependencyInventoryCheck.evaluate(pom, INVENTORY).status()).isEqualTo(Evidence.Status.OK);
    }

    @Test
    void anUnpinnedInventoryVersionFails() throws IOException {
        Path inventory = dir.resolve("licenses.xml");
        Files.writeString(inventory, Files.readString(INVENTORY).replaceFirst(
                "(<artifactId>h2</artifactId>\\s*<version>)[^<]+(</version>)", "$1[2.0,)$2"));

        Evidence evidence = DependencyInventoryCheck.evaluate(POM, inventory);

        assertThat(evidence.status()).isEqualTo(Evidence.Status.NOT_OK);
        assertThat(evidence.ref()).contains("com.h2database:h2").contains("not pinned");
    }

    @Test
    void missingInputsFailClosed() {
        assertThat(DependencyInventoryCheck.evaluate(POM, dir.resolve("absent.xml")).status()).isEqualTo(Evidence.Status.NOT_OK);
        assertThat(DependencyInventoryCheck.evaluate(dir.resolve("absent-pom.xml"), INVENTORY).status()).isEqualTo(Evidence.Status.NOT_OK);
    }

    private Path withExtraDependency(String group, String artifact, String version, String scope) throws IOException {
        String dependency = "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version
                + "</version>" + (scope == null ? "" : "<scope>" + scope + "</scope>") + "</dependency>";
        String pom = Files.readString(POM);
        int dependencies = pom.indexOf("<dependencies>"); // the project dependencies (no dependencyManagement in this pom)
        Path copy = dir.resolve("pom.xml");
        Files.writeString(copy, pom.substring(0, dependencies + "<dependencies>".length()) + dependency
                + pom.substring(dependencies + "<dependencies>".length()));
        return copy;
    }
}
