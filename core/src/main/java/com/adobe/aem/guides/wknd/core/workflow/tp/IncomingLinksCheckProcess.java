package com.adobe.aem.guides.wknd.core.workflow.tp;

import com.adobe.aem.guides.wknd.core.reference.tp.IncomingLinksRailClient;
import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * TP案件 カスタム開発#4（被リンクチェック）PoC:
 * References レール（Incoming Links）をワークフローのプロセスステップから
 * 内部呼び出しできるかを確認するためのステップ。
 *
 * <h3>このステップの目的（PoCの範囲）</h3>
 * <b>「呼び出せるか」の一点のみを確認する。</b> 誤検出の除去や非公開のブロック判定は行わない。
 * 結果はすべてログに出力する。
 *
 * <h3>権限について</h3>
 * 検出範囲は渡す {@link ResourceResolver} の権限で決まる。コンテンツ主管は自分の管轄配下しか
 * 見えないACL設計のため、オーサーのセッションで実行すると他部署配下からの被リンクを取りこぼす。
 * 本ステップは専用サービスユーザー {@code tp-incoming-links-service} の resolver を使い、
 * 全社横断で検出する。
 *
 * <p>ワークフローエンジン既定の {@code workflow-process-service} を直接使わず専用の
 * subservice を切っているのは、最小権限化・監査・将来の権限変更の影響範囲を閉じ込めるため
 * （FJ推奨）。</p>
 *
 * <h3>既知の制約</h3>
 * このレールはパス文字列の部分一致で候補を返すため、対象パスを接頭辞として含む別ページが
 * 誤検出として混入する。TP Sandbox で実証済み
 * （{@code TP/05-workspace/linkcheck-false-positive-test.md}）。
 */
@Component(
        service = WorkflowProcess.class,
        property = {
                "process.label=TP Incoming Links Check (PoC)"
        }
)
@Designate(ocd = IncomingLinksCheckProcess.Config.class)
public class IncomingLinksCheckProcess implements WorkflowProcess {

    private static final Logger log = LoggerFactory.getLogger(IncomingLinksCheckProcess.class);

    private static final String SERVICE_USER_SUBSERVICE = "tp-incoming-links";

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private IncomingLinksRailClient railClient;

    private int bodyLogLimit;
    private boolean failOnError;

    @Activate
    @Modified
    protected void activate(Config config) {
        this.bodyLogLimit = config.body_log_limit();
        this.failOnError = config.fail_on_error();
    }

    @Override
    public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap metaDataMap)
            throws WorkflowException {

        String payloadPath = String.valueOf(workItem.getWorkflowData().getPayload());
        log.info("[TP IncomingLinks PoC] start. payload={}", payloadPath);

        try (ResourceResolver resolver = login()) {
            if (resolver == null) {
                handleFailure("service resolver could not be obtained", null);
                return;
            }

            IncomingLinksRailClient.RailResponse response = railClient.fetch(resolver, payloadPath);

            if (response.getError() != null) {
                log.error("[TP IncomingLinks PoC] rail call threw. payload={} elapsed={}ms",
                        payloadPath, response.getElapsedMs(), response.getError());
                handleFailure("rail call threw an exception", response.getError());
                return;
            }

            log.info("[TP IncomingLinks PoC] status={} contentType={} bodyLength={} elapsed={}ms ok={}",
                    response.getStatus(), response.getContentType(),
                    response.getBodyLength(), response.getElapsedMs(), response.isOk());

            // パース方針を決めるために、返ってきたHTMLの実物をログに残す。
            // 全量だとログが溢れるため先頭のみ。必要なら OSGi Config で伸ばす。
            String body = response.getBody();
            if (body != null && !body.isEmpty()) {
                String snippet = body.length() > bodyLogLimit ? body.substring(0, bodyLogLimit) + "...(truncated)" : body;
                log.info("[TP IncomingLinks PoC] body snippet:\n{}", snippet);
            } else {
                log.warn("[TP IncomingLinks PoC] body was empty");
            }

            if (!response.isOk()) {
                handleFailure("unexpected status or content type: status=" + response.getStatus()
                        + " contentType=" + response.getContentType(), null);
            }

        } catch (WorkflowException e) {
            throw e;
        } catch (Exception e) {
            log.error("[TP IncomingLinks PoC] unexpected failure. payload={}", payloadPath, e);
            handleFailure("unexpected failure", e);
        }
    }

    /**
     * PoCのため既定ではワークフローを止めない。ログだけ残して次のステップへ進む。
     * 検証内容を確定させたい場合は OSGi Config で {@code fail.on.error} を有効にする。
     */
    private void handleFailure(String reason, Exception cause) throws WorkflowException {
        if (failOnError) {
            throw new WorkflowException("[TP IncomingLinks PoC] " + reason, cause);
        }
        log.warn("[TP IncomingLinks PoC] continuing despite failure: {}", reason);
    }

    private ResourceResolver login() {
        try {
            return resourceResolverFactory.getServiceResourceResolver(
                    Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SERVICE_USER_SUBSERVICE));
        } catch (LoginException e) {
            log.error("[TP IncomingLinks PoC] failed to login as service user {}", SERVICE_USER_SUBSERVICE, e);
            return null;
        }
    }

    @ObjectClassDefinition(name = "TP Incoming Links Check Process Config (PoC)")
    public @interface Config {

        @AttributeDefinition(
                name = "Body Log Limit",
                description = "レスポンスHTMLをログに出す際の最大文字数。パース方針を決めるための実物確認用")
        int body_log_limit() default 4000;

        @AttributeDefinition(
                name = "Fail On Error",
                description = "呼び出しに失敗したときワークフローを止めるか。"
                        + "PoC中は false（ログのみ残して継続）を推奨")
        boolean fail_on_error() default false;
    }
}
