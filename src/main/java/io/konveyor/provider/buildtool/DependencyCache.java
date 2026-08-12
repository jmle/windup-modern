package io.konveyor.provider.buildtool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public class DependencyCache {

    private static final Logger LOG = LoggerFactory.getLogger(DependencyCache.class);

    private final Path buildFile;
    private String storedHash;
    private List<BuildTool.DagEntry> cachedDag;

    public DependencyCache(Path buildFile) {
        this.buildFile = buildFile;
    }

    public boolean isValid() {
        String currentHash = computeHash();
        if (currentHash == null) return false;
        return currentHash.equals(storedHash) && cachedDag != null;
    }

    public List<BuildTool.DagEntry> getCached() {
        return cachedDag;
    }

    public void store(List<BuildTool.DagEntry> dag) {
        this.cachedDag = dag;
        this.storedHash = computeHash();
        LOG.debug("Cached {} dependency entries for {}", dag.size(), buildFile);
    }

    private String computeHash() {
        if (!Files.exists(buildFile)) return null;
        try {
            byte[] content = Files.readAllBytes(buildFile);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.warn("Failed to compute hash for {}: {}", buildFile, e.getMessage());
            return null;
        }
    }
}
