package com.adobe.aem.guides.wknd.core.reference.tp;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ExternalResourceChangeListener;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TP案件 カスタム開発: FreeHTMLブロックのリンクをAEM Author の Incoming Links (References)
 * に認識させるための隠しプロパティ({@code tpReferences})を維持するリスナー。
 *
 * 決定ログ: /Users/kfujiwara/sync/TP/decisions/012-freehtml-incoming-links.md
 *
 * 著者はFreeHTMLブロックの content プロパティに短縮パス(例: /fp)でリンクを書く。
 * 配信HTMLはそのまま短縮パスで出るためリンク切れにならないが、Incoming Links には
 * 拾われない。本リスナーは content プロパティの変更を検知し、リンク先を
 * サイトルート配下のcontent path(例: /content/tepco-site/fp)に解決した上で
 * tpReferences（多値プロパティ）に保存する。
 *
 * FreeHTMLブロックはxwalkの全ブロック共通の sling:resourceType
 * (core/franklin/components/block/v1/block) を持つため、model プロパティが
 * "freehtml" であることで種別を判定する(実機のCRXDE Lite確認で判明)。
 */
@Component(
        service = {ResourceChangeListener.class, ExternalResourceChangeListener.class},
        property = {
                ResourceChangeListener.PATHS + "=/content",
                ResourceChangeListener.CHANGES + "=ADDED",
                ResourceChangeListener.CHANGES + "=CHANGED"
        }
)
@Designate(ocd = FreeHtmlReferenceListener.Config.class)
public class FreeHtmlReferenceListener implements ResourceChangeListener, ExternalResourceChangeListener {

    private static final Logger log = LoggerFactory.getLogger(FreeHtmlReferenceListener.class);

    private static final String SERVICE_USER_SUBSERVICE = "tp-freehtml-reference";

    private static final String BLOCK_RESOURCE_TYPE = "core/franklin/components/block/v1/block";
    private static final String PROP_MODEL = "model";
    private static final String MODEL_FREEHTML = "freehtml";
    private static final String PROP_CONTENT = "content";
    private static final String PROP_REFERENCES = "tpReferences";

    private static final Pattern HREF_PATTERN =
            Pattern.compile("<a\\s+[^>]*href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    private String siteContentRoot;

    @Activate
    @Modified
    protected void activate(Config config) {
        this.siteContentRoot = config.site_content_root();
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        try (ResourceResolver resolver = login()) {
            if (resolver == null) {
                return;
            }
            for (ResourceChange change : changes) {
                handleChange(resolver, change.getPath());
            }
        } catch (PersistenceException e) {
            log.error("Failed to update tpReferences", e);
        }
    }

    private void handleChange(ResourceResolver resolver, String path) throws PersistenceException {
        Resource resource = resolver.getResource(path);
        if (resource == null || !isFreeHtmlBlock(resource)) {
            return;
        }

        String content = resource.getValueMap().get(PROP_CONTENT, String.class);
        if (content == null || content.isEmpty()) {
            return;
        }

        Set<String> references = extractReferences(content);
        Set<String> existing = new LinkedHashSet<>(
                Arrays.asList(resource.getValueMap().get(PROP_REFERENCES, new String[0])));

        if (references.equals(existing)) {
            // contentに変化がない、または前回のtpReferences書き込み自体をこのリスナーが
            // 検知した場合。再書き込みしないことで無限ループを防ぐ。
            return;
        }

        resource.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                .put(PROP_REFERENCES, references.toArray(new String[0]));
        resolver.commit();

        log.info("Updated {} on {}: {}", PROP_REFERENCES, path, references);
    }

    private boolean isFreeHtmlBlock(Resource resource) {
        return resource.isResourceType(BLOCK_RESOURCE_TYPE)
                && MODEL_FREEHTML.equals(resource.getValueMap().get(PROP_MODEL, String.class));
    }

    private Set<String> extractReferences(String rawContent) {
        String unescaped = StringEscapeUtils.unescapeHtml4(rawContent);
        Set<String> references = new LinkedHashSet<>();

        Matcher matcher = HREF_PATTERN.matcher(unescaped);
        while (matcher.find()) {
            String href = matcher.group(1);
            String canonical = toCanonicalPath(href);
            if (canonical != null) {
                references.add(canonical);
            }
        }
        return references;
    }

    private String toCanonicalPath(String href) {
        if (href.startsWith("/content/")) {
            return href;
        }
        if (href.startsWith("/")) {
            return siteContentRoot + href;
        }
        // 外部リンク・アンカーリンク等はIncoming Linksの対象外
        return null;
    }

    private ResourceResolver login() {
        try {
            return resourceResolverFactory.getServiceResourceResolver(
                    Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SERVICE_USER_SUBSERVICE));
        } catch (LoginException e) {
            log.error("Failed to login as service user {}", SERVICE_USER_SUBSERVICE, e);
            return null;
        }
    }

    @org.osgi.service.metatype.annotations.ObjectClassDefinition(
            name = "TP FreeHTML Reference Listener Config"
    )
    public @interface Config {
        @org.osgi.service.metatype.annotations.AttributeDefinition(
                name = "Site Content Root",
                description = "短縮パス(/fp等)をcontent pathに解決する際に付与するサイトルート。例: /content/tepco-site"
        )
        String site_content_root() default "/content/tepco-site";
    }
}
