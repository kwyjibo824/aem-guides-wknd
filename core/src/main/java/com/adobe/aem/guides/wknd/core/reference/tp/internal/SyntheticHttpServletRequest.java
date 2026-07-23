package com.adobe.aem.guides.wknd.core.reference.tp.internal;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TP案件 カスタム開発#4: {@code SlingRequestProcessor} に渡すための合成 HttpServletRequest。
 *
 * 実HTTPを経由せずにSlingの通常リクエストパイプラインへ内部投入するために使う。
 * ワークフローのプロセスステップには元となる {@code SlingHttpServletRequest} が存在しないため、
 * {@code RequestDispatcher} ではなく {@code SlingRequestProcessor} を使う必要がある
 * （FJ確認済み: TP/03-decision/討議_リンクチェッカー論点整理.md）。
 *
 * <p>GET専用。ボディは持たない。Granite UIのレール描画に必要な最低限
 * （method / URI / extension / parameter / locale / scheme / serverName）を返す。</p>
 */
public final class SyntheticHttpServletRequest implements HttpServletRequest {

    private final String requestUri;
    private final String queryString;
    private final Map<String, String[]> parameters;
    private final Map<String, String> headers;
    private final Map<String, Object> attributes = new HashMap<>();
    private final Locale locale;
    private final String scheme;
    private final String serverName;
    private final int serverPort;

    private String characterEncoding = "UTF-8";

    public SyntheticHttpServletRequest(String requestUri,
                                       Map<String, String[]> parameters,
                                       Locale locale,
                                       String scheme,
                                       String serverName,
                                       int serverPort) {
        this.requestUri = requestUri;
        this.parameters = new LinkedHashMap<>(parameters);
        this.locale = locale != null ? locale : Locale.JAPAN;
        this.scheme = scheme;
        this.serverName = serverName;
        this.serverPort = serverPort;
        this.queryString = buildQueryString(this.parameters);

        this.headers = new LinkedHashMap<>();
        this.headers.put("Accept-Language", this.locale.toLanguageTag());
        this.headers.put("Host", serverName);
        this.headers.put("Accept", "text/html,*/*");
    }

    private static String buildQueryString(Map<String, String[]> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : params.entrySet()) {
            for (String v : e.getValue()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(e.getKey()).append('=').append(v);
            }
        }
        return sb.toString();
    }

    // ---- リクエストの本体（ここが実際に効く） ----

    @Override
    public String getMethod() {
        return "GET";
    }

    @Override
    public String getRequestURI() {
        return requestUri;
    }

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer sb = new StringBuffer();
        sb.append(scheme).append("://").append(serverName);
        boolean defaultPort = ("https".equals(scheme) && serverPort == 443)
                || ("http".equals(scheme) && serverPort == 80);
        if (!defaultPort) {
            sb.append(':').append(serverPort);
        }
        sb.append(requestUri);
        return sb;
    }

    @Override
    public String getServletPath() {
        return requestUri;
    }

    @Override
    public String getPathInfo() {
        return null;
    }

    @Override
    public String getContextPath() {
        return "";
    }

    @Override
    public String getQueryString() {
        return queryString.isEmpty() ? null : queryString;
    }

    @Override
    public String getParameter(String name) {
        String[] values = parameters.get(name);
        return (values != null && values.length > 0) ? values[0] : null;
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters.keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        return parameters.get(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return Collections.unmodifiableMap(parameters);
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String v = headers.get(name);
        return Collections.enumeration(v == null ? Collections.emptyList() : Collections.singletonList(v));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(headers.keySet());
    }

    @Override
    public int getIntHeader(String name) {
        String v = headers.get(name);
        return v == null ? -1 : Integer.parseInt(v);
    }

    @Override
    public long getDateHeader(String name) {
        return -1L;
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return Collections.enumeration(Collections.singletonList(locale));
    }

    @Override
    public String getScheme() {
        return scheme;
    }

    @Override
    public String getServerName() {
        return serverName;
    }

    @Override
    public int getServerPort() {
        return serverPort;
    }

    @Override
    public boolean isSecure() {
        return "https".equals(scheme);
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object o) {
        if (o == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, o);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public void setCharacterEncoding(String env) {
        this.characterEncoding = env;
    }

    @Override
    public ServletInputStream getInputStream() {
        final ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(javax.servlet.ReadListener readListener) {
                // 非同期読み取りは使わない
            }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), characterEncoding));
    }

    // ---- 以降は Granite UI のレール描画では使われない想定のスタブ ----
    // 呼ばれた場合に原因を特定しやすいよう、黙って誤動作させず既定値を返す。

    @Override
    public int getContentLength() {
        return 0;
    }

    @Override
    public long getContentLengthLong() {
        return 0L;
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public String getRemoteAddr() {
        return "127.0.0.1";
    }

    @Override
    public String getRemoteHost() {
        return "localhost";
    }

    @Override
    public int getRemotePort() {
        return 0;
    }

    @Override
    public String getLocalName() {
        return serverName;
    }

    @Override
    public String getLocalAddr() {
        return "127.0.0.1";
    }

    @Override
    public int getLocalPort() {
        return serverPort;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return null;
    }

    @Override
    @Deprecated
    public String getRealPath(String path) {
        return null;
    }

    @Override
    public ServletContext getServletContext() {
        return null;
    }

    @Override
    public AsyncContext startAsync() {
        throw new IllegalStateException("Async is not supported on a synthetic request");
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        throw new IllegalStateException("Async is not supported on a synthetic request");
    }

    @Override
    public boolean isAsyncStarted() {
        return false;
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw new IllegalStateException("Async is not supported on a synthetic request");
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    @Override
    public String getAuthType() {
        return null;
    }

    @Override
    public Cookie[] getCookies() {
        return new Cookie[0];
    }

    @Override
    public String getPathTranslated() {
        return null;
    }

    @Override
    public String getRemoteUser() {
        return null;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public String getRequestedSessionId() {
        return null;
    }

    @Override
    public HttpSession getSession(boolean create) {
        return null;
    }

    @Override
    public HttpSession getSession() {
        return null;
    }

    @Override
    public String changeSessionId() {
        return null;
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    @Override
    @Deprecated
    public boolean isRequestedSessionIdFromUrl() {
        return false;
    }

    @Override
    public boolean authenticate(HttpServletResponse response) {
        return false;
    }

    @Override
    public void login(String username, String password) {
        throw new UnsupportedOperationException("login() is not supported on a synthetic request");
    }

    @Override
    public void logout() {
        // no-op
    }

    @Override
    public Collection<Part> getParts() {
        return Collections.emptyList();
    }

    @Override
    public Part getPart(String name) {
        return null;
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
        throw new UnsupportedOperationException("upgrade() is not supported on a synthetic request");
    }

    /** 未使用の警告を避けるためのユーティリティ（デバッグ時にヘッダ一覧を見る用）。 */
    public List<String> debugHeaderNames() {
        return Collections.list(getHeaderNames());
    }
}
