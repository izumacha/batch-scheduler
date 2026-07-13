package io.github.izumacha.batch.core;

import io.github.izumacha.batch.config.ValidationException;
import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.Job;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyGraphTest {

    private static Job job(String id, List<String> dependsOn) {
        return new Job(id, null, List.of("sh", "-c", "exit 0"), dependsOn, 0, 0, Map.of(), null);
    }

    private static List<String> orderIds(DependencyGraph g) {
        return g.topologicalOrder().stream().map(Job::id).toList();
    }

    @Test
    void linearChainTopoOrderIsCorrect() {
        Batch batch = new Batch("linear", List.of(
                job("a", List.of()),
                job("b", List.of("a")),
                job("c", List.of("b"))));
        DependencyGraph g = DependencyGraph.build(batch);
        assertEquals(List.of("a", "b", "c"), orderIds(g));
    }

    @Test
    void veryLongChainDoesNotOverflowTheStack() {
        // A deep linear chain must be validated and ordered iteratively, without
        // a StackOverflowError (regression guard for recursive cycle detection).
        int n = 50_000;
        List<Job> jobs = new java.util.ArrayList<>(n);
        jobs.add(job("j0", List.of()));
        for (int i = 1; i < n; i++) {
            jobs.add(job("j" + i, List.of("j" + (i - 1))));
        }
        DependencyGraph g = DependencyGraph.build(new Batch("deep", jobs));
        List<String> order = orderIds(g);
        assertEquals(n, order.size());
        assertEquals("j0", order.get(0));
        assertEquals("j" + (n - 1), order.get(n - 1));
    }

    @Test
    void cycleDeepInALongChainIsStillDetected() {
        int n = 10_000;
        List<Job> jobs = new java.util.ArrayList<>(n);
        jobs.add(job("j0", List.of("j" + (n - 1)))); // closes the loop at the end
        for (int i = 1; i < n; i++) {
            jobs.add(job("j" + i, List.of("j" + (i - 1))));
        }
        ValidationException ex = assertThrows(ValidationException.class,
                () -> DependencyGraph.build(new Batch("deepcycle", jobs)));
        assertTrue(ex.errors().stream().anyMatch(e -> e.contains("cycle")), ex.getMessage());
    }

    @Test
    void diamondTopoOrderIsDeterministicByDeclaration() {
        // a -> b, a -> c, b&c -> d. b declared before c.
        Batch batch = new Batch("diamond", List.of(
                job("a", List.of()),
                job("b", List.of("a")),
                job("c", List.of("a")),
                job("d", List.of("b", "c"))));
        DependencyGraph g = DependencyGraph.build(batch);
        assertEquals(List.of("a", "b", "c", "d"), orderIds(g));
    }

    @Test
    void readyJobsBreakTiesByDeclarationOrder() {
        // Two independent roots; declaration order x then y must be preserved.
        Batch batch = new Batch("roots", List.of(
                job("x", List.of()),
                job("y", List.of())));
        DependencyGraph g = DependencyGraph.build(batch);
        assertEquals(List.of("x", "y"), orderIds(g));
    }

    @Test
    void dependenciesOfReturnsValidatedDeps() {
        Batch batch = new Batch("b", List.of(
                job("a", List.of()),
                job("b", List.of("a"))));
        DependencyGraph g = DependencyGraph.build(batch);
        assertEquals(java.util.Set.of("a"), g.dependenciesOf("b"));
        assertTrue(g.dependenciesOf("a").isEmpty());
    }

    @Test
    void dependencyIdsAreTrimmedToMatchTrimmedJobIds() {
        // Job は id を trim して正規化する。依存側の空白も trim されないと、
        // 前後に空白を含む依存指定が正規化済み ID と一致せず、自己整合なバッチが
        // unknown job として誤って弾かれる。両者が突き合うことを検証する。
        Batch batch = new Batch("ws", List.of(
                job(" a ", List.of()),
                job("b", List.of(" a "))));
        DependencyGraph g = DependencyGraph.build(batch);
        // 依存 ID が trim され、正規化後の "a" と一致していること
        assertEquals(java.util.Set.of("a"), g.dependenciesOf("b"));
    }

    @Test
    void emptyBatchIsRejected() {
        Batch batch = new Batch("empty", List.of());
        ValidationException ex = assertThrows(ValidationException.class,
                () -> DependencyGraph.build(batch));
        assertTrue(ex.errors().contains("batch contains no jobs"));
    }

    @Test
    void duplicateIdIsDetected() {
        Batch batch = new Batch("dup", List.of(
                job("a", List.of()),
                job("a", List.of())));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> DependencyGraph.build(batch));
        assertTrue(ex.errors().contains("duplicate job id: 'a'"));
    }

    @Test
    void emptyCommandIsDetected() {
        Job empty = new Job("a", null, List.of(), List.of(), 0, 0, Map.of(), null);
        Batch batch = new Batch("ec", List.of(empty));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> DependencyGraph.build(batch));
        assertTrue(ex.errors().contains("job 'a' has an empty command"));
    }

    @Test
    void blankCommandIsDetected() {
        // `command: [""]` のように要素はあるが先頭トークン（プログラム名）が空白のみの
        // ジョブは起動が絶対に成功しない。空リストのチェックだけでは素通りするため、
        // validate 時点でエラーとして検出されることを確認する
        Job blank = new Job("a", null, List.of("   "), List.of(), 0, 0, Map.of(), null);
        // 空白コマンドのジョブ 1 件だけを含むバッチを組み立てる
        Batch batch = new Batch("bc", List.of(blank));
        // 検証で ValidationException が投げられるはず
        ValidationException ex = assertThrows(ValidationException.class,
                () -> DependencyGraph.build(batch));
        // 空白コマンドを指すエラーメッセージが含まれていること
        assertTrue(ex.errors().contains("job 'a' has a blank command"), ex.errors().toString());
    }

    @Test
    void unknownDependencyIsDetected() {
        Batch batch = new Batch("missing", List.of(
                job("a", List.of("ghost"))));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> DependencyGraph.build(batch));
        assertTrue(ex.errors().contains("job 'a' depends on unknown job 'ghost'"));
    }

    @Test
    void selfDependencyIsDetected() {
        Batch batch = new Batch("self", List.of(
                job("a", List.of("a"))));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> DependencyGraph.build(batch));
        assertTrue(ex.errors().contains("job 'a' depends on itself"));
    }

    @Test
    void cycleIsDetectedWithPathMessage() {
        Batch batch = new Batch("cycle", List.of(
                job("a", List.of("c")),
                job("b", List.of("a")),
                job("c", List.of("b"))));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> DependencyGraph.build(batch));
        boolean hasCycle = ex.errors().stream()
                .anyMatch(e -> e.startsWith("dependency cycle detected:")
                        && e.contains("a")
                        && e.contains("b")
                        && e.contains("c"));
        assertTrue(hasCycle, "expected a cycle message, got: " + ex.errors());
    }

    @Test
    void multipleErrorsAreAggregated() {
        // duplicate id 'a', empty command on 'b', unknown dep on 'c', self-dep on 'd'.
        Job dupA1 = new Job("a", null, List.of("sh", "-c", "true"), List.of(), 0, 0, Map.of(), null);
        Job dupA2 = new Job("a", null, List.of("sh", "-c", "true"), List.of(), 0, 0, Map.of(), null);
        Job emptyB = new Job("b", null, List.of(), List.of(), 0, 0, Map.of(), null);
        Job cUnknown = new Job("c", null, List.of("sh", "-c", "true"), List.of("ghost"), 0, 0, Map.of(), null);
        Job dSelf = new Job("d", null, List.of("sh", "-c", "true"), List.of("d"), 0, 0, Map.of(), null);
        Batch batch = new Batch("multi", List.of(dupA1, dupA2, emptyB, cUnknown, dSelf));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> DependencyGraph.build(batch));
        List<String> errors = ex.errors();
        assertTrue(errors.contains("duplicate job id: 'a'"), errors.toString());
        assertTrue(errors.contains("job 'b' has an empty command"), errors.toString());
        assertTrue(errors.contains("job 'c' depends on unknown job 'ghost'"), errors.toString());
        assertTrue(errors.contains("job 'd' depends on itself"), errors.toString());
        assertTrue(errors.size() >= 4, "expected at least 4 aggregated errors: " + errors);
    }
}
