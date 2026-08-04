package com.example.demo.config;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * A configuration class demonstrating javax.xml.bind (JAXB) usage
 * that should be flagged for migration to jakarta.xml.bind.
 */
@XmlRootElement
public class AppConfig {

    private String appName;
    private String version;

    public AppConfig() {
    }

    public AppConfig(String appName, String version) {
        this.appName = appName;
        this.version = version;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public JAXBContext createContext() throws JAXBException {
        return JAXBContext.newInstance(AppConfig.class);
    }
}
