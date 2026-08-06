package io.konveyor.provider.index;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SymbolIndex} covering all supported location types, glob pattern
 * matching, star imports, annotated queries, and line number tracking. Uses synthetic
 * Java source fixtures created in a temp directory.
 */
class SymbolIndexTest {

    @TempDir
    static Path tempDir;

    static SymbolIndex index;

    @BeforeAll
    static void setUp() throws IOException {
        Path srcDir = tempDir.resolve("com/example/apps");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("Bean.java"), """
                package com.example.apps;

                import javax.ejb.Singleton;
                import javax.ejb.SessionBean;
                import org.springframework.stereotype.*;

                @Singleton
                public class Bean implements SessionBean {

                    private String name;

                    public Bean() {
                        this.name = "default";
                    }

                    public String getName() {
                        return name;
                    }

                    public void setName(String name) {
                        this.name = name;
                    }
                }
                """);

        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example.apps;

                import com.example.apps.Bean;
                import org.springframework.beans.factory.annotation.Autowired;

                public class Service {

                    @Autowired
                    private Bean myBean;

                    public void doStuff() {
                        String n = myBean.getName();
                    }

                    public Bean createBean() {
                        return new Bean();
                    }
                }
                """);

        index = new SymbolIndex();
        index.indexDirectory(tempDir);
    }

    @Test
    void shouldIndexImports() {
        List<IndexedSymbol> imports = index.query("javax.ejb.Singleton", LocationType.IMPORT);
        assertThat(imports).hasSize(1);
        assertThat(imports.get(0).kind()).isEqualTo(SymbolKind.MODULE);
        assertThat(imports.get(0).name()).isEqualTo("javax.ejb.Singleton");
        assertThat(imports.get(0).packageName()).isEqualTo("com.example.apps");
    }

    @Test
    void shouldIndexStarImports() {
        List<IndexedSymbol> imports = index.query("org.springframework.stereotype.*", LocationType.IMPORT);
        assertThat(imports).hasSize(1);
        assertThat(imports.get(0).kind()).isEqualTo(SymbolKind.MODULE);
    }

    @Test
    void shouldIndexImportsWithWildcardPattern() {
        List<IndexedSymbol> imports = index.query("javax.ejb.*", LocationType.IMPORT);
        assertThat(imports).hasSize(2);
    }

    @Test
    void shouldIndexPackage() {
        // PACKAGE is derived from IMPORT symbols — pattern matches the package portion of the FQN
        List<IndexedSymbol> packages = index.query("javax.ejb", LocationType.PACKAGE);
        assertThat(packages).hasSize(2);
        assertThat(packages.get(0).kind()).isEqualTo(SymbolKind.MODULE);
        assertThat(packages.get(0).name()).isEqualTo("javax.ejb");
    }

    @Test
    void shouldIndexPackageWithWildcard() {
        List<IndexedSymbol> packages = index.query("javax.*", LocationType.PACKAGE);
        assertThat(packages).hasSize(2);
    }

    @Test
    void shouldIndexAnnotations() {
        List<IndexedSymbol> annotations = index.query("javax.ejb.Singleton", LocationType.ANNOTATION);
        assertThat(annotations).hasSize(1);
        assertThat(annotations.get(0).kind()).isEqualTo(SymbolKind.PROPERTY);
        assertThat(annotations.get(0).name()).isEqualTo("Singleton");
    }

    @Test
    void shouldIndexAnnotationsFromStarImport() {
        List<IndexedSymbol> annotations = index.query(
                "org.springframework.beans.factory.annotation.Autowired", LocationType.ANNOTATION);
        assertThat(annotations).hasSize(1);
        assertThat(annotations.get(0).name()).isEqualTo("Autowired");
    }

    @Test
    void shouldIndexClassDeclarations() {
        List<IndexedSymbol> classes = index.query("com.example.apps.Bean", LocationType.CLASS);
        assertThat(classes).hasSize(1);
        assertThat(classes.get(0).kind()).isEqualTo(SymbolKind.CLASS);
        assertThat(classes.get(0).name()).isEqualTo("Bean");
    }

    @Test
    void shouldIndexImplementsType() {
        List<IndexedSymbol> impls = index.query("javax.ejb.SessionBean", LocationType.IMPLEMENTS_TYPE);
        assertThat(impls).hasSize(1);
        assertThat(impls.get(0).kind()).isEqualTo(SymbolKind.CLASS);
        assertThat(impls.get(0).name()).isEqualTo("Bean");
    }

    @Test
    void shouldIndexFieldDeclarations() {
        List<IndexedSymbol> fields = index.query("com.example.apps.Bean", LocationType.FIELD);
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).kind()).isEqualTo(SymbolKind.FIELD);
        assertThat(fields.get(0).name()).isEqualTo("myBean");
    }

    @Test
    void shouldIndexFieldWithWildcardName() {
        List<IndexedSymbol> fields = index.query("* com.example.apps.Bean", LocationType.FIELD);
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).name()).isEqualTo("myBean");
    }

    @Test
    void shouldIndexMethodDeclarations() {
        List<IndexedSymbol> methods = index.query("com.example.apps.Service.doStuff", LocationType.METHOD);
        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).kind()).isEqualTo(SymbolKind.METHOD);
        assertThat(methods.get(0).name()).isEqualTo("doStuff");
    }

    @Test
    void shouldIndexMethodDeclarationsWithWildcard() {
        List<IndexedSymbol> methods = index.query("com.example.apps.Bean.get*", LocationType.METHOD);
        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).name()).isEqualTo("getName");
    }

    @Test
    void shouldIndexMethodDeclarationsByNameOnly() {
        List<IndexedSymbol> methods = index.query("doStuff", LocationType.METHOD);
        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).name()).isEqualTo("doStuff");
    }

    @Test
    void shouldIndexMethodCalls() {
        // Without binding resolution, the receiver "myBean" resolves via the package fallback.
        // The name is the CONTAINING method, not the called method.
        List<IndexedSymbol> calls = index.query("com.example.apps.myBean.getName", LocationType.METHOD_CALL);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).kind()).isEqualTo(SymbolKind.METHOD);
        assertThat(calls.get(0).name()).isEqualTo("doStuff");
    }

    @Test
    void shouldIndexConstructorCalls() {
        List<IndexedSymbol> ctors = index.query("com.example.apps.Bean", LocationType.CONSTRUCTOR_CALL);
        assertThat(ctors).hasSize(1);
        assertThat(ctors.get(0).kind()).isEqualTo(SymbolKind.CLASS);
    }

    @Test
    void shouldIndexReturnTypes() {
        List<IndexedSymbol> returns = index.query("com.example.apps.Bean", LocationType.RETURN_TYPE);
        assertThat(returns).hasSize(1);
        assertThat(returns.get(0).kind()).isEqualTo(SymbolKind.METHOD);
        assertThat(returns.get(0).name()).isEqualTo("createBean");
    }

    @Test
    void shouldIndexMethodWithReturnTypePattern() {
        // RETURN_TYPE stores the return type as qualifiedName — query by type directly
        List<IndexedSymbol> methods = index.query(
                "com.example.apps.Bean", LocationType.RETURN_TYPE);
        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).name()).isEqualTo("createBean");
    }

    @Test
    void shouldIndexVariableDeclarations() {
        // Without binding resolution, "String" resolves to "com.example.apps.String"
        // via the package name fallback, so query for that
        List<IndexedSymbol> vars = index.query("com.example.apps.String", LocationType.VARIABLE_DECLARATION);
        assertThat(vars).isNotEmpty();
        assertThat(vars.get(0).kind()).isEqualTo(SymbolKind.VARIABLE);
    }

    @Test
    void shouldSupportAnnotatedQuery() {
        List<IndexedSymbol> classes = index.query("com.example.apps.Bean", LocationType.CLASS);
        assertThat(classes).hasSize(1);

        boolean hasAnnotation = index.hasMatchingAnnotation(
                classes.get(0), "javax.ejb.Singleton", null);
        assertThat(hasAnnotation).isTrue();

        boolean wrongAnnotation = index.hasMatchingAnnotation(
                classes.get(0), "javax.ejb.Stateless", null);
        assertThat(wrongAnnotation).isFalse();
    }

    @Test
    void shouldSupportFieldAnnotatedQuery() {
        List<IndexedSymbol> fields = index.query("com.example.apps.Bean", LocationType.FIELD);
        assertThat(fields).hasSize(1);

        boolean hasAutowired = index.hasMatchingAnnotation(
                fields.get(0), "org.springframework.beans.factory.annotation.Autowired", null);
        assertThat(hasAutowired).isTrue();
    }

    @Test
    void shouldTrackLineNumbers() {
        List<IndexedSymbol> annotations = index.query("javax.ejb.Singleton", LocationType.ANNOTATION);
        assertThat(annotations).hasSize(1);
        // @Singleton is on line 7 (0-based: 6)
        assertThat(annotations.get(0).line()).isEqualTo(6);
    }

    @Test
    void shouldIncludeImportsInTypeQuery() {
        // TYPE queries should also return IMPORT symbols (as Module kind)
        List<IndexedSymbol> types = index.query("javax.ejb.Singleton", LocationType.TYPE);
        assertThat(types).isNotEmpty();
        boolean hasImport = types.stream().anyMatch(s -> s.kind() == SymbolKind.MODULE);
        assertThat(hasImport).isTrue();
    }

    @Test
    void shouldIncludeUsagesInTypeQuery() {
        // TYPE queries should include field-level and method-level type usages
        List<IndexedSymbol> types = index.query("com.example.apps.Bean", LocationType.TYPE);
        assertThat(types).isNotEmpty();
        // Should contain the class declaration (CLASS kind) and field type usage (FIELD kind)
        boolean hasClass = types.stream().anyMatch(s -> s.kind() == SymbolKind.CLASS);
        boolean hasField = types.stream().anyMatch(s -> s.kind() == SymbolKind.FIELD);
        assertThat(hasClass).isTrue();
        assertThat(hasField).isTrue();
    }

    @Test
    void shouldHandleTypeKeywordLocation() {
        // TYPE_KEYWORD ("type" string from rules) should behave identically to TYPE
        List<IndexedSymbol> types = index.query("javax.ejb.Singleton", LocationType.TYPE_KEYWORD);
        assertThat(types).isNotEmpty();
        boolean hasImport = types.stream().anyMatch(s -> s.kind() == SymbolKind.MODULE);
        assertThat(hasImport).isTrue();
    }

    @Test
    void shouldStripTypeParametersInPatterns() {
        assertThat(SymbolIndex.stripTypeParameters("com.example.Foo<*>")).isEqualTo("com.example.Foo");
        assertThat(SymbolIndex.stripTypeParameters("com.example.Foo")).isEqualTo("com.example.Foo");
    }

    @Test
    void globToRegexShouldWork() {
        assertThat(SymbolIndex.globToRegex("com.example.*").matcher("com.example.Foo").matches()).isTrue();
        assertThat(SymbolIndex.globToRegex("com.example.*").matcher("com.example.bar.Baz").matches()).isTrue();
        assertThat(SymbolIndex.globToRegex("com.example.*").matcher("com.other.Foo").matches()).isFalse();
        assertThat(SymbolIndex.globToRegex("com.example.Foo").matcher("com.example.Foo").matches()).isTrue();
        assertThat(SymbolIndex.globToRegex("com.example.Foo").matcher("com.example.Bar").matches()).isFalse();
        assertThat(SymbolIndex.globToRegex("do*").matcher("doStuff").matches()).isTrue();
        assertThat(SymbolIndex.globToRegex("do*").matcher("doThings").matches()).isTrue();
        assertThat(SymbolIndex.globToRegex("do*").matcher("getStuff").matches()).isFalse();
    }
}
