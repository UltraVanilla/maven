///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.github.jknack:handlebars:4.4.0
//DEPS com.google.code.gson:gson:2.13.1

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

import com.github.jknack.handlebars.Handlebars;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

class GithubJavaMagic {
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static State state;

    private static void buildProjects(final Path tmpDir, final Path pagesPath, final Configuration config)
            throws Exception {
        final var mavenRepositoryPath = Paths.get(System.getProperty("user.home")).resolve(".m2").resolve("repository");

        final var repositoryBackup = mavenRepositoryPath.resolveSibling(UUID.randomUUID().toString());

        Files.createDirectories(tmpDir);

        if (Files.exists(mavenRepositoryPath)) {
            Files.move(mavenRepositoryPath, repositoryBackup);
        }

        final String repositoryName = "release";
        final var ourRepositoryPath = pagesPath.resolve(repositoryName);
        Files.createDirectories(ourRepositoryPath);
        Files.move(ourRepositoryPath, mavenRepositoryPath);

        final var javadocPath = pagesPath.resolve("javadoc");

        for (final var repository : config.repositories()) {
            final var gitPath = tmpDir.resolve(UUID.randomUUID().toString());
            final var pb = new ProcessBuilder("git", "clone", repository.url().toString(), gitPath.toString())
                    .inheritIO();
            final var result = pb.start().waitFor();
            if (result != 0) {
                System.err.println("Git clone command did not succeed!");
                continue;
            }
            final var tags = getGitTags(gitPath);
            for (final var gradleProject : repository.gradleProjects()) {

                for (final var tag : tags) {
                    final var publishedMaven = new PublishedMaven(gradleProject.name(), tag);

                    if (state.publishedMavens().contains(publishedMaven)) {
                        continue;
                    }

                    final var pbCheckout = new ProcessBuilder("git", "checkout", tag)
                            .inheritIO();
                    pbCheckout.directory(new File(gitPath.toString()));
                    final var resultCheckout = pbCheckout.start().waitFor();
                    if (resultCheckout != 0) {
                        System.err.println("Git checkout command did not succeed!");
                        continue;
                    }

                    System.err.println("Building artifacts for %s %s...".formatted(gradleProject.name(), tag));
                    final var buildArgs = List.of("./gradlew", "publishToMavenLocal");
                    final var pbBuild = new ProcessBuilder(buildArgs)
                            .inheritIO();
                    pbBuild.directory(new File(gitPath.resolve(gradleProject.path()).toString()));
                    final var resultBuild = pbBuild.start().waitFor();
                    if (resultBuild != 0) {
                        System.err.println("Gradle build command did not succeed!");
                        continue;
                    }

                    state.publishedMavens().add(publishedMaven);

                    if (state.publishedJavadocs().stream().anyMatch(javadocState ->
                            javadocState.repository().equals(gradleProject.name()) &&
                            javadocState.tag().equals(tag)
                    )) {
                        continue;
                    }

                    System.err.println("Building javadocs for %s %s...".formatted(gradleProject.name(), tag));
                    final var javadocArgs = List.of("./gradlew", "javadoc");
                    final var pbJavadoc = new ProcessBuilder(javadocArgs)
                            .inheritIO();
                    pbJavadoc.directory(new File(gitPath.resolve(gradleProject.path()).toString()));
                    final var resultJavadoc = pbJavadoc.start().waitFor();
                    if (resultJavadoc != 0) {
                        System.err.println("Gradle javadoc command did not succeed!");
                        continue;
                    }

                    final var javadocSubPath = javadocPath.resolve(gradleProject.name()).resolve(tag);
                    Files.createDirectories(javadocSubPath);

                    final var foundJavadocDirs = findJavadocDirs(gitPath);

                    final var paths = new ArrayList<String>();

                    for (final var javadocDir : foundJavadocDirs) {
                        Files.createDirectories(javadocSubPath.resolve(javadocDir).getParent());
                        Files.move(gitPath.resolve(javadocDir), javadocSubPath.resolve(javadocDir));
                        paths.add(javadocDir.toString());
                    }

                    final var publishedJavadoc = new PublishedJavadoc(gradleProject.name(), tag, paths);
                    state.publishedJavadocs().add(publishedJavadoc);
                }
            }
        }

        Files.move(mavenRepositoryPath, ourRepositoryPath);
        if (Files.exists(repositoryBackup)) {
            Files.move(repositoryBackup, mavenRepositoryPath);
        }

        renameMavenMetadata(ourRepositoryPath);

        deleteFolder(tmpDir);
    }

    public static void main(String... args) throws Exception {
        final Configuration config = gson.fromJson(Files.readString(Paths.get("config.json")),
                Configuration.class);

        final var workingDir = Paths.get(System.getProperty("user.dir"));
        final var tmpDir = workingDir.resolve("tmp");
        final var pagesPath = Paths.get(args[0]).toAbsolutePath().normalize();
        final var stateFilePath = pagesPath.resolve("state.json");

        String stateFileContents;
        try {
            stateFileContents = Files.readString(stateFilePath, StandardCharsets.UTF_8);
        } catch(NoSuchFileException err) {
            stateFileContents = "{\"publishedJavadocs\":[],\"publishedMavens\":[]}";
        }

        state = gson.fromJson(stateFileContents, State.class);

        buildProjects(tmpDir, pagesPath, config);

        Files.writeString(pagesPath.resolve(stateFilePath), gson.toJson(state), StandardCharsets.UTF_8);

        final var templateContent = Files.readString(Paths.get("template.hbs"));

        final var handlebars = new Handlebars();
        final var template = handlebars.compileInline(templateContent);

        final Map<String, Object> context = Map.of(
                "state", state);

        final var output = template.apply(context);

        Files.writeString(pagesPath.resolve("index.html"), output, StandardCharsets.UTF_8);
    }

    private static List<String> getGitTags(Path path) throws Exception {
        final var pb = new ProcessBuilder("git", "tag", "--list");
        pb.directory(new File(path.toString()));
        pb.redirectErrorStream(true);
        final var process = pb.start();

        final var tags = new ArrayList<String>();

        try (final var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("v"))
                    tags.add(line);
            }
        }

        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git tag --list failed with exit code " + exitCode);
        }

        return tags;
    }

    private static String getShortCommitHash(Path path) {
        try {
            final var pb = new ProcessBuilder("git", "rev-parse", "--short", "HEAD");
            pb.redirectErrorStream(true);
            pb.directory(new File(path.toString()));
            final var process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                final var line = reader.readLine();
                final int exitCode = process.waitFor();
                if (exitCode == 0 && line != null) {
                    return line.trim();
                } else {
                    throw new RuntimeException("Git command failed with exit code " + exitCode);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get git commit hash", e);
        }
    }

    private static void deleteFolder(Path path) throws IOException {
        try (var dirStream = Files.walk(path)) {
            dirStream
                    .map(Path::toFile)
                    .sorted(Comparator.reverseOrder())
                    .forEach(File::delete);
        }
    }

    private static void renameMavenMetadata(Path rootDir) {
        try (Stream<Path> paths = Files.walk(rootDir)) {
            paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("maven-metadata-local.xml")).forEach(path -> {
                        Path target = path.resolveSibling("maven-metadata.xml");
                        try {
                            Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Path> findJavadocDirs(Path rootDir) throws IOException {
        final var result = new ArrayList<Path>();

        Files.walkFileTree(rootDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("index.html")) {
                    result.add(rootDir.relativize(file).getParent());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return result;
    }
}

record Configuration(List<RepositoryConfiguration> repositories) { }

record RepositoryConfiguration(URL url, List<GradleProject> gradleProjects) { }

record GradleProject(String name, String path) { }

record State(List<PublishedJavadoc> publishedJavadocs, List<PublishedMaven> publishedMavens) {}

record PublishedJavadoc(String repository, String tag, List<String> paths) {}

record PublishedMaven(String repository, String tag) {}
