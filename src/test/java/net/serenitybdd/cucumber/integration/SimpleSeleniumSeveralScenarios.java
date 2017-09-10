package net.serenitybdd.cucumber.integration;

import cucumber.api.CucumberOptions;
import cucumber.runtime.CucumberWithSerenity;
import org.junit.runner.RunWith;


@RunWith(CucumberWithSerenity.class)
@CucumberOptions(features="src/test/resources/samples/web/aPassingBehaviorWithSeleniumAndSeveralScenarios.feature")
public class SimpleSeleniumSeveralScenarios {}
