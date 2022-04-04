package org.matsim.run.batch;

import com.google.inject.AbstractModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.BatchRun;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.VirusStrainConfigGroup;
import org.matsim.episim.BatchRun.Parameter;
import org.matsim.episim.model.VirusStrain;
import org.matsim.episim.policy.FixedPolicy;
import org.matsim.episim.policy.FixedPolicy.ConfigBuilder;
import org.matsim.run.RunParallel;
import org.matsim.run.batch.CologneStrainBatch.Params;
import org.matsim.run.modules.AbstractSnzScenario2020;
import org.matsim.run.modules.CologneStrainScenario;
import org.matsim.run.modules.SnzBerlinProductionScenario;

import javax.annotation.Nullable;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;


/**
 * Strain paper
 */
public class StrainPaper implements BatchRun<StrainPaper.Params> {

	@Override
	public CologneStrainScenario getBindings(int id, @Nullable Params params) {
		return new CologneStrainScenario( 1.0);
	}

	@Override
	public BatchRun.Metadata getMetadata() {
		return BatchRun.Metadata.of("cologne", "strain");
	}

//	@Override
//	public int getOffset() {
//		return 1500;
//	}

	@Nullable
	@Override	
	public Config prepareConfig(int id, Params params) {

		CologneStrainScenario scenario = getBindings(id, params);

		Config config = scenario.config();
		config.global().setRandomSeed(params.seed);

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		episimConfig.setCalibrationParameter(1.1293077849372072e-05);

		ConfigBuilder builder = FixedPolicy.parse(episimConfig.getPolicy());

		builder.clearAfter("2020-12-14");

		for (String act : AbstractSnzScenario2020.DEFAULT_ACTIVITIES) {
//			if (act.contains("educ_higher")) continue;
			builder.restrict("2020-12-15", params.activityLevel, act);
		}

		//schools
		if (params.schools.equals("50%open")) {
			builder.clearAfter( "2020-12-14", "educ_primary", "educ_secondary", "educ_tertiary", "educ_other", "educ_kiga");
			builder.restrict("2020-12-15", .5, "educ_primary", "educ_secondary", "educ_tertiary", "educ_other", "educ_kiga");
		}

		if (params.schools.equals("open")) {
			builder.clearAfter( "2020-12-14", "educ_primary", "educ_secondary", "educ_tertiary", "educ_other", "educ_kiga");
			builder.restrict("2020-12-15", 1.0, "educ_primary", "educ_secondary", "educ_tertiary", "educ_other", "educ_kiga");
		}

		episimConfig.setPolicy(builder.build());
		
		{
			Map<LocalDate, Double> outdoorFractionOld = episimConfig.getLeisureOutdoorFraction();
			Map<LocalDate, Double> outdoorFractionNew = new HashMap<LocalDate, Double>();
			
			for (Entry<LocalDate, Double> entry : outdoorFractionOld.entrySet()) {
				if (entry.getKey().isBefore(LocalDate.parse("2021-01-01")))
						outdoorFractionNew.put(entry.getKey(), entry.getValue());
			}
			
			episimConfig.setLeisureOutdoorFraction(outdoorFractionNew);
		}


		VirusStrainConfigGroup virusStrainConfigGroup = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class);

		virusStrainConfigGroup.getOrAddParams(VirusStrain.ALPHA).setInfectiousness(params.b117inf);

		return config;
	}

	public static final class Params {

		@GenerateSeeds(10)
		public long seed;

//		@StringParameter({"50%open", "open", "activityLevel"})
		@StringParameter({"activityLevel"})
		public String schools;

		@StringParameter({"2020-12-15"})
		String b117date;

		@Parameter({1.2, 1.5, 1.8, 2.1, 2.4})
		double b117inf;

		@Parameter({0.47, 0.57, 0.67, 0.77, 0.87})
		double activityLevel;

	}

	public static void main(String[] args) {
		String[] args2 = {
				RunParallel.OPTION_SETUP, StrainPaper.class.getName(),
				RunParallel.OPTION_PARAMS, Params.class.getName(),
				RunParallel.OPTION_TASKS, Integer.toString(1),
				RunParallel.OPTION_ITERATIONS, Integer.toString(500),
				RunParallel.OPTION_METADATA
		};

		RunParallel.main(args2);
	}


}

