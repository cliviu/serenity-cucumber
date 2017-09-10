package net.serenitybdd.cucumber.cli;

import cucumber.runtime.ClassFinder;
import cucumber.runtime.Runtime;
import cucumber.runtime.RuntimeOptions;
import cucumber.runtime.io.MultiLoader;
import cucumber.runtime.io.ResourceLoader;
import cucumber.runtime.io.ResourceLoaderClassFinder;
import cucumber.runtime.CucumberWithSerenity;
import cucumber.runtime.CucumberWithSerenityRuntime;

import java.io.IOException;
import java.util.Arrays;

public class Main {

    public static void main(String[] argv) throws Throwable {
        byte exitstatus = run(argv, Thread.currentThread().getContextClassLoader());
        System.exit(exitstatus);
    }

    public static byte run(String[] argv, ClassLoader classLoader) throws IOException {
        RuntimeOptions runtimeOptions = new RuntimeOptions(Arrays.asList(argv));
        ResourceLoader resourceLoader = new MultiLoader(classLoader);
        ClassFinder classFinder = new ResourceLoaderClassFinder(resourceLoader, classLoader);

        Runtime runtime =  CucumberWithSerenityRuntime.using(resourceLoader, classLoader, classFinder, runtimeOptions);

        CucumberWithSerenity.setRuntimeOptions(runtimeOptions);
        runtime.run();
        return runtime.exitStatus();
    }
}
