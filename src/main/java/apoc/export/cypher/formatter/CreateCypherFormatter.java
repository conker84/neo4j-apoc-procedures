package apoc.export.cypher.formatter;

import apoc.export.util.ExportConfig;
import apoc.export.util.Reporter;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;

import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * @author AgileLARUS
 *
 * @since 16-06-2017
 */
public class CreateCypherFormatter extends AbstractCypherFormatter implements CypherFormatter {

    @Override
    public String statementForNode(Node node, Map<String, Set<String>> uniqueConstraints, Set<String> indexedProperties, Set<String> indexNames) {
        StringBuilder result = new StringBuilder(100);
        result.append("CREATE (");
        String labels = CypherFormatterUtils.formatAllLabels(node, uniqueConstraints, indexNames);
        if (!labels.isEmpty()) {
            result.append(labels);
        }
        if (node.getPropertyKeys().iterator().hasNext()) {
            result.append(" {");
            result.append(CypherFormatterUtils.formatNodeProperties("", node, uniqueConstraints, indexNames, true));
            result.append("}");
        }
        result.append(");");
        return result.toString();
    }

    @Override
    public String statementForRelationship(Relationship relationship,  Map<String, Set<String>> uniqueConstraints, Set<String> indexedProperties) {
        StringBuilder result = new StringBuilder(100);
        result.append("MATCH ");
        result.append(CypherFormatterUtils.formatNodeLookup("n1", relationship.getStartNode(), uniqueConstraints, indexedProperties));
        result.append(", ");
        result.append(CypherFormatterUtils.formatNodeLookup("n2", relationship.getEndNode(), uniqueConstraints, indexedProperties));
        result.append(" CREATE (n1)-[r:" + CypherFormatterUtils.quote(relationship.getType().name()));
        if (relationship.getPropertyKeys().iterator().hasNext()) {
            result.append(" {");
            result.append(CypherFormatterUtils.formatRelationshipProperties("", relationship, true));
            result.append("}");
        }
        result.append("]->(n2);");
        return result.toString();
    }

    @Override
    public void statementForNodes(Iterable<Node> nodes, Map<String, Set<String>> uniqueConstraints, Set<String> indexNames, ExportConfig exportConfig, PrintWriter out, Reporter reporter) {
        int batchSize = exportConfig.getBatchSize();
        ExportConfig.OptimizationType exportType = exportConfig.getOptimizationType();

        AtomicInteger nodeCount = new AtomicInteger(0);
        Function<Node, Map.Entry<String, Set<String>>> keyMapper = (node) -> {
            String key = CypherFormatterUtils.formatAllLabels(node, uniqueConstraints, indexNames);
            return new AbstractMap.SimpleImmutableEntry<>(key, CypherFormatterUtils.getNodeIdProperties(node, uniqueConstraints).keySet());
        };
        Map<Map.Entry<String, Set<String>>, List<Node>> nodeMap = StreamSupport.stream(nodes.spliterator(), false)
                .collect(Collectors.groupingBy(keyMapper));

        AtomicInteger propertiesCount = new AtomicInteger(0);
        AtomicInteger batchCount = new AtomicInteger(0);

        int unwindBatchSize = exportConfig.getUnwindBatchSize();
        nodeMap.forEach((key, nodeList) -> {
            int nodeListSize = nodeList.size();
            nodeCount.addAndGet(nodeListSize);
            for (int i = 0; i < nodeListSize; i++) {
                switch (exportType){
                    case UNWIND_BATCH_PARAMS:
                        if (batchCount.get() % batchSize == 0) {
                            out.append(":param rows => [");
                        }
                        break;
                    default:
                        if (batchCount.get() % unwindBatchSize == 0) {
                            if (batchCount.get() % batchSize == 0) {
                                out.append(exportConfig.getFormat().begin());
                            }
                            out.append("UNWIND [");
                        }
                        break;
                }
                batchCount.incrementAndGet();

                Node node = nodeList.get(i);

                Map<String, Object> props = node.getAllProperties();
                // start element
                out.append("{");

                // id
                Map<String, Object> idMap = CypherFormatterUtils.getNodeIdProperties(node, uniqueConstraints);
                out.append(idMap.entrySet().stream()
                        .map(e -> String.format("`%s`: %s", e.getKey(), CypherFormatterUtils.toString(e.getValue()))).collect(Collectors.joining(",")));

                // properties
                out.append(", ");
                out.append("properties: ");
                out.append("{");
                if (!props.isEmpty()) {
                    String properties = props.entrySet().stream()
                            .filter(es -> !idMap.containsKey(es.getKey()))
                            .map(es -> CypherFormatterUtils.formatPropertyName("", es.getKey(), es.getValue(), true))
                            .collect(Collectors.joining(", "));
                    out.append(properties);
                    propertiesCount.addAndGet(props.size());
                }
                out.append("}");

                // end element
                out.append("}");
                boolean isEnd = i == nodeListSize - 1;
                if (isEnd || batchCount.get() % unwindBatchSize == 0 || batchCount.get() % batchSize == 0) {
                    int unwindLength = (i + 1) % unwindBatchSize;
                    int unwindLeftOver = (unwindBatchSize - unwindLength) % unwindBatchSize;
                    batchCount.addAndGet(unwindLeftOver);
                    switch (exportType){
                        case UNWIND_BATCH_PARAMS:
                            if(isEnd || batchCount.get() % batchSize == 0) {
                                out.append("]");
                                out.append(StringUtils.LF);
                                out.append(exportConfig.getFormat().begin());
                                out.append("UNWIND $rows AS row");
                            }
                            else{
                                out.append(", ");
                            }
                            break;
                        default:
                            out.append("] as row ");
                            break;
                    }
                    if(isEnd || batchCount.get() % batchSize == 0 || exportType != ExportConfig.OptimizationType.UNWIND_BATCH_PARAMS) {
                        out.append(StringUtils.LF);
                        out.append("MERGE ");
                        out.append(String.format("(n%s{", key.getKey()));
                        out.append(key.getValue().stream().map(s -> String.format("`%s`: row.`%s`", s, s)).collect(Collectors.joining(",")));
                        out.append("}) SET n += row.properties;");
                        out.append(StringUtils.LF);
                        if(exportType == ExportConfig.OptimizationType.UNWIND_BATCH_PARAMS || (batchCount.get() % batchSize == 0)){
                            out.append(exportConfig.getFormat().commit());
                        }
                    }

                } else {
                    out.append(", ");
                }
            }
        });
        if (batchCount.get() % batchSize != 0) {
            out.append(exportConfig.getFormat().commit());
        }

        reporter.update(nodeCount.get(), 0, propertiesCount.longValue());
    }

    @Override
    public void statementForRelationships(Iterable<Relationship> relationship, Map<String, Set<String>> uniqueConstraints, Set<String> indexNames, ExportConfig exportConfig, PrintWriter out, Reporter reporter) {
        int batchSize = exportConfig.getBatchSize();
        ExportConfig.OptimizationType exportType = exportConfig.getOptimizationType();

        AtomicInteger relCount = new AtomicInteger(0);


        Function<Relationship, Map<String, Object>> keyMapper = (rel) -> {
            Node start = rel.getStartNode();
            String startLabels = CypherFormatterUtils.formatAllLabels(start, uniqueConstraints, indexNames);

            // define the end labels
            Node end = rel.getEndNode();
            String endLabels = CypherFormatterUtils.formatAllLabels(end, uniqueConstraints, indexNames);

            // define the type
            String type = rel.getType().name();

            // create the path
            Map<String, Object> key = new HashMap<>();
            key.put("start", new AbstractMap.SimpleImmutableEntry<>(startLabels, CypherFormatterUtils.getNodeIdProperties(start, uniqueConstraints).keySet()));
            key.put("type", type);
            key.put("end", new AbstractMap.SimpleImmutableEntry<>(endLabels, CypherFormatterUtils.getNodeIdProperties(end, uniqueConstraints).keySet()));
            return key;
        };
        Map<Map<String, Object>, List<Relationship>> concurrentMap = StreamSupport.stream(relationship.spliterator(), false)
                .collect(Collectors.groupingBy(keyMapper));

        AtomicInteger propertiesCount = new AtomicInteger(0);
        AtomicInteger batchCount = new AtomicInteger(0);

        int unwindBatchSize = exportConfig.getUnwindBatchSize();

        concurrentMap.forEach((path, relationshipList) -> {
            int relSize = relationshipList.size();
            relCount.addAndGet(relSize);
            for (int i = 0; i < relSize; i++) {
                switch (exportType){
                    case UNWIND_BATCH_PARAMS:
                        if (batchCount.get() % batchSize == 0) {
                            out.append(":param rows => [");
                        }
                        break;
                    default:
                        if (batchCount.get() % unwindBatchSize == 0) {
                            if (batchCount.get() % batchSize == 0) {
                                out.append(exportConfig.getFormat().begin());
                            }
                            out.append("UNWIND [");
                        }
                        break;
                }
                batchCount.incrementAndGet();
                Relationship rel = relationshipList.get(i);

                Map<String, Object> props = rel.getAllProperties();
                // start element
                out.append("{");

                // start node
                out.append("start: ");
                out.append("{");
                out.append(CypherFormatterUtils.getNodeIdProperties(rel.getStartNode(), uniqueConstraints).entrySet().stream()
                        .map(e -> String.format("`%s`: %s", e.getKey(), CypherFormatterUtils.toString(e.getValue()))).collect(Collectors.joining(",")));
                out.append("}");

                out.append(", ");

                // end node
                out.append("end: ");
                out.append("{");

                out.append(CypherFormatterUtils.getNodeIdProperties(rel.getEndNode(), uniqueConstraints).entrySet().stream()
                        .map(e -> String.format("`%s`: %s", e.getKey(), CypherFormatterUtils.toString(e.getValue()))).collect(Collectors.joining(",")));
                out.append("}");

                // properties
                out.append(", ");
                out.append("properties: ");
                out.append("{");
                if (!props.isEmpty()) {
                    out.append(CypherFormatterUtils.formatProperties("", props, true).substring(2));
                    propertiesCount.addAndGet(props.size());
                }
                out.append("}");

                // end element
                out.append("}");

                boolean isEnd = i == relSize - 1;
                if (isEnd || batchCount.get() % unwindBatchSize == 0) {
                    int unwindLength = (i + 1) % unwindBatchSize;
                    int unwindLeftOver = (unwindBatchSize - unwindLength) % unwindBatchSize;
                    batchCount.addAndGet(unwindLeftOver);
                    switch (exportType){
                        case UNWIND_BATCH_PARAMS:
                            if(isEnd || batchCount.get() % batchSize == 0) {
                                out.append("]");
                                out.append(StringUtils.LF);
                                out.append(exportConfig.getFormat().begin());
                                out.append("UNWIND $rows AS row");
                            }
                            else{
                                out.append(", ");
                            }
                            break;
                        default:
                            out.append("] as row ");
                            break;
                    }
                    if(isEnd || batchCount.get() % batchSize == 0 || exportType != ExportConfig.OptimizationType.UNWIND_BATCH_PARAMS) {
                        out.append(StringUtils.LF);
                        out.append("MATCH ");

                        // match start node
                        Map.Entry<String, Set<String>> startEntry = (Map.Entry<String, Set<String>>) path.get("start");
                        out.append(String.format("(start%s{", startEntry.getKey()));
                        out.append(startEntry.getValue().stream().map(s -> String.format("`%s`: row.start.`%s`", s, s)).collect(Collectors.joining(",")));
                        out.append("})");

                        out.append(StringUtils.LF);
                        out.append("MATCH ");

                        // match end node
                        Map.Entry<String, Set<String>> endEntry = (Map.Entry<String, Set<String>>) path.get("end");
                        out.append(String.format("(end%s{", endEntry.getKey()));
                        out.append(endEntry.getValue().stream().map(s -> String.format("`%s`: row.end.`%s`", s, s)).collect(Collectors.joining(",")));
                        out.append("})");

                        out.append(StringUtils.LF);

                        // merge relationship
                        out.append(String.format("MERGE (start)-[r:`%s`]->(end) SET r += row.properties;", path.get("type")));
                        out.append(StringUtils.LF);
                        if(exportType == ExportConfig.OptimizationType.UNWIND_BATCH_PARAMS || (batchCount.get() % batchSize == 0)){
                            out.append(exportConfig.getFormat().commit());
                        }
                    }
                } else {
                    out.append(", ");
                }
            }
        });
        if (batchCount.get() % batchSize != 0) {
            out.append(exportConfig.getFormat().commit());
        }

        reporter.update(0, relCount.get(), propertiesCount.longValue());
    }
}