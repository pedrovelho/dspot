package fr.inria.diversify.dspot.dynamic;

import fr.inria.diversify.buildSystem.DiversifyClassLoader;
import fr.inria.diversify.buildSystem.android.InvalidSdkException;
import fr.inria.diversify.dspot.Amplification;
import fr.inria.diversify.dspot.assertGenerator.AssertGenerator;
import fr.inria.diversify.dspot.DSpotUtils;
import fr.inria.diversify.dspot.TestClassMinimisation;
import fr.inria.diversify.dspot.amp.*;
import fr.inria.diversify.dspot.value.ValueFactory;
import fr.inria.diversify.testRunner.TestRunner;
import fr.inria.diversify.factories.DiversityCompiler;
import fr.inria.diversify.runner.InputConfiguration;
import fr.inria.diversify.runner.InputProgram;
import fr.inria.diversify.util.FileUtils;
import fr.inria.diversify.util.InitUtils;
import fr.inria.diversify.util.Log;
import fr.inria.diversify.util.PrintClassUtils;
import spoon.reflect.declaration.CtType;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.stream.Collectors;

/**
 * User: Simon
 * Date: 24/03/16
 * Time: 15:40
 */
public class TestGeneratorMain {
    protected final InputProgram inputProgram;
    protected DiversityCompiler compiler;
    protected DiversifyClassLoader applicationClassLoader;
    protected DiversifyClassLoader applicationWithBranchLoggerClassLoader;
    protected ValueFactory valueFactory;
    protected String branchDir;
    protected File resultDir;

    public TestGeneratorMain(InputConfiguration inputConfiguration) throws InvalidSdkException, Exception {
        InitUtils.initLogLevel(inputConfiguration);

        inputProgram = InitUtils.initInputProgram(inputConfiguration);
        InitUtils.initDependency(inputConfiguration);

        resultDir = new File(inputConfiguration.getProperty("result"));

//        String outputDirectory = inputConfiguration.getProperty("tmpDir") + "/tmp_" + System.currentTimeMillis();
        String outputDirectory = inputConfiguration.getProperty("tmpDir") + "/tmp_grobid";

//        FileUtils.copyDirectory(new File(inputProgram.getProgramDir()), new File(outputDirectory));
//        inputProgram.setProgramDir(outputDirectory);

//        branchDir = addBranchLogger(inputConfiguration);
        branchDir = inputConfiguration.getProperty("tmpDir") + "/tmp_branch";
        inputProgram.setProgramDir(branchDir);
        InitUtils.addApplicationClassesToClassPath(inputProgram);
        applicationWithBranchLoggerClassLoader = DSpotUtils.initClassLoader(inputProgram, inputConfiguration);

        inputProgram.setProgramDir(outputDirectory);
        compiler = DSpotUtils.initDiversityCompiler(inputProgram, false);

//        String mavenHome = inputConfiguration.getProperty("maven.home",null);
//        String mavenLocalRepository = inputConfiguration.getProperty("maven.localRepository",null);
//        DSpotUtils.compile(inputProgram, mavenHome, mavenLocalRepository);
        InitUtils.addApplicationClassesToClassPath(inputProgram);
        applicationClassLoader = DSpotUtils.initClassLoader(inputProgram, inputConfiguration);

    }

    protected String addBranchLogger(InputConfiguration inputConfiguration) throws IOException, InterruptedException {
        String programDir = inputProgram.getProgramDir();
        String outputDirectory = inputConfiguration.getProperty("tmpDir") + "/tmp_branchLogger" + System.currentTimeMillis();
        FileUtils.copyDirectory(new File(inputProgram.getProgramDir()), new File(outputDirectory));
        inputProgram.setProgramDir(outputDirectory);
        DSpotUtils.addBranchLogger(inputProgram);

        String mavenHome = inputConfiguration.getProperty("maven.home",null);
        String mavenLocalRepository = inputConfiguration.getProperty("maven.localRepository",null);
        DSpotUtils.compileTests(inputProgram, mavenHome, mavenLocalRepository);

        applicationWithBranchLoggerClassLoader = DSpotUtils.initClassLoader(inputProgram, inputConfiguration);
        inputProgram.setProgramDir(programDir);

        return outputDirectory;
    }

    public void testGenerator(String logDir) throws IOException {
        TestRunner testRunnerWithBranchLogger = new TestRunner(inputProgram, applicationWithBranchLoggerClassLoader, compiler);
        TestRunner testRunner = new TestRunner(inputProgram, applicationClassLoader, compiler);

        valueFactory = new ValueFactory(inputProgram, logDir);
        TestClassMinimisation testClassMinimisation = new TestClassMinimisation(inputProgram,testRunnerWithBranchLogger, branchDir + "/log");

        TestGenerator testGenerator = new TestGenerator(inputProgram, testRunner, valueFactory, testClassMinimisation);
        Collection<CtType> testClasses = testGenerator.generateTestClasses(logDir);

        int count = testClasses.stream()
                .mapToInt(test -> test.getMethods().size())
                .sum();
        Log.debug("nb test before amplification: {}", count);

        Set<CtType> ampTests = testClasses.stream()
                .flatMap(test -> amplificationTestClass(test).stream())
                .collect(Collectors.toSet());

        count = testClasses.stream()
                .mapToInt(test -> test.getMethods().size())
                .sum();
        Log.debug("nb test after amplification: {}", count);

        testClasses.addAll(ampTests);
        testClasses = addAssert(testClasses);

        if(!resultDir.exists()) {
            resultDir.mkdirs();
        }
        for(CtType test : testClasses) {
            PrintClassUtils.printJavaFile(resultDir, test);
        }
    }

    public Set<CtType> amplificationTestClass(CtType testClass) {
        Amplification testAmplification = new Amplification(inputProgram, compiler, applicationWithBranchLoggerClassLoader, initAmplifiers(), new File(branchDir + "/log"));

        try {
            return testAmplification.amplification(testClass, 3).stream()
                    .peek(test -> testClass.addMethod(test))
                    .map(test -> test.getDeclaringType())
                .collect(Collectors.toSet());
        } catch (Exception e) {
            e.printStackTrace();
            return new HashSet<>();
        }
    }

    protected List<AbstractAmp> initAmplifiers() {
        List<AbstractAmp> amplifiers = new ArrayList<>();

        amplifiers.add(new TestDataMutator());
        amplifiers.add(new TestMethodCallAdder());
        amplifiers.add(new TestMethodCallRemover());
        amplifiers.add(new StatementAdd(inputProgram.getFactory(), valueFactory, "org.grobid"));

        return amplifiers;
    }


    protected Collection<CtType> addAssert(Collection<CtType> testClasses) {
        AssertGenerator assertGenerator = new AssertGenerator(inputProgram, compiler, applicationClassLoader);

        return testClasses.stream()
                .map(test -> {
                    try {
                        return assertGenerator.generateAsserts(test);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return null;
                    }
                })
                .filter(test -> test != null)
                .filter(test -> !test.getMethods().isEmpty())
                .collect(Collectors.toList());

//        for(CtType test : testClasses) {
//            PrintClassUtils.printJavaFile(resultDir, test);
//        }
    }

    public void clean() throws IOException {
        FileUtils.forceDelete(compiler.getSourceOutputDirectory());
        FileUtils.forceDelete(compiler.getBinaryOutputDirectory());
    }

    protected static void kill() throws IOException {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        Runtime r = Runtime.getRuntime();
        r.exec("kill "+pid);
    }

    public static void main(String[] args) throws Exception, InvalidSdkException {
        InputConfiguration inputConfiguration = new InputConfiguration(args[0]);
        TestGeneratorMain testGeneratorMain = new TestGeneratorMain(inputConfiguration);
        testGeneratorMain.testGenerator(args[1]);
        testGeneratorMain.clean();
        kill();
    }
}
