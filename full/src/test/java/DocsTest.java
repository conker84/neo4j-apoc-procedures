import apoc.util.TestUtil;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.internal.kernel.api.procs.*;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ConfigurationBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static apoc.ApocConfig.APOC_UUID_ENABLED;
import static apoc.ApocConfig.apocConfig;
import static org.junit.Assert.assertFalse;

/**
 * @author ab-larus
 * @since 05.09.18
 */
public class DocsTest {

    public static final String GENERATED_DOCUMENTATION_DIR = "../docs/asciidoc/modules/ROOT/examples/generated-documentation";
    public static final String GENERATED_PARTIALS_DOCUMENTATION_DIR = "../docs/asciidoc/modules/ROOT/partials/generated-documentation";
    public static final String GENERATED_OVERVIEW_DIR = "../docs/asciidoc/modules/ROOT/pages/overview";
    @Rule
    public DbmsRule db = new ImpermanentDbmsRule()
            .withSetting(GraphDatabaseSettings.auth_enabled, true)
            .withSetting(GraphDatabaseSettings.procedure_unrestricted, Collections.singletonList("apoc.*"));

    @Before
    public void setUp() throws Exception {
        apocConfig().setProperty(APOC_UUID_ENABLED, true);

        Set<Class<?>> allClasses = allClasses();
        assertFalse(allClasses.isEmpty());

        for (Class<?> klass : allClasses) {
            if(!klass.getName().endsWith("Test")) {
                TestUtil.registerProcedure(db, klass);
            }
        }

        new File(GENERATED_DOCUMENTATION_DIR).mkdirs();
        new File(GENERATED_PARTIALS_DOCUMENTATION_DIR).mkdirs();
        new File(GENERATED_OVERVIEW_DIR).mkdirs();
    }

    static class Row {
        private String type;
        private String name;
        private String signature;
        private String description;

        public Row(String type, String name, String signature, String description) {
            this.type = type;
            this.name = name;
            this.signature = signature;
            this.description = description;
        }
    }

    class DocumentationGenerator {
        private final List<Row> rows;
        private Set<String> extended;
        private Map<String, String> docs;
        private final DependencyResolver resolver;

        DocumentationGenerator(Set<String> extended, Map<String, String> docs) {
            this.extended = extended;
            this.docs = docs;
            rows = new ArrayList<>();
            resolver = db.getDependencyResolver();


            List<Row> procedureRows = collectProcedures();
            List<Row> functionRows = collectFunctions();

            rows.addAll(procedureRows);
            rows.addAll(functionRows);
        }

        public void writeAllToCsv(Map<String, String> docs, Set<String> extended) {
            try (Writer writer = new OutputStreamWriter( new FileOutputStream( new File(GENERATED_DOCUMENTATION_DIR, "documentation.csv")), StandardCharsets.UTF_8 ))
            {
                writer.write("¦type¦qualified name¦signature¦description¦core¦documentation\n");
                for (Row row : rows) {

                    Optional<String> documentation = docs.keySet().stream()
                            .filter((key) -> Pattern.compile(key).matcher(row.name).matches())
                            .map(value -> String.format("xref::%s", docs.get(value)))
                            .findFirst();
                    String description = row.description.replaceAll("(\\{[a-zA-Z0-9_][a-zA-Z0-9_-]+})", "\\\\$1");
                    writer.write(String.format("¦%s¦%s¦%s¦%s¦%s¦%s\n",
                            row.type,
                            row.name,
                            row.signature,
                            description,
                            !extended.contains(row.name),
                            documentation.orElse("")));
                }

            }
            catch ( Exception e )
            {
                throw new RuntimeException( e.getMessage(), e );
            }
        }

        public void writeNamespaceCsvs() {
            Map<String, List<Row>> namespaces = rows.stream().collect(Collectors.groupingBy(value -> {
                String[] parts = value.name.split("\\.");
                parts = Arrays.copyOf(parts, parts.length - 1);
                return String.join(".", parts);
            }));


            for (Map.Entry<String, List<Row>> record : namespaces.entrySet()) {
                try (Writer writer = new OutputStreamWriter( new FileOutputStream( new File(GENERATED_DOCUMENTATION_DIR, String.format("%s.csv", record.getKey()))), StandardCharsets.UTF_8 ))
                {
                    writer.write("¦type¦qualified name¦signature¦description\n");
                    for (Row row : record.getValue()) {
                        String description = row.description.replaceAll("(\\{[a-zA-Z0-9_][a-zA-Z0-9_-]+})", "\\\\$1");
                        writer.write(String.format("¦%s¦%s¦%s¦%s\n", row.type, row.name, row.signature, description));
                    }

                }
                catch ( Exception e )
                {
                    throw new RuntimeException( e.getMessage(), e );
                }
            }

            for (Map.Entry<String, List<Row>> record : namespaces.entrySet()) {
                try (Writer writer = new OutputStreamWriter( new FileOutputStream( new File(GENERATED_DOCUMENTATION_DIR, String.format("%s-lite.csv", record.getKey()))), StandardCharsets.UTF_8 ))
                {
                    writer.write("¦signature\n");
                    for (Row row : record.getValue()) {
                        writer.write(String.format("¦%s\n", row.signature));
                    }

                }
                catch ( Exception e )
                {
                    throw new RuntimeException( e.getMessage(), e );
                }
            }

        }

        private void writeRowLight(Row row) {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(GENERATED_DOCUMENTATION_DIR, String.format("%s-lite.csv", row.name))), StandardCharsets.UTF_8)) {
                writer.write("¦signature\n");
                writer.write(String.format("¦%s\n", row.signature));
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        public void writeIndividualCsvs() {
            rows.forEach(this::writeRow);
            rows.forEach(this::writeRowLight);
        }

        public void writeNavAndOverview() {
            Map<String, List<Row>> topLevelNamespaces = topLevelNamespaces();

            try (Writer overviewWriter = new OutputStreamWriter(new FileOutputStream(new File(GENERATED_PARTIALS_DOCUMENTATION_DIR, "documentation.adoc")), StandardCharsets.UTF_8);
                 Writer navWriter = new OutputStreamWriter(new FileOutputStream(new File(GENERATED_PARTIALS_DOCUMENTATION_DIR, "nav.adoc")), StandardCharsets.UTF_8)) {
                writeAutoGeneratedHeader(overviewWriter);
                writeAutoGeneratedHeader(navWriter);

                topLevelNamespaces.keySet().stream().sorted().forEach(topLevelNamespace -> {
                    writeTopLevelNamespacePage(topLevelNamespaces, topLevelNamespace);

                    try {
                        if (topLevelNamespaces.get(topLevelNamespace).size() < 3) {
                            overviewWriter.write("[discrete]\n");
                        }

                        overviewWriter.write("== xref::overview/" + topLevelNamespace + "/index.adoc[]\n\n");
                        overviewWriter.write(header());

                        navWriter.write("** xref::overview/" + topLevelNamespace + "/index.adoc[]\n");
                        for (Row row : topLevelNamespaces.get(topLevelNamespace)) {
                            String releaseType = extended.contains(row.name) ? "full" : "core";
                            String description = row.description
                                    .replace("|", "\\|")
                                    .replaceAll("(\\{[a-zA-Z0-9_][a-zA-Z0-9_-]+})", "\\\\$1");
                            overviewWriter.write(String.format("|%s\n|%s\n|%s\n",
                                    String.format("%s[%s icon:book[]]\n\n%s", "xref::overview/" + topLevelNamespace + "/" + row.name + ".adoc", row.name, description),
                                    String.format("label:%s[]", row.type),
                                    String.format("label:apoc-%s[]", releaseType)));
                            navWriter.write("*** xref::overview/" + topLevelNamespace + "/" + row.name  + ".adoc[]\n");
                        }

                        overviewWriter.write(footer());


                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
            catch ( Exception e )
            {
                throw new RuntimeException( e.getMessage(), e );
            }
        }

        private void writeAutoGeneratedHeader(Writer overviewWriter) throws IOException {
            overviewWriter.write("////\nThis file is generated by DocsTest, so don't change it!\n////\n\n");
        }

        private void writeTopLevelNamespacePage(Map<String, List<Row>> topLevelNamespaces, String topLevelNamespace) {
            new File(GENERATED_OVERVIEW_DIR, topLevelNamespace).mkdirs();

            try (Writer sectionWriter = new OutputStreamWriter(new FileOutputStream(new File(new File(GENERATED_OVERVIEW_DIR, topLevelNamespace), "index.adoc")), StandardCharsets.UTF_8)) {
                writeAutoGeneratedHeader(sectionWriter);
                sectionWriter.write("= " + topLevelNamespace + "\n");
                sectionWriter.write(":description: This section contains reference documentation for the " + topLevelNamespace + " procedures.\n\n");

                sectionWriter.write(header());

                for (Row row : topLevelNamespaces.get(topLevelNamespace)) {
                    String releaseType = extended.contains(row.name) ? "full" : "core";
                    String description = row.description
                            .replace("|", "\\|")
                            .replaceAll("(\\{[a-zA-Z0-9_][a-zA-Z0-9_-]+})", "\\\\$1");
                    sectionWriter.write(String.format("|%s\n|%s\n|%s\n",
                            String.format("xref::%s[%s icon:book[]]\n\n%s", "overview/" + topLevelNamespace + "/" + row.name + ".adoc", row.name, description),
                            String.format("label:%s[]", row.type),
                            String.format("label:apoc-%s[]", releaseType)));
                }

                sectionWriter.write(footer());

            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        public void writeProcedurePages() {
            for (ProcedureSignature procedure : allProcedures()) {
                String[] parts = procedure.name().toString().split("\\.");
                String topLevelDirectory = String.format("%s.%s", parts[0], parts[1]);
                new File(GENERATED_OVERVIEW_DIR, topLevelDirectory).mkdirs();

                writeProcedurePage(procedure, topLevelDirectory);
            }
        }

        public void writeFunctionPages() {
            allFunctions().forEach(userFunctionSignature -> {
                String[] parts = userFunctionSignature.name().toString().split("\\.");
                String topLevelDirectory = String.format("%s.%s", parts[0], parts[1]);
                new File(GENERATED_OVERVIEW_DIR, topLevelDirectory).mkdirs();

                writeFunctionPage(userFunctionSignature, topLevelDirectory);
            });
        }

        private void writeFunctionPage(UserFunctionSignature userFunctionSignature, String topLevelDirectory) {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(new File(GENERATED_OVERVIEW_DIR, topLevelDirectory), userFunctionSignature.name().toString() + ".adoc")), StandardCharsets.UTF_8)) {
                writeIndividualPageHeader(writer, userFunctionSignature.name().toString(), "function");
                writeLabel(writer, userFunctionSignature.name(), "function");
                writeDescription(writer, userFunctionSignature.description());
                writeSignature(writer, userFunctionSignature.toString());
                writeInputParameters(writer, userFunctionSignature.inputSignature());
                writeExtraDocumentation(writer, userFunctionSignature.name());
            }
            catch ( Exception e )
            {
                throw new RuntimeException( e.getMessage(), e );
            }
        }

        private void writeProcedurePage(ProcedureSignature procedure, String topLevelDirectory) {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(new File(GENERATED_OVERVIEW_DIR, topLevelDirectory), procedure.name().toString() + ".adoc")), StandardCharsets.UTF_8)) {
                writeIndividualPageHeader(writer, procedure.name().toString(), "procedure");
                writeLabel(writer, procedure.name(), "procedure");
                writeDescription(writer, procedure.description());
                writeSignature(writer, procedure.toString());
                writeInputParameters(writer, procedure.inputSignature());
                writeOutputParameters(procedure, writer);
                writeExtraDocumentation(writer, procedure.name());

            }
            catch ( Exception e )
            {
                throw new RuntimeException( e.getMessage(), e );
            }
        }

        Map<String, List<Row>> topLevelNamespaces() {
            return rows.stream().filter(value -> value.name.split("\\.").length == 3).collect(Collectors.groupingBy(value -> {
                String[] parts = value.name.split("\\.");
                parts = Arrays.copyOf(parts, parts.length - 1);
                return String.join(".", parts);
            }));
        }



        private void writeRow(Row row) {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(GENERATED_DOCUMENTATION_DIR, String.format("%s.csv", row.name))), StandardCharsets.UTF_8)) {
                writer.write("¦type¦qualified name¦signature¦description\n");
                String description = row.description.replaceAll("(\\{[a-zA-Z0-9_][a-zA-Z0-9_-]+})", "\\\\$1");
                writer.write(String.format("¦%s¦%s¦%s¦%s\n", row.type, row.name, row.signature, description));
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        private void writeExtraDocumentation(Writer writer, QualifiedName name) throws IOException {
            Optional<String> documentation = findExtraDocumentations(name);
            if (documentation.isPresent()) {
                writer.write(documentation.get() + "[More documentation of " + name.toString() + ",role=more information]\n\n");
            }
        }

        @NotNull
        private String extractDescription(Optional<String> description) {
            return description
                    .orElse("")
                    .replaceAll("(\\{[a-zA-Z0-9_][a-zA-Z0-9_-]+})", "\\\\$1");
        }

        private void writeOutputParameters(ProcedureSignature procedure, Writer writer) throws IOException {
            if(procedure.outputSignature().size() > 0) {
                writeOutputParametersHeader(writer);

                for (FieldSignature fieldSignature : procedure.outputSignature()) {
                    writer.write(String.format("|%s|%s\n", fieldSignature.name(), fieldSignature.neo4jType().toString()));
                }
                writeParametersFooter(writer);
            }
        }

        private void writeInputParameters(Writer writer, List<FieldSignature> fieldSignatures) throws IOException {
            List<FieldSignature> x = fieldSignatures;
            if (x.size() > 0) {
                writeInputParametersHeader(writer);
                for (FieldSignature fieldSignature : x) {
                    writer.write(String.format("|%s|%s|%s\n", fieldSignature.name(), fieldSignature.neo4jType().toString(), fieldSignature.defaultValue().map(DefaultParameterValue::value).orElse(null)));
                }
                writeParametersFooter(writer);
            }
        }

        private void writeSignature(Writer writer, String name) throws IOException {
            writer.write("== Signature\n\n");
            writer.write("[source]\n----\n" + name + "\n----\n\n");
        }

        private void writeLabel(Writer writer, QualifiedName name, String type) throws IOException {
            String release = extended.contains(name.toString()) ? "full" : "core";
            writer.write("label:" + type + "[] label:apoc-" + release + "[]\n\n");
        }

        private void writeDescription(Writer writer, Optional<String> potentialDescription) throws IOException {
            String description = extractDescription(potentialDescription);
            if (!description.isBlank()) {
                writer.write("[.emphasis]\n" + description.trim() + "\n\n");
            }
        }

        private void writeIndividualPageHeader(Writer writer, String thing, String type) throws IOException {
            writeAutoGeneratedHeader(writer);
            writer.write("= " + thing + "\n");
            writer.write(":description: This section contains reference documentation for the " + thing + " " + type + ".\n\n");
        }

        private List<Row> collectProcedures() {
            return db.executeTransactionally("CALL dbms.procedures() YIELD signature, name, description WHERE name STARTS WITH 'apoc' RETURN 'procedure' AS type, name, description, signature ORDER BY signature", Collections.emptyMap(),
                    result -> result.stream().map(record -> new Row(
                            record.get("type").toString(),
                            record.get("name").toString(),
                            record.get("signature").toString(),
                            record.get("description").toString())
                    ).collect(Collectors.toList()));
        }

        private List<Row> collectFunctions() {
            return db.executeTransactionally("CALL dbms.functions() YIELD signature, name, description WHERE name STARTS WITH 'apoc' RETURN 'function' AS type, name, description, signature ORDER BY signature", Collections.emptyMap(),
                    result -> result.stream().map(record -> new Row(
                            record.get("type").toString(),
                            record.get("name").toString(),
                            record.get("signature").toString(),
                            record.get("description").toString())
                    ).collect(Collectors.toList()));
        }

        private void writeParametersFooter(Writer writer) throws IOException {
            writer.write("|===\n\n");
        }

        private void writeInputParametersHeader(Writer writer) throws IOException {
            writer.write("== Input parameters\n");
            writer.write("[.procedures, opts=header]\n" +
                    "|===\n" +
                    "| Name | Type | Default \n");
        }

        private void writeOutputParametersHeader(Writer writer) throws IOException {
            writer.write("== Output parameters\n");
            writer.write("[.procedures, opts=header]\n" +
                    "|===\n" +
                    "| Name | Type \n");
        }

        @NotNull
        private Optional<String> findExtraDocumentations(QualifiedName name) {
            return docs.keySet().stream()
                    .filter((key) -> Pattern.compile(key).matcher(name.toString()).matches())
                    .map(value -> String.format("xref::%s", docs.get(value)))
                    .findFirst();
        }

        @NotNull
        private Set<ProcedureSignature> allProcedures() {
            GlobalProcedures globalProcedures = resolver.resolveDependency(GlobalProcedures.class);
            Set<ProcedureSignature> allProcedures = globalProcedures.getAllProcedures().stream().filter(signature -> signature.name().toString().startsWith("apoc")).collect(Collectors.toSet());
            return allProcedures;
        }

        @NotNull
        private Stream<UserFunctionSignature> allFunctions() {
            Stream<UserFunctionSignature> loadedFunctions = resolver.resolveDependency( GlobalProcedures.class ).getAllNonAggregatingFunctions().filter(signature -> signature.name().toString().startsWith("apoc"));
            Stream<UserFunctionSignature> loadedAggregationFunctions = resolver.resolveDependency( GlobalProcedures.class ).getAllAggregatingFunctions().filter(signature -> signature.name().toString().startsWith("apoc"));
            return Stream.concat(loadedFunctions, loadedAggregationFunctions);
        }
    }

    @Test
    public void generateDocs() {
        Set<String> extended = readExtended();
        Map<String, String> docs = createDocsMapping();
        DocumentationGenerator documentationGenerator = new DocumentationGenerator(extended, docs);

        documentationGenerator.writeAllToCsv(docs, extended);
        documentationGenerator.writeNamespaceCsvs();
        documentationGenerator.writeIndividualCsvs();

        documentationGenerator.writeNavAndOverview();

        documentationGenerator.writeProcedurePages();
        documentationGenerator.writeFunctionPages();
    }

    private List<Row> collectProcedures() {
        return db.executeTransactionally("CALL dbms.procedures() YIELD signature, name, description WHERE name STARTS WITH 'apoc' RETURN 'procedure' AS type, name, description, signature ORDER BY signature", Collections.emptyMap(),
                result -> result.stream().map(record -> new Row(
                        record.get("type").toString(),
                        record.get("name").toString(),
                        record.get("signature").toString(),
                        record.get("description").toString())
                ).collect(Collectors.toList()));
    }

    private List<Row> collectFunctions() {
        return db.executeTransactionally("CALL dbms.functions() YIELD signature, name, description WHERE name STARTS WITH 'apoc' RETURN 'function' AS type, name, description, signature ORDER BY signature", Collections.emptyMap(),
                result -> result.stream().map(record -> new Row(
                        record.get("type").toString(),
                        record.get("name").toString(),
                        record.get("signature").toString(),
                        record.get("description").toString())
                ).collect(Collectors.toList()));
    }



    private void writeDocumentationCsv(Map<String, String> docs, List<Row> rows, Set<String> extended) {
        try (Writer writer = new OutputStreamWriter( new FileOutputStream( new File(GENERATED_DOCUMENTATION_DIR, "documentation.csv")), StandardCharsets.UTF_8 ))
        {
            writer.write("¦type¦qualified name¦signature¦description¦core¦documentation\n");
            for (Row row : rows) {

                Optional<String> documentation = docs.keySet().stream()
                        .filter((key) -> Pattern.compile(key).matcher(row.name).matches())
                        .map(value -> String.format("xref::%s", docs.get(value)))
                        .findFirst();
                String description = row.description.replaceAll("(\\{[a-zA-Z0-9_][a-zA-Z0-9_-]+})", "\\\\$1");
                writer.write(String.format("¦%s¦%s¦%s¦%s¦%s¦%s\n",
                        row.type,
                        row.name,
                        row.signature,
                        description,
                        !extended.contains(row.name),
                        documentation.orElse("")));
            }

        }
        catch ( Exception e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
    }

    @NotNull
    private Set<String> readExtended() {
        Set<String> extended = new HashSet<>();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("extended.txt")) {
            if (stream != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                String name;
                while ((name = reader.readLine()) != null) {
                    extended.add(name);
                }
            }
        } catch (IOException e) {
            // Failed to load extended file
        }
        return extended;
    }

    @NotNull
    private Map<String, String> createDocsMapping() {
        Map<String, String> docs = new HashMap<>();
        docs.put("apoc.path.expand", "graph-querying/expand-paths.adoc");
        docs.put("apoc.path.expandConfig", "graph-querying/expand-paths-config.adoc");
        docs.put("apoc.path.subgraphNodes", "graph-querying/expand-subgraph-nodes.adoc");
        docs.put("apoc.path.subgraphAll", "graph-querying/expand-subgraph.adoc");
        docs.put("apoc.export.cypher.*", "export/cypher.adoc");
        docs.put("apoc.export.json.*", "export/json.adoc");
        docs.put("apoc.export.csv.*", "export/csv.adoc");
        docs.put("apoc.export.graphml.*", "export/graphml.adoc");
        docs.put("apoc.graph.*", "export/gephi.adoc");
        docs.put("apoc.load.json.*|apoc.import.json", "import/load-json.adoc");
        docs.put("apoc.load.csv", "import/load-csv.adoc");
        docs.put("apoc.import.csv", "import/import-csv.adoc");
        docs.put("apoc.import.graphml", "import/graphml.adoc");
        docs.put("apoc.coll.*", "data-structures/collection-list-functions.adoc");
        docs.put("apoc.convert.*", "data-structures/conversion-functions.adoc");
        docs.put("apoc.map.*", "data-structures/map-functions.adoc");
        docs.put("apoc.create.v.*|apoc.create.virtual.*", "virtual/virtual-nodes-rels.adoc");
        docs.put("apoc.math.*|apoc.number.romanToArabic|apoc.number.arabicToRoman", "mathematical/math-functions.adoc");
        docs.put("apoc.meta.*", "database-introspection/meta.adoc");
        docs.put("apoc.nodes.*|apoc.node.*|apoc.any.properties|apoc.any.property|apoc.label.exists", "graph-querying/node-querying.adoc");
        docs.put("apoc.number.format.*|apoc.number.parseInt.*|apoc.number.parseFloat.*", "mathematical/number-conversions.adoc");
        docs.put("apoc.number.exact.*", "mathematical/exact-math-functions.adoc");
        docs.put("apoc.path.*", "graph-querying/path-querying.adoc");
        docs.put("apoc.text.*", "misc/text-functions.adoc");
        docs.put("apoc.util.md5|apoc.util.sha1", "misc/text-functions.adoc#text-functions-hashing");
        docs.put("apoc.mongodb.*", "database-integration/mongodb.adoc");
        docs.put("apoc.nlp.aws.*", "nlp/aws.adoc");
        docs.put("apoc.nlp.gcp.*", "nlp/gcp.adoc");
        docs.put("apoc.nlp.azure.*", "nlp/azure.adoc");
        docs.put("apoc.neighbors.*", "graph-querying/neighborhood.adoc");
        docs.put("apoc.monitor.*", "database-introspection/monitoring.adoc");
        docs.put("apoc.periodic.iterate", "graph-updates/periodic-execution.adoc#commit-batching");
        docs.put("apoc.periodic.commit", "graph-updates/periodic-execution.adoc#periodic-commit");
        docs.put("apoc.periodic.rock_n_roll", "graph-updates/periodic-execution.adoc#periodic-rock-n-roll");
        docs.put("apoc.refactor.clone.*", "graph-updates/graph-refactoring/clone-nodes.adoc");
        docs.put("apoc.refactor.cloneSubgraph.*", "graph-updates/graph-refactoring/clone-subgraph.adoc");
        docs.put("apoc.refactor.merge.*", "graph-updates/graph-refactoring/merge-nodes.adoc");
        docs.put("apoc.refactor.to|apoc.refactor.from", "graph-updates/graph-refactoring/redirect-relationship.adoc");
        docs.put("apoc.refactor.invert", "graph-updates/graph-refactoring/invert-relationship.adoc");
        docs.put("apoc.refactor.setType", "graph-updates/graph-refactoring/set-relationship-type.adoc");
        docs.put("apoc.static.*", "misc/static-values.adoc");
        docs.put("apoc.spatial.*", "misc/spatial.adoc");
        docs.put("apoc.schema.*", "indexes/schema-index-operations.adoc");
        docs.put("apoc.search.node.*", "graph-querying/parallel-node-search.adoc");
        docs.put("apoc.trigger.*", "background-operations/triggers.adoc");
        docs.put("apoc.ttl.*", "graph-updates/ttl.adoc");
        docs.put("apoc.create.uuid", "graph-updates/uuid.adoc");
        docs.put("apoc.cypher.*", "cypher-execution/index.adoc");
        docs.put("apoc.date.*", "temporal/datetime-conversions.adoc");
        docs.put("apoc.hashing.*", "comparing-graphs/fingerprinting.adoc");
        docs.put("apoc.temporal.*", "temporal/temporal-conversions.adoc");
        docs.put("apoc.uuid.*", "graph-updates/uuid.adoc");
        docs.put("apoc.systemdb.*", "database-introspection/systemdb.adoc");
        docs.put("apoc.periodic.submit|apoc.periodic.schedule|apoc.periodic.list|apoc.periodic.countdown", "background-operations/periodic-background.adoc");
        docs.put("apoc.model.jdbc", "database-integration/database-modeling.adoc");
        docs.put("apoc.algo.*", "algorithms/path-finding-procedures.adoc");
        docs.put("apoc.atomic.*", "graph-updates/atomic-updates.adoc");
        docs.put("apoc.bolt.*", "database-integration/bolt-neo4j.adoc");
        docs.put("apoc.case|apoc.do.case|apoc.when|apoc.do.when", "cypher-execution/conditionals.adoc");
        docs.put("apoc.es.*", "database-integration/elasticsearch.adoc");
        docs.put("apoc.refactor.rename.*", "graph-updates/graph-refactoring/rename-label-type-property.adoc");
        docs.put("apoc.couchbase.*", "database-integration/couchbase.adoc");
        docs.put("apoc.create.node.*|apoc.create.*Labels|apoc.create.setP.*|apoc.create.setRel.*|apoc.create.relationship|apoc.nodes.link|apoc.merge.*|apoc.create.remove.*", "graph-updates/data-creation.adoc");
        docs.put("apoc.custom.*", "cypher-execution/cypher-based-procedures-functions.adoc");
        docs.put("apoc.generate.*", "graph-updates/graph-generators.adoc");
        docs.put("apoc.config.*", "database-introspection/config.adoc");
        docs.put("apoc.load.jdbc.*", "database-integration/load-jdbc.adoc");
        docs.put("apoc.load.xml.*|apoc.import.xml|apoc.xml.parse", "import/xml.adoc");
        docs.put("apoc.lock.*", "graph-updates/locking.adoc");
        return docs;
    }

    @NotNull
    private String footer() {
        return "|===\n\n";
    }

    @NotNull
    private String header() {
        return "[.procedures, opts=header, cols='5a,1a,1a']\n" +
                "|===\n" +
                "| Qualified Name | Type | Release\n";
    }

    private Set<Class<?>> allClasses() {
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .forPackages("apoc")
                .setScanners(new SubTypesScanner(false), new TypeAnnotationsScanner())
                .filterInputsBy(input -> !input.endsWith("Test.class") && !input.endsWith("Result.class") && !input.contains("$"))
        );

        return reflections.getSubTypesOf(Object.class);
    }

}