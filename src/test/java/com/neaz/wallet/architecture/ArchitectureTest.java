package com.neaz.wallet.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the layering rules from CLAUDE.md section 4.5 so they cannot erode silently.
 *
 * <p>Rules use {@code allowEmptyShould(true)} because features are added one at a time; a rule about a
 * package that does not exist yet should pass, not fail.
 */
@AnalyzeClasses(packages = "com.neaz.wallet", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.neaz.wallet.";
    private static final List<String> FEATURES =
            List.of("account", "ledger", "deposit", "transfer", "billpayment", "idempotency", "outbox");

    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..", "tools.jackson..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiDoesNotUseInfrastructure = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule transactionalClassesOnlyInApplication = noClasses()
            .that().resideOutsideOfPackage("..application..")
            .should().beAnnotatedWith(SPRING_TRANSACTIONAL)
            .orShould().beAnnotatedWith(JAKARTA_TRANSACTIONAL)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule transactionalMethodsOnlyInApplication = methods()
            .that().areDeclaredInClassesThat().resideOutsideOfPackage("..application..")
            .should().notBeAnnotatedWith(SPRING_TRANSACTIONAL)
            .andShould().notBeAnnotatedWith(JAKARTA_TRANSACTIONAL)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule coreModulesDoNotDependOnUseCases = noClasses()
            .that().resideInAnyPackage(ROOT + "ledger..", ROOT + "account..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "transfer..", ROOT + "deposit..", ROOT + "billpayment..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noImplSuffix = classes()
            .should().haveSimpleNameNotEndingWith("Impl")
            .allowEmptyShould(true);

    /** A feature may use another feature's application layer only, never its api or infrastructure. */
    @ArchTest
    static void featuresDoNotReachIntoEachOthersInternals(JavaClasses classes) {
        for (String feature : FEATURES) {
            String[] otherInternals = FEATURES.stream()
                    .filter(other -> !other.equals(feature))
                    .flatMap(other -> Stream.of(
                            ROOT + other + ".api..", ROOT + other + ".infrastructure.."))
                    .toArray(String[]::new);

            noClasses()
                    .that().resideInAPackage(ROOT + feature + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(otherInternals)
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }
}
