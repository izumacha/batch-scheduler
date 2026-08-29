package io.github.izumacha.batch.state;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Collects the records a logger emits, and puts the logger back afterwards.
 *
 * <p>A successful {@code fsync} leaves no trace inside the JVM, so several
 * tests observe {@code Durability}'s and {@code JsonExecutionStore}'s
 * {@code FINE}/{@code WARNING} records instead of the syscall. Doing that by
 * hand means the same four-step dance every time -- lower the level, stop
 * propagation to the parent so expected warnings do not pollute the test
 * output, attach a handler, and restore all three afterwards. Written out
 * repeatedly it drifts: one copy saved and restored the level, another did
 * not and only passed because its assertion happened to target
 * {@code WARNING}, which clears the inherited {@code INFO} threshold.
 *
 * <p>Implements {@link AutoCloseable} so a test can scope it with
 * try-with-resources and cannot forget the restore (CLAUDE.md §6 DRY, §8
 * "リソースを確実に解放する").
 */
final class LogCapture implements AutoCloseable {

    // 記録を溜める対象のロガー（後片付けで元に戻すために保持する）
    private final Logger logger;
    // 取り付けたハンドラ（後片付けで取り外すために保持する）
    private final Handler handler;
    // 捕まえたログの一覧
    private final List<LogRecord> records = new ArrayList<>();
    // 差し替える前のログレベル
    private final Level originalLevel;
    // 差し替える前の親ロガーへの伝播設定
    private final boolean originalUseParentHandlers;

    private LogCapture(Logger logger) {
        // 対象のロガーを保持する
        this.logger = logger;
        // 元の設定を控えておく（close() で戻すため）
        this.originalLevel = logger.getLevel();
        this.originalUseParentHandlers = logger.getUseParentHandlers();
        // 受け取ったログをリストへ溜めるだけのハンドラを組み立てる
        this.handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                // 受け取ったログをそのまま積む
                records.add(record);
            }

            @Override
            public void flush() {
                // 溜め込むだけなので書き出す先は無い
            }

            @Override
            public void close() {
                // 解放すべき資源を持たない
            }
        };
        // FINE も含めてすべて受け取れるよう、ハンドラとロガーの両方のしきい値を下げる
        this.handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        // 親ロガーへ流さないことで、想定した警告がテスト出力を汚すのを防ぐ
        logger.setUseParentHandlers(false);
        // 組み立てたハンドラを取り付ける
        logger.addHandler(this.handler);
    }

    /** 指定したクラスのロガーを捕まえ始める。 */
    static LogCapture of(Class<?> type) {
        // クラス名のロガーを対象にする（本番と同じ取得の仕方）
        return new LogCapture(Logger.getLogger(type.getName()));
    }

    /** 捕まえたログをそのまま返す（記録順）。 */
    List<LogRecord> records() {
        return records;
    }

    /** 捕まえたログの本文だけを返す（記録順）。 */
    List<String> messages() {
        // 本文だけを取り出して返す
        return records.stream().map(LogRecord::getMessage).toList();
    }

    /** 溜めた記録を捨てる（判定用の呼び出しで出たログを期待値から外すときに使う）。 */
    void clear() {
        records.clear();
    }

    @Override
    public void close() {
        // 取り付けたハンドラを外し、控えておいた設定へ戻す
        logger.removeHandler(handler);
        logger.setLevel(originalLevel);
        logger.setUseParentHandlers(originalUseParentHandlers);
    }
}
