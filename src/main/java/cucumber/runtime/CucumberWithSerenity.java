package cucumber.runtime;

import com.google.common.base.Splitter;
import cucumber.api.junit.Cucumber;
import cucumber.runtime.formatter.SerenityReporter;
import cucumber.runtime.io.ResourceLoader;
import cucumber.runtime.io.ResourceLoaderClassFinder;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.core.webdriver.Configuration;
import org.junit.runners.model.InitializationError;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Glue code for running Cucumber via Serenity.
 * Sets up Serenity reporting and instrumentation.
 *
 * @author L.Carausu (liviu.carausu@gmail.com)
 */
public class CucumberWithSerenity extends Cucumber {

    private static ThreadLocal<RuntimeOptions> RUNTIME_OPTIONS = new ThreadLocal<>();

    public static void setRuntimeOptions(RuntimeOptions runtimeOptions) {
        RUNTIME_OPTIONS.set(runtimeOptions);
    }

    public CucumberWithSerenity(Class clazz) throws InitializationError, IOException
    {
        super(clazz);
        RuntimeOptionsFactory runtimeOptionsFactory = new RuntimeOptionsFactory(clazz);
        RUNTIME_OPTIONS.set(runtimeOptionsFactory.create());
    }

    public static RuntimeOptions currentRuntimeOptions() {
        return RUNTIME_OPTIONS.get();
    }

    /**
     * Create the Runtime. Sets the Serenity runtime.
     */
    @Override
    protected cucumber.runtime.Runtime createRuntime(ResourceLoader resourceLoader,
                                                     ClassLoader classLoader,
                                                     RuntimeOptions runtimeOptions) throws InitializationError, IOException {
        runtimeOptions.getTagFilters().addAll(environmentSpecifiedTags(runtimeOptions.getTagFilters()));
        RUNTIME_OPTIONS.set(runtimeOptions);
        System.out.println("XXX Created runtime " + runtimeOptions);
        return CucumberWithSerenityRuntime.using(resourceLoader, classLoader, runtimeOptions);
    }

    private Collection<String> environmentSpecifiedTags(List<? extends Object> existingTags) {
        EnvironmentVariables environmentVariables = Injectors.getInjector().getInstance(EnvironmentVariables.class);
        String tagsExpression = ThucydidesSystemProperty.TAGS.from(environmentVariables,"");
        List<String> existingTagsValues = existingTags.stream().map(Object::toString).collect(Collectors.toList());
        return Splitter.on(",").trimResults().omitEmptyStrings().splitToList(tagsExpression).stream()
                .map(this::toCucumberTag).filter(t -> !existingTagsValues.contains(t)).collect(Collectors.toList());
    }

    private String toCucumberTag(String from) {
        String tag = from.replaceAll(":","=");
        if (tag.startsWith("~@") || tag.startsWith("@")) { return tag; }
        if (tag.startsWith("~")) { return "~@" + tag.substring(1); }

        return "@" + tag;
    }

    public static Runtime createSerenityEnabledRuntime(ResourceLoader resourceLoader,
                                                       ClassLoader classLoader,
                                                       RuntimeOptions runtimeOptions,
                                                       Configuration systemConfiguration) {
        ClassFinder classFinder = new ResourceLoaderClassFinder(resourceLoader, classLoader);
        SerenityReporter reporter = new SerenityReporter(systemConfiguration);
        RUNTIME_OPTIONS.set(runtimeOptions);
        Runtime runtime = new Runtime(resourceLoader, classFinder, classLoader, runtimeOptions);
        //the order here is important, add plugin after the runtime is created
        runtimeOptions.addPlugin(reporter);
        return runtime;
    }

    public static List<String> getFeaturePaths() {
        return RUNTIME_OPTIONS.get().getFeaturePaths();
    }
}
