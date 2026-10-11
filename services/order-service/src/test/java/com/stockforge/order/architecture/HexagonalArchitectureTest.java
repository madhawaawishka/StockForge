package com.stockforge.order.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.stockforge.order", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule dependenciesPointInwards = onionArchitecture()
            .domainModels("..domain.model..")
            .domainServices("..domain.port..")
            .applicationServices("..application..")
            .adapter("web", "..adapter.in.web..")
            .adapter("grpc", "..adapter.in.grpc..")
            .adapter("kafka", "..adapter.in.kafka..")
            .adapter("scheduler", "..adapter.in.scheduler..")
            .adapter("persistence", "..adapter.out.persistence..")
            .adapter("messaging", "..adapter.out.messaging..")
            .adapter("id", "..adapter.out.id..")
            .adapter("grpcClient", "..adapter.out.grpc..")
            .ignoreDependency(resideInAPackage("..config.."), alwaysTrue());

    @ArchTest
    static final ArchRule domainIsPureJava = classes()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..domain..", "com.stockforge.events..")
            .because("the domain must be testable and understandable without any framework");

    @ArchTest
    static final ArchRule applicationUsesOnlyTransactionDemarcationFromSpring = classes()
            .that()
            .resideInAPackage("..application..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "..domain..",
                    "..application..",
                    "com.stockforge.events..",
                    "org.springframework.transaction.annotation..")
            .because("use cases own transaction boundaries but must not depend on web or persistence");

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
            .resideInAnyPackage("..adapter.in.web..", "..adapter.in.grpc..", "..adapter.out.persistence..")
            .should()
            .notBePublic()
            .because("adapters are implementation details reached only through ports, HTTP, or gRPC");
}
