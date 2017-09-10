package net.serenitybdd.cucumber.integration;

import cucumber.api.CucumberOptions;
import cucumber.runtime.CucumberWithSerenity;
import org.junit.runner.RunWith;

/**
 * Created by john on 23/07/2014.
 */
@RunWith(CucumberWithSerenity.class)
@CucumberOptions(features="src/test/resources/samples/calculator/basic_arithmetic_with_tables_and_background.feature")
public class BasicArithemticWithTablesAndBackgroundScenario {}
