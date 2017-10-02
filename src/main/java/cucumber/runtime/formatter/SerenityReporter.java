package cucumber.runtime.formatter;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import cucumber.api.Result;
import cucumber.api.TestCase;
import cucumber.api.TestStep;
import cucumber.api.event.EventHandler;
import cucumber.api.event.EventPublisher;
import cucumber.api.event.TestCaseFinished;
import cucumber.api.event.TestCaseStarted;
import cucumber.api.event.TestRunFinished;
import cucumber.api.event.TestRunStarted;
import cucumber.api.event.TestSourceRead;
import cucumber.api.event.TestStepFinished;
import cucumber.api.event.TestStepStarted;
import cucumber.api.event.WriteEvent;
import cucumber.api.formatter.Formatter;
import cucumber.runner.PickleTestStep;
import gherkin.ast.Background;
import gherkin.ast.Examples;
import gherkin.ast.Feature;
import gherkin.ast.Scenario;
import gherkin.ast.ScenarioDefinition;
import gherkin.ast.ScenarioOutline;
import gherkin.ast.Step;
import gherkin.ast.TableCell;
import gherkin.ast.TableRow;
import gherkin.ast.Tag;
import gherkin.pickles.Argument;
import gherkin.pickles.PickleCell;
import gherkin.pickles.PickleRow;
import gherkin.pickles.PickleString;
import gherkin.pickles.PickleTable;
import gherkin.pickles.PickleTag;
import net.serenitybdd.core.Serenity;
import net.serenitybdd.core.SerenityListeners;
import net.serenitybdd.core.SerenityReports;
import net.serenitybdd.cucumber.model.FeatureFileContents;
import net.thucydides.core.model.DataTable;
import net.thucydides.core.model.Story;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.model.TestResult;
import net.thucydides.core.model.TestTag;
import net.thucydides.core.model.stacktrace.RootCauseAnalyzer;
import net.thucydides.core.reports.ReportService;
import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.ExecutedStepDescription;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.core.steps.StepFailure;
import net.thucydides.core.util.Inflector;
import net.thucydides.core.webdriver.Configuration;
import net.thucydides.core.webdriver.ThucydidesWebDriverSupport;
import org.junit.internal.AssumptionViolatedException;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.stream.Collectors;

import static cucumber.runtime.formatter.TaggedScenario.isIgnored;
import static cucumber.runtime.formatter.TaggedScenario.isManual;
import static cucumber.runtime.formatter.TaggedScenario.isPending;
import static cucumber.runtime.formatter.TaggedScenario.isSkippedOrWIP;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

/**
 * Generates Thucydides reports.
 *
 * @author L.Carausu (liviu.carausu@gmail.com)
 */
public class SerenityReporter implements Formatter {

    private static final String OPEN_PARAM_CHAR = "\uff5f";
    private static final String CLOSE_PARAM_CHAR = "\uff60";

    private static final String SCENARIO_OUTLINE_NOT_KNOWN_YET = "";

    private final Queue<Step> stepQueue;
    private final Queue<TestStep> testStepQueue;

    private Configuration systemConfiguration;

    private ThreadLocal<SerenityListeners> thucydidesListenersThreadLocal;

    private final List<BaseStepListener> baseStepListeners;

    private Feature currentFeature;

    private int currentExample = 0;

    private boolean examplesRunning;

    private List<Map<String, String>> exampleRows;

    private int exampleCount = 0;

    private DataTable table;

    private boolean waitingToProcessBackgroundSteps = false;

    private String currentUri;

    private String defaultFeatureName;
    private String defaultFeatureId;


    private final static String FEATURES_ROOT_PATH = "features";

    private Optional<TestResult> forcedStoryResult = Optional.absent();
    private Optional<TestResult> forcedScenarioResult = Optional.absent();

    private FeatureFileContents featureFileContents;

    private String currentFeatureFile;
    private List<Map<String, Object>> featureMaps = new ArrayList<Map<String, Object>>();
    private Map<String, Object> currentTestCaseMap;
    private final TestSourcesModel testSources = new TestSourcesModel();

    private EventHandler<TestSourceRead> testSourceReadHandler = new EventHandler<TestSourceRead>() {
        @Override
        public void receive(TestSourceRead event) {
            handleTestSourceRead(event);
        }
    };
    private EventHandler<TestCaseStarted> caseStartedHandler= new EventHandler<TestCaseStarted>() {
        @Override
        public void receive(TestCaseStarted event) {
            handleTestCaseStarted(event);
        }
    };
    private EventHandler<TestCaseFinished> caseFinishedHandler= new EventHandler<TestCaseFinished>() {
        @Override
        public void receive(TestCaseFinished event) {
            handleTestCaseFinished(event);
        }
    };
    private EventHandler<TestStepStarted> stepStartedHandler = new EventHandler<TestStepStarted>() {
        @Override
        public void receive(TestStepStarted event) {
            handleTestStepStarted(event);
        }
    };
    private EventHandler<TestStepFinished> stepFinishedHandler = new EventHandler<TestStepFinished>() {
        @Override
        public void receive(TestStepFinished event) {
            handleTestStepFinished(event);
        }
    };

    private EventHandler<TestRunStarted> runStartedHandler = new EventHandler<TestRunStarted>() {
        @Override
        public void receive(TestRunStarted event) {
            handleTestRunStarted(event);
        }
    };

    private void handleTestRunStarted(TestRunStarted event)
    {
    }

    private EventHandler<TestRunFinished> runFinishedHandler = new EventHandler<TestRunFinished>() {
        @Override
        public void receive(TestRunFinished event) {
            handleTestRunFinished(event);
        }
    };
    private EventHandler<WriteEvent> writeEventHandler = new EventHandler<WriteEvent>() {
        @Override
        public void receive(WriteEvent event) {
            handleWrite(event);
        }
    };

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestSourceRead.class, testSourceReadHandler);
        publisher.registerHandlerFor(TestRunStarted.class, runStartedHandler);
        publisher.registerHandlerFor(TestRunFinished.class, runFinishedHandler);
        publisher.registerHandlerFor(TestCaseStarted.class, caseStartedHandler);
        publisher.registerHandlerFor(TestCaseFinished.class, caseFinishedHandler);
        publisher.registerHandlerFor(TestStepStarted.class, stepStartedHandler);
        publisher.registerHandlerFor(TestStepFinished.class, stepFinishedHandler);
        publisher.registerHandlerFor(WriteEvent.class, writeEventHandler);
    }

    private void handleTestSourceRead(TestSourceRead event) {
        testSources.addTestSourceReadEvent(event.uri, event);
        String uri = event.uri;
        currentUri = uri;
        String featuresRoot = File.separatorChar + FEATURES_ROOT_PATH + File.separatorChar;
        if (uri.contains(featuresRoot)) {
            currentUri = uri.substring(uri.lastIndexOf(featuresRoot) + FEATURES_ROOT_PATH.length() + 2);
        }
        defaultFeatureId = new File(currentUri).getName().replace(".feature", "");
        defaultFeatureName = Inflector.getInstance().humanize(defaultFeatureId);
        featureFileContents = new FeatureFileContents(uri);

        Feature feature = testSources.getFeature(event.uri);
        assureTestSuiteFinished();
        if (feature.getName().isEmpty()) {
            feature = featureWithDefaultName(feature, defaultFeatureName, defaultFeatureId);
        }

        currentFeature = feature;
        clearStoryResult();

        configureDriver(feature);
        getThucydidesListeners();
        Story userStory = Story.withIdAndPath(TestSourcesModel.convertToId(feature.getName()), feature.getName(), currentUri).asFeature();

        if (!isEmpty(feature.getDescription())) {
            userStory = userStory.withNarrative(feature.getDescription());
        }
        StepEventBus.getEventBus().testSuiteStarted(userStory);

        checkForPending(feature);
        checkForSkipped(feature);
        checkForIgnored(feature);
        checkForManual(feature);

    }

    private void handleTestCaseStarted(TestCaseStarted event) {
        if (currentFeatureFile == null || !currentFeatureFile.equals(event.testCase.getUri())) {
            currentFeatureFile = event.testCase.getUri();
            Map<String, Object> currentFeatureMap = createFeatureMap(event.testCase);
            featureMaps.add(currentFeatureMap);
        }
        currentTestCaseMap = createTestCase(event.testCase);
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, event.testCase.getLine());
        if (astNode != null) {
            ScenarioDefinition scenarioDefinition = TestSourcesModel.getScenarioDefinition(astNode);
            Feature currentFeature = testSources.getFeature(event.testCase.getUri());
            //the sources are read in parallel, current feature cannot be used
            String scenarioId = scenarioIdFrom(currentFeature.getName(),TestSourcesModel.convertToId(scenarioDefinition.getName()));
            boolean newScenario = !scenarioId.equals(currentScenario);
            if(newScenario) {
                if (scenarioDefinition instanceof Scenario) {
                    configureDriver(currentFeature);
                    clearScenarioResult();
                } else if (scenarioDefinition instanceof ScenarioOutline) {
                    examplesRunning = true;
                    former_scenarioOutline((ScenarioOutline) scenarioDefinition);
                    examples(currentFeature.getName(),scenarioDefinition.getName(),((ScenarioOutline)scenarioDefinition).getExamples());
                }
                startOfScenarioLifeCycle(currentFeature,scenarioDefinition);
                currentScenario = scenarioIdFrom(currentFeature.getName(),TestSourcesModel.convertToId(scenarioDefinition.getName()));
            } else {
                if (scenarioDefinition instanceof ScenarioOutline) {
                    startExample();
                }
            }
            Background background = TestSourcesModel.getBackgoundForTestCase(astNode);
            if(background != null) {
                handleBackground(background);
            }
        }
    }

    private void handleTestCaseFinished(TestCaseFinished event) {
        if(examplesRunning) {
            handleResult(event.result);
        }
        updatePendingResults();
        updateSkippedResults();
        StepEventBus.getEventBus().testFinished();
        stepQueue.clear();
        if (examplesRunning) {
            finishExample();
        }
    }

    private Map<String, Object> createFeatureMap(TestCase testCase) {
        Map<String, Object> featureMap = new HashMap<String, Object>();
        featureMap.put("uri", testCase.getUri());
        featureMap.put("elements", new ArrayList<Map<String, Object>>());
        Feature feature = testSources.getFeature(testCase.getUri());
        if (feature != null) {
            featureMap.put("keyword", feature.getKeyword());
            featureMap.put("name", feature.getName());
            featureMap.put("description", feature.getDescription() != null ? feature.getDescription() : "");
            featureMap.put("line", feature.getLocation().getLine());
            featureMap.put("id", TestSourcesModel.convertToId(feature.getName()));
        }
        return featureMap;
    }

    private Map<String, Object> createTestCase(TestCase testCase) {
        Map<String, Object> testCaseMap = new HashMap<String, Object>();
        testCaseMap.put("name", testCase.getName());
        testCaseMap.put("line", testCase.getLine());
        testCaseMap.put("type", "scenario");
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, testCase.getLine());
        if (astNode != null) {
            testCaseMap.put("id", TestSourcesModel.calculateId(astNode));
            ScenarioDefinition scenarioDefinition = TestSourcesModel.getScenarioDefinition(astNode);
            testCaseMap.put("keyword", scenarioDefinition.getKeyword());
            testCaseMap.put("description", scenarioDefinition.getDescription() != null ? scenarioDefinition.getDescription() : "");
            testCaseMap.put("scenarioDefinition", scenarioDefinition);
        }
        testCaseMap.put("steps", new ArrayList<Map<String, Object>>());
        if (!testCase.getTags().isEmpty()) {
            List<Map<String, Object>> tagList = new ArrayList<Map<String, Object>>();
            for (PickleTag tag : testCase.getTags()) {
                Map<String, Object> tagMap = new HashMap<String, Object>();
                tagMap.put("name", tag.getName());
                tagList.add(tagMap);
            }
            testCaseMap.put("tags", tagList);
        }
        return testCaseMap;
    }

    private Map<String, Object> createTestStep(TestStep testStep) {
        Map<String, Object> stepMap = new HashMap<String, Object>();
        stepMap.put("name", testStep.getStepText());
        stepMap.put("line", testStep.getStepLine());
        if (!testStep.getStepArgument().isEmpty()) {
            Argument argument = testStep.getStepArgument().get(0);
            if (argument instanceof PickleString) {
                stepMap.put("doc_string", createDocStringMap(argument));
            } else if (argument instanceof PickleTable) {
                stepMap.put("rows", createDataTableList(argument));
            }
        }
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, testStep.getStepLine());
        if (astNode != null) {
            Step step = (Step) astNode.node;
            stepMap.put("keyword", step.getKeyword());
        }

        return stepMap;
    }

    private Map<String, Object> createDocStringMap(Argument argument) {
        Map<String, Object> docStringMap = new HashMap<String, Object>();
        PickleString docString = ((PickleString)argument);
        docStringMap.put("value", docString.getContent());
        docStringMap.put("line", docString.getLocation().getLine());
        return docStringMap;
    }

    private List<Map<String, Object>> createDataTableList(Argument argument) {
        List<Map<String, Object>> rowList = new ArrayList<Map<String, Object>>();
        for (PickleRow row : ((PickleTable)argument).getRows()) {
            Map<String, Object> rowMap = new HashMap<String, Object>();
            rowMap.put("cells", createCellList(row));
            rowList.add(rowMap);
        }
        return rowList;
    }

    private List<String> createCellList(PickleRow row) {
        List<String> cells = new ArrayList<>();
        for (PickleCell cell : row.getCells()) {
            cells.add(cell.getValue());
        }
        return cells;
    }

    private void handleTestStepStarted(TestStepStarted event)
    {
        if(event.testStep instanceof PickleTestStep) {
            TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, event.testStep.getStepLine());
            if (astNode != null) {
                Step step = (Step) astNode.node;
                if (!addingScenarioOutlineSteps) {
                    stepQueue.add(step);
                    testStepQueue.add(event.testStep);
                }
                Step currentStep = stepQueue.peek();
                String stepTitle = stepTitleFrom(currentStep, event.testStep);
                StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(stepTitle));
                StepEventBus.getEventBus().updateCurrentStepTitle(normalized(stepTitle));
            }
        }
    }

    private void handleWrite(WriteEvent event) {
        StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(event.text));
    }

    private void handleTestStepFinished(TestStepFinished event)
    {
        if(event.testStep instanceof PickleTestStep) {
            handleResult(event.result);
        }
    }

    private void handleTestRunFinished(TestRunFinished event)
    {
        ScenarioDefinition scenarioDefinition = (ScenarioDefinition)currentTestCaseMap.get("scenarioDefinition");
        checkForLifecycleTags(scenarioDefinition);
        updateTestResultsFromTags();
        if (examplesRunning) {
            finishExample();
        } else {
            generateReports();
        }
        assureTestSuiteFinished();
    }

    private void clearStoryResult() {
        forcedStoryResult = Optional.absent();
    }

    private void clearScenarioResult() {
        forcedScenarioResult = Optional.absent();
    }

    private boolean isPendingStory() {
        return ((forcedStoryResult.or(TestResult.UNDEFINED) == TestResult.PENDING)
                || (forcedScenarioResult.or(TestResult.UNDEFINED) == TestResult.PENDING));
    }

    private boolean isSkippedStory() {
        return ((forcedStoryResult.or(TestResult.UNDEFINED) == TestResult.SKIPPED)
                || (forcedScenarioResult.or(TestResult.UNDEFINED) == TestResult.SKIPPED));
    }

    public SerenityReporter(Configuration systemConfiguration) {
        this.systemConfiguration = systemConfiguration;
        this.stepQueue = new LinkedList<>();
        this.testStepQueue = new LinkedList<>();
        thucydidesListenersThreadLocal = new ThreadLocal<>();
        baseStepListeners = Lists.newArrayList();
        clearStoryResult();
    }

    protected SerenityListeners getThucydidesListeners() {
        if (thucydidesListenersThreadLocal.get() == null) {
            SerenityListeners listeners = SerenityReports.setupListeners(systemConfiguration);
            thucydidesListenersThreadLocal.set(listeners);
            synchronized (baseStepListeners) {
                baseStepListeners.add(listeners.getBaseStepListener());
            }
        }
        return thucydidesListenersThreadLocal.get();
    }

    protected ReportService getReportService() {
        return SerenityReports.getReportService(systemConfiguration);
    }


    FeatureFileContents featureFileContents() {
        return new FeatureFileContents(currentUri);
    }

    private Feature featureWithDefaultName(Feature feature, String defaultName, String id) {
        return feature; //TODO
        /*return new Feature(feature.getComments(),
                feature.getTags(),
                feature.getKeyword(),
                defaultName,
                feature.getDescription(),
                feature.getLine(),
                id);*/
    }

    private void checkForPending(Feature feature) {
        if (isPending(feature.getTags())) {
            forcedStoryResult = Optional.of(TestResult.PENDING);
        }
    }

    private void checkForSkipped(Feature feature) {
        if (isSkippedOrWIP(feature.getTags())) {
            forcedStoryResult = Optional.of(TestResult.SKIPPED);
        }
    }

    private void checkForIgnored(Feature feature) {
        if (isIgnored(feature.getTags())) {
            forcedStoryResult = Optional.of(TestResult.IGNORED);
        }
    }

    private void checkForPendingScenario(List<Tag> tags) {
        if (isPending(tags)) {
            forcedScenarioResult = Optional.of(TestResult.PENDING);
        }
    }

    private void checkForSkippedScenario(List<Tag> tags) {
        if (isSkippedOrWIP(tags)) {
            forcedScenarioResult = Optional.of(TestResult.SKIPPED);
        }
    }

    private void checkForIgnoredScenario(List<Tag> tags) {
        if (isIgnored(tags)) {
            forcedScenarioResult = Optional.of(TestResult.IGNORED);
        }
    }

    private void checkForManual(Feature feature) {
        if (isManual(feature.getTags())) {
            forcedStoryResult = Optional.of(TestResult.SKIPPED);
            StepEventBus.getEventBus().testIsManual();
            StepEventBus.getEventBus().suspendTest(TestResult.SKIPPED);
        }
    }

    private void checkForManualScenario(List<Tag> tags) {
        if (isManual(tags)) {
            forcedScenarioResult = Optional.of(TestResult.SKIPPED);
            StepEventBus.getEventBus().testIsManual();
            StepEventBus.getEventBus().suspendTest(TestResult.SKIPPED);
        }
    }
    

    private void configureDriver(Feature feature) {
        StepEventBus.getEventBus().setUniqueSession(systemConfiguration.shouldUseAUniqueBrowser());

        List<String> tags = getTagNamesFrom(feature.getTags());

        String requestedDriver = getDriverFrom(tags);
        if (isNotEmpty(requestedDriver)) {
            ThucydidesWebDriverSupport.useDefaultDriver(requestedDriver);
        }
    }

    private List<String> getTagNamesFrom(List<Tag> tags) {
        List<String> tagNames = Lists.newArrayList();
        for (Tag tag : tags) {
            tagNames.add(tag.getName());

        }
        return tagNames;
    }

    private String getDriverFrom(List<String> tags) {
        String requestedDriver = null;
        for (String tag : tags) {
            if (tag.startsWith("@driver:")) {
                requestedDriver = tag.substring(8);
            }
        }
        return requestedDriver;
    }

    boolean addingScenarioOutlineSteps = false;

    int scenarioOutlineStartsAt;
    int scenarioOutlineEndsAt;


    public void former_scenarioOutline(ScenarioOutline scenarioOutline) {
        addingScenarioOutlineSteps = true;
        scenarioOutlineStartsAt = scenarioOutline.getLocation().getLine();
    }

    String currentScenarioId;


    private void examples(String featureName,String id,List<Examples> examplesList) {
        /*String scenarioOutline = featureFileContents.betweenLine(scenarioOutlineStartsAt)
                .and(examples.getLine() - 1);*/
        //scenarioOutlineEndsAt = examples.getLine() - 1;

        addingScenarioOutlineSteps = false;
        reinitializeExamples();
        for (Examples examples : examplesList) {
            List<TableRow> examplesTableRows = examples.getTableBody();
            List<String> headers = getHeadersFrom(examples.getTableHeader());
            List<Map<String, String>> rows = getValuesFrom(examplesTableRows, headers);
            for (int i = 0; i < examplesTableRows.size(); i++) {
                addRow(exampleRows, headers, examplesTableRows.get(i));
            }
            String scenarioId = scenarioIdFrom(featureName, id);
            boolean newScenario = !scenarioId.equals(currentScenarioId);
            table = (newScenario) ?
                    thucydidesTableFrom(SCENARIO_OUTLINE_NOT_KNOWN_YET, headers, rows, trim(examples.getName()), trim(examples.getDescription()))
                    : addTableRowsTo(table, headers, rows, trim(examples.getName()), trim(examples.getDescription()));
            exampleCount = table.getSize();
            currentScenarioId = scenarioId;
        }
    }

    private List<String> getHeadersFrom(TableRow headerRow) {
        return headerRow.getCells().stream().map(TableCell::getValue).collect(Collectors.toList());
    }

    private List<Map<String, String>> getValuesFrom(List<TableRow> examplesTableRows, List<String> headers) {

        List<Map<String, String>> rows = Lists.newArrayList();
        for (int row = 0; row < examplesTableRows.size(); row++) {
            Map<String, String> rowValues = Maps.newLinkedHashMap();
            int column = 0;
            List<String> cells = examplesTableRows.get(row).getCells().stream().map(TableCell::getValue).collect(Collectors.toList());
            for (String cellValue : cells) {
                String columnName = headers.get(column++);
                rowValues.put(columnName, cellValue);
            }
            rows.add(rowValues);
        }
        return rows;
    }

    private void addRow(List<Map<String, String>> exampleRows,
                        List<String> headers,
                        TableRow currentTableRow) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int j = 0; j < headers.size(); j++) {
            List<String> cells = currentTableRow.getCells().stream().map(TableCell::getValue).collect(Collectors.toList());
            row.put(headers.get(j), cells.get(j));
        }
        exampleRows.add(row);
    }

    private String scenarioIdFrom(String featureId,String scenarioIdOrExampleId) {
        return (featureId != null && scenarioIdOrExampleId != null) ? String.format("%s;%s", featureId, scenarioIdOrExampleId) : "";
    }

    private void reinitializeExamples() {
        examplesRunning = true;
        currentExample = 0;
        exampleRows = new ArrayList<>();
    }

    private DataTable thucydidesTableFrom(String scenarioOutline,
                                          List<String> headers,
                                          List<Map<String, String>> rows,
                                          String name,
                                          String description) {
        return DataTable.withHeaders(headers).andScenarioOutline(scenarioOutline).andMappedRows(rows).andTitle(name).andDescription(description).build();
    }

    private DataTable addTableRowsTo(DataTable table, List<String> headers,
                                     List<Map<String, String>> rows,
                                     String name,
                                     String description) {
        table.startNewDataSet(name, description);
        for (Map<String, String> row : rows) {
            table.appendRow(rowValuesFrom(headers, row));
        }
        return table;
    }

    private Map<String, String> rowValuesFrom(List<String> headers, Map<String, String> row) {
        Map<String, String> rowValues = Maps.newHashMap();
        for (String header : headers) {
            rowValues.put(header, row.get(header));
        }
        return ImmutableMap.copyOf(rowValues);
    }

    String currentScenario;

    private void startOfScenarioLifeCycle(Feature currentFeature,ScenarioDefinition scenario) {

        boolean newScenario = !scenarioIdFrom(TestSourcesModel.convertToId(currentFeature.getName()),TestSourcesModel.convertToId(scenario.getName())).equals(currentScenario);
        currentScenario = scenarioIdFrom(TestSourcesModel.convertToId(currentFeature.getName()),TestSourcesModel.convertToId(scenario.getName()));
        if (examplesRunning) {
            if (newScenario) {
                startScenario(currentFeature,scenario);
                StepEventBus.getEventBus().useExamplesFrom(table);
            } else {
                StepEventBus.getEventBus().addNewExamplesFrom(table);
            }
            startExample();
        } else {
            startScenario(currentFeature,scenario);
        }
    }

    private void startScenario(Feature currentFeature,ScenarioDefinition scenarioDefinition) {
        clearScenarioResult();
        StepEventBus.getEventBus().setTestSource(StepEventBus.TEST_SOURCE_CUCUMBER);
        StepEventBus.getEventBus().testStarted(scenarioDefinition.getName(),scenarioIdFrom(TestSourcesModel.convertToId(currentFeature.getName()), TestSourcesModel.convertToId(scenarioDefinition.getName())));
        StepEventBus.getEventBus().addDescriptionToCurrentTest(scenarioDefinition.getDescription());
        StepEventBus.getEventBus().addTagsToCurrentTest(convertCucumberTags(currentFeature.getTags()));
        if(isScenario(scenarioDefinition)) {
            StepEventBus.getEventBus().addTagsToCurrentTest(convertCucumberTags(((Scenario) scenarioDefinition).getTags()));
        }
        else if(isScenarioOutline(scenarioDefinition)) {
            StepEventBus.getEventBus().addTagsToCurrentTest(convertCucumberTags(((ScenarioOutline)scenarioDefinition).getTags()));
        }

        registerFeatureJiraIssues(currentFeature.getTags());
        if(isScenario(scenarioDefinition)) {
            registerScenarioJiraIssues(((Scenario) scenarioDefinition).getTags());
        }
        else if(isScenarioOutline(scenarioDefinition)) {
            registerScenarioJiraIssues(((ScenarioOutline)scenarioDefinition).getTags());
        }

        checkForLifecycleTags(scenarioDefinition);
        updateTestResultsFromTags();
    }

    private boolean isScenario(ScenarioDefinition scenarioDefinition) {
        return scenarioDefinition instanceof Scenario;
    }

    private boolean isScenarioOutline(ScenarioDefinition scenarioDefinition) {
        return scenarioDefinition instanceof ScenarioOutline;
    }

    private void checkForLifecycleTags(ScenarioDefinition scenarioDefinition) {
        checkForSkipped(currentFeature);
        checkForIgnored(currentFeature);
        checkForPending(currentFeature);
        checkForManual(currentFeature);
        List<Tag> tags = new ArrayList<>();
        if(isScenario(scenarioDefinition)) {
            tags = ((Scenario)scenarioDefinition).getTags();
        } else if(isScenarioOutline(scenarioDefinition)) {
            tags = ((ScenarioOutline)scenarioDefinition).getTags();
        }
        checkForPendingScenario(tags);
        checkForSkippedScenario(tags);
        checkForIgnoredScenario(tags);
        checkForManualScenario(tags);
    }

    private Optional<TestResult> forcedResult() {
        return forcedStoryResult.or(forcedScenarioResult);
    }

    private void updateTestResultsFromTags() {
        if (!forcedResult().isPresent()) {
            return;
        }
        switch (forcedResult().get()) {
            case PENDING:
                StepEventBus.getEventBus().suspendTest(TestResult.PENDING);
                return;
            case SKIPPED:
                StepEventBus.getEventBus().suspendTest(TestResult.SKIPPED);
                return;
            case IGNORED:
                StepEventBus.getEventBus().suspendTest(TestResult.IGNORED);
                return;
            case COMPROMISED:
                StepEventBus.getEventBus().suspendTest(TestResult.COMPROMISED);
                return;
        }
    }

    private void registerFeatureJiraIssues(List<Tag> tags) {
        List<String> issues = extractJiraIssueTags(tags);
        if (!issues.isEmpty()) {
            StepEventBus.getEventBus().addIssuesToCurrentStory(issues);
        }
    }

    private void registerScenarioJiraIssues(List<Tag> tags) {
        List<String> issues = extractJiraIssueTags(tags);
        if (!issues.isEmpty()) {
            StepEventBus.getEventBus().addIssuesToCurrentTest(issues);
        }
    }

    private List<TestTag> convertCucumberTags(List<Tag> cucumberTags) {
        List<TestTag> tags = Lists.newArrayList();
        for (Tag tag : cucumberTags) {
            tags.add(TestTag.withValue(tag.getName().substring(1)));
        }
        return ImmutableList.copyOf(tags);
    }

    private List<String> extractJiraIssueTags(List<Tag> cucumberTags) {
        List<String> issues = Lists.newArrayList();
        for (Tag tag : cucumberTags) {
            if (tag.getName().startsWith("@issue:")) {
                String tagIssueValue = tag.getName().substring("@issue:".length());
                issues.add(tagIssueValue);
            }
            if (tag.getName().startsWith("@issues:")) {
                String tagIssuesValues = tag.getName().substring("@issues:".length());
                issues.addAll(Arrays.asList(tagIssuesValues.split(",")));
            }
        }
        return issues;
    }

    private void startExample() {
        Map<String, String> data = exampleRows.get(currentExample);
        StepEventBus.getEventBus().clearStepFailures();
        StepEventBus.getEventBus().exampleStarted(data);
        currentExample++;
    }

    private void finishExample() {
        StepEventBus.getEventBus().exampleFinished();
        exampleCount--;

        if (exampleCount == 0) {
            examplesRunning = false;

            //TODO
            String scenarioOutline = featureFileContents().trimmedContent()
                    .betweenLine(scenarioOutlineStartsAt)
                    .and(scenarioOutlineEndsAt);
            System.out.println("XXXSnarioOutline " + scenarioOutline + " " + scenarioOutlineStartsAt + "-- " + scenarioOutlineEndsAt);
            table.setScenarioOutline(scenarioOutline);
            generateReports();
        } else {
            examplesRunning = true;
        }
    }

    private void handleBackground(Background background) {
        waitingToProcessBackgroundSteps = true;
        String backgroundName = background.getName();
        if(backgroundName != null) {
            StepEventBus.getEventBus().setBackgroundTitle(backgroundName);
        }
        String backgroundDescription = background.getDescription();
        if(backgroundDescription == null) {
            backgroundDescription = "";
        }
        StepEventBus.getEventBus().setBackgroundDescription(backgroundDescription);
    }

    private void assureTestSuiteFinished() {
        if (currentFeature != null) {
            stepQueue.clear();
            testStepQueue.clear();
            StepEventBus.getEventBus().testSuiteFinished();
            StepEventBus.getEventBus().clear();
            Serenity.done();
            table = null;
            currentScenarioId = null;
        }
    }

    private void handleResult(Result result) {
        Step currentStep = stepQueue.poll();
        TestStep currentTestStep = testStepQueue.poll();
        if (Result.Type.PASSED.equals(result.getStatus())) {
            StepEventBus.getEventBus().stepFinished();
        } else if (Result.Type.FAILED.equals(result.getStatus())) {
            failed(stepTitleFrom(currentStep,currentTestStep), result.getError());
        } else if (Result.Type.SKIPPED.equals(result.getStatus())) {
            StepEventBus.getEventBus().stepIgnored();
        } else if (Result.Type.PENDING.equals(result.getStatus())) {
            StepEventBus.getEventBus().stepPending();
        } else if (Result.Type.UNDEFINED.equals(result.getStatus())) {
            //not needed anymore
            //StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(stepTitleFrom(currentStep, currentTestStep)));
            StepEventBus.getEventBus().stepPending();
        }

        if (stepQueue.isEmpty()) {
            if (waitingToProcessBackgroundSteps) {
                waitingToProcessBackgroundSteps = false;
            } else {
                updatePendingResults();
                updateSkippedResults();
            }
        }
    }

    private void updatePendingResults() {
        if (isPendingStory()) {
            StepEventBus.getEventBus().setAllStepsTo(TestResult.PENDING);
        }
    }

    private void updateSkippedResults() {
        if (isSkippedStory()) {
            StepEventBus.getEventBus().setAllStepsTo(TestResult.SKIPPED);
        }
    }

    private void failed(String stepTitle, Throwable cause) {
        if (!errorOrFailureRecordedForStep(stepTitle, cause)) {
            StepEventBus.getEventBus().updateCurrentStepTitle(stepTitle);
            Throwable rootCause = new RootCauseAnalyzer(cause).getRootCause().toException();
            if (isAssumptionFailure(rootCause)) {
                StepEventBus.getEventBus().assumptionViolated(rootCause.getMessage());
            } else {
                StepEventBus.getEventBus().stepFailed(new StepFailure(ExecutedStepDescription.withTitle(normalized(stepTitle)), rootCause));
            }
        }
    }

    private boolean errorOrFailureRecordedForStep(String stepTitle, Throwable cause) {
        if (!latestTestOutcome().isPresent()) {
            return false;
        }
        if (!latestTestOutcome().get().testStepWithDescription(stepTitle).isPresent()) {
            return false;
        }
        Optional<net.thucydides.core.model.TestStep> matchingTestStep = latestTestOutcome().get().testStepWithDescription(stepTitle);
        if (matchingTestStep.isPresent() && matchingTestStep.get().getException() != null) {
            return (matchingTestStep.get().getException().getOriginalCause() == cause);
        }

        return false;
    }

    private Optional<TestOutcome> latestTestOutcome() {
        List<TestOutcome> recordedOutcomes = StepEventBus.getEventBus().getBaseStepListener().getTestOutcomes();
        return (recordedOutcomes.isEmpty()) ? Optional.<TestOutcome>absent()
                : Optional.of(recordedOutcomes.get(recordedOutcomes.size() - 1));
    }

    private boolean isAssumptionFailure(Throwable rootCause) {
        return (AssumptionViolatedException.class.isAssignableFrom(rootCause.getClass()));
    }

    private String stepTitleFrom(Step currentStep,TestStep testStep) {

        if(currentStep != null)
            return currentStep.getKeyword()
                    //TODO get Name
                    + testStep.getPickleStep().getText()
                    + embeddedTableDataIn(testStep);
        return "";
    }

    private String embeddedTableDataIn(TestStep currentStep) {
        if (!currentStep.getStepArgument().isEmpty()) {
            Argument argument = currentStep.getStepArgument().get(0);
            if (argument instanceof PickleTable) {
                List<Map<String, Object>> rowList = new ArrayList<Map<String, Object>>();
                for (PickleRow row : ((PickleTable)argument).getRows()) {
                    Map<String, Object> rowMap = new HashMap<String, Object>();
                    rowMap.put("cells", createCellList(row));
                    rowList.add(rowMap);
                }
                return convertToTextTable(rowList);
            }
        }
        return "";
    }

    private String convertToTextTable(List<Map<String, Object>> rows) {
        StringBuilder textTable = new StringBuilder();
        textTable.append(System.lineSeparator());
        for (Map<String,Object> row : rows) {
            textTable.append("|");
            for (String cell : (List<String>)row.get("cells")) {
                textTable.append(" ");
                textTable.append(cell);
                textTable.append(" |");
            }
            if (row != rows.get(rows.size() - 1)) {
                textTable.append(System.lineSeparator());
            }
        }
        return textTable.toString();
    }

    private  void generateReports() {
        getReportService().generateReportsFor(getAllTestOutcomes());
    }

    public List<TestOutcome> getAllTestOutcomes() {
        return baseStepListeners.stream().map(BaseStepListener::getTestOutcomes).flatMap(List::stream)
                .collect(Collectors.toList());
    }

    private String normalized(String value) {
        return value.replaceAll(OPEN_PARAM_CHAR, "{").replaceAll(CLOSE_PARAM_CHAR, "}");
    }

    private String trim(String stringToBeTrimmed)
    {
        return (stringToBeTrimmed == null) ? stringToBeTrimmed : stringToBeTrimmed.trim();
    }
}
