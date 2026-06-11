package io.github.izumacha.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/**
 * Immutable definition of a batch: a named collection of {@link Job}s with
 * dependencies between them. The structural validity of those dependencies
 * (unique ids, all dependencies present, acyclic) is verified separately when
 * building a dependency graph from the batch.
 *
 * @param name human-friendly batch name; defaults to {@code "batch"} when blank
 * @param jobs the jobs in this batch, in declaration order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Batch(String name, List<Job> jobs) {

    public Batch {
        // name が空白なら "batch" をデフォルト名として使い、そうでなければ前後の空白を除去する
        name = (name == null || name.isBlank()) ? "batch" : name.trim();
        // jobs が null なら空リストに、そうでなければ変更不可のコピーにする
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }

    /** Looks up a job by id. */
    public Optional<Job> job(String id) {
        // id が null の場合は空の Optional を返す（ジョブが見つからないことを示す）
        if (id == null) {
            return Optional.empty();
        }
        // ジョブリストの中から id が一致する最初の要素を探して返す
        return jobs.stream().filter(j -> j.id().equals(id)).findFirst();
    }

    public boolean isEmpty() {
        // ジョブリストが空かどうかを返す
        return jobs.isEmpty();
    }
}
