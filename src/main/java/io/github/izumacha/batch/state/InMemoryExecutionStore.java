package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ExecutionStore} backed by a thread-safe map keyed by runId.
 * State lives only for the lifetime of the JVM; useful for tests and ephemeral
 * runs.
 */
public final class InMemoryExecutionStore implements ExecutionStore {

    // runId をキーとしてスレッドセーフな ConcurrentHashMap で実行結果をメモリ上に保持する
    private final Map<String, ExecutionResult> byRunId = new ConcurrentHashMap<>();

    @Override
    public void save(ExecutionResult result) {
        // result が null の場合は例外を投げる（null を保存しようとするのは誤り）
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        // runId が null の場合も例外を投げる（キーが null ではマップに格納できない）
        if (result.runId() == null) {
            throw new IllegalArgumentException("result.runId must not be null");
        }
        // runId をキーにして実行結果をマップに格納する（同じ runId があれば上書きする）
        byRunId.put(result.runId(), result);
    }

    @Override
    public Optional<ExecutionResult> findById(String runId) {
        // runId が null の場合は空の Optional を返す（見つからないことを示す）
        if (runId == null) {
            return Optional.empty();
        }
        // マップから runId に対応する実行結果を取得し、Optional でラップして返す
        return Optional.ofNullable(byRunId.get(runId));
    }

    @Override
    public List<ExecutionResult> findAll() {
        // マップの全値を新しいリストにコピーする（変更可能なリストとして作成する）
        List<ExecutionResult> all = new ArrayList<>(byRunId.values());
        // 開始日時の降順（最新順）でリストを並べ替える
        all.sort(ExecutionResults.BY_STARTED_AT_DESC);
        // 並べ替え済みのリストを返す
        return all;
    }
}
