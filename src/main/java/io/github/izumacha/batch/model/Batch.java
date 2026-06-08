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
        name = (name == null || name.isBlank()) ? "batch" : name.trim();
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }

    /** Looks up a job by id. */
    public Optional<Job> job(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return jobs.stream().filter(j -> j.id().equals(id)).findFirst();
    }

    public boolean isEmpty() {
        return jobs.isEmpty();
    }
}
