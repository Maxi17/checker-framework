package tests;

import java.io.File;
import java.util.List;
import org.checkerframework.framework.test.FrameworkPerDirectoryTest;
import org.junit.runners.Parameterized.Parameters;

/** Tests for the PurityChecker. */
public class PurityTest extends FrameworkPerDirectoryTest {

    /** @param testFiles the files containing test code, which will be type-checked */
    public PurityTest(List<File> testFiles) {
        super(
                testFiles,
                org.checkerframework.common.purity.PurityChecker.class,
                "purity",
                "-Anomsgtext");
    }

    @Parameters
    public static String[] getTestDirs() {
        return new String[] {"purity"};
    }
}
