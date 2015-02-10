package com.puppetlabs.enterprise;

import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;

import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.Scheme;

import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.HttpParams;
import org.eclipse.jgit.transport.http.HttpConnection;

import org.eclipse.jgit.transport.http.apache.TemporaryBufferEntity;
import org.eclipse.jgit.transport.http.apache.internal.HttpApacheText;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.eclipse.jgit.util.TemporaryBuffer.LocalFile;
/**
 * A {@link HttpConnection} which uses {@link HttpClient}
 *
 * @since 3.3
 */
public class HttpClientConnection implements HttpConnection {
    HttpClient client;

    String urlStr;

    HttpUriRequest req;

    HttpResponse resp = null;

    String method = "GET"; //$NON-NLS-1$

    private TemporaryBufferEntity entity;

    private boolean isUsingProxy = false;

    private Proxy proxy;

    private Integer timeout = null;

    private Integer readTimeout;

    private Boolean followRedirects;

    private X509HostnameVerifier hostnameverifier = new BrowserCompatHostnameVerifier();

    private Boolean useSSL = false;

    SSLContext ctx;

    private HttpClient getClient() {
        if (client == null)
            client = new DefaultHttpClient();
        HttpParams params = client.getParams();
        if (proxy != null && !Proxy.NO_PROXY.equals(proxy)) {
            isUsingProxy = true;
            InetSocketAddress adr = (InetSocketAddress) proxy.address();
            params.setParameter(ConnRoutePNames.DEFAULT_PROXY,
                    new HttpHost(adr.getHostName(), adr.getPort()));
        }
        if (timeout != null)
            params.setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT,
                    timeout.intValue());
        if (readTimeout != null)
            params.setIntParameter(CoreConnectionPNames.SO_TIMEOUT,
                    readTimeout.intValue());
        if (followRedirects != null)
            params.setBooleanParameter(ClientPNames.HANDLE_REDIRECTS,
                    followRedirects.booleanValue());

        if (hostnameverifier != null && useSSL) {
            SSLSocketFactory sf;
            sf = new SSLSocketFactory(getSSLContext(), hostnameverifier);
            Scheme https = new Scheme("https", 443, sf); //$NON-NLS-1$
            client.getConnectionManager().getSchemeRegistry().register(https);
        }

        return client;
    }

    private SSLContext getSSLContext() {
        if (ctx == null) {
            try {
                ctx = SSLContext.getInstance("TLS"); //$NON-NLS-1$
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(
                        HttpApacheText.get().unexpectedSSLContextException, e);
            }
        }
        return ctx;
    }

    /**
     * Sets the buffer from which to take the request body
     *
     * @param buffer
     */
    public void setBuffer(TemporaryBuffer buffer) {
        this.entity = new TemporaryBufferEntity(buffer);
    }

    /**
     * @param urlStr
     * @param proxy
     */
    public HttpClientConnection(SSLContext sslContext, String urlStr, Proxy proxy) throws FileNotFoundException {
        this.urlStr = urlStr;
        this.proxy = proxy;
        this.client = null;
        this.ctx = sslContext;
        this.useSSL = this.ctx != null;
    }

    public int getResponseCode() throws IOException {
        execute();
        return resp.getStatusLine().getStatusCode();
    }

    public URL getURL() {
        try {
            return new URL(urlStr);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    public String getResponseMessage() throws IOException {
        execute();
        return resp.getStatusLine().getReasonPhrase();
    }

    private void execute() throws IOException, ClientProtocolException {
        if (resp == null)
            if (entity != null) {
                if (req instanceof HttpEntityEnclosingRequest) {
                    HttpEntityEnclosingRequest eReq = (HttpEntityEnclosingRequest) req;
                    eReq.setEntity(entity);
                }
                resp = getClient().execute(req);
                entity.getBuffer().close();
                entity = null;
            } else
                resp = getClient().execute(req);
    }

    public Map<String, List<String>> getHeaderFields() {
        Map<String, List<String>> ret = new HashMap<String, List<String>>();
        for (Header hdr : resp.getAllHeaders()) {
            List<String> list = new LinkedList<String>();
            for (HeaderElement hdrElem : hdr.getElements())
                list.add(hdrElem.toString());
            ret.put(hdr.getName(), list);
        }
        return ret;
    }

    public void setRequestProperty(String name, String value) {
        req.addHeader(name, value);
    }

    public void setRequestMethod(String method) throws ProtocolException {
        this.method = method;
        if ("GET".equalsIgnoreCase(method)) //$NON-NLS-1$
            req = new HttpGet(urlStr);
        else if ("PUT".equalsIgnoreCase(method)) //$NON-NLS-1$
            req = new HttpPut(urlStr);
        else if ("POST".equalsIgnoreCase(method)) //$NON-NLS-1$
            req = new HttpPost(urlStr);
        else {
            this.method = null;
            throw new UnsupportedOperationException();
        }
    }

    public void setUseCaches(boolean usecaches) {
        // not needed
    }

    public void setConnectTimeout(int timeout) {
        this.timeout = new Integer(timeout);
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = new Integer(readTimeout);
    }

    public String getContentType() {
        HttpEntity responseEntity = resp.getEntity();
        if (responseEntity != null) {
            Header contentType = responseEntity.getContentType();
            if (contentType != null)
                return contentType.getValue();
        }
        return null;
    }

    public InputStream getInputStream() throws IOException {
        return resp.getEntity().getContent();
    }

    // will return only the first field
    public String getHeaderField(String name) {
        Header header = resp.getFirstHeader(name);
        return (header == null) ? null : header.getValue();
    }

    public int getContentLength() {
        return Integer.parseInt(resp.getFirstHeader("content-length") //$NON-NLS-1$
                .getValue());
    }

    public void setInstanceFollowRedirects(boolean followRedirects) {
        this.followRedirects = new Boolean(followRedirects);
    }

    public void setDoOutput(boolean dooutput) {
        // TODO: check whether we can really ignore this.
    }

    public void setFixedLengthStreamingMode(int contentLength) {
        if (entity != null)
            throw new IllegalArgumentException();
        entity = new TemporaryBufferEntity(new LocalFile());
        entity.setContentLength(contentLength);
    }

    public OutputStream getOutputStream() throws IOException {
        if (entity == null)
            entity = new TemporaryBufferEntity(new LocalFile());
        return entity.getBuffer();
    }

    public void setChunkedStreamingMode(int chunklen) {
        if (entity == null)
            entity = new TemporaryBufferEntity(new LocalFile());
        entity.setChunked(true);
    }

    public String getRequestMethod() {
        return method;
    }

    public boolean usingProxy() {
        return isUsingProxy;
    }

    public void connect() throws IOException {
        execute();
    }

    public void setHostnameVerifier(final HostnameVerifier hostnameverifier) {
        this.hostnameverifier = new X509HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
                return hostnameverifier.verify(hostname, session);
            }

            public void verify(String host, String[] cns, String[] subjectAlts)
                    throws SSLException {
                throw new UnsupportedOperationException(); // TODO message
            }

            public void verify(String host, X509Certificate cert)
                    throws SSLException {
                throw new UnsupportedOperationException(); // TODO message
            }

            public void verify(String host, SSLSocket ssl) throws IOException {
                hostnameverifier.verify(host, ssl.getSession());
            }
        };
    }

    public void configure(KeyManager[] km, TrustManager[] tm,
                          SecureRandom random) throws KeyManagementException {
        getSSLContext().init(km, tm, random);
    }
}
