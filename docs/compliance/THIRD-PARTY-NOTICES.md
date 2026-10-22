# Third-Party Notices

The release artifact `url-shortener-0.1.0-SNAPSHOT.jar` (Spring Boot executable jar) bundles the third-party components
below, unmodified, under `BOOT-INF/lib/`. Each bundled jar keeps its own license and notice files (for example
`META-INF/LICENSE.txt`, `META-INF/NOTICE.txt`), which apply in full and are reproduced by reference here.

Where a component is offered under more than one license, the licenses are separated by " / "; this project uses each
such component under the first-listed license (Logback under EPL-2.0, H2 under MPL-2.0, jakarta.annotation-api under
EPL-2.0, HdrHistogram under CC0). Source code for the components under EPL, MPL or LGPL is available from Maven
Central (`-sources.jar` for the listed coordinates) and from each project's site.

Test-scope dependencies (for example the OpenAPI contract validator and its java-json-tools, Rhino and mailapi
dependencies) are not distributed and are not listed. The full inventory, including test scope, is
[licenses.xml](licenses.xml) (LIC-01).

Generated 2026-10-22 from the `BOOT-INF/lib/` contents of the jar built at commit e15092a and the license names and
URLs in licenses.xml (spring-boot-jarmode-tools, added by spring-boot-maven-plugin and not in licenses.xml, from its
bundled `META-INF/LICENSE.txt`). Regenerate it whenever a runtime dependency changes.

## Bundled components (45)

| Group | Artifact | Version | License |
|---|---|---|---|
| ch.qos.logback | logback-classic | 1.5.34 | [EPL-2.0](https://www.eclipse.org/legal/epl-v20.html) / [LGPL-2.1-only](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html) |
| ch.qos.logback | logback-core | 1.5.34 | [EPL-2.0](https://www.eclipse.org/legal/epl-v20.html) / [LGPL-2.1-only](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html) |
| com.bucket4j | bucket4j_jdk17-core | 8.21.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0) |
| com.fasterxml | classmate | 1.7.3 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| com.fasterxml.jackson.core | jackson-annotations | 2.21 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| com.fasterxml.jackson.core | jackson-core | 2.21.8 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| com.fasterxml.jackson.core | jackson-databind | 2.21.8 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| com.fasterxml.jackson.datatype | jackson-datatype-jdk8 | 2.21.8 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| com.fasterxml.jackson.datatype | jackson-datatype-jsr310 | 2.21.8 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| com.fasterxml.jackson.module | jackson-module-parameter-names | 2.21.8 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| com.h2database | h2 | 2.3.232 | [MPL 2.0](https://www.mozilla.org/en-US/MPL/2.0/) / [EPL 1.0](https://opensource.org/licenses/eclipse-1.0.php) |
| com.zaxxer | HikariCP | 6.3.3 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| io.micrometer | micrometer-commons | 1.15.12 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| io.micrometer | micrometer-core | 1.15.12 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| io.micrometer | micrometer-jakarta9 | 1.15.12 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| io.micrometer | micrometer-observation | 1.15.12 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| jakarta.annotation | jakarta.annotation-api | 2.1.1 | [EPL 2.0](http://www.eclipse.org/legal/epl-2.0) / [GPL2 w/ CPE](https://www.gnu.org/software/classpath/license.html) |
| jakarta.validation | jakarta.validation-api | 3.0.2 | [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| org.apache.logging.log4j | log4j-api | 2.25.5 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| org.apache.logging.log4j | log4j-to-slf4j | 2.25.5 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| org.apache.tomcat.embed | tomcat-embed-core | 10.1.61 | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| org.apache.tomcat.embed | tomcat-embed-el | 10.1.61 | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| org.apache.tomcat.embed | tomcat-embed-websocket | 10.1.61 | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| org.hdrhistogram | HdrHistogram | 2.2.2 | [Public Domain, per Creative Commons CC0](http://creativecommons.org/publicdomain/zero/1.0/) / [BSD-2-Clause](https://opensource.org/licenses/BSD-2-Clause) |
| org.hibernate.validator | hibernate-validator | 8.0.3.Final | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| org.jboss.logging | jboss-logging | 3.6.3.Final | [Apache License 2.0](https://repository.jboss.org/licenses/apache-2.0.txt) |
| org.latencyutils | LatencyUtils | 2.0.3 | [Public Domain, per Creative Commons CC0](http://creativecommons.org/publicdomain/zero/1.0/) |
| org.slf4j | jul-to-slf4j | 2.0.18 | [MIT](https://opensource.org/license/mit) |
| org.slf4j | slf4j-api | 2.0.18 | [MIT](https://opensource.org/license/mit) |
| org.springframework | spring-aop | 6.2.19 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework | spring-beans | 6.2.19 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework | spring-context | 6.2.19 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework | spring-core | 6.2.19 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework | spring-expression | 6.2.19 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework | spring-jcl | 6.2.19 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework | spring-jdbc | 6.2.19 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework | spring-tx | 6.2.19 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework | spring-web | 6.2.19 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework | spring-webmvc | 6.2.19 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework.boot | spring-boot | 3.5.16 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework.boot | spring-boot-actuator | 3.5.16 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework.boot | spring-boot-actuator-autoconfigure | 3.5.16 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework.boot | spring-boot-autoconfigure | 3.5.16 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.springframework.boot | spring-boot-jarmode-tools | 3.5.16 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| org.yaml | snakeyaml | 2.4 | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
