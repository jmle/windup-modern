package io.konveyor.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates or updates {@code ~/.analyze/globalSettings.xml} with custom local repository
 * path and HTTP/HTTPS proxy configuration for Maven. Preserves any existing settings
 * elements (mirrors, servers, profiles, etc.) during round-tripping.
 */
public class MavenSettingsGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(MavenSettingsGenerator.class);

    private static final String SETTINGS_NS = "http://maven.apache.org/SETTINGS/1.0.0";

    public Path generate(String mavenCacheDir, String httpProxy, String httpsProxy,
                         String noProxy) throws IOException {
        Path settingsDir = Path.of(System.getProperty("user.home"), ".analyze");
        Files.createDirectories(settingsDir);
        Path settingsFile = settingsDir.resolve("globalSettings.xml");

        try {
            Document doc = loadOrCreate(settingsFile);
            Element root = doc.getDocumentElement();

            if (mavenCacheDir != null && !mavenCacheDir.isEmpty()) {
                setChildText(doc, root, "localRepository", mavenCacheDir);
            }

            updateProxies(doc, root, httpProxy, httpsProxy, noProxy);
            writeDocument(doc, settingsFile);
            LOG.info("Generated Maven global settings at {}", settingsFile);
            return settingsFile;
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IOException("Failed to generate Maven settings", e);
        }
    }

    Document loadOrCreate(Path settingsFile) throws ParserConfigurationException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        if (Files.exists(settingsFile)) {
            try {
                return builder.parse(settingsFile.toFile());
            } catch (SAXException e) {
                LOG.warn("Existing settings file is malformed, creating fresh: {}", e.getMessage());
            }
        }

        Document doc = builder.newDocument();
        Element root = doc.createElement("settings");
        root.setAttribute("xmlns", SETTINGS_NS);
        root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
        root.setAttribute("xsi:schemaLocation",
                SETTINGS_NS + " https://maven.apache.org/xsd/settings-1.0.0.xsd");
        doc.appendChild(root);
        return doc;
    }

    private void setChildText(Document doc, Element parent, String tagName, String text) {
        NodeList existing = parent.getElementsByTagName(tagName);
        if (existing.getLength() > 0) {
            existing.item(0).setTextContent(text);
        } else {
            Element elem = doc.createElement(tagName);
            elem.setTextContent(text);
            parent.insertBefore(elem, parent.getFirstChild());
        }
    }

    void updateProxies(Document doc, Element root,
                       String httpProxy, String httpsProxy, String noProxy) {
        NodeList existing = root.getElementsByTagName("proxies");
        for (int i = existing.getLength() - 1; i >= 0; i--) {
            root.removeChild(existing.item(i));
        }

        boolean hasHttp = httpProxy != null && !httpProxy.isEmpty();
        boolean hasHttps = httpsProxy != null && !httpsProxy.isEmpty();
        if (!hasHttp && !hasHttps) return;

        Element proxiesElem = doc.createElement("proxies");
        int id = 1;

        if (hasHttp) {
            Element e = buildProxyElement(doc, httpProxy, "http", id++, noProxy);
            if (e != null) proxiesElem.appendChild(e);
        }
        if (hasHttps) {
            Element e = buildProxyElement(doc, httpsProxy, "https", id, noProxy);
            if (e != null) proxiesElem.appendChild(e);
        }

        if (proxiesElem.hasChildNodes()) {
            root.appendChild(proxiesElem);
        }
    }

    Element buildProxyElement(Document doc, String proxyUrl, String protocol,
                              int id, String noProxy) {
        try {
            URI uri = new URI(proxyUrl);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return null;

            int port = uri.getPort();
            if (port < 0) {
                port = "https".equals(uri.getScheme()) ? 443 : 80;
            }

            Element proxy = doc.createElement("proxy");
            addChild(doc, proxy, "id", protocol + "-proxy-" + id);
            addChild(doc, proxy, "active", "true");
            addChild(doc, proxy, "protocol", protocol);
            addChild(doc, proxy, "host", host);
            addChild(doc, proxy, "port", String.valueOf(port));

            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int colonIdx = userInfo.indexOf(':');
                if (colonIdx >= 0) {
                    addChild(doc, proxy, "username", userInfo.substring(0, colonIdx));
                    addChild(doc, proxy, "password", userInfo.substring(colonIdx + 1));
                } else {
                    addChild(doc, proxy, "username", userInfo);
                }
            }

            if (noProxy != null && !noProxy.isEmpty()) {
                String mavenNoProxy = noProxy.replace(",", "|").replaceAll("\\s+", "");
                addChild(doc, proxy, "nonProxyHosts", mavenNoProxy);
            }

            return proxy;
        } catch (URISyntaxException e) {
            LOG.warn("Failed to parse proxy URL '{}': {}", proxyUrl, e.getMessage());
            return null;
        }
    }

    private void addChild(Document doc, Element parent, String tag, String text) {
        Element elem = doc.createElement(tag);
        elem.setTextContent(text);
        parent.appendChild(elem);
    }

    private void writeDocument(Document doc, Path path) throws TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.transform(new DOMSource(doc), new StreamResult(path.toFile()));
    }
}
