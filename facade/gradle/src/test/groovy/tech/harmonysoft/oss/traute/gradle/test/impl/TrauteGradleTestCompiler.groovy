package tech.harmonysoft.oss.traute.gradle.test.impl

import groovy.io.FileType
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.jetbrains.annotations.NotNull
import tech.harmonysoft.oss.traute.common.settings.TrautePluginSettingsBuilder
import tech.harmonysoft.oss.traute.javac.TrauteJavacPlugin
import tech.harmonysoft.oss.traute.test.api.engine.TestCompiler
import tech.harmonysoft.oss.traute.test.api.model.ClassFile
import tech.harmonysoft.oss.traute.test.api.model.CompilationResult
import tech.harmonysoft.oss.traute.test.api.model.TestSource
import tech.harmonysoft.oss.traute.test.impl.model.ClassFileImpl
import tech.harmonysoft.oss.traute.test.impl.model.CompilationResultImpl

import java.nio.file.Files

class TrauteGradleTestCompiler implements TestCompiler {

    static final def INSTANCE = new TrauteGradleTestCompiler()

    static final def BUILD_GRADLE_CONTENT =
            """plugins {
              |    id 'tech.harmonysoft.oss.traute'
              |}
              |
              |sourceCompatibility = 1.8
              |
              |repositories {
              |    mavenCentral()
              |    maven { url 'https://maven.google.com' }
              |}
              |
              |traute {
              |    javacPluginSpec = ${getTrauteJavacDependencySpec()}
              |}
              |
              |dependencies {
              |    compile 'org.jetbrains:annotations:15.0'
              |    compile 'com.google.code.findbugs:jsr305:3.0.2'
              |    compile 'javax:javaee-api:8.0'
              |    compile 'findbugs:annotations:1.0.0'
              |    compile 'com.android.support:support-core-utils:26.1.0'
              |    compile 'org.eclipse.jdt:org.eclipse.jdt.annotation:2.1.0'
              |}""".stripMargin()

    private final def projectDirs = new WeakHashMap<CompilationResult, File>()

    @NotNull
    @Override
    CompilationResult compile(@NotNull TestSource testSource) {
        def projectRootDir = createRootDir()
        createBuildGradle(projectRootDir)

        def sourceRoot = createSourceRootDir(projectRootDir)
        def sourceFile = createFile(testSource.qualifiedClassName, sourceRoot)
        sourceFile.text = testSource.sourceText

        def pluginClasspathResource = getClass().classLoader.getResource("plugin-classpath.txt")
        if (pluginClasspathResource == null) {
            throw new IllegalStateException("Did not find plugin classpath resource, run `testClasses` build task.")
        }

        def pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }

        def additionalInfo = ['build.gradle': BUILD_GRADLE_CONTENT]
        try {
            def buildResult = GradleRunner.create()
                    .withProjectDir(projectRootDir)
                    .withPluginClasspath(pluginClasspath)
                    .withArguments('compileJava')
                    .withDebug(true)
                    .build()
            return new CompilationResultImpl(
                    { findBinaries(projectRootDir) },
                    buildResult.output,
                    testSource,
                    additionalInfo)
        } catch (UnexpectedBuildFailure e) {
            projectRootDir.deleteDir()
            return new CompilationResultImpl(
                    { throw new IllegalStateException(
                            "There are no binaries for failed compilation. Build output:\n\n${e.buildResult.output}")
                    },
                    e.buildResult.output,
                    testSource,
                    additionalInfo
            )
        }
    }

    @NotNull
    private static File createRootDir() {
        def result = Files.createTempDirectory("gradle-traute").toFile()
        if (!result.directory) {
            throw new IllegalStateException("Can't create a root directory for a test "
                    + "project at ${result.absolutePath}")
        }
        return result
    }

    private static void createBuildGradle(@NotNull File projectRootDir) {
        def file = new File(projectRootDir, 'build.gradle')
        file.text = BUILD_GRADLE_CONTENT
    }

    @NotNull
    private static File createSourceRootDir(@NotNull File projectRootDir) {
        def result = new File(projectRootDir, 'src/main/java')
        boolean created = result.mkdirs()
        if (!created) {
            throw new IllegalStateException("Can't create a source root directory for a "
                    + "test project at $result.absolutePath")
        }
        return result
    }

    @NotNull
    private static File createFile(@NotNull String qualifiedClassName, @NotNull File sourceRootDir) {
        def i = qualifiedClassName.lastIndexOf('.')
        if (i <= 0) {
            return new File(sourceRootDir, "${qualifiedClassName}.java")
        } else {
            def dir = new File(sourceRootDir, qualifiedClassName[0..i - 1].replace('.', '/'))
            def created = dir.mkdirs()
            if (!created) {
                throw new IllegalStateException("Can't create a directory for the source class "
                        + "$qualifiedClassName at ${dir.absolutePath}")
            }
            return new File(dir, qualifiedClassName.substring(i + 1) + ".java")
        }
    }

    @NotNull
    private static Collection<ClassFile> findBinaries(@NotNull File projectRoot) {
        def binariesRoot = new File(projectRoot, 'build/classes/java/main')
        if (!binariesRoot.isDirectory()) {
            return []
        }
        def result = []
        binariesRoot.eachFileRecurse(FileType.FILES) {
            def className = it.absolutePath.substring(binariesRoot.absolutePath.length())
            className = className[0..-7] // Strip '.class'
            if (className.startsWith('/')) {
                className = className.substring(1)
            }
            className = className.replace('/', '.')
            result << new ClassFileImpl(className, it.bytes)
        }
        return result
    }

    /**
     * We want to setup our test gradle project in a way to use javac traute plugin from the local project.
     * So, it's necessary to locate plugin classpath root(s) (roots in case of the IDE runs where there
     * are different roots for binaries and resources by default) and specify them as a
     * <a href="https://docs.gradle.org/current/userguide/working_with_files.html#sec:file_collections">FileCollection</a>
     *
     * @return
     */
    @NotNull
    private static String getTrauteJavacDependencySpec() {
        def roots = [].toSet()
        roots << findRootInClassPath(TrauteJavacPlugin)
        roots << findRootInClassPath(TrautePluginSettingsBuilder)
        roots << findRootInClassPath('META-INF/services/com.sun.source.util.Plugin')

        return "files(${roots.collect {"'$it'"}.join(',')})"
    }

    @NotNull
    private static String findRootInClassPath(@NotNull Class<?> anchor) {
        return findRootInClassPath(anchor.name.replace('.', '/') + '.class')
    }

    @NotNull
    private static String findRootInClassPath(@NotNull String anchor) {
        def url = TrauteGradleTestCompiler.classLoader.getResource(anchor)
        if (!url) {
            throw new IllegalStateException(
                    "Can't setup gradle test compiler - failed to find resource '$anchor' in classpath"
            )
        }

        def path = url.file
        if (!path) {
            throw new IllegalStateException(
                    "Can't setup gradle test compiler - failed to map classpath resource '$url' to a file"
            )
        }

        def result = path.substring(0, path.indexOf(anchor))
        if (result.endsWith('/')) {
            result = result[0..-2]
        }
        if (result.endsWith('!')) {
            result = result[0..-2]
        }
        return result
    }

    @Override
    void release(@NotNull CompilationResult result) {
        projectDirs[result]?.deleteDir()
    }
}