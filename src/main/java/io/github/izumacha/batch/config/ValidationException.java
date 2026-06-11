package io.github.izumacha.batch.config;

import java.util.List;

/**
 * Thrown when a batch is structurally invalid (e.g. duplicate job ids, a
 * missing dependency, an empty command, or a dependency cycle). Carries the
 * full list of problems so the caller can report them all at once.
 */
public class ValidationException extends RuntimeException {

    // 検出されたすべてのバリデーションエラーを保持するフィールド
    private final List<String> errors;

    public ValidationException(List<String> errors) {
        // エラーリストからユーザー向けのメッセージを組み立てて親クラスに渡す
        super(buildMessage(errors));
        // イミュータブルなコピーを作成してフィールドに保存する（外から変更されないようにする）
        this.errors = List.copyOf(errors);
    }

    /** The individual validation problems, in detection order. */
    public List<String> errors() {
        // 保持しているエラーリストをそのまま返す（すでにイミュータブルなので安全）
        return errors;
    }

    private static String buildMessage(List<String> errors) {
        // エラーリストが null または空のときはデフォルトメッセージを返す
        if (errors == null || errors.isEmpty()) {
            return "batch validation failed";
        }
        // "batch validation failed with X error(s):" という見出し部分を作る
        StringBuilder sb = new StringBuilder("batch validation failed with ")
                .append(errors.size())
                // 1件のときは "error:" 、複数のときは "errors:" にする
                .append(errors.size() == 1 ? " error:" : " errors:");
        // 各エラーを箇条書き（"  - エラー内容"）で改行しながら追記する
        for (String e : errors) {
            sb.append("\n  - ").append(e);
        }
        // 完成したエラーメッセージ文字列を返す
        return sb.toString();
    }
}
