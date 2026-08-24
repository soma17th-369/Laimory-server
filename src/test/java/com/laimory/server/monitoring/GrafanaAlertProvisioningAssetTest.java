package com.laimory.server.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class GrafanaAlertProvisioningAssetTest {

    private static final Path MONITORING_DIR = Path.of("deploy", "monitoring");
    private static final Path ALERT_DIR =
            MONITORING_DIR.resolve(Path.of("grafana", "provisioning", "alerting"));
    private static final Path MANIFEST =
            MONITORING_DIR.resolve(Path.of("grafana", "alert-rule-files.txt"));

    @Test
    void manifestOwnsEveryRuleFileAndProvisioningYamlIsValid() throws IOException {
        List<String> expectedFiles = Files.readAllLines(MANIFEST).stream()
                .filter(line -> !line.isBlank())
                .toList();
        List<String> actualFiles;
        try (var paths = Files.list(ALERT_DIR)) {
            actualFiles = paths.filter(path -> path.getFileName().toString().endsWith("-rules.yml"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }

        assertThat(expectedFiles).isSorted().doesNotHaveDuplicates();
        assertThat(actualFiles).containsExactlyElementsOf(expectedFiles);
        assertThat(ALERT_DIR.resolve("rules.yml")).doesNotExist();
        assertThat(ALERT_DIR.resolve("operational-rules.yml")).doesNotExist();

        Set<String> groupNames = new HashSet<>();
        Set<String> ruleUids = new HashSet<>();
        Map<String, Map<String, Object>> rulesByUid = new HashMap<>();
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

        for (String file : expectedFiles) {
            Map<String, Object> root = loadMap(yaml, ALERT_DIR.resolve(file));
            assertThat(root.get("apiVersion")).isEqualTo(1);

            for (Map<String, Object> group : listOfMaps(root.get("groups"))) {
                assertThat(groupNames.add((String) group.get("name")))
                        .as("duplicate group in %s", file)
                        .isTrue();
                List<Map<String, Object>> rules = listOfMaps(group.get("rules"));
                assertThat(rules).as("rules in %s", file).isNotEmpty();

                for (Map<String, Object> rule : rules) {
                    String uid = (String) rule.get("uid");
                    assertThat(ruleUids.add(uid)).as("duplicate alert UID %s", uid).isTrue();
                    rulesByUid.put(uid, rule);
                }
            }
        }

        assertThat(groupNames).hasSize(9);
        assertThat(ruleUids).hasSize(26);
        assertMemoryRule(rulesByUid.get("laimory_host_memory_low"), "host!=\"elk\"", 0.15);
        assertMemoryRule(rulesByUid.get("laimory_elk_memory_low"), "host=\"elk\"", 0.1);
        assertApplicationErrorRule(rulesByUid.get("laimory_application_error_log"));
    }

    private static void assertMemoryRule(
            Map<String, Object> rule, String expectedSelector, double expectedThreshold) {
        assertThat(rule).isNotNull();
        assertThat(rule.get("for")).isEqualTo("10m");

        List<Map<String, Object>> data = listOfMaps(rule.get("data"));
        Map<String, Object> query = data.stream()
                .filter(item -> "A".equals(item.get("refId")))
                .findFirst()
                .orElseThrow();
        assertThat((String) map(query.get("model")).get("expr")).contains(expectedSelector);

        Map<String, Object> threshold = data.stream()
                .filter(item -> "C".equals(item.get("refId")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> condition =
                listOfMaps(map(threshold.get("model")).get("conditions")).getFirst();
        List<?> params = (List<?>) map(condition.get("evaluator")).get("params");
        assertThat(((Number) params.getFirst()).doubleValue()).isEqualTo(expectedThreshold);
    }

    private static void assertApplicationErrorRule(Map<String, Object> rule) {
        assertThat(rule).isNotNull();
        assertThat(rule.get("for")).isEqualTo("0s");
        assertThat(rule.get("noDataState")).isEqualTo("OK");
        assertThat(map(rule.get("labels")).get("severity")).isEqualTo("warning");
        assertThat(map(rule.get("labels"))).doesNotContainKey("environment");

        List<Map<String, Object>> data = listOfMaps(rule.get("data"));
        Map<String, Object> query = data.stream()
                .filter(item -> "A".equals(item.get("refId")))
                .findFirst()
                .orElseThrow();
        assertThat(query.get("datasourceUid")).isEqualTo("elasticsearch-dev");
        Map<String, Object> queryModel = map(query.get("model"));
        assertThat(queryModel.get("query"))
                .isEqualTo("service:laimory AND level:ERROR");
        assertThat(listOfMaps(queryModel.get("metrics")))
                .singleElement()
                .satisfies(metric -> assertThat(metric)
                        .containsEntry("id", "1")
                        .containsEntry("type", "count"));
        List<Map<String, Object>> bucketAggs = listOfMaps(queryModel.get("bucketAggs"));
        assertThat(bucketAggs).hasSize(2);
        assertThat(bucketAggs.getFirst())
                .containsEntry("field", "environment")
                .containsEntry("type", "terms");
        assertThat(bucketAggs.getLast())
                .containsEntry("field", "@timestamp")
                .containsEntry("id", "2")
                .containsEntry("type", "date_histogram");
        assertThat(map(bucketAggs.getLast().get("settings")))
                .containsEntry("interval", "1m")
                .containsEntry("min_doc_count", 0);

        Map<String, Object> reduce = data.stream()
                .filter(item -> "B".equals(item.get("refId")))
                .findFirst()
                .orElseThrow();
        assertThat(map(reduce.get("model")).get("reducer")).isEqualTo("sum");

        Map<String, Object> threshold = data.stream()
                .filter(item -> "C".equals(item.get("refId")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> condition =
                listOfMaps(map(threshold.get("model")).get("conditions")).getFirst();
        assertThat(map(condition.get("evaluator")))
                .containsEntry("type", "gt")
                .containsEntry("params", List.of(0));

        Map<String, Object> annotations = map(rule.get("annotations"));
        assertThat((String) annotations.get("runbook_url"))
                .startsWith("https://dev.laimory.app/kibana/app/discover#/")
                .contains("level%3A%22ERROR%22");
        assertThat((String) annotations.get("summary")).doesNotContain("{{");
        assertThat((String) annotations.get("description")).doesNotContain("{{");
    }

    private static Map<String, Object> loadMap(Yaml yaml, Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return map(yaml.load(reader));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            result.add(map(item));
        }
        return result;
    }
}
