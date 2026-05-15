package com.adobe.aem.guides.wknd.core.workflow.tp;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.day.cq.mailer.MessageGateway;
import com.day.cq.mailer.MessageGatewayService;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * TP案件 コンテンツ公開ワークフロー
 * カスタム開発#2: 公開完了通知ステップ
 *
 * 設計で「環境/通知種別/コンテンツ階層 によって通知先が変動」とあるため、
 * 宛先は以下のソースをマージして決定する:
 *   1) 起票ウィザードで入力された notificationEmails (ワークフロー metadata)
 *   2) PROCESS_ARGS の defaultRecipients (環境/種別/階層別の固定宛先)
 *
 * PROCESS_ARGS 例:
 *   notificationType=preview-complete,defaultRecipients=ops@tp.example.com;admin@tp.example.com
 */
@Component(service = WorkflowProcess.class, property = {
        "process.label=TP Notify by Email"
})
public class EmailNotificationProcess implements WorkflowProcess {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationProcess.class);

    private static final String METADATA_WIZARD_EMAILS = "notificationEmails";
    private static final String ARG_NOTIFICATION_TYPE = "notificationType";
    private static final String ARG_DEFAULT_RECIPIENTS = "defaultRecipients";

    @Reference
    private MessageGatewayService messageGatewayService;

    @Override
    public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args)
            throws WorkflowException {

        MetaDataMap metadata = workItem.getWorkflow().getMetaDataMap();
        String notificationType = arg(args, ARG_NOTIFICATION_TYPE, "publish-complete");

        Set<String> recipients = new LinkedHashSet<>();
        addRecipients(recipients, metadata.get(METADATA_WIZARD_EMAILS, String.class), ",");
        addRecipients(recipients, arg(args, ARG_DEFAULT_RECIPIENTS, null), "[;,]");

        if (recipients.isEmpty()) {
            log.info("TP Notify: no recipients for type='{}', skipping.", notificationType);
            return;
        }

        String payloadPath = workItem.getWorkflowData().getPayload().toString();
        MessageGateway<Email> gateway = messageGatewayService.getGateway(Email.class);
        if (gateway == null) {
            log.warn("TP Notify: MessageGateway unavailable, cannot send notifications.");
            return;
        }

        String subject = buildSubject(notificationType, payloadPath);
        String body = buildBody(notificationType, payloadPath, workItem);

        for (String address : recipients) {
            try {
                Email message = new SimpleEmail();
                message.addTo(address);
                message.setSubject(subject);
                message.setMsg(body);
                gateway.send(message);
                log.info("TP Notify: sent '{}' to {}", notificationType, address);
            } catch (Exception e) {
                log.error("TP Notify: failed to send to {}", address, e);
            }
        }
    }

    private void addRecipients(Set<String> bucket, String raw, String splitRegex) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        for (String token : raw.split(splitRegex)) {
            String addr = token.trim();
            if (!addr.isEmpty()) {
                bucket.add(addr);
            }
        }
    }

    private String arg(MetaDataMap args, String key, String defaultValue) {
        String fromMap = args.get(key, String.class);
        if (fromMap != null && !fromMap.trim().isEmpty()) {
            return fromMap.trim();
        }
        String processArgs = args.get("PROCESS_ARGS", String.class);
        if (processArgs != null) {
            for (String token : processArgs.split(",")) {
                String[] kv = token.trim().split("=", 2);
                if (kv.length == 2 && key.equals(kv[0].trim())) {
                    return kv[1].trim();
                }
            }
        }
        return defaultValue;
    }

    private String buildSubject(String type, String payloadPath) {
        switch (type) {
            case "preview-complete":
                return "[AEM] Preview公開完了: " + payloadPath;
            case "publish-complete":
                return "[AEM] 本番公開完了: " + payloadPath;
            case "publish-approved":
                return "[AEM] 本番公開承認待ち: " + payloadPath;
            default:
                return "[AEM] 公開通知 (" + type + "): " + payloadPath;
        }
    }

    private String buildBody(String type, String payloadPath, WorkItem workItem) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下のコンテンツに対する処理が完了しました。\n\n");
        sb.append("通知種別: ").append(type).append("\n");
        sb.append("対象パス: ").append(payloadPath).append("\n");
        sb.append("ワークフロー: ").append(workItem.getWorkflow().getId()).append("\n");
        String initiator = workItem.getWorkflow().getInitiator();
        if (initiator != null) {
            sb.append("起票者: ").append(initiator).append("\n");
        }
        return sb.toString();
    }
}
