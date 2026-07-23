package com.adobe.aem.guides.wknd.core.reference.tp.internal;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * TP案件 カスタム開発#4: {@code SlingRequestProcessor} の出力を受け取るための合成 HttpServletResponse。
 *
 * ボディをメモリ上に蓄積し、{@link #getBodyAsString()} で取り出せるようにする。
 * ステータスコードとContent-Typeも保持し、呼び出し側が「本当に200/text/htmlが返ったか」を
 * 検証できるようにしている（レール実装が変わったときに黙って壊れないようにするため）。
 */
public final class CapturingHttpServletResponse implements HttpServletResponse {

    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final Map<String, String> headers = new LinkedHashMap<>();

    private int status = SC_OK;
    private String contentType;
    private String characterEncoding = StandardCharsets.UTF_8.name();
    private Locale locale = Locale.JAPAN;
    private PrintWriter writer;
    private boolean committed;

    /** 蓄積されたレスポンスボディを文字列として返す。 */
    public String getBodyAsString() {
        flushWriterIfNeeded();
        try {
            return new String(body.toByteArray(), characterEncoding);
        } catch (UnsupportedEncodingException e) {
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void flushWriterIfNeeded() {
        if (writer != null) {
            writer.flush();
        }
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return new ServletOutputStream() {
            @Override
            public void write(int b) {
                body.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                body.write(b, off, len);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                // 非同期書き込みは使わない
            }
        };
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(body, characterEncoding), false);
        }
        return writer;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public void setStatus(int sc) {
        this.status = sc;
    }

    @Override
    @Deprecated
    public void setStatus(int sc, String sm) {
        this.status = sc;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public void setContentType(String type) {
        this.contentType = type;
        if (type != null) {
            int idx = type.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (idx >= 0) {
                this.characterEncoding = type.substring(idx + "charset=".length()).trim();
            }
        }
    }

    @Override
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        this.characterEncoding = charset;
    }

    @Override
    public void sendError(int sc, String msg) {
        this.status = sc;
        this.committed = true;
    }

    @Override
    public void sendError(int sc) {
        this.status = sc;
        this.committed = true;
    }

    @Override
    public void sendRedirect(String location) {
        this.status = SC_MOVED_TEMPORARILY;
        headers.put("Location", location);
        this.committed = true;
    }

    // ---- ヘッダ ----

    @Override
    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        headers.merge(name, value, (a, b) -> a + "," + b);
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        String v = headers.get(name);
        return v == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(v);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return headers.keySet();
    }

    @Override
    public boolean containsHeader(String name) {
        return headers.containsKey(name);
    }

    @Override
    public void setDateHeader(String name, long date) {
        headers.put(name, String.valueOf(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        addHeader(name, String.valueOf(date));
    }

    @Override
    public void setIntHeader(String name, int value) {
        headers.put(name, String.valueOf(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        addHeader(name, String.valueOf(value));
    }

    // ---- バッファ ----

    @Override
    public void setContentLength(int len) {
        headers.put("Content-Length", String.valueOf(len));
    }

    @Override
    public void setContentLengthLong(long len) {
        headers.put("Content-Length", String.valueOf(len));
    }

    @Override
    public void setBufferSize(int size) {
        // メモリ上に全量を蓄積するためバッファサイズは無視する
    }

    @Override
    public int getBufferSize() {
        return body.size();
    }

    @Override
    public void flushBuffer() {
        flushWriterIfNeeded();
        this.committed = true;
    }

    @Override
    public void resetBuffer() {
        body.reset();
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public void reset() {
        body.reset();
        headers.clear();
        status = SC_OK;
        committed = false;
    }

    @Override
    public void setLocale(Locale loc) {
        this.locale = loc;
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    @Override
    public void addCookie(Cookie cookie) {
        // レール描画では Cookie を返さない想定
    }

    @Override
    public String encodeURL(String url) {
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return url;
    }

    @Override
    @Deprecated
    public String encodeUrl(String url) {
        return url;
    }

    @Override
    @Deprecated
    public String encodeRedirectUrl(String url) {
        return url;
    }
}
