package com.puppetlabs.puppetserver;

/**
 * This class is a simple data structure that contains the response to an
 * agent -> master HTTP request, including all information
 * produced by the master during the processing of the request that needs to be
 * included in the response back to the client.
 *
 * Instances of this class act as carriers for information from the old ruby
 * code, running in JRuby, back to the `RequestHandlerService` implementation
 * in clojure.
 */
public class JRubyPuppetResponse {
    private final Integer status;
    private final Object body;
    private final String contentType;
    private final String puppetVersion;

    public JRubyPuppetResponse(
            Integer status,
            Object body,
            String contentType,
            String puppetVersion) {
        this.status = status;
        this.body = body;
        this.contentType = contentType;
        this.puppetVersion = puppetVersion;
    }

    public Object getBody() {
        return body;
    }

    public Integer getStatus() {
        return status;
    }

    public String getContentType() {
        return contentType;
    }

    public String getPuppetVersion() {
        return puppetVersion;
    }
}
