package com.ledgersync.batch.arch;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.persistence.Entity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.CrudRepository;

/**
 * Constitution compliance, enforced mechanically rather than by review vigilance.
 *
 * <p>This is a <strong>standing gate</strong> (T024). It passes trivially while packages are
 * empty and grows teeth as code lands — that is the intent, not a weakness.
 *
 * <p>Runs under Surefire with no Spring context and no database, as the Development Workflow
 * section requires.
 *
 * <p><strong>Test classes are excluded</strong> ({@link ImportOption.DoNotIncludeTests}). The
 * constitution's ban on field {@code @Autowired} exists because it hides dependencies and makes a
 * class unconstructible in a test — a rationale that is vacuous when applied to the test itself.
 * Spring's test support injects fields by design, and forcing constructor injection into JUnit
 * classes would buy nothing. The rules below govern production code, which is where the
 * principles bite.
 */
@AnalyzeClasses(
        packages = "com.ledgersync.batch",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ConstitutionRulesTest {

    /** Principle III: constructor injection only. Field injection is prohibited outright. */
    @ArchTest
    static final ArchRule no_field_injection = fields()
            .should().notBeAnnotatedWith(Autowired.class)
            .because("Constitution III requires constructor injection; field @Autowired hides "
                    + "dependencies and makes classes unconstructible in tests");

    /** Principle III: records are DTOs. A record must never be a JPA entity. */
    @ArchTest
    static final ArchRule records_are_never_entities = classes()
            .that().areAnnotatedWith(Entity.class)
            .should(notBeRecords())
            .because("Constitution III keeps entity and DTO types distinct");

    /**
     * Principle II: the reconciliation core points at nothing. It must be unit-testable with no
     * database and no Spring context, which it cannot be if it reaches into ingestion or
     * persistence.
     */
    @ArchTest
    static final ArchRule reconciliation_core_depends_on_nothing_outward = noClasses()
            .that().resideInAPackage("..reconciliation..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..ingestion..", "..persistence..", "..alerting..")
            .because("Constitution II requires reconciliation logic free of I/O concerns");

    /** Principle II: persistence concerns must not leak into the reconciliation layer. */
    @ArchTest
    static final ArchRule reconciliation_is_free_of_persistence_frameworks = noClasses()
            .that().resideInAPackage("..reconciliation..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "org.springframework.data..")
            .because("Constitution II: the reconciliation layer must be testable without a database");

    /** Principle II: ingestion parses and validates input; it never reaches reconciliation rules. */
    @ArchTest
    static final ArchRule ingestion_does_not_contain_reconciliation = noClasses()
            .that().resideInAPackage("..ingestion..")
            .should().dependOnClassesThat().resideInAPackage("..reconciliation..")
            .because("Constitution II forbids reconciliation rules in the ingestion layer");

    /**
     * Principle II: "the Ingestion Layer MUST NOT write to the domain datastore."
     *
     * <p>Reading is permitted — the eligibility check in {@code LandingZoneScanner} needs run
     * history. Writing is not. This rule draws that line precisely rather than banning the
     * dependency wholesale, which would have forced the eligibility logic somewhere worse.
     */
    @ArchTest
    static final ArchRule ingestion_never_writes_to_the_datastore = noClasses()
            .that().resideInAPackage("..ingestion..")
            .should().callMethodWhere(
                    target(nameMatching("save.*|delete.*|flush")).and(
                            target(owner(assignableTo(CrudRepository.class)))))
            .because("Constitution II: the ingestion layer may read, but must not write to the "
                    + "domain datastore");

    private static ArchCondition<JavaClass> notBeRecords() {
        return new ArchCondition<>("not be records") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.isRecord()) {
                    events.add(SimpleConditionEvent.violated(
                            item, item.getName() + " is a record annotated @Entity"));
                }
            }
        };
    }
}
