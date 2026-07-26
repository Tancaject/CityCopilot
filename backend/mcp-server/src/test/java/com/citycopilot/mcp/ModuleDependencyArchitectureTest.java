package com.citycopilot.mcp;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.citycopilot",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ModuleDependencyArchitectureTest {

    @ArchTest
    static final ArchRule core_is_independent =
            noClasses().that().resideInAPackage("com.citycopilot.core..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.citycopilot.agent..",
                            "com.citycopilot.mcp..",
                            "com.citycopilot.eval.."
                    )
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule agent_does_not_depend_on_adapters =
            noClasses().that().resideInAPackage("com.citycopilot.agent..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.citycopilot.core..",
                            "com.citycopilot.mcp..",
                            "com.citycopilot.eval.."
                    )
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule mcp_depends_only_on_contracts_not_business_modules =
            noClasses().that().resideInAPackage("com.citycopilot.mcp..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.citycopilot.core..",
                            "com.citycopilot.agent..",
                            "com.citycopilot.eval.."
                    );
}
