package io.github.izumacha.batch.core;

import io.github.izumacha.batch.config.ValidationException;
import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.Job;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * {@link Batch} 内のジョブとその依存関係を検証済み・非循環の形で表すクラス。
 * {@link #build(Batch)} で構築し、すべての構造上の問題を一度に検出して
 * {@link ValidationException} として報告する。構築後のクエリは常に成功する。
 */
public final class DependencyGraph {

    // バッチ定義本体を保持するフィールド
    private final Batch batch;
    /** jobId → 検証済みの依存IDセット（宣言順を保持）。 */
    // ジョブ ID をキー、そのジョブが依存するジョブ ID のセットを値とするマップ
    private final Map<String, Set<String>> dependencies;
    /** jobId → 宣言インデックス（同順位のタイブレークに使用）。 */
    // ジョブ ID をキー、そのジョブがバッチ内で何番目に宣言されたかを値とするマップ
    private final Map<String, Integer> declarationIndex;
    /** jobId → Job 本体（{@link #topologicalOrder()} での O(1) 参照用）。 */
    // ジョブ ID をキー、Job 本体を値とするマップ。Batch#job(String) は毎回リストを
    // 線形走査するため、これを使わず事前にマップ化しておくことでトポロジカル順の
    // 組み立てを O(n) に保つ（大量ジョブでの O(n^2) 劣化を防ぐ）。
    private final Map<String, Job> jobsById;

    // プライベートコンストラクタ：buildメソッド経由でのみインスタンスを生成する
    private DependencyGraph(Batch batch,
                            Map<String, Set<String>> dependencies,
                            Map<String, Integer> declarationIndex,
                            Map<String, Job> jobsById) {
        // バッチ定義を格納する
        this.batch = batch;
        // 依存関係マップを格納する
        this.dependencies = dependencies;
        // 宣言順インデックスマップを格納する
        this.declarationIndex = declarationIndex;
        // ジョブ ID → Job 本体のマップを格納する
        this.jobsById = jobsById;
    }

    /**
     * 指定したバッチを検証して依存グラフを構築する。すべての構造上の問題を収集し、
     * 1 つでも見つかった場合は {@link ValidationException} をスローする。
     */
    public static DependencyGraph build(Batch batch) {
        // batch が null の場合はすぐに例外を投げる
        if (batch == null) {
            throw new ValidationException(List.of("batch is null"));
        }

        // 検証エラーを蓄積するリストを作成する
        List<String> errors = new ArrayList<>();
        // バッチ内のジョブ一覧を取得する
        List<Job> jobs = batch.jobs();

        // ジョブが 1 件もない場合はエラーを記録してすぐに例外を投げる
        if (jobs.isEmpty()) {
            errors.add("batch contains no jobs");
            throw new ValidationException(errors);
        }

        // 重複ID の検出と、最初に出現した宣言順インデックスの記録
        // LinkedHashMap を使って宣言順を保持する
        Map<String, Integer> declarationIndex = new LinkedHashMap<>();
        // 重複エラーを 1 度だけ記録するための追跡セット
        Set<String> reportedDuplicates = new HashSet<>();
        // ジョブを宣言順に走査して重複 ID を検出する
        for (int i = 0; i < jobs.size(); i++) {
            // 現在のインデックス i にあるジョブの ID を取得する
            String id = jobs.get(i).id();
            // 同じ ID がすでに存在するか確認する
            if (declarationIndex.containsKey(id)) {
                // まだ報告していなければエラーに追加する（同一 ID の重複報告を防ぐ）
                if (reportedDuplicates.add(id)) {
                    errors.add("duplicate job id: '" + id + "'");
                }
            } else {
                // 初めて出現した ID は宣言インデックスに登録する
                declarationIndex.put(id, i);
            }
        }

        // ジョブごとの検証：空コマンド・存在しない依存・自己依存
        // LinkedHashMap を使って宣言順に依存関係マップを構築する
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        // ジョブ ID → Job 本体のマップも同じ 1 パスで組み立てる（topologicalOrder の O(1) 参照用）
        Map<String, Job> jobsById = new LinkedHashMap<>();
        // 各ジョブを走査して依存関係を検証する
        for (Job job : jobs) {
            // 現在のジョブの ID を取得する
            String id = job.id();
            // 重複 ID は最初の宣言のみを対象にする
            if (dependencies.containsKey(id)) {
                continue;
            }
            // コマンドが空のジョブはエラーとして記録する
            if (job.command().isEmpty()) {
                errors.add("job '" + id + "' has an empty command");
            }
            // このジョブの検証済み依存セットを作成する（宣言順を保持）
            Set<String> deps = new LinkedHashSet<>();
            // 各依存 ID を検証する
            for (String dep : job.dependsOn()) {
                // 自分自身への依存はエラーとして記録し、このエントリをスキップする
                if (dep.equals(id)) {
                    errors.add("job '" + id + "' depends on itself");
                    continue;
                }
                // 宣言されていない ID への依存はエラーとして記録し、スキップする
                if (!declarationIndex.containsKey(dep)) {
                    errors.add("job '" + id + "' depends on unknown job '" + dep + "'");
                    continue;
                }
                // 検証済みの依存 ID をセットに追加する
                deps.add(dep);
            }
            // このジョブの依存セットをマップに登録する
            dependencies.put(id, deps);
            // このジョブ本体を ID 引きできるように登録する（最初の宣言のみ、重複時は上と同様スキップ済み）
            jobsById.put(id, job);
        }

        // 循環検出は辺が有効であることが確認されてから行う
        // （自己依存・不明な依存を除外した dependencies マップ上で実行する）
        detectCycles(declarationIndex, dependencies, errors);

        // エラーが 1 件以上あれば ValidationException をスローする
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        // 検証済みのグラフインスタンスを返す
        return new DependencyGraph(batch, dependencies, declarationIndex, jobsById);
    }

    // DFS ノードの訪問状態を表す名前付き定数（int で管理してスタック使用量を最小化する）
    /** ノードがまだ DFS で訪問されていない状態 */
    private static final int NODE_STATE_UNVISITED = 0;
    /** ノードが現在の DFS パス上にある（処理中）状態 */
    private static final int NODE_STATE_IN_PROGRESS = 1;
    /** ノードの DFS が完了した状態 */
    private static final int NODE_STATE_DONE = 2;

    // 繰り返し DFS（深さ優先探索）で循環を検出するプライベートメソッド
    private static void detectCycles(Map<String, Integer> declarationIndex,
                                     Map<String, Set<String>> dependencies,
                                     List<String> errors) {
        // NODE_STATE_UNVISITED / IN_PROGRESS / DONE の 3 状態でノードを管理する
        // スタックオーバーフローを防ぐために再帰ではなく明示的なスタックを使う
        Map<String, Integer> state = new HashMap<>();
        // 同じ循環を複数回報告しないための追跡セット
        Set<String> reportedCycles = new HashSet<>();
        // 決定的な循環報告のために宣言順で走査する
        for (String start : declarationIndex.keySet()) {
            // すでに処理済みのノードはスキップする（UNVISITED 以外）
            if (state.getOrDefault(start, NODE_STATE_UNVISITED) != NODE_STATE_UNVISITED) {
                continue;
            }
            // DFS のスタック（各要素は現在ノードの依存イテレータ）
            Deque<Iterator<String>> iterators = new ArrayDeque<>();
            // 現在探索中のパス（先頭が最も深いノード）
            Deque<String> path = new ArrayDeque<>();

            // 開始ノードを「処理中」に設定してスタックに積む
            state.put(start, NODE_STATE_IN_PROGRESS);
            path.push(start);
            // 開始ノードの依存イテレータをスタックに積む
            iterators.push(dependencies.getOrDefault(start, Set.of()).iterator());

            // スタックが空になるまで DFS を継続する
            while (!iterators.isEmpty()) {
                // 現在処理中のイテレータを取得する（pop はしない）
                Iterator<String> it = iterators.peek();
                // 次の依存ノードがある場合
                if (it.hasNext()) {
                    // 次の依存 ID を取得する
                    String dep = it.next();
                    // その依存ノードの状態を取得する（未記録なら UNVISITED）
                    int depState = state.getOrDefault(dep, NODE_STATE_UNVISITED);
                    // 未訪問なら処理中に設定してスタックに積む
                    if (depState == NODE_STATE_UNVISITED) {
                        state.put(dep, NODE_STATE_IN_PROGRESS);
                        path.push(dep);
                        iterators.push(dependencies.getOrDefault(dep, Set.of()).iterator());
                    } else if (depState == NODE_STATE_IN_PROGRESS) {
                        // 後退辺（バックエッジ）: dep は現在のパス上にあるので循環を報告する
                        reportCycle(dep, path, reportedCycles, errors);
                    }
                    // NODE_STATE_DONE は処理完了済みのため何もしない
                } else {
                    // このノードの依存をすべて処理し終えたのでスタックから取り出す
                    iterators.pop();
                    // パスからも取り出して「完了」状態にする
                    state.put(path.pop(), NODE_STATE_DONE);
                }
            }
        }
    }

    /** 現在の DFS パスから dep への後退辺で閉じる循環を errors に記録する。 */
    private static void reportCycle(String dep,
                                    Deque<String> path,
                                    Set<String> reportedCycles,
                                    List<String> errors) {
        // path は先頭が最も深いノード: [current, ..., dep, ...]
        // current から dep までを収集してから逆順にして dep..current とし、最後に dep を追加して閉じる
        List<String> collected = new ArrayList<>();
        // path の先頭（最も深いノード）から dep まで収集する
        for (String node : path) {
            collected.add(node);
            // dep に到達したら収集を終了する
            if (node.equals(dep)) {
                break;
            }
        }
        // 循環パスのリストを作成する（サイズ +1 は閉じるための dep の分）
        List<String> cycle = new ArrayList<>(collected.size() + 1);
        // 収集リストを逆順にして循環を時系列順にする
        for (int i = collected.size() - 1; i >= 0; i--) {
            cycle.add(collected.get(i));
        }
        // 循環を閉じるために dep を再度追加する
        cycle.add(dep);
        // 正規化キーが未報告であればエラーとして記録する
        if (reportedCycles.add(canonicalCycleKey(cycle))) {
            errors.add("dependency cycle detected: " + String.join(" -> ", cycle));
        }
    }

    /** 回転に依存しないキーを生成して同じ循環を 1 度だけ報告するようにする。 */
    private static String canonicalCycleKey(List<String> path) {
        // 末尾の重複（閉じるための dep）を除いたノードリストを作成する
        List<String> nodes = new ArrayList<>(path.subList(0, path.size() - 1));
        // ノードが空ならそのまま空文字を返す
        if (nodes.isEmpty()) {
            return "";
        }
        // 辞書順で最も小さい ID のインデックスを探す
        int minIdx = 0;
        for (int i = 1; i < nodes.size(); i++) {
            // 現在の最小より小さければ最小インデックスを更新する
            if (nodes.get(i).compareTo(nodes.get(minIdx)) < 0) {
                minIdx = i;
            }
        }
        // 最小 ID を先頭にした回転済みリストを作成する
        List<String> rotated = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            // minIdx を基点に循環インデックスで要素を並べる
            rotated.add(nodes.get((minIdx + i) % nodes.size()));
        }
        // 回転済みリストを "->" で結合してキーとして返す
        return String.join("->", rotated);
    }

    /**
     * カーン法（Kahn's algorithm）による決定的なトポロジカル順を返す。
     * 同時に実行可能なジョブが複数ある場合は宣言順に並べる。
     */
    public List<Job> topologicalOrder() {
        // 各ジョブの入次数（依存されているジョブ数）を計算するマップ
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        // 各ジョブに依存するジョブのリスト（ジョブ x が完了したら通知するジョブたち）
        // dependents.get(x) = x に依存するジョブのリスト
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        // 全ジョブの入次数を 0 に初期化し、dependents の空リストも用意する
        for (String id : declarationIndex.keySet()) {
            inDegree.put(id, 0);
            dependents.put(id, new ArrayList<>());
        }
        // 依存関係マップを走査して入次数と dependents を計算する
        for (Map.Entry<String, Set<String>> e : dependencies.entrySet()) {
            // このジョブ（e.getKey()）の依存先（e.getValue()）ごとに処理する
            String id = e.getKey();
            for (String dep : e.getValue()) {
                // id の入次数を 1 増やす（dep の完了を待っているため）
                inDegree.merge(id, 1, Integer::sum);
                // dep が完了したときに通知するリストに id を追加する
                dependents.get(dep).add(id);
            }
        }

        // 宣言順で優先度付きキューを使って「実行可能なジョブ」を管理する
        // 優先度は宣言インデックスで決定し、小さいほど優先される
        PriorityQueue<String> ready =
                new PriorityQueue<>(Comparator.comparingInt(declarationIndex::get));
        // 入次数が 0 のジョブ（依存がない、または依存がすべて完了）を最初に追加する
        for (String id : declarationIndex.keySet()) {
            if (inDegree.get(id) == 0) {
                ready.add(id);
            }
        }

        // トポロジカル順に並べたジョブリストを作成する
        List<Job> order = new ArrayList<>();
        // 実行可能なジョブがなくなるまでループする
        while (!ready.isEmpty()) {
            // 最も優先度の高い（宣言順が最も早い）ジョブを取り出す
            String id = ready.poll();
            // ID に対応する Job 本体を取得する（jobsById での O(1) 参照。
            // batch.job(id) はリストを毎回線形走査するため大量ジョブで O(n^2) になり使わない）
            Job job = jobsById.get(id);
            // jobsById は declarationIndex と同じ「宣言順で最初に出現した ID」の集合を
            // キーに持つはずなので本来 null にはならないが、内部不変条件が壊れた場合に
            // null を order へ静かに混入させると呼び出し側で原因不明な
            // NullPointerException になるため、ここで明確なエラーとして検出する
            if (job == null) {
                throw new IllegalStateException(
                        "internal error: no Job registered for id '" + id + "' in jobsById");
            }
            // そのジョブを実行順リストに追加する
            order.add(job);
            // このジョブが完了したことで入次数が 0 になる依存ジョブをキューに追加する
            for (String dependent : dependents.get(id)) {
                // 入次数を 1 減らし、0 になったら実行可能キューに追加する
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }

        // グラフは検証済みで非循環のため、常にすべてのジョブが処理されるはず
        if (order.size() != declarationIndex.size()) {
            throw new IllegalStateException("topological sort failed on a validated graph");
        }
        // トポロジカル順に並べたジョブリストを返す
        return order;
    }

    /** 指定したジョブの検証済み依存 ID セットを返す。 */
    public Set<String> dependenciesOf(String jobId) {
        // 依存マップからジョブの依存セットを取得する
        Set<String> deps = dependencies.get(jobId);
        // jobId が存在しない場合は例外を投げる
        if (deps == null) {
            throw new IllegalArgumentException("unknown job id: '" + jobId + "'");
        }
        // 変更不可なコピーとして返す（外部からの変更を防ぐ）
        return Set.copyOf(deps);
    }

    /** このグラフの元となったバッチを返す。 */
    public Batch batch() {
        // バッチ定義をそのまま返す
        return batch;
    }
}
