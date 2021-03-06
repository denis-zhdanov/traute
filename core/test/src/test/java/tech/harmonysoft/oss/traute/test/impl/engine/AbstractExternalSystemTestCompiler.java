package tech.harmonysoft.oss.traute.test.impl.engine;

import org.jetbrains.annotations.NotNull;
import tech.harmonysoft.oss.traute.common.instrumentation.InstrumentationType;
import tech.harmonysoft.oss.traute.common.settings.TrautePluginSettings;
import tech.harmonysoft.oss.traute.test.api.engine.TestCompiler;
import tech.harmonysoft.oss.traute.test.api.model.ClassFile;
import tech.harmonysoft.oss.traute.test.api.model.CompilationResult;
import tech.harmonysoft.oss.traute.test.api.model.TestSource;
import tech.harmonysoft.oss.traute.test.impl.model.ClassFileImpl;
import tech.harmonysoft.oss.traute.test.impl.model.CompilationResultImpl;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

import static java.util.stream.Collectors.joining;
import static tech.harmonysoft.oss.traute.common.instrumentation.InstrumentationType.METHOD_PARAMETER;
import static tech.harmonysoft.oss.traute.common.instrumentation.InstrumentationType.METHOD_RETURN;
import static tech.harmonysoft.oss.traute.common.settings.TrautePluginSettingsBuilder.*;
import static tech.harmonysoft.oss.traute.common.util.TrauteConstants.*;

public abstract class AbstractExternalSystemTestCompiler implements TestCompiler {

    private final Map<CompilationResult, File> projectDirs = new WeakHashMap<>();

    @Override
    @NotNull
    public CompilationResult compile(@NotNull Collection<TestSource> testSources,
                                     @NotNull TrautePluginSettings settings)
    {
        try {
            return doCompile(testSources, settings);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private CompilationResult doCompile(@NotNull Collection<TestSource> testSources,
                                        @NotNull TrautePluginSettings settings)
            throws Exception
    {
        File projectRootDir = createRootDir();
        File externalSystemConfig = createExternalSystemConfig(projectRootDir, settings);
        File sourceRoot = createSourceRootDir(projectRootDir, getRelativeSrcPath());
        for (TestSource testSource : testSources) {
            File sourceFile = createFile(testSource.getQualifiedClassName(), sourceRoot);
            write(sourceFile, testSource.getSourceText());
        }

        String output = compile(projectRootDir);
        CompilationResultImpl result = new CompilationResultImpl(
                () -> findBinaries(projectRootDir, getRelativeBinariesPath()),
                output,
                testSources,
                Collections.singletonMap(externalSystemConfig.getName(), new String(read(externalSystemConfig)))
        );
        projectDirs.put(result, projectRootDir);
        return result;
    }

    @NotNull
    protected abstract File createExternalSystemConfig(@NotNull File projectRootDir,
                                                       @NotNull TrautePluginSettings settings)
            throws IOException;

    @NotNull
    protected abstract String getRelativeSrcPath();

    @NotNull
    protected abstract String compile(@NotNull File projectRootDir) throws Exception;

    @NotNull
    protected abstract String getRelativeBinariesPath();

    @Override
    public void release(@NotNull CompilationResult result) {
        File rootDir = projectDirs.get(result);
        if (rootDir.isDirectory()) {
            delete(rootDir);
        }
    }

    @NotNull
    private static File createRootDir() throws IOException {
        File result = Files.createTempDirectory("maven-traute").toFile();
        if (!result.isDirectory()) {
            throw new IllegalStateException("Can't create a root directory for a test project at "
                                            + result.getAbsolutePath());
        }
        return result;
    }

    @NotNull
    private static File createSourceRootDir(@NotNull File projectRootDir, @NotNull String relativeSrcPath) {
        File result = new File(projectRootDir, relativeSrcPath);
        boolean created = result.mkdirs();
        if (!created) {
            throw new IllegalStateException("Can't create a source root directory for a test project at "
                                            + result.getAbsolutePath());
        }
        return result;
    }

    @NotNull
    private static File createFile(@NotNull String qualifiedClassName, @NotNull File sourceRootDir) {
        int i = qualifiedClassName.lastIndexOf('.');
        if (i <= 0) {
            return new File(sourceRootDir, qualifiedClassName + ".java");
        } else {
            File dir = new File(sourceRootDir, qualifiedClassName.substring(0, i)
                                                                 .replace('.', '/'));
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                if (!created) {
                    throw new IllegalStateException(String.format("Can't create a directory for the source class %s at %s",
                                                                  qualifiedClassName, dir.getAbsolutePath()));
                }
            }
            return new File(dir, qualifiedClassName.substring(i + 1) + ".java");
        }
    }

    protected static void write(@NotNull File file, @NotNull String content) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(content);
        }
    }

    @NotNull
    public static byte[] read(@NotNull File file) throws IOException {
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            while ((read = in.read(buffer)) >= 0) {
                bOut.write(buffer, 0, read);
            }
        }
        return bOut.toByteArray();
    }

    @NotNull
    private static Collection<ClassFile> findBinaries(@NotNull File projectRoot, @NotNull String relativeBinariesPath) {
        try {
            return doFindBinaries(projectRoot, relativeBinariesPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private static Collection<ClassFile> doFindBinaries(@NotNull File projectRoot, @NotNull String relativeBinariesPath)
            throws IOException
    {
        File binariesRoot = new File(projectRoot, relativeBinariesPath);
        if (!binariesRoot.isDirectory()) {
            return Collections.emptyList();
        }
        List<ClassFile> result = new ArrayList<>();
        Stack<File> toProcess = new Stack<>();
        toProcess.push(binariesRoot);
        while (!toProcess.isEmpty()) {
            File file = toProcess.pop();
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        toProcess.push(child);
                    }
                }
                continue;
            }

            String className = file.getAbsolutePath().substring(binariesRoot.getAbsolutePath().length());
            className = className.substring(0, className.length() - ".class".length());
            if (className.startsWith("/")) {
                className = className.substring(1);
            }
            className = className.replace('/', '.');
            result.add(new ClassFileImpl(className, read(file)));
        }
        return result;
    }

    private void delete(@NotNull File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        boolean deleted = file.delete();
        if (!deleted) {
            throw new RuntimeException("Can't remove file system entry " + file.getAbsolutePath());
        }
    }

    @NotNull
    protected static Collection<String> getCompilerArgs(@NotNull TrautePluginSettings settings) {
        List<String> result = new ArrayList<>();

        Set<String> notNullAnnotations = settings.getNotNullAnnotations();
        if (!notNullAnnotations.isEmpty() && !DEFAULT_NOT_NULL_ANNOTATIONS.equals(notNullAnnotations))
        {
            String notNullAnnotationsString = notNullAnnotations.stream().collect(joining(SEPARATOR));
            result.add(String.format("-A%s=%s", OPTION_ANNOTATIONS_NOT_NULL, notNullAnnotationsString));
        }

        Set<String> nullableAnnotations = settings.getNullableAnnotations();
        if (!nullableAnnotations.isEmpty() && !DEFAULT_NULLABLE_ANNOTATIONS.equals(nullableAnnotations))
        {
            String annotationsString = nullableAnnotations.stream().collect(joining(SEPARATOR));
            result.add(String.format("-A%s=%s", OPTION_ANNOTATIONS_NULLABLE, annotationsString));
        }

        Set<InstrumentationType> instrumentationsToApply = settings.getInstrumentationsToApply();
        if (!instrumentationsToApply.isEmpty() && !DEFAULT_INSTRUMENTATIONS_TO_APPLY.equals(instrumentationsToApply)) {
            String instrumentationsString = instrumentationsToApply.stream()
                                                                   .map(InstrumentationType::getShortName)
                                                                   .collect(joining(SEPARATOR));
            result.add(String.format("-A%s=%s", OPTION_INSTRUMENTATIONS_TO_USE, instrumentationsString));
        }

        Set<String> parametersNotNullByDefaultAnnotations = settings.getNotNullByDefaultAnnotations(METHOD_PARAMETER);
        if (!parametersNotNullByDefaultAnnotations.isEmpty()
            && !DEFAULT_PARAMETERS_NOT_NULL_BY_DEFAULT_ANNOTATIONS.equals(parametersNotNullByDefaultAnnotations))
        {
            String annotationsString = parametersNotNullByDefaultAnnotations.stream()
                                                                            .collect(joining(SEPARATOR));
            result.add(String.format("-A%s%s=%s",
                                     OPTION_PREFIX_ANNOTATIONS_NOT_NULL_BY_DEFAULT,
                                     METHOD_PARAMETER.getShortName(),
                                     annotationsString));
        }

        Set<String> returnNotNullByDefaultAnnotations = settings.getNotNullByDefaultAnnotations(METHOD_RETURN);
        if (!returnNotNullByDefaultAnnotations.isEmpty()
            && !DEFAULT_RETURN_NOT_NULL_BY_DEFAULT_ANNOTATIONS.equals(returnNotNullByDefaultAnnotations))
        {
            String annotationsString = returnNotNullByDefaultAnnotations.stream().collect(joining(SEPARATOR));
            result.add(String.format("-A%s%s=%s",
                                     OPTION_PREFIX_ANNOTATIONS_NOT_NULL_BY_DEFAULT,
                                     METHOD_RETURN.getShortName(),
                                     annotationsString));
        }

        if (settings.isVerboseMode()) {
            result.add(String.format("-A%s=true", OPTION_LOG_VERBOSE));
        }

        settings.getLogFile().ifPresent(
                file -> result.add(String.format("-A%s=%s", OPTION_LOG_FILE, file.getAbsolutePath()))
        );

        settings.getExceptionsToThrow()
                .forEach((key, value) -> result.add(String.format("-A%s%s=%s",
                                                                  OPTION_PREFIX_EXCEPTION_TO_THROW,
                                                                  key.getShortName(),
                                                                  value)));
        settings.getExceptionTextPatterns()
                .forEach((key, value) -> result.add(String.format("-A%s%s=%s",
                                                                  OPTION_PREFIX_EXCEPTION_TEXT,
                                                                  key.getShortName(),
                                                                  value)));
        return result;
    }
}
