package com.adobe.aem.guides.wknd.core.reference.tp;

import com.adobe.aem.guides.wknd.core.reference.tp.internal.CapturingHttpServletResponse;
import com.adobe.aem.guides.wknd.core.reference.tp.internal.SyntheticHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.engine.SlingRequestProcessor;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.util.Collections;
import java.util.Locale;

/**
 * TP案件 カスタム開発#4（被リンクチェック）PoC:
 * Sites コンソールの References レール（Incoming Links）を、ワークフローの
 * プロセスステップから内部呼び出しできるかを確認するためのクライアント。
 *
 * <h3>なぜ HTTP ループバックではないのか</h3>
 * ワークフローのプロセスステップが持っているのはサービスユーザーの
 * {@link ResourceResolver} であり、HTTPセッションでもトークンでもない。
 * resolver を HTTP 認証に変換する正規の手段が存在しないため、
 * {@code localhost} へ HttpClient で叩く方式は使えない。
 *
 * <p>代わりに {@link SlingRequestProcessor} を使う。実HTTPを経由せずに
 * Sling の通常リクエストパイプライン（request filter を含む）へ内部投入でき、
 * かつ任意の {@link ResourceResolver} を渡せるため認証の問題が発生しない。
 * FJレビューで本命として確認済み。</p>
 *
 * <p>{@code RequestDispatcher} は既存の {@code SlingHttpServletRequest} からの
 * include/forward 用であり、元リクエストが存在しないワークフロー起点では使えない。
 * 通る filter chain も REQUEST ではなく INCLUDE/FORWARD になるため不適。</p>
 *
 * <h3>このPoCで確認したいこと</h3>
 * <ol>
 *   <li>合成リクエストで Granite UI のレールコンポーネントが描画されるか</li>
 *   <li>ステータス・Content-Type・ボディが期待通り返るか</li>
 *   <li>返ってきたHTMLの構造（パース方針を決めるための実物確認）</li>
 * </ol>
 *
 * <p><b>注意:</b> このレールはパス文字列の部分一致で候補を返すため、対象パスを接頭辞として
 * 含む別ページが誤検出として混入する（TP Sandbox で実証済み）。本クラスは生の結果を
 * 返すだけで、誤検出の除去は行わない。</p>
 */
@Component(service = IncomingLinksRailClient.class)
@Designate(ocd = IncomingLinksRailClient.Config.class)
public class IncomingLinksRailClient {

    /** Sites コンソールの References レールが内部で叩いているパス。 */
    private static final String RAIL_PATH =
            "/mnt/overlay/wcm/core/content/sites/jcr:content/rails/references/items/references.provider.html";

    @Reference
    private SlingRequestProcessor slingRequestProcessor;

    private String scheme;
    private String serverName;
    private int serverPort;

    @Activate
    @Modified
    protected void activate(Config config) {
        this.scheme = config.scheme();
        this.serverName = config.server_name();
        this.serverPort = config.server_port();
    }

    /**
     * References レールを内部呼び出しして生のレスポンスを返す。
     *
     * @param resolver 実行に使う ResourceResolver。この権限で見える範囲だけが検出対象になる
     * @param targetPath 被リンクを調べたいコンテンツパス（例: {@code /content/sample-name/fp}）
     */
    public RailResponse fetch(ResourceResolver resolver, String targetPath) {
        SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(
                RAIL_PATH,
                Collections.singletonMap("item", new String[]{targetPath}),
                Locale.JAPAN,
                scheme,
                serverName,
                serverPort);

        CapturingHttpServletResponse response = new CapturingHttpServletResponse();

        long start = System.currentTimeMillis();
        try {
            slingRequestProcessor.processRequest(request, response, resolver);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return RailResponse.failure(targetPath, elapsed, e);
        }
        long elapsed = System.currentTimeMillis() - start;

        return RailResponse.success(
                targetPath, elapsed, response.getStatus(),
                response.getContentType(), response.getBodyAsString());
    }

    /** レール呼び出しの生結果。 */
    public static final class RailResponse {
        private final String targetPath;
        private final long elapsedMs;
        private final int status;
        private final String contentType;
        private final String body;
        private final Exception error;

        private RailResponse(String targetPath, long elapsedMs, int status,
                             String contentType, String body, Exception error) {
            this.targetPath = targetPath;
            this.elapsedMs = elapsedMs;
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.error = error;
        }

        static RailResponse success(String targetPath, long elapsedMs, int status,
                                    String contentType, String body) {
            return new RailResponse(targetPath, elapsedMs, status, contentType, body, null);
        }

        static RailResponse failure(String targetPath, long elapsedMs, Exception error) {
            return new RailResponse(targetPath, elapsedMs, -1, null, null, error);
        }

        /** 例外なく処理が完走し、200 かつ text/html が返ったか。 */
        public boolean isOk() {
            return error == null && status == 200
                    && contentType != null && contentType.startsWith("text/html");
        }

        public String getTargetPath() {
            return targetPath;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public int getStatus() {
            return status;
        }

        public String getContentType() {
            return contentType;
        }

        /** レールが返したHTML。パース方針を決めるためにまず実物を確認する。 */
        public String getBody() {
            return body;
        }

        public Exception getError() {
            return error;
        }

        public int getBodyLength() {
            return body == null ? 0 : body.length();
        }
    }

    @ObjectClassDefinition(name = "TP Incoming Links Rail Client Config")
    public @interface Config {

        @AttributeDefinition(
                name = "Scheme",
                description = "合成リクエストのスキーム。通常は https のままでよい")
        String scheme() default "https";

        @AttributeDefinition(
                name = "Server Name",
                description = "合成リクエストのホスト名。絶対URL生成が必要な場合にのみ影響する。"
                        + "実HTTPは発生しないため、到達可能である必要はない")
        String server_name() default "localhost";

        @AttributeDefinition(
                name = "Server Port",
                description = "合成リクエストのポート")
        int server_port() default 443;
    }
}
