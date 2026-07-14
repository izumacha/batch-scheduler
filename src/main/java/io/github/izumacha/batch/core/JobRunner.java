package io.github.izumacha.batch.core;

import io.github.izumacha.batch.model.Job;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 単一の {@link Job} を外部プロセスとして実行し、リトライ・タイムアウト・
 * 出力キャプチャを管理するクラス。ジョブの失敗時に例外は投げず、
 * すべての結果（成功・非ゼロ終了・タイムアウト・起動失敗）を {@link JobResult} として返す。
 */
public final class JobRunner {

    // このクラス専用のロガー（java.util.logging を使用する）
    private static final Logger LOGGER = Logger.getLogger(JobRunner.class.getName());

    // キャプチャするプロセス出力の最大行数（デフォルト値）
    private static final int DEFAULT_MAX_CAPTURED_OUTPUT_LINES = 50;
    // 読み取りバッファサイズ（4KiB）。マジックナンバー 4096 を定数化して意図を明示する
    private static final int READ_BUFFER_CHARS = 4 * 1024;
    // リトライ前に待機する時間（デフォルト1秒）
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(1);
    // プロセス終了後に出力リーダースレッドが終了するのを待つ最大時間
    private static final Duration READER_JOIN_TIMEOUT = Duration.ofSeconds(5);
    // タイムアウト時に子孫プロセスを掃引（列挙して強制終了）する最大回数。
    // fork し続けるジョブが相手でも killTree が無限ループしないための上限。
    private static final int MAX_KILL_SWEEPS = 10;
    // 親プロセスを一時停止する kill コマンド自体の完了を待つ上限時間
    private static final Duration KILL_COMMAND_TIMEOUT = Duration.ofSeconds(1);

    // Attempt.message が「プレーンなメッセージ」か「キャプチャした出力の末尾」かを
    // 区別するための内部プレフィックス。Attempt 生成側と summarize の剥がし側で
    // 同一文字列を共有するため、1 箇所に定数化する（綴り違いで出力末尾が黙って消える事故を防ぐ）。
    private static final String OUTPUT_PREFIX = "OUTPUT:";

    // キャプチャする出力の最大行数（コンストラクタで設定する）
    private final int maxCapturedOutputLines;
    // リトライ間隔（コンストラクタで設定する）
    private final Duration retryBackoff;
    // true にすると出力を標準出力にも表示する
    private final boolean echoOutput;

    public JobRunner() {
        // デフォルト設定でインスタンスを生成する
        this(DEFAULT_MAX_CAPTURED_OUTPUT_LINES, DEFAULT_RETRY_BACKOFF, false);
    }

    public JobRunner(int maxCapturedOutputLines, Duration retryBackoff, boolean echoOutput) {
        // 最大行数が負の値の場合は例外を投げる（0は出力キャプチャなしを意味するので許可）
        if (maxCapturedOutputLines < 0) {
            throw new IllegalArgumentException("maxCapturedOutputLines must be >= 0");
        }
        // 最大行数フィールドを設定する
        this.maxCapturedOutputLines = maxCapturedOutputLines;
        // リトライ間隔が null の場合はゼロ（待機なし）にする
        this.retryBackoff = retryBackoff == null ? Duration.ZERO : retryBackoff;
        // 出力エコーフラグを設定する
        this.echoOutput = echoOutput;
    }

    /**
     * ジョブを実行し、終了コード 0 になるまで最大 {@link Job#maxAttempts()} 回リトライする。
     * ジョブが失敗しても例外は投げず、常に終端 {@link JobResult} を返す。
     */
    public JobResult run(Job job) {
        // ジョブ全体の開始時刻を記録する
        Instant startedAt = Instant.now();
        // 実際に試行した回数を追跡する変数を初期化する
        int attempts = 0;
        // 最後に取得した終了コードを「未取得」の番兵値で初期化する
        int lastExitCode = JobResult.NO_EXIT_CODE;
        // 最後の試行のメッセージを初期化する
        String lastMessage = "did not run";
        // 成功フラグを false で初期化する
        boolean succeeded = false;
        // タイムアウトフラグを false で初期化する
        boolean lastTimedOut = false;

        // ジョブの最大試行回数を取得する
        int maxAttempts = job.maxAttempts();
        // 最大試行回数までループして試行する
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // スレッドが割り込まれていたら、これ以上リトライせずにループを抜ける。
            // 割り込み(interrupt)はグレースフルシャットダウンや Future.cancel(true) などの
            // 「もう中断してよい」という合図。バックオフ(リトライ間隔)が 0 のときは下の
            // Thread.sleep が呼ばれず割り込みを検知できないため、ここで毎回確認しないと
            // 割り込み後も残り試行分だけ子プロセスを起動＆即中断する空回りが続いてしまう。
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            // 現在の試行回数をフィールドに記録する
            attempts = attempt;
            // 1回分の試行を実行して結果を取得する
            Attempt result = runOnce(job);
            // 最後の終了コードを更新する
            lastExitCode = result.exitCode;
            // 最後のメッセージを更新する
            lastMessage = result.message;
            // タイムアウトフラグを更新する
            lastTimedOut = result.timedOut;

            // 終了コードが0でタイムアウトでも起動失敗でもない場合は成功とする
            if (result.exitCode == 0 && !result.timedOut && !result.failedToStart) {
                // 成功フラグを立ててループを抜ける
                succeeded = true;
                break;
            }

            // 次の試行が残っており、バックオフが設定されている場合は待機する
            if (attempt < maxAttempts && !retryBackoff.isZero() && !retryBackoff.isNegative()) {
                try {
                    // 次のリトライまで指定の時間だけスリープする
                    Thread.sleep(retryBackoff.toMillis());
                } catch (InterruptedException e) {
                    // スリープが割り込まれた場合は割り込みフラグを復元してループを抜ける
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // ジョブ全体の終了時刻を記録する
        Instant finishedAt = Instant.now();
        // 成功か失敗かに応じてジョブのステータスを決定する
        JobStatus status = succeeded ? JobStatus.SUCCEEDED : JobStatus.FAILED;
        // 人間が読みやすいサマリーメッセージを組み立てる
        String message = summarize(succeeded, lastExitCode, attempts, lastTimedOut, lastMessage, job);

        // 最終的な JobResult を生成して返す
        return new JobResult(
                job.id(),
                status,
                lastExitCode,
                attempts,
                startedAt,
                finishedAt,
                message
        );
    }

    private Attempt runOnce(Job job) {
        // ジョブのコマンドリストを使って ProcessBuilder を生成する
        ProcessBuilder pb = new ProcessBuilder(job.command());
        // 標準エラーを標準出力にマージする（まとめて一つのストリームとして読める）
        pb.redirectErrorStream(true);
        // 作業ディレクトリが指定されている場合はそれを設定する
        if (job.workingDir() != null) {
            pb.directory(new java.io.File(job.workingDir()));
        }
        // 起動したプロセスを格納する変数を宣言する
        Process process;
        try {
            // ジョブに環境変数が設定されている場合はプロセスの環境に追加する
            Map<String, String> env = job.env();
            if (!env.isEmpty()) {
                // プロセスビルダーの環境マップに一括で追加する
                pb.environment().putAll(env);
            }
            // プロセスを起動する
            process = pb.start();
        } catch (IllegalArgumentException e) {
            // 環境変数のキーや値が無効な場合（例: '=' を含むキー）は起動失敗として返す
            return Attempt.failedToStart("failed to start: invalid environment (" + e.getMessage() + ")");
        } catch (IOException e) {
            // コマンドが見つからない・実行権限がないなどの IO エラーは起動失敗として返す
            return Attempt.failedToStart("failed to start: " + e.getMessage());
        }

        // JobRunner はジョブへの標準入力供給に対応していないため、起動直後に子プロセスの
        // 標準入力（親から見た書き込み側パイプ）を閉じて EOF を通知する。閉じないままだと、
        // 標準入力を読もうとするコマンド（`read`・`cat`・対話的なプロンプト等）が入力を
        // 待って無期限にブロックし、タイムアウト未設定のジョブはバッチ全体を止めてしまう。
        // 閉じずに放置するとプロセスごとにパイプのファイルディスクリプタも消費し続ける
        // （§8 リソースを確実に解放する）。
        try {
            // 子プロセスの標準入力（親から見た書き込み側パイプ）を閉じて EOF を通知する
            process.getOutputStream().close();
        } catch (IOException e) {
            // クローズ失敗はジョブの成否に影響しないため警告ログのみ残して処理を続行する
            LOGGER.warning("failed to close stdin for job '" + job.id() + "': " + e.getMessage());
        }

        // パイプバッファが満杯になってプロセスがブロックしないよう、
        // 別スレッドで出力を読み続ける OutputCollector を起動する
        OutputCollector collector = new OutputCollector(process, maxCapturedOutputLines, echoOutput);
        // デーモンスレッドとして起動する（JVM 終了時に自動で終了させるため）
        Thread reader = new Thread(collector, "jobrunner-output-" + job.id());
        reader.setDaemon(true);
        // スレッドを開始して出力の読み込みを始める
        reader.start();

        try {
            // タイムアウトが設定されている場合は指定時間だけ待機する
            if (job.hasTimeout()) {
                // プロセスが指定秒以内に終了したかどうかを確認する
                boolean finished = process.waitFor(job.timeoutSeconds(), TimeUnit.SECONDS);
                if (!finished) {
                    // タイムアウトした場合はプロセスツリーを強制終了する
                    killTree(process);
                    // プロセスが終了するまで短時間だけ待機する（孤立した子孫プロセス対策）
                    // waitFor の戻り値: true=タイムアウト前に終了、false=まだ終了していない
                    boolean cleanedUp = process.waitFor(READER_JOIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    // 短時間待ちでも終了しなかった場合はログに残す（孤立プロセスの可能性）
                    if (!cleanedUp) {
                        LOGGER.warning("Process did not exit within cleanup window after kill for job: " + job.id());
                    }
                    // リーダースレッドが残りの出力を読み終えるまで待機する。
                    // プロセスは上の waitFor で終了確認済みなのでパイプは閉じており通常は即座に返るが、
                    // 負荷の高い環境で末尾のバッファ排出が遅れても出力が欠けないよう、
                    // 成功パスと同じ READER_JOIN_TIMEOUT を上限にする（短すぎると末尾の出力が切れる）。
                    joinQuietly(reader, READER_JOIN_TIMEOUT);
                    // タイムアウト結果を返す
                    return Attempt.timedOut(
                            "timed out after " + job.timeoutSeconds() + "s",
                            collector.tail());
                }
            } else {
                // タイムアウトなしの場合はプロセスが終了するまで無制限に待機する
                process.waitFor();
            }
        } catch (InterruptedException e) {
            // 待機中に割り込まれた場合は割り込みフラグを復元してプロセスを強制終了する
            Thread.currentThread().interrupt();
            killTree(process);
            // リーダースレッドの終了を待つ
            joinQuietly(reader, READER_JOIN_TIMEOUT);
            // 割り込みによる起動失敗として返す
            return Attempt.failedToStart("interrupted while waiting for process");
        }

        // リーダースレッドがすべての出力を読み終えるまで待機する
        joinQuietly(reader, READER_JOIN_TIMEOUT);
        // プロセスの終了コードを取得する
        int exitCode = process.exitValue();
        // 正常終了した試行結果を返す（出力の末尾も含める）
        return Attempt.completed(exitCode, collector.tail());
    }

    /**
     * プロセスとその子孫プロセスをすべて強制終了する。
     *
     * <p>順序が重要: 子孫の列挙(descendants())は親が生きている間しか使えない
     * （親が exit すると子は孤児化して親子関係を辿れなくなる）。さらに fork し
     * 続けるジョブでは、列挙と kill の間に生まれた子が取り残される。そこで
     * まず親へ SIGSTOP を送って「新たな fork」と「親の exit」の両方を凍結し、
     * 子孫一覧が安定した状態で列挙・強制終了してから、最後に親を強制終了する。
     * SIGSTOP を送れない環境（kill コマンドが無い等）では凍結なしの
     * ベストエフォート掃引にフォールバックする。
     */
    private static void killTree(Process process) {
        // 親を一時停止して fork の発生源と exit の両方を凍結する（ベストエフォート）
        trySignalStop(process.pid());
        // 子孫を列挙して強制終了する。fork し続ける子孫が居ても必ず有限で
        // 打ち切れるよう、列挙が空になるか上限回数に達するまで掃引を繰り返す
        for (int sweep = 0; sweep < MAX_KILL_SWEEPS; sweep++) {
            // 現時点で親にぶら下がっている子孫プロセスの一覧を取得する
            List<ProcessHandle> descendants = process.descendants().toList();
            // 子孫がいなければ掃引完了
            if (descendants.isEmpty()) {
                break;
            }
            // 見つかった子孫をすべて強制終了する
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
        // 最後に親プロセス自身を強制終了する（SIGKILL は SIGSTOP 中でも有効）
        process.destroyForcibly();
    }

    /**
     * 指定 PID へ SIGSTOP を送って一時停止を試みる（POSIX の kill コマンド経由）。
     * Java 標準 API には停止シグナルを送る手段が無いため外部コマンドに委ねる。
     * 送れなくても killTree はフォールバックで動くため、失敗は致命的ではない。
     */
    private static void trySignalStop(long pid) {
        try {
            // kill コマンドを引数配列で起動する（シェルを介さないため引数の解釈事故がない）
            Process kill = new ProcessBuilder("kill", "-STOP", Long.toString(pid)).start();
            // kill コマンド自体がハングしないよう短い上限付きで終了を待つ
            if (!kill.waitFor(KILL_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                // 万一終わらなければ kill コマンドを強制終了して先へ進む
                kill.destroyForcibly();
            }
        } catch (IOException e) {
            // kill コマンドが存在しない環境（例: Windows）では凍結をスキップして
            // フォールバック掃引に任せる。silent にせず FINE レベルで痕跡を残す
            LOGGER.fine("SIGSTOP unavailable, falling back to best-effort kill sweep: " + e);
        } catch (InterruptedException e) {
            // 待機中に割り込まれた場合は割り込みフラグを復元して先へ進む
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread t, Duration timeout) {
        try {
            // スレッドが終了するまで指定時間だけ待機する
            t.join(timeout.toMillis());
        } catch (InterruptedException e) {
            // 割り込まれた場合は割り込みフラグを復元して続行する
            Thread.currentThread().interrupt();
        }
    }

    private static String summarize(boolean succeeded,
                                    int exitCode,
                                    int attempts,
                                    boolean timedOut,
                                    String lastMessage,
                                    Job job) {
        // サマリーを組み立てる StringBuilder を作成する
        StringBuilder sb = new StringBuilder();
        if (succeeded) {
            // 成功した場合は終了コード 0 を表示する
            sb.append("exit 0");
            // 複数回試行した場合は試行回数も表示する
            if (attempts > 1) {
                sb.append(" after ").append(attempts).append(" attempts");
            }
        } else if (timedOut) {
            // タイムアウトした場合はタイムアウト情報を表示する
            sb.append("timed out after ").append(job.timeoutSeconds()).append("s");
            // 複数回試行した場合は試行回数も表示する
            if (attempts > 1) {
                sb.append(" (").append(attempts).append(" attempts)");
            }
        } else if (exitCode == JobResult.NO_EXIT_CODE) {
            // 終了コードが取得できない場合（起動失敗など）は最後のメッセージを表示する
            sb.append(lastMessage);
            // 複数回試行した場合は試行回数も表示する
            if (attempts > 1) {
                sb.append(" (").append(attempts).append(" attempts)");
            }
        } else {
            // 通常の失敗の場合は終了コードを表示する
            sb.append("exit ").append(exitCode);
            // 複数回試行した場合は試行回数も表示する
            if (attempts > 1) {
                sb.append(" after ").append(attempts).append(" attempts");
            }
        }

        // 最後のメッセージが出力キャプチャの場合は先頭行を追記してコンテキストを補足する
        String tail = lastMessage != null && lastMessage.startsWith(OUTPUT_PREFIX)
                ? lastMessage.substring(OUTPUT_PREFIX.length())
                : null;
        // 失敗時かつ出力がある場合は最初の行をサマリーに付加する
        if (!succeeded && tail != null && !tail.isBlank()) {
            sb.append(": ").append(firstLine(tail));
        }
        // 完成したサマリー文字列を返す
        return sb.toString();
    }

    private static String firstLine(String s) {
        // 最初の改行文字の位置を探す
        int nl = s.indexOf('\n');
        // 改行がない場合はトリムして返し、ある場合は最初の行だけ返す
        return nl < 0 ? s.trim() : s.substring(0, nl).trim();
    }

    /** 1回のプロセス試行の結果を表すイミュータブルなレコード */
    private record Attempt(int exitCode, boolean timedOut, boolean failedToStart, String message) {
        // 正常完了した試行の結果を生成するファクトリメソッド
        static Attempt completed(int exitCode, String tail) {
            // 出力がある場合は OUTPUT_PREFIX を付けてメッセージにする
            String msg = tail == null || tail.isBlank() ? null : OUTPUT_PREFIX + tail;
            // タイムアウトなし・起動失敗なしの試行結果を返す
            return new Attempt(exitCode, false, false, msg);
        }

        // タイムアウトした試行の結果を生成するファクトリメソッド
        static Attempt timedOut(String message, String tail) {
            // 出力がある場合は出力を、ない場合はタイムアウトメッセージをセットする
            String msg = tail == null || tail.isBlank() ? message : OUTPUT_PREFIX + tail;
            // 終了コードは番兵値、タイムアウトフラグを true にして返す
            return new Attempt(JobResult.NO_EXIT_CODE, true, false, msg);
        }

        // プロセスの起動に失敗した試行の結果を生成するファクトリメソッド
        static Attempt failedToStart(String message) {
            // 終了コードは番兵値、起動失敗フラグを true にして返す
            return new Attempt(JobResult.NO_EXIT_CODE, false, true, message);
        }
    }

    /**
     * プロセスの標準出力（標準エラーも統合済み）を別スレッドで読み込み、
     * 最後の N 行だけを保持するリングバッファ方式の内部クラス
     */
    private static final class OutputCollector implements Runnable {
        // この内部クラス専用のロガー（出力読み込み中の異常を記録するため）
        private static final Logger COLLECTOR_LOGGER = Logger.getLogger(OutputCollector.class.getName());
        // 1行の最大文字数（これを超える行は切り詰める）
        private static final int MAX_LINE_CHARS = 8 * 1024;
        // 行が切り詰められたことを示すマーカー文字列
        private static final String TRUNCATION_MARK = "…[truncated]";

        // 出力を読み込む対象のプロセス
        private final Process process;
        // 保持する最大行数（リングバッファのサイズ）
        private final int maxLines;
        // true の場合は標準出力にも出力をエコーする
        private final boolean echo;
        // キャプチャした行を格納する両端キュー（最大 maxLines 行のリングバッファ）
        private final Deque<String> lines = new ArrayDeque<>();

        OutputCollector(Process process, int maxLines, boolean echo) {
            // プロセスを保持する
            this.process = process;
            // 最大行数を保持する
            this.maxLines = maxLines;
            // エコーフラグを保持する
            this.echo = echo;
        }

        @Override
        public void run() {
            // BufferedReader.readLine() を使わずに手動で行を区切る。
            // これにより、暴走プロセスが超長い行を出力してもメモリを食い尽くさないよう
            // 1行の文字数を MAX_LINE_CHARS で上限を設ける。
            // ただしパイプはすべてドレインするのでプロセスがブロックすることはない。
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                // READ_BUFFER_CHARS 文字分の読み込みバッファを用意する（4KiB）
                char[] buf = new char[READ_BUFFER_CHARS];
                // 現在構築中の行を格納する StringBuilder を作成する
                StringBuilder line = new StringBuilder();
                // 現在の行が切り詰められたかどうかのフラグ
                boolean lineTruncated = false;
                // 読み込んだ文字数を格納する変数
                int n;
                // ストリームの終端まで読み続ける
                while ((n = br.read(buf)) != -1) {
                    // 読み込んだバッファの各文字を処理する
                    for (int i = 0; i < n; i++) {
                        // 現在の文字を取り出す
                        char c = buf[i];
                        if (c == '\n') {
                            // 改行文字の場合は行を確定してバッファに追加する
                            emit(line.toString());
                            // 行バッファをリセットする
                            line.setLength(0);
                            // 切り詰めフラグをリセットする
                            lineTruncated = false;
                        } else if (c != '\r' && !lineTruncated) {
                            // CR 以外の文字で、まだ切り詰めていない場合は行に追加する
                            line.append(c);
                            // 行の文字数が上限に達した場合は切り詰めマーカーを付ける
                            if (line.length() >= MAX_LINE_CHARS) {
                                line.append(TRUNCATION_MARK);
                                // 以降の文字は無視する（パイプはドレインし続ける）
                                lineTruncated = true;
                            }
                        }
                    }
                }
                // ストリーム終端に改行のない最後の行を出力する
                if (line.length() > 0) {
                    emit(line.toString());
                }
            } catch (IOException e) {
                // ストリームが閉じられた場合（プロセスが強制終了されたなど）は警告をログに残す
                // 正常な kill シナリオでは "Stream closed" が多いが、予期しない IO エラーの場合もあるため記録する
                COLLECTOR_LOGGER.warning("Output stream read interrupted for process: " + e.getMessage());
            }
        }

        private void emit(String line) {
            // エコーが有効な場合は標準出力にも表示する
            if (echo) {
                System.out.println(line);
            }
            // 最大行数が 0 より大きい場合のみリングバッファに格納する
            if (maxLines > 0) {
                // 複数スレッドからの同時アクセスを防ぐために synchronized で保護する
                synchronized (lines) {
                    // 行をリングバッファの末尾に追加する
                    lines.addLast(line);
                    // 上限を超えた場合は古い行（先頭）を削除してリングバッファを維持する
                    while (lines.size() > maxLines) {
                        lines.removeFirst();
                    }
                }
            }
        }

        String tail() {
            // 複数スレッドからの同時アクセスを防ぐために synchronized で保護する
            synchronized (lines) {
                // 格納している行をリストにコピーして改行でつなぎ文字列として返す
                return String.join("\n", new ArrayList<>(lines));
            }
        }
    }
}
