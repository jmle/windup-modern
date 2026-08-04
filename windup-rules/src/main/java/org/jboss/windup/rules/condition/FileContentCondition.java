package org.jboss.windup.rules.condition;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.RuleCondition;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.ModelRegistry;

/**
 * Scans file content in the analysis context using a regular expression and
 * returns matched file/line information.
 *
 * <p>An optional {@code filename} filter (glob pattern) restricts which files
 * are searched. The glob uses standard filesystem conventions:
 * {@code *} matches any sequence of characters (no path separators),
 * {@code ?} matches a single character.</p>
 */
public class FileContentCondition implements RuleCondition {

    private static final Logger LOG = Logger.getLogger(FileContentCondition.class.getName());

    private final Pattern contentPattern;
    private final String contentPatternSource;
    private final Pattern filenamePattern;
    private final String filenameGlob;

    /**
     * Creates a new file content condition.
     *
     * @param pattern  the regular expression to match in file content (must not be null)
     * @param filename optional filename glob filter (e.g. {@code *.java}); null means all files
     */
    public FileContentCondition(String pattern, String filename) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be null or blank");
        }
        this.contentPatternSource = pattern;
        this.contentPattern = Pattern.compile(pattern);
        this.filenameGlob = filename;
        this.filenamePattern = filename != null ? compileFilenameGlob(filename) : null;
    }

    @Override
    public ConditionResult evaluate(AnalysisRun run) {
        if (run == null || run.getContext() == null) {
            return ConditionResult.noMatch();
        }

        ModelRegistry<FileModel> fileRegistry = run.getContext().files();
        List<FileModel> allFiles = fileRegistry.findAll();

        List<FileContentMatch> matched = new ArrayList<>();

        for (FileModel file : allFiles) {
            if (file.isDirectory()) {
                continue;
            }
            if (filenamePattern != null && !filenamePattern.matcher(file.getFileName()).matches()) {
                continue;
            }
            if (file.getFilePath() == null || !Files.isRegularFile(file.getFilePath())) {
                continue;
            }

            try {
                List<String> lines = Files.readAllLines(file.getFilePath());
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = contentPattern.matcher(lines.get(i));
                    while (m.find()) {
                        FileContentMatch match = new FileContentMatch(
                                file, i + 1, m.start() + 1, m.group());
                        matched.add(match);
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not read file: " + file.getFilePath(), e);
            }
        }

        if (matched.isEmpty()) {
            LOG.fine(() -> String.format(
                    "file-content condition pattern='%s' filename='%s': no matches among %d files",
                    contentPatternSource, filenameGlob, allFiles.size()));
            return ConditionResult.noMatch();
        }

        LOG.fine(() -> String.format(
                "file-content condition pattern='%s' filename='%s': %d matches across %d files",
                contentPatternSource, filenameGlob, matched.size(), allFiles.size()));
        return ConditionResult.match(matched);
    }

    /**
     * Converts a filename glob (e.g. {@code *.java}) into a compiled regex.
     * Supports {@code *} (any chars) and {@code ?} (single char).
     */
    static Pattern compileFilenameGlob(String glob) {
        StringBuilder regex = new StringBuilder();
        regex.append('^');
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }

    /** Returns the original regex pattern string. */
    public String getContentPatternSource() {
        return contentPatternSource;
    }

    /** Returns the original filename glob, or null. */
    public String getFilenameGlob() {
        return filenameGlob;
    }

    /**
     * Represents a single regex match within a file.
     */
    public static class FileContentMatch {
        private final FileModel file;
        private final int lineNumber;
        private final int columnNumber;
        private final String matchedText;

        public FileContentMatch(FileModel file, int lineNumber, int columnNumber, String matchedText) {
            this.file = file;
            this.lineNumber = lineNumber;
            this.columnNumber = columnNumber;
            this.matchedText = matchedText;
        }

        public FileModel getFile() {
            return file;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public int getColumnNumber() {
            return columnNumber;
        }

        public String getMatchedText() {
            return matchedText;
        }

        @Override
        public String toString() {
            return "FileContentMatch{" +
                    "file=" + (file != null ? file.getFilePath() : "null") +
                    ", line=" + lineNumber +
                    ", col=" + columnNumber +
                    ", matched='" + matchedText + '\'' +
                    '}';
        }
    }
}
