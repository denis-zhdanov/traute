package tech.harmonysoft.oss.traute.test.suite;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tech.harmonysoft.oss.traute.test.fixture.NN;

import javax.tools.JavaFileObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static tech.harmonysoft.oss.traute.test.util.TestConstants.CLASS_NAME;
import static tech.harmonysoft.oss.traute.test.util.TestConstants.PACKAGE;
import static tech.harmonysoft.oss.traute.test.util.TestUtil.prepareParameterTestSource;
import static tech.harmonysoft.oss.traute.test.util.TestUtil.prepareReturnTestSource;

public abstract class LoggingTest extends AbstractTrauteTest {

    @ParameterizedTest
    @CsvSource(value = { "true", "false" })
    public void verboseMode_instrumentation(boolean verbose) {
        settingsBuilder.withVerboseMode(verbose);
        String testSource = String.format(
                "package %s;\n" +
                "\n" +
                "import %s;\n" +
                "\n" +
                "public class %s {\n" +
                "\n" +
                "  @NotNull\n" +
                "  public Integer test(@NotNull Integer i1, @NotNull Integer i2) {\n" +
                "    return i1 + i2;\n" +
                "  }\n" +
                "\n" +
                "  @NotNull\n" +
                "  private Integer negative(@NotNull Integer i) {\n" +
                "      return -1 * i;\n" +
                "  }\n" +
                "\n" +
                "  public static void main(String[] args) {\n" +
                "    new Test().test(1, 2);\n" +
                "  }\n" +
                "}", PACKAGE, NotNull.class.getName(), CLASS_NAME);

        String[] targetParameterNames = {"i1", "i2", "i"};
        for (String name : targetParameterNames) {
            expectCompilationResult.withText(String.format("added a null-check for argument '%s'", name), verbose);
        }

        String methodPrefix = PACKAGE + "." + CLASS_NAME + ".";
        String[] targetMethods = {methodPrefix + "test()", methodPrefix + "negative()"};
        for (String method : targetMethods) {
            expectCompilationResult.withText("added a null-check for 'return' expression in method " + method,
                                             verbose);
        }

        doTest(testSource);
    }

    @Test
    public void verbose_classStats() {
        settingsBuilder.withVerboseMode(true);
        String testSource = String.format(
                "package %s;\n" +
                "\n" +
                "import %s;\n" +
                "\n" +
                "public class %s {\n" +
                "\n" +
                "  @NotNull\n" +
                "  public Integer test(@NotNull Integer i1, @NotNull Integer i2) {\n" +
                "    return i1 + i2;\n" +
                "  }\n" +
                "\n" +
                "  class Inner {\n" +
                "    @NotNull\n" +
                "    String inner(@NotNull String s) { return s + 1;}\n" +
                "  }\n" +
                "\n" +
                "  static class StaticInner {\n" +
                "    @NotNull\n" +
                "    String staticInner(@NotNull String s) { return s + 2;}\n" +
                "  }\n" +
                "\n" +
                "  public static void main(String[] args) {\n" +
                "    new Test().test(1, 2);\n" +
                "  }\n" +
                "}", PACKAGE, NotNull.class.getName(), CLASS_NAME);

        expectCompilationResult.withText(String.format(
                "added 7 instrumentations to the .*?/%s/%s%s - METHOD_PARAMETER: 4, METHOD_RETURN: 3",
                PACKAGE.replaceAll("\\.", "/"), CLASS_NAME, JavaFileObject.Kind.SOURCE.extension
        ));
        doCompile(testSource);
    }

    @Test
    public void customSetting_annotations() {
        settingsBuilder.withNotNullAnnotations(NN.class.getName());
        expectCompilationResult.withText(String.format("using the following NotNull annotations: \\[%s\\]",
                                                       NN.class.getName()));
        doCompile(prepareReturnTestSource("return 1;"));
    }

    @Test
    public void customSetting_verbose() {
        settingsBuilder.withVerboseMode(true);
        expectCompilationResult.withText("'verbose mode' is on");
        doCompile(prepareReturnTestSource("return 1;"));
    }

    @Test
    public void noReportForNonEnhancedSource() {
        settingsBuilder.withVerboseMode(true);
        expectCompilationResult.withText("added 0 instrumentation", false);
        doCompile(prepareParameterTestSource(null, "public void test() {}", ""));
    }

    @Test
    public void logFile() throws IOException {
        File logFile = Files.createTempFile("", "traute.log").toFile();
        settingsBuilder.withLogFile(logFile)
                       .withNotNullAnnotations(NN.class.getName());
        doCompile(prepareReturnTestSource("return 1;"));

        String logs = new String(Files.readAllBytes(logFile.toPath()));
        String toContain = String.format("using the following NotNull annotations: [%s]", NN.class.getName());
        assertTrue(logs.contains(toContain),
                   String.format("Expected text '%s' to contain '%s'", logs, toContain));
    }

    @Test
    public void innerClass() {
        settingsBuilder.withVerboseMode(true);
        String testSource = String.format(
                "package %s;\n" +
                "\n" +
                "import %s;\n" +
                "\n" +
                "public class %s {\n" +
                "\n" +
                "  class Inner {\n" +
                "    public void test(@NotNull Integer i) {\n" +
                "    }\n" +
                "  }\n" +
                "}", PACKAGE, NotNull.class.getName(), CLASS_NAME);

        expectCompilationResult.withText(String.format(
                "added a null-check for argument 'i' in the method %s.%s.Inner.test()",
                PACKAGE, CLASS_NAME
        ));
        doCompile(testSource);
    }

    @Test
    public void afterInnerClass() {
        settingsBuilder.withVerboseMode(true);
        String testSource = String.format(
                "package %s;\n" +
                "\n" +
                "import %s;\n" +
                "\n" +
                "public class %s {\n" +
                "\n" +
                "  class Inner {\n" +
                "  }\n" +
                "\n" +
                "  public void test(@NotNull Integer i) {\n" +
                "  }\n" +
                "}", PACKAGE, NotNull.class.getName(), CLASS_NAME);

        expectCompilationResult.withText(String.format(
                "added a null-check for argument 'i' in the method %s.%s.test()",
                PACKAGE, CLASS_NAME
        ));
        doCompile(testSource);
    }

    @Test
    public void anonymousClass() {
        settingsBuilder.withVerboseMode(true);
        String testSource = String.format(
                "package %s;\n" +
                "\n" +
                "import %s;\n" +
                "\n" +
                "public class %s {\n" +
                "\n" +
                "  public void test() {\n" +
                "    new Object() {\n" +
                "      public boolean equals(@NotNull Object o) {\n" +
                "        return false;\n" +
                "      }\n" +
                "    };\n" +
                "  }\n" +
                "}", PACKAGE, NotNull.class.getName(), CLASS_NAME);

        expectCompilationResult.withText(String.format(
                "added a null-check for argument 'o' in the method %s.%s\\$1.equals()",
                PACKAGE, CLASS_NAME
        ));
        doCompile(testSource);
    }
}
