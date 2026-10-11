package com.stockforge.gateway.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.stockforge.gateway",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule onion_architecture_is_respected = onionArchitecture()
            .domainModels("..domain.model..")
            .domainServices("..domain.port..")
            .applicationServices("..application..")
            .adapter("in-web", "..adapter.in.web..")
            .adapter("out-grpc", "..adapter.out.grpc..")
            .adapter("out-redis", "..adapter.out.redis..")
            .ignoreDependency(resideInAPackage("..config.."), alwaysTrue());
}
