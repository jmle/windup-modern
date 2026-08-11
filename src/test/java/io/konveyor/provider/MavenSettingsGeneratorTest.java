package io.konveyor.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenSettingsGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesSettingsWithLocalRepository() throws Exception {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            MavenSettingsGenerator generator = new MavenSettingsGenerator();
            Path settingsFile = generator.generate("/custom/repo", null, null, null);

            assertThat(settingsFile).exists();
            Document doc = parseXml(settingsFile);
            assertThat(getElementText(doc, "localRepository")).isEqualTo("/custom/repo");
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void generatesSettingsWithHttpProxy() throws Exception {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            MavenSettingsGenerator generator = new MavenSettingsGenerator();
            Path settingsFile = generator.generate(null,
                    "http://proxy.example.com:8080", null, "localhost, 127.0.0.1");

            Document doc = parseXml(settingsFile);
            NodeList proxyNodes = doc.getElementsByTagName("proxy");
            assertThat(proxyNodes.getLength()).isEqualTo(1);

            Element httpProxy = (Element) proxyNodes.item(0);
            assertThat(getChildText(httpProxy, "protocol")).isEqualTo("http");
            assertThat(getChildText(httpProxy, "host")).isEqualTo("proxy.example.com");
            assertThat(getChildText(httpProxy, "port")).isEqualTo("8080");
            assertThat(getChildText(httpProxy, "nonProxyHosts")).isEqualTo("localhost|127.0.0.1");
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void generatesSettingsWithBothProxies() throws Exception {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            MavenSettingsGenerator generator = new MavenSettingsGenerator();
            generator.generate(null,
                    "http://proxy.example.com:8080",
                    "https://proxy.example.com:8443",
                    null);

            Path settingsFile = tempDir.resolve(".analyze/globalSettings.xml");
            Document doc = parseXml(settingsFile);
            NodeList proxyNodes = doc.getElementsByTagName("proxy");
            assertThat(proxyNodes.getLength()).isEqualTo(2);

            Element first = (Element) proxyNodes.item(0);
            assertThat(getChildText(first, "protocol")).isEqualTo("http");
            assertThat(getChildText(first, "id")).isEqualTo("http-proxy-1");

            Element second = (Element) proxyNodes.item(1);
            assertThat(getChildText(second, "protocol")).isEqualTo("https");
            assertThat(getChildText(second, "id")).isEqualTo("https-proxy-2");
            assertThat(getChildText(second, "port")).isEqualTo("8443");
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void extractsProxyCredentials() throws Exception {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            MavenSettingsGenerator generator = new MavenSettingsGenerator();
            generator.generate(null,
                    "http://user:pass@proxy.example.com:3128", null, null);

            Path settingsFile = tempDir.resolve(".analyze/globalSettings.xml");
            Document doc = parseXml(settingsFile);
            Element proxy = (Element) doc.getElementsByTagName("proxy").item(0);
            assertThat(getChildText(proxy, "username")).isEqualTo("user");
            assertThat(getChildText(proxy, "password")).isEqualTo("pass");
            assertThat(getChildText(proxy, "host")).isEqualTo("proxy.example.com");
            assertThat(getChildText(proxy, "port")).isEqualTo("3128");
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void preservesExistingElements() throws Exception {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            Path analyzeDir = tempDir.resolve(".analyze");
            Files.createDirectories(analyzeDir);
            Files.writeString(analyzeDir.resolve("globalSettings.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <settings>
                      <mirrors>
                        <mirror>
                          <id>internal</id>
                          <url>https://nexus.corp/maven</url>
                        </mirror>
                      </mirrors>
                    </settings>
                    """);

            MavenSettingsGenerator generator = new MavenSettingsGenerator();
            generator.generate("/custom/repo", null, null, null);

            Path settingsFile = analyzeDir.resolve("globalSettings.xml");
            Document doc = parseXml(settingsFile);
            assertThat(getElementText(doc, "localRepository")).isEqualTo("/custom/repo");
            NodeList mirrors = doc.getElementsByTagName("mirror");
            assertThat(mirrors.getLength()).isEqualTo(1);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void updatesExistingLocalRepository() throws Exception {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            Path analyzeDir = tempDir.resolve(".analyze");
            Files.createDirectories(analyzeDir);
            Files.writeString(analyzeDir.resolve("globalSettings.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <settings>
                      <localRepository>/old/repo</localRepository>
                    </settings>
                    """);

            MavenSettingsGenerator generator = new MavenSettingsGenerator();
            generator.generate("/new/repo", null, null, null);

            Path settingsFile = analyzeDir.resolve("globalSettings.xml");
            Document doc = parseXml(settingsFile);
            assertThat(getElementText(doc, "localRepository")).isEqualTo("/new/repo");
            assertThat(doc.getElementsByTagName("localRepository").getLength()).isEqualTo(1);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void defaultPortForHttpIs80() throws Exception {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            MavenSettingsGenerator generator = new MavenSettingsGenerator();
            generator.generate(null, "http://proxy.example.com", null, null);

            Path settingsFile = tempDir.resolve(".analyze/globalSettings.xml");
            Document doc = parseXml(settingsFile);
            Element proxy = (Element) doc.getElementsByTagName("proxy").item(0);
            assertThat(getChildText(proxy, "port")).isEqualTo("80");
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    private Document parseXml(Path file) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(file.toFile());
    }

    private String getElementText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }

    private String getChildText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }
}
