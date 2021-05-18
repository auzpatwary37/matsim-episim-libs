package org.matsim.run;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.EpisimModule;
import org.matsim.episim.EpisimRunner;
import org.matsim.episim.TracingConfigGroup;
import org.matsim.episim.policy.FixedPolicy;
import org.matsim.run.modules.SnzBerlinProductionScenario;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Parameterized.class)
public class RunEpisimSnapshotTest {

	@Rule
	public MatsimTestUtils utils = new MatsimTestUtils();
	private Config config;
	private EpisimConfigGroup episimConfig;
	private EpisimRunner runner;

	@Parameterized.Parameter
	public TracingConfigGroup.Strategy strategy;

	@Parameterized.Parameter(1)
	public String model;

	@Parameterized.Parameters(name = "tracing-{0}-{1}")
	public static Collection<Object[]> parameters() {
		List<Object[]> args = new ArrayList<>(Arrays.asList(new Object[][]{
				{TracingConfigGroup.Strategy.INDIVIDUAL_ONLY, "bln"},
				{TracingConfigGroup.Strategy.LOCATION_WITH_TESTING, "bln"},
				{TracingConfigGroup.Strategy.IDENTIFY_SOURCE, "bln"}
		}));

		if (Files.exists(RunSnzIntegrationTest.INPUT) && Files.isDirectory(RunSnzIntegrationTest.INPUT)) {
			args.add(new Object[]{
					TracingConfigGroup.Strategy.INDIVIDUAL_ONLY, "snz"
			});
		}

		return args;
	}

	private String snapshotName() {
		return String.format("episim-snapshot-%03d-%s.zip", 15, episimConfig.getStartDate().plusDays(14).toString());
	}

	@Before
	public void setup() {
		OutputDirectoryLogging.catchLogEntries();

		AbstractModule testScenario = model.equals("snz") ? new RunSnzIntegrationTest.SnzTestScenario(utils,
				SnzBerlinProductionScenario.Restrictions.onlyEdu) : new RunEpisimIntegrationTest.TestScenario(utils);

		Injector injector = Guice.createInjector(Modules.override(new EpisimModule()).with(testScenario));

		config = injector.getInstance(Config.class);
		episimConfig = injector.getInstance(EpisimConfigGroup.class);
		runner = injector.getInstance(EpisimRunner.class);
		episimConfig.setPolicyConfig(FixedPolicy.config()
				.shutdown(1, "freight")
				.restrict(6, 0.2, "leisure", "edu", "business")
				.restrict(6, 0.2, "work", "other")
				.restrict(6, 0.3, "shop", "errands")
				.build()
		);

		TracingConfigGroup tracingConfig = injector.getInstance(TracingConfigGroup.class);
		tracingConfig.setPutTraceablePersonsInQuarantineAfterDay(5);
		tracingConfig.setTracingProbability(0.75);
		tracingConfig.setEquipmentRate(0.75);
		tracingConfig.setStrategy(strategy);
		tracingConfig.setLocationThreshold(1);

	}

	@Test
	public void compareSnapshots() {

		episimConfig.setSnapshotInterval(15);
		runner.run(30);

		setup();

		String fromSnapshot = utils.getOutputDirectory().replace(utils.getMethodName(), "fromSnapshot");
		episimConfig.setStartFromSnapshot(utils.getOutputDirectory() + snapshotName());
		config.controler().setOutputDirectory(fromSnapshot);

		runner.run(30);

		for (File file : Objects.requireNonNull(new File(utils.getOutputDirectory()).listFiles())) {

			if (file.isDirectory() || file.getName().endsWith(".zip") || file.getName().endsWith(".xml") || file.getName().endsWith(".gz")
					|| file.getName().endsWith("cputime.tsv")) continue;

			assertThat(file)
					.hasSameTextualContentAs(new File(fromSnapshot, file.getName()));

			// check event files
			if (file.getName().equals("events")) {
				for (File event : Objects.requireNonNull(file.listFiles())) {
					assertThat(event)
							.hasSameBinaryContentAs(new File(fromSnapshot, "events/" + event.getName()));
				}
			}

		}

	}

	@Test
	@Ignore("Snapshot file not checked into git because of its size")
	public void fixedSnapshot() {

		episimConfig.setStartFromSnapshot(utils.getInputDirectory() + snapshotName());
		runner.run(30);

		RunEpisimIntegrationTest.assertSimulationOutput(utils);
	}
}
