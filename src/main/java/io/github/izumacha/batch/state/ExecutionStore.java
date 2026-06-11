package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;

import java.util.List;
import java.util.Optional;

/**
 * バッチ実行結果（{@link ExecutionResult}）を永続化・取得するインターフェース。
 * 実行履歴を後から参照できるようにするための契約（Port）を定義する。
 */
public interface ExecutionStore {

    /** 指定された実行結果を保存する（同じ runId が既にあれば上書きする） */
    // バッチ実行結果を永続ストレージに書き込むメソッドの宣言（実装は各サブクラスが提供する）
    void save(ExecutionResult result);

    /** 指定された runId の実行結果を返す（存在しない場合は空の Optional を返す） */
    // 特定の実行 ID に対応する結果を検索するメソッドの宣言
    Optional<ExecutionResult> findById(String runId);

    /** 保存されたすべての実行結果を最新順で返す */
    // すべての実行記録を取得するメソッドの宣言（最新順＝開始時刻の降順）
    List<ExecutionResult> findAll();
}
