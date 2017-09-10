package net.serenitybdd.cucumber.integration;

import cucumber.api.CucumberOptions;
import cucumber.runtime.CucumberWithSerenity;
import org.junit.runner.RunWith;


@RunWith(CucumberWithSerenity.class)
@CucumberOptions(features="src/test/resources/samples/simple_tagged_pending_scenario.feature")
public class SimpleTaggedPendingScenario {}
