package org.jboss.windup.java.model;

import java.util.Objects;

/**
 * Represents a web service endpoint (SOAP or REST) discovered during analysis.
 *
 * <p>Modernized from the legacy graph-backed {@code WebServiceModel} and its
 * subtypes ({@code JaxWSWebServiceModel}, {@code JaxRSWebServiceModel},
 * {@code JaxRPCWebServiceModel}) into a single flat POJO that uses a
 * {@link Protocol} enum to distinguish SOAP from REST.</p>
 */
public final class WebServiceModel {

    /**
     * The web service protocol.
     */
    public enum Protocol {
        SOAP,
        REST
    }

    private String implementationClass;
    private String interfaceClass;
    private String serviceUrl;
    private Protocol protocol;

    public WebServiceModel() {
    }

    public WebServiceModel(String implementationClass, String interfaceClass,
                           String serviceUrl, Protocol protocol) {
        this.implementationClass = implementationClass;
        this.interfaceClass = interfaceClass;
        this.serviceUrl = serviceUrl;
        this.protocol = protocol;
    }

    public String getImplementationClass() {
        return implementationClass;
    }

    public void setImplementationClass(String implementationClass) {
        this.implementationClass = implementationClass;
    }

    public String getInterfaceClass() {
        return interfaceClass;
    }

    public void setInterfaceClass(String interfaceClass) {
        this.interfaceClass = interfaceClass;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebServiceModel that)) return false;
        return Objects.equals(implementationClass, that.implementationClass)
                && protocol == that.protocol;
    }

    @Override
    public int hashCode() {
        return Objects.hash(implementationClass, protocol);
    }

    @Override
    public String toString() {
        return "WebServiceModel{" +
                "implementationClass='" + implementationClass + '\'' +
                ", interfaceClass='" + interfaceClass + '\'' +
                ", serviceUrl='" + serviceUrl + '\'' +
                ", protocol=" + protocol +
                '}';
    }
}
