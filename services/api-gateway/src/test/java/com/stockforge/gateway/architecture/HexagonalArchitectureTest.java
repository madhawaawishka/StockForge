package com.stockforge.gateway.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The architecture rules of CLAUDE.md and ADR-004, executable. Fix the dependency, not the rule. */
@AnalyzeClasses(packages = "com.stockforge.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule dependenciesPointInwards = onionArchitecture()
            .domainModels("..domain.model..")
            .domainServices("..domain.port..")
            .applicationServices("..application..")
            .adapter("web", "..adapter.in.web..")
            .adapter("grpc", "..adapter.out.grpc..")
            .adapter("redis", "..adapter.out.redis..")
            .ignoreDependency(resideInAPackage("..config.."), alwaysTrue());

    @ArchTest
    static final ArchRule domainIsPureJava = classes()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..domain..")
            .because("the domain must be testable and understandable without any framework");

    @ArchTest
    static final ArchRule applicationIsFrameworkFree = classes()
            .that()
            .resideInAPackage("..application..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..domain..", "..application..")
            .because("gateway use cases orchestrate ports; transport and security live in adapters");

    @ArchTest
    static final ArchRule useCasesAreWiredExplicitly = noClasses()
            .that()
            .resideInAnyPackage("..domain..", "..application..")
            .should()
            .beAnnotatedWith("org.springframework.stereotype.Component")
            .orShould()
            .beAnnotatedWith("org.springframework.stereotype.Service")
            .because("use cases are registered as @Bean in the config package");

    @ArchTest
    static final ArchRule adaptersAreNotPublicApi = classes()
            .that()
            .resideInAnyPackage("..adapter.in.web..", "..adapter.out.grpc..", "..adapter.out.redis..")
            .should()
            .notBePublic()
            .because("adapters are implementation details reached only through ports or HTTP");
}
