package pro.sketchware.ai;

import android.content.Context;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import a.a.a.yq;

public final class GigaChatProjectTools {
    private static final long MAX_READ_BYTES = 200_000;
    private static final long MAX_WRITE_BYTES = 200_000;
    private static final int MAX_LISTED_FILES = 120;

    private final File projectRoot;
    private final File javaSourceRoot;
    private final File layoutRoot;
    private final File manifestFile;

    public GigaChatProjectTools(Context context, String projectId) throws IOException {
        yq project = new yq(context, projectId);
        projectRoot = new File(project.projectMyscPath).getCanonicalFile();
        javaSourceRoot = new File(project.javaFilesPath).getCanonicalFile();
        layoutRoot = new File(project.resDirectoryPath, "layout").getCanonicalFile();
        manifestFile = new File(project.androidManifestPath).getCanonicalFile();
    }

    public String listProjectFiles() throws IOException {
        ArrayDeque<File> pending = new ArrayDeque<>();
        List<String> paths = new ArrayList<>();
        pending.add(projectRoot);

        while (!pending.isEmpty() && paths.size() < MAX_LISTED_FILES) {
            File directory = pending.removeFirst();
            File[] children = directory.listFiles();
            if (children == null) continue;

            for (File child : children) {
                if (child.isDirectory()) {
                    if (!isIgnoredDirectory(child.getName()) && isWithin(child, projectRoot)) {
                        pending.addLast(child);
                    }
                } else if (isTextSource(child) && !isSensitiveFile(child) && isWithin(child, projectRoot)) {
                    paths.add(relativePath(child));
                    if (paths.size() >= MAX_LISTED_FILES) break;
                }
            }
        }
        return String.join("\n", paths);
    }

    public String readProjectFile(String relativePath) throws IOException {
        File file = resolveProjectFile(relativePath);
        if (!isTextSource(file) || isSensitiveFile(file)) {
            throw new IOException("This file type is not available to the assistant.");
        }
        if (!file.isFile()) throw new IOException("File does not exist: " + relativePath);
        if (file.length() > MAX_READ_BYTES) throw new IOException("File exceeds the 200 KB read limit.");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    public String writeProjectFile(String relativePath, String content) throws IOException {
        File file = resolveProjectFile(relativePath);
        if (!isWritableSource(file)) {
            throw new IOException("Writes are limited to project Java, layout XML, and the Android manifest.");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) throw new IOException("File exceeds the 200 KB write limit.");

        File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new IOException("Could not create the target directory.");
        }
        File canonicalParent = parent.getCanonicalFile();
        if (!isWithin(canonicalParent, projectRoot)) throw new IOException("Target directory is outside this project.");
        Files.write(file.toPath(), bytes);
        return "Updated " + relativePath + " (" + bytes.length + " bytes).";
    }

    private File resolveProjectFile(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank() || new File(relativePath).isAbsolute()) {
            throw new IOException("A relative project path is required.");
        }
        File file = new File(projectRoot, relativePath).getCanonicalFile();
        if (!isWithin(file, projectRoot)) throw new IOException("Path is outside this project.");
        return file;
    }

    private boolean isWritableSource(File file) throws IOException {
        if (isSensitiveFile(file)) return false;
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return (isWithin(file, javaSourceRoot) && (name.endsWith(".java") || name.endsWith(".kt")))
            || (isWithin(file, layoutRoot) && name.endsWith(".xml"))
            || file.equals(manifestFile);
    }

    private static boolean isWithin(File file, File root) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private String relativePath(File file) throws IOException {
        String rootPath = projectRoot.getCanonicalPath();
        return file.getCanonicalPath().substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private static boolean isIgnoredDirectory(String name) {
        return name.startsWith(".") || name.equals("bin") || name.equals("build") || name.equals("gen");
    }

    private static boolean isSensitiveFile(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith(".env")
                || name.equals("google-services.json")
                || name.equals("local.properties")
                || name.equals("key.properties")
                || name.equals("secrets.properties")
                || name.equals("secret.properties")
                || name.equals("credentials.json")
                || name.endsWith(".jks")
                || name.endsWith(".keystore");
    }

    private static boolean isTextSource(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".xml")
                || name.endsWith(".gradle") || name.endsWith(".properties") || name.endsWith(".pro")
                || name.endsWith(".json") || name.endsWith(".md") || name.endsWith(".txt");
    }
}