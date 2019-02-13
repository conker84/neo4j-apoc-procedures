package apoc.export.cypher;

import apoc.util.TestUtil;
import apoc.util.Util;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Session;
import org.testcontainers.containers.Neo4jContainer;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;

import static apoc.export.cypher.ExportCypherTest.ExportCypherResults.*;
import static apoc.util.MapUtil.map;
import static apoc.util.TestContainerUtil.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeNotNull;

/**
 * @author as
 * @since 13.02.19
 */
//@Ignore
public class ExportCypherEnterpriseFeaturesTest {

    private static File directory = new File("target/import");

    static { //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
    }

    private static Neo4jContainer neo4jContainer;
    private static Session session;
    private static Driver driver;

    private static String PREFIX = "/";

    @BeforeClass
    public static void beforeAll() {
        TestUtil.ignoreException(() -> {
            neo4jContainer = createEnterpriseDB(true);
            neo4jContainer.start();
        }, Exception.class);
        assumeNotNull(neo4jContainer);
        driver = GraphDatabase.driver(neo4jContainer.getBoltUrl(), AuthTokens.none());
        session = driver.session();
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT ON (t:Person) ASSERT (t.name, t.surname) IS NODE KEY;");
            tx.success();
            return null;
        });
        session.writeTransaction(tx -> {
            tx.run("CREATE (a:Person {name: 'John', surname: 'Snow'}) " +
                    "CREATE (b:Person {name: 'Matt', surname: 'Jackson'}) " +
                    "CREATE (c:Person {name: 'Jenny', surname: 'White'}) " +
                    "CREATE (d:Person {name: 'Susan', surname: 'Brown'}) " +
                    "CREATE (e:Person {name: 'Tom', surname: 'Taylor'})" +
                    "CREATE (a)-[:KNOWS]->(b);");
            tx.success();
            return null;
        });
    }

    @AfterClass
    public static void afterAll() {
        if (neo4jContainer != null) {
            session.close();
            driver.close();
            neo4jContainer.close();
        }
        cleanBuild();
    }

    @Test
    public void testExportWithCompoundConstraintCypherShell() throws Exception {
        String fileName = "testCypherShellWithCompoundConstraint.cypher";
        File output = getFile(fileName);
        testCall(session, "CALL apoc.export.cypher.all({file},{config});\n",
                map("file", "testCypherShellWithCompoundConstraint.cypher", "config", Util.map("format", "cypher-shell")), (r) -> {
                });
        assertEquals(EXPECTED_CYPHER_SHELL_WITH_COMPOUND_CONSTRAINT, readFile(output));
    }

    public File getFile(String fileName) {
        File output = new File(directory, fileName);
        try {
            output.createNewFile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        output.setWritable(true);
        output.setExecutable(true);
        output.setReadable(true);
        return output;
    }

    @Test
    public void testExportWithCompoundConstraintPlain() throws Exception {
        File output = getFile("testPlainFormatWithCompoundConstraint.cypher");
        testCall(session, "CALL apoc.export.cypher.all({file},{config});\n",
                map("file", PREFIX + output.getPath().replace("target/", ""), "config", Util.map("format", "plain")), (r) -> {
                });
        assertEquals(EXPECTED_PLAIN_FORMAT_WITH_COMPOUND_CONSTRAINT, readFile(output));
    }

    @Test
    public void testExportWithCompoundConstraintNeo4jShell() throws Exception {
        File output = getFile("testNeo4jShellWithCompoundConstraint.cypher");
        testCall(session, "CALL apoc.export.cypher.all({file},{config});\n",
                map("file", PREFIX + output.getPath().replace("target/", ""), "config", Util.map("format", "neo4j-shell")), (r) -> {
                });
        assertEquals(EXPECTED_NEO4J_SHELL_WITH_COMPOUND_CONSTRAINT, readFile(output));
    }

    private static String readFile(File output) throws FileNotFoundException {
        return new Scanner(output).useDelimiter("\\Z").next() + String.format("%n");
    }

}